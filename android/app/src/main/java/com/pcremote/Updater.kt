package com.pcremote

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Failure already worded for the user. */
private class UpdateError(message: String) : IOException(message)

/**
 * Downloads, verifies and installs this app's own updates while the process
 * lives; there is no service. State changes run on the main thread, network,
 * hashing and the install session on one worker thread.
 */
internal class UpdateManager(context: Context) {
  private val app = context.applicationContext
  private val main = Handler(Looper.getMainLooper())
  private val worker = Executors.newSingleThreadExecutor()
  private val dir = File(app.cacheDir, "updates")
  private val installPrefs = app.getSharedPreferences("update_install", Context.MODE_PRIVATE)
  private val http = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(10, TimeUnit.MINUTES)
    .followRedirects(false)
    .followSslRedirects(false)
    // Two or three requests per update: never reuse a connection the server may have closed.
    .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
    .build()
  private val listeners = LinkedHashSet<(UpdateFlow, String?) -> Unit>()
  private var foreground = WeakReference<Activity>(null)
  @Volatile private var generation = 0
  @Volatile private var call: Call? = null
  private var verified: Verified? = null
  /** System confirmation waiting for the app to be on screen. */
  private var confirmation: Intent? = null
  private var lastConfirmation: Intent? = null

  private class Verified(val release: Release, val file: File, val sha: String)

  var flow: UpdateFlow = UpdateFlow.Idle
    private set

  init {
    cleanup()
  }

  /** Listener gets the flow and, once, a message worth a snackbar. */
  fun observe(listener: (UpdateFlow, String?) -> Unit): () -> Unit {
    listeners += listener
    listener(flow, null)
    return { listeners -= listener }
  }

  private fun publish(next: UpdateFlow, notice: String? = null) {
    flow = next
    listeners.toList().forEach { it(next, notice) }
  }

  /** GitHub; a debuggable build may name a test server in files/[DEBUG_RELEASES_FILE]. */
  fun origin(): UpdateOrigin {
    if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return UpdateOrigin.GITHUB
    val file = File(app.filesDir, DEBUG_RELEASES_FILE)
    val text = try {
      if (file.isFile && file.length() <= 4096) file.readText() else null
    } catch (_: IOException) { null }
    return text?.let(UpdateOrigin::debug) ?: UpdateOrigin.GITHUB
  }

  /** Activity on screen: the only time the system confirmation may be started. */
  fun attach(activity: Activity) {
    foreground = WeakReference(activity)
    showConfirmation()
  }

  fun detach(activity: Activity) {
    if (foreground.get() === activity) foreground.clear()
  }

  /** Downloads and verifies [release]; with [install], set only by a tap, installs right after. */
  fun start(release: Release, install: Boolean) {
    val (next, command) = UpdateFlows.request(flow, release.version, install)
    when (command) {
      UpdateCommand.DOWNLOAD -> {
        publish(next)
        download(release)
      }
      UpdateCommand.INSTALL -> {
        val ready = verified
        if (ready == null || ready.release.version != release.version) {
          publish(UpdateFlow.Downloading(release.version, 0, 0, install = true))
          download(release)
        } else {
          publish(next)
          install(ready)
        }
      }
      UpdateCommand.NONE -> {
        publish(next)
        // A confirmation left open (Home, Recents) can be shown again.
        if (install && next is UpdateFlow.Installing && next.confirming && confirmation == null) {
          confirmation = lastConfirmation
          showConfirmation()
        }
      }
    }
  }

  /** "Agora não": a running download finishes but does not install. */
  fun keep() = publish(UpdateFlows.keep(flow))

  /** Partial downloads and APKs of versions already installed. */
  fun cleanup() = worker.execute {
    val installed = Releases.installed(installedInfo(0)?.versionName)
    UpdateCheck.staleFiles(dir.list()?.toList().orEmpty(), installed).forEach { File(dir, it).delete() }
  }

  private fun download(release: Release) {
    val run = ++generation
    call?.cancel()
    val origin = origin()
    val apk = release.apk
    val sums = release.sums
    if (apk == null || sums == null || !origin.allowsAsset(apk.url) || !origin.allowsAsset(sums.url)) {
      val message = "Esta versão não traz APK e SHA-256 para o app instalar. Use Ver no GitHub."
      publish(UpdateFlows.failed(flow, message), message)
      return
    }
    worker.execute {
      val result = attempt(release, apk, sums, origin, run)
      main.post {
        if (run != generation) return@post
        when (result) {
          is Verified -> {
            verified = result
            val (next, command) = UpdateFlows.verified(flow)
            publish(next)
            if (command == UpdateCommand.INSTALL) install(result)
          }
          is UpdateError -> {
            val message = result.message.orEmpty()
            publish(UpdateFlows.failed(flow, message), message)
          }
        }
      }
    }
  }

  /** A dropped connection gets one more try; a failed verification never does. Returns [Verified] or [UpdateError]. */
  private fun attempt(release: Release, apk: ReleaseAsset, sums: ReleaseAsset, origin: UpdateOrigin, run: Int): Any {
    val dropped = UpdateError("Falha no download. Confira a conexão e tente de novo.")
    repeat(2) { index ->
      try {
        return fetch(release, apk, sums, origin, run)
      } catch (e: UpdateError) {
        return e
      } catch (_: IOException) {
        if (index == 1 || run != generation) return dropped
        SystemClock.sleep(1000)
      } catch (_: RuntimeException) {
        return UpdateError("Não foi possível preparar a atualização.")
      }
    }
    return dropped
  }

  /** Worker thread: SHA256SUMS, then the APK (or the one already in the cache), then verification. */
  private fun fetch(release: Release, apk: ReleaseAsset, sums: ReleaseAsset, origin: UpdateOrigin, run: Int): Verified {
    val listed = ByteArrayOutputStream()
    stream(origin, sums.url, UpdateCheck.MAX_SUMS_BYTES, listed, run, null)
    val expected = Sha256Sums.parse(listed.toString("UTF-8"))[apk.name] ?: throw UpdateError(UpdateCheck.sha(null, "").orEmpty())

    dir.mkdirs()
    val file = File(dir, UpdateCheck.fileName(release.version))
    if (file.isFile && sha256(file) != expected) file.delete()
    if (!file.isFile) {
      val part = File(dir, "${file.name}.part")
      try {
        val size = FileOutputStream(part).use { out ->
          stream(origin, apk.url, UpdateCheck.MAX_APK_BYTES, out, run) { received, total ->
            main.post { if (run == generation) publish(UpdateFlows.progress(flow, received, if (total > 0) total else apk.size)) }
          }
        }
        if (apk.size > 0 && size != apk.size) throw UpdateError("O APK baixado não tem o tamanho publicado. Nada foi instalado.")
        if (!part.renameTo(file)) throw UpdateError("Não foi possível guardar a atualização.")
      } finally {
        part.delete()
      }
    }
    main.post { if (run == generation) publish(UpdateFlows.downloaded(flow)) }
    verify(file, expected)
    return Verified(release, file, expected)
  }

  /**
   * Follows redirects by hand so every hop is checked before it is connected:
   * the first URL and each Location must stay on [origin].
   */
  private fun open(origin: UpdateOrigin, url: String, run: Int): Response {
    var target = url.toHttpUrlOrNull() ?: throw UpdateError(BLOCKED)
    repeat(MAX_REDIRECTS + 1) {
      if (!origin.allowsHop(target.toString())) throw UpdateError(BLOCKED)
      val current = http.newCall(Request.Builder().url(target).build())
      call = current
      if (run != generation) current.cancel()
      val response = current.execute()
      if (!response.isRedirect) return response
      val location = response.header("Location")
      response.close()
      target = location?.let(target::resolve) ?: throw UpdateError(BLOCKED)
    }
    throw UpdateError(BLOCKED)
  }

  /** Copies [url] into [sink], refusing more than [limit] bytes; returns the byte count. */
  private fun stream(origin: UpdateOrigin, url: String, limit: Long, sink: OutputStream, run: Int, progress: ((Long, Long) -> Unit)?): Long {
    open(origin, url, run).use { response ->
      if (!response.isSuccessful) throw UpdateError("O servidor de downloads respondeu com o código ${response.code}.")
      val body = response.body ?: throw UpdateError("O servidor de downloads não enviou o arquivo.")
      val total = body.contentLength()
      val tooBig = if (limit >= 1_048_576) "O APK passa do limite de ${limit / 1_048_576} MB. Nada foi instalado." else "A lista de SHA-256 da release é grande demais."
      if (total > limit) throw UpdateError(tooBig)
      val buffer = ByteArray(64 * 1024)
      var received = 0L
      var reported = 0L
      body.byteStream().use { input ->
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          received += count
          if (received > limit) throw UpdateError(tooBig)
          sink.write(buffer, 0, count)
          val now = SystemClock.uptimeMillis()
          if (progress != null && now - reported >= 100) {
            reported = now
            progress(received, total)
          }
        }
      }
      progress?.invoke(received, total)
      return received
    }
  }

  /** SHA-256 from the release, then package, versionCode and signers against the installed app. */
  private fun verify(file: File, expected: String) {
    val problem = UpdateCheck.sha(expected, sha256(file)) ?: UpdateCheck.archive(identity(installedInfo(SIGNING_FLAGS)), archiveIdentity(file))
    if (problem != null) {
      file.delete()
      throw UpdateError(problem)
    }
  }

  private fun install(ready: Verified) {
    val run = ++generation
    worker.execute {
      val problem = try {
        verify(ready.file, ready.sha) // Again: the file sat in the cache since the download.
        commit(ready.file, ready.release.version)
        null
      } catch (e: UpdateError) {
        e.message
      } catch (_: Exception) {
        "O Android não abriu a instalação. Tente de novo ou use Ver no GitHub."
      }
      if (problem != null) main.post {
        if (!ready.file.isFile && verified === ready) verified = null
        if (run == generation) publish(UpdateFlows.failed(flow, problem), problem)
      }
    }
  }

  /** Return through the Activity when authorized; otherwise deliver status by broadcast. */
  private fun commit(file: File, version: AppVersion) {
    val installer = app.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
      setAppPackageName(app.packageName)
      setSize(file.length())
      if (Build.VERSION.SDK_INT >= 26) setInstallReason(PackageManager.INSTALL_REASON_USER)
      // Once this app is the installer of record, Android 12+ updates it without asking again.
      if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
    }
    val id = installer.createSession(params)
    try {
      installer.openSession(id).use { session ->
        session.openWrite("base.apk", 0, file.length()).use { out ->
          file.inputStream().use { it.copyTo(out, 64 * 1024) }
          session.fsync(out)
        }
        val token = UUID.randomUUID().toString()
        // Persist before commit: replacing our own APK terminates this process.
        check(installPrefs.edit().putInt("session", id).putString("token", token)
          .putString("version", version.toString()).commit())
        val reopen = Build.VERSION.SDK_INT < 29 || Settings.canDrawOverlays(app)
        val target = if (reopen) MainActivity::class.java else UpdateInstallReceiver::class.java
        val status = Intent(app, target).setAction(INSTALL_RESULT)
          .putExtra(INSTALL_TOKEN, token)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // The system adds the status extras, so Android 12+ needs a mutable PendingIntent.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic().apply {
          setPendingIntentCreatorBackgroundActivityStartMode(
            if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
          )
        }.toBundle() else null
        val result = if (reopen) PendingIntent.getActivity(app, id, status, flags, options)
          else PendingIntent.getBroadcast(app, id, status, flags)
        session.commit(result.intentSender)
      }
    } catch (e: Exception) {
      clearInstallAttempt(id)
      try { installer.abandonSession(id) } catch (_: Exception) {}
      throw e
    }
  }

  /** MainActivity is exported; accept results only for the private committed attempt. */
  fun handleInstallResult(intent: Intent?): Boolean {
    if (intent?.action != INSTALL_RESULT) return false
    val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
    val attempt = UpdateInstallAttempt(installPrefs.getInt("session", -1), installPrefs.getString("token", null))
    if (!attempt.matches(id, intent.getStringExtra(INSTALL_TOKEN))) return true
    val version = Releases.installed(installPrefs.getString("version", null)) ?: return true
    if (flow !is UpdateFlow.Installing) flow = UpdateFlow.Installing(version)
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) clearInstallAttempt(id)
    onSessionStatus(intent)
    return true
  }

  private fun clearInstallAttempt(id: Int) {
    if (installPrefs.getInt("session", -1) == id) installPrefs.edit().clear().commit()
  }

  /** Activity callback, or legacy [UpdateInstallReceiver], on the main thread. */
  fun onSessionStatus(intent: Intent) {
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    if (flow !is UpdateFlow.Installing) {
      // A session from a previous process: nobody is waiting for it.
      if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) abandon(intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1))
      return
    }
    val next = UpdateFlows.session(flow, status)
    when (status) {
      PackageInstaller.STATUS_PENDING_USER_ACTION -> {
        val confirm = confirmIntent(intent)
        if (confirm == null) {
          val message = "O Android não mostrou a confirmação da instalação."
          publish(UpdateFlows.failed(flow, message), message)
          return
        }
        confirmation = confirm
        lastConfirmation = confirm
        publish(next)
        showConfirmation()
      }
      PackageInstaller.STATUS_SUCCESS -> {
        lastConfirmation = null
        verified?.file?.delete()
        verified = null
        publish(next, "Atualização instalada.")
      }
      else -> {
        lastConfirmation = null
        publish(next, UpdateCheck.installFailure(status))
      }
    }
  }

  private fun abandon(id: Int) {
    if (id < 0) return
    try { app.packageManager.packageInstaller.abandonSession(id) } catch (_: Exception) {}
  }

  @Suppress("DEPRECATION")
  private fun confirmIntent(intent: Intent): Intent? {
    val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
    // Never forward URI grants: the confirmation only needs the session id it carries.
    return confirm?.apply { flags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION).inv() }
  }

  private fun showConfirmation() {
    val activity = foreground.get() ?: return
    val confirm = confirmation ?: return
    confirmation = null
    try {
      activity.startActivity(confirm)
    } catch (_: ActivityNotFoundException) {
      val message = "O Android não mostrou a confirmação da instalação."
      publish(UpdateFlows.failed(flow, message), message)
    } catch (_: SecurityException) {
      val message = "O Android não mostrou a confirmação da instalação."
      publish(UpdateFlows.failed(flow, message), message)
    }
  }

  @Suppress("DEPRECATION")
  private fun installedInfo(flags: Int): PackageInfo? = try {
    app.packageManager.getPackageInfo(app.packageName, flags)
  } catch (_: PackageManager.NameNotFoundException) { null }

  @Suppress("DEPRECATION")
  private fun archiveIdentity(file: File): ApkIdentity? = identity(app.packageManager.getPackageArchiveInfo(file.path, SIGNING_FLAGS))

  @Suppress("DEPRECATION")
  private fun identity(info: PackageInfo?): ApkIdentity? {
    info ?: return null
    val signers: Array<Signature>? = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    return ApkIdentity(info.packageName ?: "", code, signers.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet())
  }

  companion object {
    private const val INSTALL_RESULT = "com.pcremote.INSTALL_RESULT"
    private const val INSTALL_TOKEN = "com.pcremote.INSTALL_TOKEN"
    /** Debug builds only: one line with the releases URL of a test server. */
    const val DEBUG_RELEASES_FILE = "debug-releases-url.txt"
    private const val MAX_REDIRECTS = 5
    private const val BLOCKED = "O download foi desviado para um endereço não permitido. Nada foi instalado."

    @Suppress("DEPRECATION")
    private val SIGNING_FLAGS = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    fun sha256(file: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          val count = input.read(buffer)
          if (count < 0) break
          digest.update(buffer, 0, count)
        }
      }
      return hex(digest.digest())
    }
  }
}

/** Status of the install session. Not exported: only the PendingIntent this app created reaches it. */
internal class UpdateInstallReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val updates = (context.applicationContext as RemoteApplication).updates
    if (!updates.handleInstallResult(intent)) updates.onSessionStatus(intent)
  }
}

/** Runs in the new version right after an update replaced the app. */
internal class UpdatedReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    val app = context.applicationContext as RemoteApplication
    app.updates.cleanup()
    UpdateNotice.replaced(app)
  }
}

/** "PC Remote atualizado para X", once per newer build. */
internal object UpdateNotice {
  private const val CHANNEL = "updates"
  private const val ID = 8

  @Suppress("DEPRECATION")
  private fun installed(context: Context): Pair<String, Long>? = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    (Releases.installed(info.versionName)?.toString() ?: info.versionName.orEmpty()) to code
  } catch (_: PackageManager.NameNotFoundException) { null }

  /** The app is open: remember this build and drop a notice about it. */
  fun seen(context: Context) {
    val (_, code) = installed(context) ?: return
    UiPreferences(context).lastVersionCode = code
    try { (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID) } catch (_: RuntimeException) {}
  }

  fun replaced(context: Context) {
    val (version, code) = installed(context) ?: return
    val prefs = UiPreferences(context)
    val previous = prefs.lastVersionCode
    prefs.lastVersionCode = code
    if (previous != 0L && code <= previous) return // The same build reinstalled.
    try {
      if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
      val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Atualizações", NotificationManager.IMPORTANCE_DEFAULT))
      val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
      val pending = PendingIntent.getActivity(context, ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_circle_arrow_up)
        .setContentTitle("PC Remote atualizado para $version").setContentText("Toque para abrir.")
        .setContentIntent(pending).setAutoCancel(true).build()
      manager.notify(ID, notification)
    } catch (_: RuntimeException) { /* The app still opens on the new version. */ }
  }
}
