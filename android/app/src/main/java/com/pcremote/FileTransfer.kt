package com.pcremote

import android.content.ContentResolver
import android.net.Uri
import android.os.Handler
import android.provider.OpenableColumns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Pinned client of the authenticated control connection, while the PC accepts files. */
internal class FileLink(val client: OkHttpClient, val port: Int, val generation: Int, val maxBytes: Long)

/**
 * files.v1 sender. State lives on the main thread; a worker thread only reads the
 * document, hashes it and writes binary frames inside the PC's window. Every
 * callback is fenced by the job it belongs to, and no job outlives the control
 * connection that issued its ticket.
 */
internal class FileTransfer(
  private val resolver: ContentResolver,
  private val main: Handler,
  private val link: () -> FileLink?,
  /** Sends a control request and returns its id; completes with (error message, error code). */
  private val control: (RemoteAction, (String?, String?) -> Unit) -> String?,
  private val publish: (FilesView) -> Unit,
  private val failed: (String) -> Unit,
) {
  private enum class Phase { OFFER, CONNECT, ACCEPT, STREAM, END }

  private class Job(val item: FileItem, val generation: Int) {
    val transferId: String = UUID.randomUUID().toString()
    var phase = Phase.OFFER
    var offerId: String? = null
    var ticket: Incoming.FileTicket? = null
    var socket: WebSocket? = null
    var beginId = ""
    var endId = ""
    var watchdog: Runnable? = null
    var shown = -1
    val lock = ReentrantLock()
    val moved: java.util.concurrent.locks.Condition = lock.newCondition()
    /** Bytes the PC confirmed; written on the main thread under [lock]. */
    @Volatile var received = 0L
    /** Bytes handed to the socket; written by the worker before each frame. */
    @Volatile var sent = 0L
    @Volatile var stopped = false
  }

  private val queue = FileQueue()
  private val checker = Executors.newSingleThreadExecutor { task -> Thread(task, "pc-remote-file-check").apply { isDaemon = true } }
  private var active: Job? = null
  private var paused = false
  private var last: FileResult? = null
  private var checking = 0
  private var cancelling = 0
  /** Bumped when the queue is dropped: checks still running belong to it. */
  private var epoch = 0
  /** Bumped when the control connection ends: its cancel acks no longer hold the queue. */
  private var session = 0

  fun add(uris: List<Uri>) {
    val distinct = uris.distinctBy { it.toString() }
    if (distinct.isEmpty()) return
    val room = queue.room(active != null, checking)
    if (distinct.size > room) failed("A fila aceita até ${FileRules.MAX_QUEUE} arquivos; ${distinct.size - room} ficaram de fora.")
    val accepted = distinct.take(room)
    if (accepted.isEmpty()) return
    paused = false
    checking += accepted.size
    val own = epoch
    accepted.forEach { uri ->
      checker.execute {
        val check = inspect(uri)
        main.post {
          if (own != epoch) return@post
          checking--
          when (check) {
            is FileRules.Check.Ok -> if (!queue.add(FileItem(uri.toString(), check.name, check.size, check.mime), active != null)) failed("${check.name} já está na fila.")
            is FileRules.Check.Rejected -> failed(check.message)
          }
          pump()
        }
      }
    }
    publishNow()
  }

  /** The user's Cancelar: the PC drops the ticket or the partial file. */
  fun cancel() {
    val job = active ?: return
    finish(job, FileResult("Envio de ${job.item.name} cancelado.", FileOutcome.CANCELLED))
    release(job.transferId)
    pump()
  }

  fun clearQueue() {
    epoch++; checking = 0; queue.clear(); paused = false
    publishNow()
  }

  fun resume() {
    paused = false
    pump()
  }

  /** A new authenticated session starts with the waiting files. */
  fun connected() {
    paused = false
    pump()
  }

  /** Waiting files stay for the next connection; the one in flight cannot continue. */
  fun connectionEnded() {
    session++
    cancelling = 0
    active?.let { job -> finish(job, FileResult("${job.item.name} não foi enviado. A conexão com o PC caiu.", FileOutcome.FAILED)) }
    publishNow()
  }

  /** The PC was removed or replaced: nothing queued for it may reach another PC. */
  fun forget() {
    clearQueue()
    connectionEnded()
    last = null
    publishNow()
  }

  /** Starts the next file when the PC accepts files and nothing else is in flight. */
  fun pump() {
    if (active == null && !paused && cancelling == 0 && !queue.isEmpty()) link()?.let { l -> queue.next()?.let { start(it, l) } }
    publishNow()
  }

  /** Control socket: a ticket is accepted only for the offer in flight; any other is released. */
  fun ticket(t: Incoming.FileTicket) {
    val job = active
    if (job != null && job.phase == Phase.OFFER && job.ticket == null && t.id == job.offerId && t.transferId == job.transferId) job.ticket = t
    else release(t.transferId)
  }

  private fun start(item: FileItem, l: FileLink) {
    val job = Job(item, l.generation)
    active = job
    if (item.size > l.maxBytes) { fail(job, "O PC aceita até ${FileRules.megabytes(l.maxBytes)} MB.", pause = false, cancel = false); return }
    job.offerId = control(RemoteAction.FileOffer(job.transferId, item.name, item.size, item.mime)) { error, code ->
      if (active !== job || job.phase != Phase.OFFER) return@control
      val t = job.ticket
      when {
        error != null -> fail(job, FileRules.errorMessage(code, error), FileRules.pausesQueue(code), cancel = false)
        t == null -> fail(job, "O PC não enviou a autorização do arquivo.", pause = true, cancel = true)
        else -> connect(job, t)
      }
    }
  }

  private fun connect(job: Job, t: Incoming.FileTicket) {
    val l = link()
    if (l == null || l.generation != job.generation) { fail(job, "O PC deixou de aceitar arquivos.", pause = true, cancel = true); return }
    job.phase = Phase.CONNECT
    arm(job)
    val listener = object : WebSocketListener() {
      override fun onOpen(webSocket: WebSocket, response: Response) { main.post { opened(job, webSocket, t) } }
      override fun onMessage(webSocket: WebSocket, text: String) { val message = Protocol.parseFile(text); main.post { received(job, webSocket, message) } }
      override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.cancel(); main.post { received(job, webSocket, null) } }
      override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); main.post { closed(job, webSocket) } }
      override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { main.post { closed(job, webSocket) } }
      override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { main.post { closed(job, webSocket) } }
    }
    // Same client as /v1/ws: the DNS override and the trust manager pinned to this PC.
    job.socket = try { l.client.newWebSocket(Request.Builder().url("wss://pc-remote.local:${l.port}/v1/files").build(), listener) } catch (_: Exception) { null }
    if (job.socket == null) fail(job, "Não foi possível abrir a conexão de envio.", pause = true, cancel = true)
  }

  private fun opened(job: Job, ws: WebSocket, t: Incoming.FileTicket) {
    if (active !== job || job.socket !== ws || job.phase != Phase.CONNECT) { ws.cancel(); return }
    job.beginId = UUID.randomUUID().toString()
    val frame = Protocol.fileBegin(job.beginId, job.transferId, t.ticket)
    if (frame == null || !ws.send(frame)) { fail(job, "Não foi possível iniciar o envio.", pause = true, cancel = true); return }
    job.phase = Phase.ACCEPT
    arm(job)
  }

  private fun received(job: Job, ws: WebSocket, message: FileMessage?) {
    if (active !== job || job.socket !== ws) return
    when {
      // The PC deletes partial data and closes after an error, so there is nothing to cancel.
      message is FileMessage.Error -> fail(job, FileRules.errorMessage(message.code, message.message), FileRules.pausesQueue(message.code), cancel = false)
      message is FileMessage.Accept && job.phase == Phase.ACCEPT && message.id == job.beginId && message.transferId == job.transferId -> stream(job, ws)
      message is FileMessage.Progress && (job.phase == Phase.STREAM || job.phase == Phase.END) && message.transferId == job.transferId &&
        message.received >= job.received && message.received <= minOf(job.sent, job.item.size) -> progress(job, message.received)
      message is FileMessage.Done && job.phase == Phase.END && message.id == job.endId && message.transferId == job.transferId -> {
        job.received = job.item.size
        finish(job, FileResult("${message.name} enviado ao PC.", FileOutcome.SENT))
        pump()
      }
      else -> fail(job, "O PC respondeu fora do protocolo.", pause = true, cancel = true)
    }
  }

  private fun closed(job: Job, ws: WebSocket) {
    if (active !== job || job.socket !== ws) return
    fail(job, "A conexão de envio foi encerrada.", pause = true, cancel = true)
  }

  private fun stream(job: Job, ws: WebSocket) {
    val t = job.ticket ?: return
    job.phase = Phase.STREAM
    arm(job)
    publishNow()
    Thread({ upload(job, ws, t.chunkBytes, t.windowBytes) }, "pc-remote-file-send").apply { isDaemon = true }.start()
  }

  /** Worker thread: sends exactly the declared size, hashing it and never exceeding the PC's window. */
  private fun upload(job: Job, ws: WebSocket, chunk: Int, window: Int) {
    val size = job.item.size
    var problem: String? = null
    var pause = false
    var digest: String? = null
    try {
      val input = resolver.openInputStream(Uri.parse(job.item.uri)) ?: throw IOException("stream")
      input.use { stream ->
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(chunk)
        var sent = 0L
        while (sent < size) {
          val want = minOf(chunk.toLong(), size - sent).toInt()
          var n = 0
          while (n < want) { val r = stream.read(buffer, n, want - n); if (r < 0) break; n += r }
          // A document replaced since it was queued would fail the PC's checks anyway.
          val head = sent == 0L && n >= minOf(size, FileRules.HEAD_BYTES.toLong())
          if (n < want || (head && !FileRules.signatureMatches(job.item.mime, buffer, n))) { problem = FileRules.CHANGED; break }
          sha.update(buffer, 0, n)
          if (!awaitWindow(job, sent + n, window)) return
          job.sent = sent + n
          if (!ws.send(buffer.toByteString(0, n))) { problem = "A conexão de envio foi encerrada."; pause = true; break }
          sent += n
        }
        if (problem == null && stream.read() >= 0) problem = FileRules.CHANGED
        if (problem == null) digest = sha.digest().joinToString("") { "%02x".format(it) }
      }
    } catch (_: SecurityException) {
      problem = "O Android não deu permissão para ler o arquivo."
    } catch (_: Exception) {
      problem = "Não foi possível ler o arquivo."
    }
    if (job.stopped) return
    val reason = problem
    main.post { streamed(job, ws, digest, reason, pause) }
  }

  private fun awaitWindow(job: Job, total: Long, window: Int): Boolean = job.lock.withLock {
    while (!job.stopped && total - job.received > window) job.moved.await(1, TimeUnit.SECONDS)
    !job.stopped
  }

  private fun streamed(job: Job, ws: WebSocket, digest: String?, problem: String?, pause: Boolean) {
    if (active !== job || job.socket !== ws) return
    if (problem != null || digest == null) { fail(job, problem ?: "Não foi possível ler o arquivo.", pause, cancel = true); return }
    job.endId = UUID.randomUUID().toString()
    val frame = Protocol.fileEnd(job.endId, job.transferId, digest)
    if (frame == null || !ws.send(frame)) { fail(job, "Não foi possível concluir o envio.", pause = true, cancel = true); return }
    job.phase = Phase.END
    arm(job)
  }

  private fun progress(job: Job, received: Long) {
    if (received > job.received) arm(job)
    job.lock.withLock { job.received = received; job.moved.signalAll() }
    val permille = (received * 1000 / job.item.size).toInt()
    if (permille != job.shown) { job.shown = permille; publishNow() }
  }

  /** Aborts when the PC confirms nothing new for [STALL_MS]. */
  private fun arm(job: Job) {
    job.watchdog?.let(main::removeCallbacks)
    val task = Runnable { if (active === job) fail(job, "O PC parou de responder.", pause = true, cancel = true) }
    job.watchdog = task
    main.postDelayed(task, STALL_MS)
  }

  private fun fail(job: Job, reason: String, pause: Boolean, cancel: Boolean) {
    if (active !== job) return
    val result = FileResult("${job.item.name} não foi enviado. $reason", FileOutcome.FAILED)
    finish(job, result)
    if (pause) paused = true
    failed(result.message)
    if (cancel) release(job.transferId)
    pump()
  }

  /** Every exit path: stops the worker, closes the file socket and records the result. */
  private fun finish(job: Job, result: FileResult) {
    if (active !== job) return
    active = null
    job.watchdog?.let(main::removeCallbacks)
    job.watchdog = null
    job.lock.withLock { job.stopped = true; job.moved.signalAll() }
    job.socket?.let { if (result.outcome == FileOutcome.SENT) it.close(1000, null) else it.cancel() }
    last = result
  }

  /** file.cancel is idempotent on the PC; the next offer waits for its ack so the PC is free again. */
  private fun release(transferId: String) {
    val own = session
    cancelling++
    control(RemoteAction.FileCancel(transferId)) { _, _ -> if (own == session) { cancelling--; pump() } }
  }

  private fun publishNow() {
    val job = active
    publish(FilesView(job?.let { FileProgress(it.item.name, it.item.size, it.received, it.phase >= Phase.STREAM) }, queue.names, paused, last))
  }

  /** Worker thread: name, size, type and first bytes, before the document joins the queue. */
  private fun inspect(uri: Uri): FileRules.Check {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return FileRules.Check.Rejected("Só dá para enviar arquivos escolhidos no Android.")
    return try {
      var name: String? = null
      var size = -1L
      resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { row ->
        if (row.moveToFirst()) {
          val n = row.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (n >= 0 && !row.isNull(n)) name = row.getString(n)
          val s = row.getColumnIndex(OpenableColumns.SIZE)
          if (s >= 0 && !row.isNull(s)) size = row.getLong(s)
        }
      }
      if (size < 0) size = try { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (_: Exception) { -1L }
      val head = ByteArray(FileRules.HEAD_BYTES)
      var read = 0
      val input = resolver.openInputStream(uri) ?: return FileRules.Check.Rejected("Não foi possível abrir o arquivo.")
      input.use { while (read < head.size) { val n = it.read(head, read, head.size - read); if (n < 0) break; read += n } }
      FileRules.check(name, resolver.getType(uri), size, head, read)
    } catch (_: SecurityException) {
      FileRules.Check.Rejected("O Android não deu permissão para ler o arquivo.")
    } catch (_: Exception) {
      FileRules.Check.Rejected("Não foi possível ler o arquivo.")
    }
  }

  private companion object {
    const val STALL_MS = 20_000L
  }
}
