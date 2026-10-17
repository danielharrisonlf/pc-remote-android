package com.pcremote

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Rules shared by the media notification, the widget and the Quick Settings tile.
 * Pure functions, so they run in JVM tests.
 */
internal object MediaRules {
  const val TOGGLE = "toggle"
  const val PLAY = "play"
  const val PAUSE = "pause"
  const val PREVIOUS = "previous"
  const val NEXT = "next"
  const val FORWARD = "forward"
  const val REWIND = "rewind"

  /** The session the app follows: the one the user picked, else the first (the PC's current one). */
  fun followed(snapshot: Snapshot?, selectedId: String): MediaSession? =
    snapshot?.sessions?.let { sessions -> sessions.firstOrNull { it.id == selectedId } ?: sessions.firstOrNull() }

  /** media.control command and seek offset for [command]; null when the player does not offer it. */
  fun command(command: String, session: MediaSession): Pair<String, Int?>? {
    val c = session.capabilities
    return when (command) {
      TOGGLE -> if (session.playing) command(PAUSE, session) else command(PLAY, session)
      PLAY -> if (c.play) "play" to null else null
      PAUSE -> if (c.pause) "pause" to null else null
      PREVIOUS -> if (c.previous) "previous" to null else null
      NEXT -> if (c.next) "next" to null else null
      FORWARD -> if (c.seek) "seek" to 10 else null
      REWIND -> if (c.seek) "seek" to -10 else null
      else -> null
    }
  }

  /** The notification appears on its own only while the app is open and the PC is playing. */
  fun shouldStart(connected: Boolean, session: MediaSession?, foreground: Boolean, suppressed: Boolean): Boolean =
    connected && session?.playing == true && foreground && !suppressed

  /** Title, line below and play state for the widget and the tile. */
  fun widget(state: UiState): WidgetContent {
    val pc = state.pcName.ifBlank { "PC Remote" }
    if (state.computers.isEmpty()) return WidgetContent("PC Remote", "Pareie um PC no app", false)
    if (state.status != "Conectado") return WidgetContent(pc, "Toque ⏯ para conectar", false)
    val session = followed(state.snapshot, state.selectedSessionId) ?: return WidgetContent("Nada tocando", pc, false)
    return WidgetContent(session.title.ifBlank { session.app }, "${session.app} · $pc", session.playing)
  }
}

internal data class WidgetContent(val title: String, val subtitle: String, val playing: Boolean)

/**
 * When a running media notification goes away by itself (times from a monotonic clock):
 * no session for 30 s, everything paused for 15 min while the app is not open, or the
 * connection lost for 60 s.
 */
internal class KeepPolicy(private val noSessionMs: Long = 30_000, private val pausedMs: Long = 15 * 60_000, private val disconnectedMs: Long = 60_000) {
  enum class Stop { NO_SESSION, PAUSED, DISCONNECTED }
  private var noSessionSince: Long? = null
  private var pausedSince: Long? = null
  private var disconnectedSince: Long? = null

  /** [sessions] is null until the first snapshot of a connection. */
  fun update(now: Long, connected: Boolean, sessions: List<MediaSession>?, foreground: Boolean): Stop? {
    if (!connected) {
      val since = disconnectedSince ?: now.also { disconnectedSince = it }
      return if (now - since >= disconnectedMs) Stop.DISCONNECTED else null
    }
    disconnectedSince = null
    if (sessions == null) return null
    if (sessions.isEmpty()) {
      pausedSince = null
      val since = noSessionSince ?: now.also { noSessionSince = it }
      return if (now - since >= noSessionMs) Stop.NO_SESSION else null
    }
    noSessionSince = null
    if (foreground || sessions.any { it.playing }) { pausedSince = null; return null }
    val since = pausedSince ?: now.also { pausedSince = it }
    return if (now - since >= pausedMs) Stop.PAUSED else null
  }
}

/**
 * Starts the media notification when the app is open and the PC starts playing, and
 * keeps the widget in step with the connection. Created with the client.
 */
internal class MediaCoordinator(private val app: Application, private val client: RemoteClient) {
  private val main = Handler(Looper.getMainLooper())
  private var resumed = 0
  private var widgetShown: WidgetContent? = null
  private var widgetTask: Runnable? = null
  private var widgetAt = 0L
  /** "Fechar" on the notification: it does not come back until the app is opened again. */
  var suppressed = false

  val foreground: Boolean get() = resumed > 0

  init {
    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
      override fun onActivityResumed(activity: Activity) {
        resumed++
        suppressed = false
        // MainActivity tells the client it is in the foreground right after this callback.
        main.post { evaluate(client.view) }
      }
      override fun onActivityPaused(activity: Activity) { resumed = (resumed - 1).coerceAtLeast(0) }
      override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
      override fun onActivityStarted(activity: Activity) {}
      override fun onActivityStopped(activity: Activity) {}
      override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
      override fun onActivityDestroyed(activity: Activity) {}
    })
    client.observe { evaluate(it) }
  }

  private fun evaluate(state: UiState) {
    val session = MediaRules.followed(state.snapshot, state.selectedSessionId)
    if (!MediaControlService.running && MediaRules.shouldStart(state.status == "Conectado", session, foreground, suppressed)) MediaControlService.show(app)
    scheduleWidget()
  }

  /** At most two widget redraws a second, and only when what it shows changed. */
  private fun scheduleWidget() {
    if (widgetTask != null) return
    val task = Runnable {
      widgetTask = null
      widgetAt = SystemClock.uptimeMillis()
      val content = MediaRules.widget(client.view)
      if (content != widgetShown) { widgetShown = content; MediaWidget.update(app, content) }
    }
    widgetTask = task
    main.postDelayed(task, (WIDGET_INTERVAL_MS - (SystemClock.uptimeMillis() - widgetAt)).coerceIn(0, WIDGET_INTERVAL_MS))
  }

  private companion object {
    const val WIDGET_INTERVAL_MS = 500L
  }
}

/** One media command without the service: right away when connected, else after connecting (10 s at most). */
internal object MediaCommands {
  fun direct(client: RemoteClient, command: String) {
    val now = client.view
    if (now.status == "Conectado" && now.snapshot != null) { run(client, now, command); return }
    val main = Handler(Looper.getMainLooper())
    var unsubscribe: (() -> Unit)? = null
    val timeout = Runnable { unsubscribe?.invoke(); unsubscribe = null }
    unsubscribe = client.observe { state ->
      if (state.status == "Conectado" && state.snapshot != null && unsubscribe != null) {
        main.removeCallbacks(timeout)
        // Not from inside the listener loop: unsubscribing there would change the set it walks.
        val stop = unsubscribe
        unsubscribe = null
        main.post { stop?.invoke(); run(client, state, command) }
      }
    }
    main.postDelayed(timeout, 10_000)
    client.reconnect()
  }

  private fun run(client: RemoteClient, state: UiState, command: String) {
    val session = MediaRules.followed(state.snapshot, state.selectedSessionId) ?: return
    val (name, offset) = MediaRules.command(command, session) ?: return
    client.execute(RemoteAction.MediaControl(session.id, name, offset))
  }
}
