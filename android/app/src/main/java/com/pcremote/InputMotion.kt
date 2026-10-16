package com.pcremote

import android.os.Handler
import kotlin.math.roundToInt

/** Main-thread motion queue. Only one request and one bounded accumulated delta exist. */
internal class InputMotion(private val handler: Handler, private val active: () -> Boolean,
  private val send: (Int, Int, (String?) -> Unit) -> Unit, private val failed: (String) -> Unit,
  private val now: () -> Long = { android.os.SystemClock.uptimeMillis() }) {
  private var x = 0f
  private var y = 0f
  private var inFlight = false
  private var lastSent = 0L
  private var generation = 0
  private var scheduled: Runnable? = null
  fun move(dx: Float, dy: Float) {
    if (!active() || !dx.isFinite() || !dy.isFinite()) return
    x = (x + dx).coerceIn(-512f,512f); y = (y + dy).coerceIn(-512f,512f)
    flush()
  }
  fun cancel() {
    generation++; x = 0f; y = 0f; inFlight = false
    scheduled?.let(handler::removeCallbacks); scheduled = null
  }
  private fun flush() {
    if (!active() || inFlight || scheduled != null) return
    val dx = x.roundToInt(); val dy = y.roundToInt()
    if(dx == 0 && dy == 0) return
    val delay = (34L - (now() - lastSent)).coerceAtLeast(0L)
    if(delay > 0) { val task = Runnable { scheduled = null; flush() }; scheduled = task; handler.postDelayed(task,delay); return }
    x -= dx; y -= dy; inFlight = true; lastSent = now()
    val own = generation
    send(dx,dy) { error ->
      if(own != generation) return@send
      inFlight = false
      if(error == null) flush() else { cancel(); failed(error) }
    }
  }
}
