package com.pcremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession as SystemSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * The PC's playback outside the app: a media notification tied to its own MediaSession,
 * so it also shows on the lock screen and in the system media controls. While it runs,
 * RemoteClient keeps the connection (and its retries) with the app in the background.
 * It never plays audio and never takes over the phone's volume keys.
 *
 * Started by MediaCoordinator (app open, PC playing) or by a tap on the widget, the
 * Quick Settings tile or the notification; see KeepPolicy for when it ends by itself.
 */
internal class MediaControlService : Service() {
  private val main = Handler(Looper.getMainLooper())
  private lateinit var client: RemoteClient
  private lateinit var session: SystemSession
  private var unsubscribe: (() -> Unit)? = null
  private val policy = KeepPolicy()
  private var startedPcId = ""
  /** A command waiting for the connection; [message] tells the user what is going on meanwhile. */
  private var pending: String? = null
  private var message: String? = null
  private var shown: Any? = null
  private var stopping = false
  private val pendingTimeout = Runnable { if (pending != null) finish("PC não encontrado") }
  private val tick = object : Runnable {
    override fun run() { check(client.view); main.postDelayed(this, TICK_MS) }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    running = true
    client = (application as RemoteApplication).client
    client.keepAlive = true
    startedPcId = client.currentPcId
    session = SystemSession(this, "PC Remote").apply {
      setCallback(object : SystemSession.Callback() {
        override fun onPlay() = command(MediaRules.PLAY)
        override fun onPause() = command(MediaRules.PAUSE)
        override fun onSkipToNext() = command(MediaRules.NEXT)
        override fun onSkipToPrevious() = command(MediaRules.PREVIOUS)
        override fun onFastForward() = command(MediaRules.FORWARD)
        override fun onRewind() = command(MediaRules.REWIND)
        override fun onCustomAction(action: String, extras: Bundle?) { if (action == CLOSE) close() }
      }, main)
      isActive = true
    }
    unsubscribe = client.observe { onState(it) }
    main.postDelayed(tick, TICK_MS)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // Every startForegroundService needs its startForeground, even for a running service.
    if (!foreground(build(client.view))) return START_NOT_STICKY
    when (intent?.action) {
      ACTION_CLOSE -> close()
      ACTION_COMMAND -> intent.getStringExtra(EXTRA_COMMAND)?.let(::command)
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    running = false
    client.keepAlive = false
    unsubscribe?.invoke()
    unsubscribe = null
    main.removeCallbacksAndMessages(null)
    session.isActive = false
    session.release()
    super.onDestroy()
  }

  private fun foreground(notification: Notification): Boolean = try {
    if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(ID, notification)
    true
  } catch (_: RuntimeException) { stopSelf(); false }

  /** A tap anywhere: runs now when connected, else connects first (10 s at most). */
  private fun command(name: String) {
    if (stopping) return
    val state = client.view
    if (state.computers.isEmpty()) { finish("Pareie um PC no app PC Remote"); return }
    if (state.status == "Conectado" && state.snapshot != null) { execute(name, state); return }
    pending = name
    message = "Conectando a ${state.pcName.ifBlank { "PC" }}…"
    main.removeCallbacks(pendingTimeout)
    main.postDelayed(pendingTimeout, CONNECT_MS)
    refresh(state)
    client.reconnect()
  }

  private fun execute(name: String, state: UiState) {
    val followed = MediaRules.followed(state.snapshot, state.selectedSessionId)
    if (followed == null) { finish("Nada tocando no PC"); return }
    val (command, offset) = MediaRules.command(name, followed) ?: run {
      message = "O player não oferece esse controle"
      refresh(state)
      return
    }
    message = null
    refresh(state)
    client.execute(RemoteAction.MediaControl(followed.id, command, offset)) { error ->
      if (error != null && !stopping) { message = error; refresh(client.view) }
    }
  }

  private fun onState(state: UiState) {
    if (stopping) return
    // Another PC, or this one removed: the notification belonged to the previous one.
    if (client.currentPcId != startedPcId) { stop(); return }
    val waiting = pending
    if (waiting != null) {
      if (state.status == "Conectado" && state.snapshot != null) {
        pending = null
        main.removeCallbacks(pendingTimeout)
        execute(waiting, state)
      } else if (state.status == "Desconectado" && client.stoppedByUser) finish(state.error.ifBlank { "PC não encontrado" })
      else refresh(state)
      return
    }
    // "Desconectar" in the app ends the notification at once.
    if (state.status == "Desconectado" && client.stoppedByUser) { stop(); return }
    if (message != null && state.status == "Conectado") message = null
    refresh(state)
    check(state)
  }

  private fun check(state: UiState) {
    if (stopping || pending != null) return
    val coordinator = (application as RemoteApplication).media
    val stop = policy.update(SystemClock.elapsedRealtime(), state.status == "Conectado", state.snapshot?.sessions, coordinator?.foreground == true)
    if (stop != null) stop()
  }

  /** Shows [text] for a moment, then ends. */
  private fun finish(text: String) {
    pending = null
    message = text
    main.removeCallbacks(pendingTimeout)
    refresh(client.view)
    main.postDelayed({ stop() }, FINISH_MS)
  }

  /** "Fechar": the notification stays away until the app is opened again. */
  private fun close() {
    (application as RemoteApplication).media?.suppressed = true
    stop()
  }

  private fun stop() {
    if (stopping) return
    stopping = true
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun refresh(state: UiState) {
    if (stopping) return
    val followed = MediaRules.followed(state.snapshot, state.selectedSessionId)
    val key = listOf(message, state.status, state.pcName, followed?.id, followed?.title, followed?.app, followed?.playing, followed?.capabilities)
    if (key == shown) return
    shown = key
    try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, build(state)) } catch (_: RuntimeException) {}
  }

  private fun build(state: UiState): Notification {
    if (Build.VERSION.SDK_INT >= 26) try {
      (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel(CHANNEL, "Controles de mídia", NotificationManager.IMPORTANCE_LOW))
    } catch (_: RuntimeException) {}
    val pc = state.pcName.ifBlank { "PC" }
    val connected = state.status == "Conectado" && state.snapshot != null
    val followed = if (connected) MediaRules.followed(state.snapshot, state.selectedSessionId) else null
    val title = followed?.let { it.title.ifBlank { it.app } } ?: pc
    val text = message ?: followed?.let { "${it.app} · $pc" } ?: if (connected) "Nada tocando no PC" else "Conectando a $pc…"

    val actions = ArrayList<Notification.Action>()
    val compact = ArrayList<Int>()
    var playbackActions = 0L
    if (followed != null) {
      val c = followed.capabilities
      if (c.previous) { compact.add(actions.size); actions.add(action(R.drawable.ic_skip_back, "Faixa anterior", MediaRules.PREVIOUS)); playbackActions = playbackActions or PlaybackState.ACTION_SKIP_TO_PREVIOUS }
      if (followed.playing && c.pause) { compact.add(actions.size); actions.add(action(R.drawable.ic_pause, "Pausar", MediaRules.PAUSE)) }
      else if (!followed.playing && c.play) { compact.add(actions.size); actions.add(action(R.drawable.ic_play, "Tocar", MediaRules.PLAY)) }
      if (c.next) { compact.add(actions.size); actions.add(action(R.drawable.ic_skip_forward, "Próxima faixa", MediaRules.NEXT)); playbackActions = playbackActions or PlaybackState.ACTION_SKIP_TO_NEXT }
      if (c.play) playbackActions = playbackActions or PlaybackState.ACTION_PLAY
      if (c.pause) playbackActions = playbackActions or PlaybackState.ACTION_PAUSE
      if (c.play && c.pause) playbackActions = playbackActions or PlaybackState.ACTION_PLAY_PAUSE
      if (c.seek) playbackActions = playbackActions or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND
    } else if (!connected) {
      // While connecting, play stays available so the lock screen can retry.
      compact.add(actions.size); actions.add(action(R.drawable.ic_play, "Tocar", MediaRules.TOGGLE)); playbackActions = PlaybackState.ACTION_PLAY
    }
    actions.add(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_x), "Fechar", service(this, ACTION_CLOSE, null, REQUEST_CLOSE)).build())

    session.setMetadata(MediaMetadata.Builder()
      .putString(MediaMetadata.METADATA_KEY_TITLE, title)
      .putString(MediaMetadata.METADATA_KEY_ARTIST, followed?.app ?: text)
      .putString(MediaMetadata.METADATA_KEY_ALBUM, pc)
      .build())
    val playbackState = when {
      pending != null -> PlaybackState.STATE_CONNECTING
      followed?.playing == true -> PlaybackState.STATE_PLAYING
      else -> PlaybackState.STATE_PAUSED
    }
    session.setPlaybackState(PlaybackState.Builder()
      .setActions(playbackActions)
      .setState(playbackState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (playbackState == PlaybackState.STATE_PLAYING) 1f else 0f)
      // Android 13+ draws the media controls from the session, so "Fechar" is offered there too.
      .addCustomAction(PlaybackState.CustomAction.Builder(CLOSE, "Fechar", R.drawable.ic_x).build())
      .build())

    val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_TAB, Tab.MEDIA.name)
    @Suppress("DEPRECATION")
    val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
    builder.setSmallIcon(R.drawable.ic_music)
      .setContentTitle(title)
      .setContentText(text)
      .setContentIntent(PendingIntent.getActivity(this, REQUEST_OPEN, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
      // Android 14+ lets the user swipe a foreground notification away: same as "Fechar".
      .setDeleteIntent(service(this, ACTION_CLOSE, null, REQUEST_DELETE))
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
      .setCategory(Notification.CATEGORY_TRANSPORT)
      .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(*compact.take(3).toIntArray()))
    actions.forEach { builder.addAction(it) }
    return builder.build()
  }

  private fun action(icon: Int, label: String, command: String): Notification.Action =
    Notification.Action.Builder(Icon.createWithResource(this, icon), label, service(this, ACTION_COMMAND, command, requestCode(command))).build()

  companion object {
    /** True between onCreate and onDestroy; read on the main thread. */
    @Volatile var running = false
      private set
    const val EXTRA_TAB = "com.pcremote.tab"
    private const val ACTION_COMMAND = "com.pcremote.media.COMMAND"
    private const val ACTION_CLOSE = "com.pcremote.media.CLOSE"
    private const val EXTRA_COMMAND = "command"
    private const val CLOSE = "close"
    private const val CHANNEL = "media"
    private const val ID = 11
    private const val TICK_MS = 5_000L
    private const val CONNECT_MS = 10_000L
    private const val FINISH_MS = 4_000L
    private const val REQUEST_OPEN = 30
    private const val REQUEST_CLOSE = 31
    private const val REQUEST_DELETE = 32

    private fun requestCode(command: String) = 40 + listOf(MediaRules.TOGGLE, MediaRules.PLAY, MediaRules.PAUSE, MediaRules.PREVIOUS, MediaRules.NEXT, MediaRules.FORWARD, MediaRules.REWIND).indexOf(command)

    /** From the foreground only: Android refuses to start it from the background. */
    fun show(context: Context): Boolean = start(context, Intent(context, MediaControlService::class.java))

    /** A widget or tile tap; false when Android does not let the service start now. */
    fun command(context: Context, command: String): Boolean =
      start(context, Intent(context, MediaControlService::class.java).setAction(ACTION_COMMAND).putExtra(EXTRA_COMMAND, command))

    private fun start(context: Context, intent: Intent): Boolean = try {
      if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
      true
    } catch (_: RuntimeException) { false }

    /** For the widget: may start the service, which a tap on a widget allows. */
    fun widgetIntent(context: Context, command: String): PendingIntent {
      val intent = Intent(context, MediaControlService::class.java).setAction(ACTION_COMMAND).putExtra(EXTRA_COMMAND, command)
      val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(context, 60 + requestCode(command), intent, flags)
      else PendingIntent.getService(context, 60 + requestCode(command), intent, flags)
    }

    /** For the running service's own notification. */
    private fun service(context: Context, action: String, command: String?, requestCode: Int): PendingIntent {
      val intent = Intent(context, MediaControlService::class.java).setAction(action)
      if (command != null) intent.putExtra(EXTRA_COMMAND, command)
      val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      return if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(context, requestCode, intent, flags)
      else PendingIntent.getService(context, requestCode, intent, flags)
    }
  }
}
