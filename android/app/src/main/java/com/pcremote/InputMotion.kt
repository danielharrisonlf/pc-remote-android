package com.pcremote

import android.os.Handler
import kotlin.math.roundToInt

internal interface MotionScheduler {
  fun postDelayed(task: Runnable, delayMs: Long)
  fun removeCallbacks(task: Runnable)
}

internal class HandlerMotionScheduler(private val handler: Handler) : MotionScheduler {
  override fun postDelayed(task: Runnable, delayMs: Long) {
    handler.postDelayed(task, delayMs)
  }
  override fun removeCallbacks(task: Runnable) {
    handler.removeCallbacks(task)
  }
}

/**
 * Main-thread motion queue. Dispatches at a steady ~16 ms cadence (60 FPS) with a
 * bounded sliding window of in-flight frames so network RTT never stalls the cursor
 * and jitter never builds an unbounded backlog.
 */
internal class InputMotion(
  private val scheduler: MotionScheduler,
  private val active: () -> Boolean,
  private val send: (Int, Int, (String?) -> Unit) -> Unit,
  private val failed: (String) -> Unit,
  private val now: () -> Long = { android.os.SystemClock.uptimeMillis() },
) {
  constructor(
    handler: Handler,
    active: () -> Boolean,
    send: (Int, Int, (String?) -> Unit) -> Unit,
    failed: (String) -> Unit,
    now: () -> Long = { android.os.SystemClock.uptimeMillis() },
  ) : this(HandlerMotionScheduler(handler), active, send, failed, now)

  private var x = 0f
  private var y = 0f
  private var inFlight = 0
  private var lastSent = 0L
  private var generation = 0
  private var scheduled: Runnable? = null

  fun move(dx: Float, dy: Float) {
    if (!active() || !dx.isFinite() || !dy.isFinite()) return
    x = (x + dx).coerceIn(-MAX_DELTA, MAX_DELTA)
    y = (y + dy).coerceIn(-MAX_DELTA, MAX_DELTA)
    flush()
  }

  fun cancel() {
    generation++
    x = 0f
    y = 0f
    inFlight = 0
    scheduled?.let(scheduler::removeCallbacks)
    scheduled = null
  }

  private fun flush() {
    if (!active() || inFlight >= MAX_IN_FLIGHT || scheduled != null) return
    val dx = x.roundToInt()
    val dy = y.roundToInt()
    if (dx == 0 && dy == 0) return
    val delay = (FRAME_MS - (now() - lastSent)).coerceAtLeast(0L)
    if (delay > 0L) {
      val task = Runnable { scheduled = null; flush() }
      scheduled = task
      scheduler.postDelayed(task, delay)
      return
    }
    x -= dx
    y -= dy
    inFlight++
    lastSent = now()
    val own = generation
    if (inFlight < MAX_IN_FLIGHT && (x.roundToInt() != 0 || y.roundToInt() != 0)) {
      val task = Runnable { scheduled = null; flush() }
      scheduled = task
      scheduler.postDelayed(task, FRAME_MS)
    }
    send(dx, dy) { error ->
      if (own != generation) return@send
      inFlight = (inFlight - 1).coerceAtLeast(0)
      if (error == null) flush() else { cancel(); failed(error) }
    }
  }

  companion object {
    const val FRAME_MS = 16L
    const val MAX_IN_FLIGHT = 6
    const val MAX_DELTA = 512f
  }
}

