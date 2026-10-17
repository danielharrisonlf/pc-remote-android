package com.pcremote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/**
 * files.v2 receiver: the PC pushes one file at a time and the phone takes it by
 * itself when there is room. The download uses the control connection's pinned
 * client. Bytes are written and hashed on the OkHttp reader thread, so a slow disk
 * slows the socket instead of filling memory; the job's lifecycle and the views
 * stay on the main thread. Every exit deletes what was written, except a file whose
 * size and SHA-256 matched.
 */
internal class FileReceiver(
  private val app: Context,
  private val main: Handler,
  private val link: () -> FileLink?,
  /** Sends a control request; completes with (error message, error code). */
  private val control: (RemoteAction, (String?, String?) -> Unit) -> String?,
  /** The file in progress (or null) and the latest result. */
  private val publish: (FileProgress?, FileResult?) -> Unit,
  private val failed: (String) -> Unit,
  private val recorded: (FileRecord) -> Unit,
  private val foreground: () -> Boolean,
  /** clipboard.v2: where a clipboard image goes, and its file once verified (null when it failed). */
  private val clipFolder: (Long) -> File? = { null },
  private val clipDone: (Incoming.FilePush, File?) -> Unit = { _, _ -> },
) {
  /** FINISHING: size and hash matched and the file is being made visible; nothing aborts it any more. */
  private enum class Phase { PREPARE, CONNECT, ACCEPT, STREAM, FINISHING, DONE }

  /** Where a file goes while it is written; [keep] returns the final name and an openable uri. */
  private interface Target {
    val output: OutputStream
    fun keep(): Pair<String, Uri?>
    fun drop()
  }

  private class Job(val push: Incoming.FilePush, val generation: Int, val name: String) {
    /** A clipboard image: private cache, no list, no notification, no visible error. */
    val clip = push.clipboard != null
    val sha: MessageDigest = MessageDigest.getInstance("SHA-256")
    /** Written under the job's monitor; volatile so the main thread reads it without waiting on disk I/O. */
    @Volatile var phase = Phase.PREPARE
    var stopped = false
    var target: Target? = null
    var socket: WebSocket? = null
    var fetchId = ""
    var reported = 0L
    @Volatile var received = 0L
    @Volatile var touched = SystemClock.elapsedRealtime()
    var shown = -1
  }

  private val io = Executors.newSingleThreadExecutor { task -> Thread(task, "pc-remote-file-receive").apply { isDaemon = true } }
  private var job: Job? = null
  private var last: FileResult? = null
  private var notices = 0
  private val watchdog = object : Runnable {
    override fun run() {
      val j = job ?: return
      if (SystemClock.elapsedRealtime() - j.touched > STALL_MS) abort(j, FileResult("${j.name} não foi recebido. O PC parou de enviar.", FileOutcome.FAILED), cancelPc = true)
      else main.postDelayed(this, 2000)
    }
  }

  /** Control socket: a file the PC wants to send. */
  fun push(p: Incoming.FilePush) {
    val l = link()
    // One at a time. A push for another file means the PC already gave up on the current one
    // (cancelled or expired) and may not have told this socket yet, so the old job gives way.
    job?.let { current ->
      if (current.push.transferId == p.transferId) return
      abort(current, FileResult("${current.name} não foi recebido. O PC cancelou o envio.", FileOutcome.CANCELLED), cancelPc = false)
    }
    if (l == null || !l.twoWay || job != null) { decline(p.transferId, "unavailable"); return }
    val j = Job(p, l.generation, FileRules.cleanAnyName(p.name, p.mime))
    job = j
    publishNow()
    main.removeCallbacks(watchdog); main.postDelayed(watchdog, 2000)
    io.execute {
      val prepared = try { Result.success(prepare(j)) } catch (e: Exception) { Result.failure(e) }
      main.post { prepared(j, prepared) }
    }
  }

  /** The user's Cancelar: the PC stops sending and nothing is kept. */
  fun cancel() {
    val j = job ?: return
    abort(j, FileResult("Recebimento de ${j.name} cancelado.", FileOutcome.CANCELLED), cancelPc = true)
  }

  /** The control connection ended: the PC has already dropped the transfer. */
  fun connectionEnded() {
    val j = job ?: return
    abort(j, FileResult("${j.name} não foi recebido. A conexão com o PC caiu.", FileOutcome.FAILED), cancelPc = false)
  }

  /** The PC was removed or replaced. */
  fun forget() {
    connectionEnded()
    last = null
    publishNow()
  }

  private fun decline(transferId: String, code: String) = control(RemoteAction.FileDecline(transferId, code)) { _, _ -> }

  /** Worker thread: free space, then the pending entry the bytes go to. */
  private fun prepare(j: Job): Target {
    if (j.clip) return ClipTarget(clipFolder(j.push.size) ?: throw NoSpace(), j.push.transferId, j.push.name)
    val volume = app.getExternalFilesDir(null) ?: throw StorageUnavailable()
    if (StatFs(volume.path).availableBytes < j.push.size + FileRules.FREE_MARGIN) throw NoSpace()
    return if (Build.VERSION.SDK_INT >= 29) MediaTarget(app.contentResolver, j.name, j.push.mime) else {
      val folder = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, FOLDER) } ?: throw StorageUnavailable()
      if (!folder.isDirectory && !folder.mkdirs()) throw StorageUnavailable()
      FolderTarget(folder, j.name, j.push.transferId)
    }
  }

  private fun prepared(j: Job, result: Result<Target>) {
    val target = result.getOrNull()
    if (job !== j) { target?.let { io.execute { it.drop() } }; return }
    if (target == null) {
      val space = result.exceptionOrNull() is NoSpace
      decline(j.push.transferId, if (space) "storage" else "unavailable")
      finish(j, FileResult(if (space) "${j.name} não foi recebido: falta espaço no celular." else "${j.name} não foi recebido: não foi possível salvar no celular.", FileOutcome.FAILED))
      return
    }
    val l = link()
    if (l == null || l.generation != j.generation) { io.execute { target.drop() }; abort(j, FileResult("${j.name} não foi recebido. A conexão com o PC caiu.", FileOutcome.FAILED), cancelPc = false); return }
    synchronized(j) { j.target = target; j.phase = Phase.CONNECT; j.touched = SystemClock.elapsedRealtime() }
    // Same client as /v1/ws: the DNS override and the trust manager pinned to this PC.
    val socket = try { l.client.newWebSocket(Request.Builder().url("wss://pc-remote.local:${l.port}/v1/files").build(), Listener(j)) } catch (_: Exception) { null }
    if (socket == null) { abort(j, FileResult("${j.name} não foi recebido. Não foi possível abrir a conexão de envio.", FileOutcome.FAILED), cancelPc = true); return }
    synchronized(j) { j.socket = socket }
  }

  /** Reader thread callbacks. Each one holds the job's monitor, so a main-thread abort never meets a half-written chunk. */
  private inner class Listener(private val j: Job) : WebSocketListener() {
    override fun onOpen(webSocket: WebSocket, response: Response) { synchronized(j) {
      if (j.stopped) { webSocket.cancel(); return }
      j.fetchId = UUID.randomUUID().toString()
      val frame = Protocol.fileFetch(j.fetchId, j.push.transferId, j.push.ticket)
      if (frame == null || !webSocket.send(frame)) { broken(webSocket, "Não foi possível pedir o arquivo ao PC."); return }
      j.phase = Phase.ACCEPT
      j.touched = SystemClock.elapsedRealtime()
    } }

    override fun onMessage(webSocket: WebSocket, text: String) {
      val ended = synchronized(j) {
        if (j.stopped) return
        val message = Protocol.parseFile(text)
        when {
          // The PC deletes nothing on our side; it only stops sending.
          message is FileMessage.Error -> { stop(webSocket, null, pcReason(message)); null }
          message is FileMessage.Accept && j.phase == Phase.ACCEPT && message.id == j.fetchId && message.transferId == j.push.transferId -> {
            j.phase = Phase.STREAM
            j.touched = SystemClock.elapsedRealtime()
            main.post { if (job === j) publishNow() }
            null
          }
          message is FileMessage.End && j.phase == Phase.STREAM && message.transferId == j.push.transferId -> verified(webSocket, message)
          else -> { reply(webSocket, NIL, "invalid_message", "Mensagem fora do protocolo", false, "O PC respondeu fora do protocolo."); null }
        }
      }
      // fsync and the MediaStore run without the monitor, so the main thread never waits on them.
      if (ended != null) keep(webSocket, ended)
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) { synchronized(j) {
      if (j.stopped) return
      if (j.phase != Phase.STREAM || bytes.size == 0 || j.received + bytes.size > j.push.size) { reply(webSocket, NIL, "invalid_message", "Bytes além do tamanho informado", false, "O PC enviou dados fora do protocolo."); return }
      val chunk = bytes.toByteArray()
      try { j.target!!.output.write(chunk) } catch (_: Exception) { reply(webSocket, NIL, "storage", "Sem espaço no celular", true, "Não foi possível gravar no celular. Verifique o espaço livre."); return }
      j.sha.update(chunk)
      j.received += chunk.size
      j.touched = SystemClock.elapsedRealtime()
      if (j.received - j.reported >= PROGRESS_STEP || j.received == j.push.size) {
        j.reported = j.received
        Protocol.fileProgress(j.push.transferId, j.received)?.let { webSocket.send(it) }
        main.post { progressed(j) }
      }
    } }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); closed(webSocket) }
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closed(webSocket)
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed(webSocket)

    private fun closed(webSocket: WebSocket) { synchronized(j) {
      if (j.stopped || j.phase >= Phase.FINISHING) return
      stop(webSocket, null, "A conexão de envio foi encerrada.")
    } }

    /** Under the monitor: size and SHA-256 must match; then the job is FINISHING and cannot be aborted. */
    private fun verified(webSocket: WebSocket, message: FileMessage.End): FileMessage.End? {
      if (j.received != j.push.size) { reply(webSocket, message.id, "file_integrity", "Faltaram bytes do arquivo", true, "O arquivo chegou incompleto. Tente de novo."); return null }
      if (j.sha.digest().joinToString("") { "%02x".format(it) } != message.sha256) { reply(webSocket, message.id, "file_integrity", "SHA-256 diferente", true, "O arquivo chegou corrompido. Tente de novo."); return null }
      j.phase = Phase.FINISHING
      return message
    }

    /** Reader thread, without the monitor: the entry becomes a visible file and the PC gets its final name. */
    private fun keep(webSocket: WebSocket, message: FileMessage.End) {
      val kept = try { j.target!!.keep() } catch (_: Exception) { null }
      if (kept == null) {
        synchronized(j) { reply(webSocket, message.id, "storage", "Falha ao salvar no celular", true, "Não foi possível salvar no celular.") }
        return
      }
      val (name, uri) = kept
      j.phase = Phase.DONE
      Protocol.fileDone(message.id, j.push.transferId, name)?.let { webSocket.send(it) }
      val record = FileRecord(name, j.push.size, System.currentTimeMillis(), toPc = false, uri = uri?.toString(), mime = j.push.mime)
      main.post { saved(j, record) }
    }

    private fun reply(webSocket: WebSocket, id: String, code: String, message: String, retryable: Boolean, reason: String) {
      Protocol.fileError(id, code, message, retryable)?.let { webSocket.send(it) }
      stop(webSocket, 1000, reason)
    }

    private fun broken(webSocket: WebSocket, reason: String) = stop(webSocket, null, reason)

    /** Ends the download from the reader thread: closes the socket, drops the partial file and tells the main thread. */
    private fun stop(webSocket: WebSocket, closeCode: Int?, reason: String) {
      j.stopped = true
      if (closeCode != null) webSocket.close(closeCode, null) else webSocket.cancel()
      j.target?.drop()
      main.post { finish(j, FileResult("${j.name} não foi recebido. $reason", FileOutcome.FAILED)) }
    }

    private fun pcReason(error: FileMessage.Error) = when (error.code) {
      "unavailable" -> "O envio foi cancelado no PC."
      "unauthorized" -> "O PC não autorizou o envio."
      "storage" -> "O PC não conseguiu ler o arquivo."
      else -> "Falha no PC ao enviar o arquivo."
    }
  }

  private fun progressed(j: Job) {
    if (job !== j) return
    val permille = (j.received * 1000 / j.push.size).toInt()
    if (permille != j.shown) { j.shown = permille; publishNow() }
  }

  private fun saved(j: Job, record: FileRecord) {
    if (job !== j) return
    if (j.clip) {
      job = null
      main.removeCallbacks(watchdog)
      publishNow()
      clipDone(j.push, record.uri?.let { Uri.parse(it).path }?.let(::File))
      return
    }
    finish(j, FileResult("${record.name} recebido do PC.", FileOutcome.SENT))
    recorded(record)
    if (!foreground()) notifySaved(record)
  }

  /** Main thread: stops the job wherever it is; the writer cannot be mid-chunk while we hold its monitor. */
  private fun abort(j: Job, result: FileResult, cancelPc: Boolean) {
    if (job !== j) return
    val target = synchronized(j) {
      // Already verified, checked under the monitor: the file is being saved or was saved; saved() ends the job.
      if (j.phase >= Phase.FINISHING) return
      if (j.stopped) null else {
        j.stopped = true
        j.socket?.cancel()
        j.target
      }
    }
    target?.let { io.execute { it.drop() } }
    if (cancelPc) control(RemoteAction.FileCancel(j.push.transferId)) { _, _ -> }
    finish(j, result)
  }

  private fun finish(j: Job, result: FileResult) {
    if (job !== j) return
    job = null
    main.removeCallbacks(watchdog)
    if (j.clip) { publishNow(); clipDone(j.push, null); return }
    last = result
    if (result.outcome == FileOutcome.FAILED) failed(result.message)
    publishNow()
  }

  private fun publishNow() {
    val j = job?.takeUnless { it.clip }
    val streaming = j != null && j.phase >= Phase.STREAM
    publish(j?.let { FileProgress(it.name, it.push.size, it.received, streaming) }, last)
  }

  /** "Arquivo recebido do PC"; on Android 10+ the tap opens the file. */
  private fun notifySaved(record: FileRecord) {
    try {
      if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
      val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Arquivos recebidos", NotificationManager.IMPORTANCE_DEFAULT))
      val open = record.uri?.let { Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(it), record.mime.ifEmpty { FileRules.FALLBACK_MIME }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }
        ?: Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
      val id = NOTICE_BASE + (notices++ % 50)
      val pending = PendingIntent.getActivity(app, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      val notification = NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_file_down)
        .setContentTitle("Arquivo recebido do PC").setContentText(record.name)
        .setContentIntent(pending).setAutoCancel(true).build()
      manager.notify(id, notification)
    } catch (_: RuntimeException) { /* The file is saved; the list in the app shows it. */ }
  }

  private class NoSpace : IOException()
  private class StorageUnavailable : IOException()

  /** Android 10+: Download/PC Remote through the MediaStore, invisible to other apps until it is complete. */
  @RequiresApi(29)
  private class MediaTarget(private val resolver: ContentResolver, private val name: String, mime: String) : Target {
    private val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, name)
      put(MediaStore.MediaColumns.MIME_TYPE, mime)
      put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
      put(MediaStore.MediaColumns.IS_PENDING, 1)
    }) ?: throw IOException("insert")
    override val output: OutputStream = try { resolver.openOutputStream(uri, "w") ?: throw IOException("open") } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    private var dropped = false

    override fun keep(): Pair<String, Uri?> {
      output.flush()
      (output as? FileOutputStream)?.fd?.sync()
      output.close()
      resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
      // The MediaStore picks "name (1).ext" when the name is taken.
      val final = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
      return (final ?: name) to uri
    }

    override fun drop() {
      if (dropped) return
      dropped = true
      try { output.close() } catch (_: Exception) {}
      try { resolver.delete(uri, null, null) } catch (_: Exception) {}
    }
  }

  /** Android 7 to 9: the app's own Download/PC Remote; the part file is renamed without replacing anything. */
  private class FolderTarget(private val folder: File, private val name: String, transferId: String) : Target {
    private val part = File(folder, ".$transferId.pcremote-part")
    override val output: OutputStream = FileOutputStream(part)
    private var dropped = false

    override fun keep(): Pair<String, Uri?> {
      (output as FileOutputStream).fd.sync()
      output.close()
      val dot = name.lastIndexOf('.')
      val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
      val candidates = sequenceOf(name) + (1..99).asSequence().map { "$stem ($it)$ext" } + sequenceOf("$stem ${part.name.substring(1, 9)}$ext")
      for (candidate in candidates) {
        val target = File(folder, candidate)
        if (!target.exists() && part.renameTo(target)) return candidate to null
      }
      throw IOException("name")
    }

    override fun drop() {
      if (dropped) return
      dropped = true
      try { output.close() } catch (_: Exception) {}
      part.delete()
    }
  }

  /** clipboard.v2: cache/clipboard/<transferId>.png, renamed from a part file once verified. */
  private class ClipTarget(folder: File, transferId: String, private val name: String) : Target {
    private val part = File(folder, ".$transferId.part")
    private val final = File(folder, "$transferId.png")
    override val output: OutputStream = FileOutputStream(part)
    private var dropped = false

    override fun keep(): Pair<String, Uri?> {
      (output as FileOutputStream).fd.sync()
      output.close()
      if (!part.renameTo(final)) throw IOException("rename")
      return name to Uri.fromFile(final)
    }

    override fun drop() {
      if (dropped) return
      dropped = true
      try { output.close() } catch (_: Exception) {}
      part.delete()
    }
  }

  private companion object {
    const val FOLDER = "PC Remote"
    const val CHANNEL = "files"
    const val NOTICE_BASE = 900
    const val STALL_MS = 20_000L
    const val PROGRESS_STEP = 256L * 1024
    const val NIL = "00000000-0000-0000-0000-000000000000"
  }
}
