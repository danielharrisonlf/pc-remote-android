package com.pcremote

/**
 * Batches wheel steps from drag gestures. One request is in flight at a time and
 * steps that arrive meanwhile join the next request, like InputMotion does for
 * pointer movement. The protocol accepts at most 10 steps per request.
 */
internal class ScrollMotion(
  private val active: () -> Boolean,
  private val send: (Int, (String?) -> Unit) -> Unit,
  private val failed: (String) -> Unit,
) {
  private var pending = 0
  private var inFlight = false
  private var generation = 0

  fun add(steps: Int) {
    if (!active() || steps == 0) return
    // A long fling must not keep scrolling after the finger stops.
    pending = (pending + steps).coerceIn(-MAX_PENDING, MAX_PENDING)
    flush()
  }

  fun cancel() {
    generation++
    pending = 0
    inFlight = false
  }

  private fun flush() {
    if (inFlight || pending == 0 || !active()) return
    val steps = pending.coerceIn(-MAX_PER_REQUEST, MAX_PER_REQUEST)
    pending -= steps
    inFlight = true
    val own = generation
    send(steps) { error ->
      if (own != generation) return@send
      inFlight = false
      if (error == null) flush() else { cancel(); failed(error) }
    }
  }

  companion object {
    const val MAX_PER_REQUEST = 10
    const val MAX_PENDING = 30
  }
}
