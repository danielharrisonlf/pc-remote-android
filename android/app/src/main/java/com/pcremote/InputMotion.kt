package com.pcremote

import android.os.Handler
import kotlin.math.ceil
import kotlin.math.roundToInt

internal interface MotionScheduler {
  fun postDelayed(task: Runnable, delayMs: Long)
  fun removeCallbacks(task: Runnable)
}

internal class HandlerMotionScheduler(private val handler: Handler) : MotionScheduler {
  override fun postDelayed(task: Runnable, delayMs: Long) { handler.postDelayed(task, delayMs) }
  override fun removeCallbacks(task: Runnable) { handler.removeCallbacks(task) }
}

/**
 * Relative motion at a bounded cadence. The ACK window follows measured RTT.
 * Sent requests occupy their slot until completion, including between gestures.
 * Only adjacent unsent movements coalesce; discrete actions form ordering barriers.
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

  private sealed interface Item {
    class Move(var x: Float = 0f, var y: Float = 0f, var readyAt: Long? = null) : Item
    class Barrier(val completion: (String?) -> Unit) : Item
  }

  private val queue = ArrayDeque<Item>()
  private var inFlight = 0
  private var window = MIN_IN_FLIGHT
  private var roundTrip = 0.0
  private var lastSent = 0L
  private var generation = 0
  private var scheduled: Runnable? = null
  private var draining = false

  fun move(dx: Float, dy: Float) {
    if (!active() || !dx.isFinite() || !dy.isFinite()) return
    val at = now()
    val tail = queue.lastOrNull() as? Item.Move
    val move = tail ?: Item.Move().also { queue.addLast(it) }
    // Prefer current intent to replaying an old drag after a stalled link.
    if (expired(move, at)) { move.x = 0f; move.y = 0f; move.readyAt = null }
    move.x = (move.x + dx).coerceIn(-MAX_DELTA, MAX_DELTA)
    move.y = (move.y + dy).coerceIn(-MAX_DELTA, MAX_DELTA)
    if (hasPixels(move)) { if (move.readyAt == null) move.readyAt = at } else move.readyAt = null
    flush()
  }

  /** Activation ACKs seed this before the first drag. Slow samples adapt immediately. */
  fun observeRoundTrip(elapsedMs: Long) {
    if (elapsedMs < 0) return
    roundTrip = maxOf(elapsedMs.coerceAtMost(2000).toDouble(), roundTrip * 0.9)
    window = ceil((roundTrip + FRAME_MS * 2) / FRAME_MS).toInt().coerceIn(MIN_IN_FLIGHT, MAX_IN_FLIGHT)
    flush()
  }

  /** Older motion reaches the wire before the action, without waiting another RTT. */
  fun beforeAction(completion: (String?) -> Unit) {
    if (!active()) { completion(INTERRUPTED); return }
    // A subpixel remainder must not hold a click indefinitely.
    (queue.lastOrNull() as? Item.Move)?.let { if (!hasPixels(it)) queue.removeLast() }
    queue.addLast(Item.Barrier(completion))
    flush()
  }

  /** Safety cancellation cannot unsend frames already written to the socket. */
  fun cancel() {
    generation++
    scheduled?.let(scheduler::removeCallbacks)
    scheduled = null
    val barriers = queue.filterIsInstance<Item.Barrier>()
    queue.clear()
    barriers.forEach { it.completion(INTERRUPTED) }
  }

  private fun hasPixels(move: Item.Move) = move.x.roundToInt() != 0 || move.y.roundToInt() != 0
  private fun expired(move: Item.Move, at: Long) = move.readyAt?.let { at - it >= MAX_PENDING_AGE_MS } == true

  private fun schedule(delay: Long) {
    scheduled?.let(scheduler::removeCallbacks)
    val task = Runnable { scheduled = null; flush() }
    scheduled = task
    scheduler.postDelayed(task, delay.coerceAtLeast(1))
  }

  private fun flush() {
    if (draining || !active()) return
    draining = true
    try {
      while (active()) {
        when (val item = queue.firstOrNull() ?: return) {
          is Item.Barrier -> {
            queue.removeFirst()
            item.completion(null)
          }
          is Item.Move -> {
            val at = now()
            if (expired(item, at)) { queue.removeFirst(); continue }
            if (!hasPixels(item)) {
              if (queue.size > 1) { queue.removeFirst(); continue }
              return // Accumulate fractions even during very slow, precise drags.
            }
            if (inFlight >= window) {
              schedule(MAX_PENDING_AGE_MS - (at - item.readyAt!!))
              return
            }
            val delay = (FRAME_MS - (at - lastSent)).coerceAtLeast(0L)
            if (delay > 0) { schedule(delay); return }
            scheduled?.let(scheduler::removeCallbacks)
            scheduled = null
            val dx = item.x.roundToInt()
            val dy = item.y.roundToInt()
            item.x -= dx; item.y -= dy; item.readyAt = null
            if (queue.size > 1 || (item.x == 0f && item.y == 0f)) queue.removeFirst()
            inFlight++
            lastSent = at
            val own = generation
            var completed = false
            send(dx, dy) { error ->
              if (completed) return@send
              completed = true
              inFlight--
              if (own == generation) {
                if (error == null) observeRoundTrip(now() - at)
                else { cancel(); failed(error) }
              }
              // A cancelled gesture still releases its actual transport slot.
              flush()
            }
          }
        }
      }
    } finally {
      draining = false
    }
  }

  companion object {
    const val FRAME_MS = 16L
    const val MIN_IN_FLIGHT = 6
    const val MAX_IN_FLIGHT = 16
    const val MAX_PENDING_AGE_MS = 80L
    const val MAX_DELTA = 512f
    private const val INTERRUPTED = "Controle interrompido."
  }
}
