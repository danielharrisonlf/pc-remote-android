package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Test

class InputMotionTest {
  private var clock = 1000L
  private var active = true
  private val sent = mutableListOf<Pair<Int, Int>>()
  private val callbacks = mutableListOf<(String?) -> Unit>()
  private val failures = mutableListOf<String>()
  private val scheduledTasks = mutableListOf<Pair<Long, Runnable>>()

  private val scheduler = object : MotionScheduler {
    override fun postDelayed(task: Runnable, delayMs: Long) {
      scheduledTasks += (clock + delayMs) to task
    }
    override fun removeCallbacks(task: Runnable) {
      scheduledTasks.removeAll { it.second === task }
    }
  }

  private val motion = InputMotion(
    scheduler = scheduler,
    active = { active },
    send = { dx, dy, done ->
      sent += dx to dy
      callbacks += done
    },
    failed = { failures += it },
    now = { clock },
  )

  private fun advanceTime(ms: Long) {
    clock += ms
    while (true) {
      val next = scheduledTasks.firstOrNull { it.first <= clock } ?: break
      scheduledTasks.remove(next)
      next.second.run()
    }
  }

  @Test fun dispatchesWithoutWaitingForRoundTripAckWithinSlidingWindow() {
    motion.move(10f, 5f)
    assertEquals(listOf(10 to 5), sent)

    // Next move arrives 16ms later while first request is still in flight (no ACK yet)
    clock += 16L
    motion.move(12f, -4f)
    assertEquals(listOf(10 to 5, 12 to -4), sent)
    assertEquals(2, callbacks.size)
  }

  @Test fun enforces16msCadenceAndCoalescesSubFrameDeltas() {
    motion.move(8f, 4f)
    assertEquals(listOf(8 to 4), sent)

    // Moves arriving 4ms and 8ms later coalesce into the next 16ms frame
    clock += 4L
    motion.move(3f, 2f)
    clock += 4L
    motion.move(5f, -1f)
    assertEquals(listOf(8 to 4), sent)

    // Advancing to 16ms triggers the scheduled flush with coalesced delta (8, 1)
    advanceTime(8L)
    assertEquals(listOf(8 to 4, 8 to 1), sent)
  }

  @Test fun slidingWindowCapsInFlightFramesDuringNetworkJitter() {
    repeat(InputMotion.MIN_IN_FLIGHT) {
      motion.move(5f, 0f)
      clock += InputMotion.FRAME_MS
    }
    assertEquals(InputMotion.MIN_IN_FLIGHT, sent.size)

    // Window is full: additional movements accumulate without dispatching until an ACK arrives
    motion.move(20f, 10f)
    motion.move(15f, 5f)
    assertEquals(InputMotion.MIN_IN_FLIGHT, sent.size)

    // One ACK frees a slot and flushes the accumulated (35, 15) delta immediately
    callbacks.removeAt(0)(null)
    assertEquals(InputMotion.MIN_IN_FLIGHT + 1, sent.size)
    assertEquals(35 to 15, sent.last())
  }

  @Test fun cancelDiscardsUnsentMotionAndIgnoresStaleAcks() {
    motion.move(10f, 10f)
    clock += 5L
    motion.move(20f, 20f)
    motion.cancel()

    callbacks.removeAt(0)(null)
    assertEquals(1, sent.size)

    clock += 20L
    motion.move(4f, 6f)
    assertEquals(listOf(10 to 10, 4 to 6), sent)
  }

  @Test fun errorCancelsQueueAndNotifiesFailure() {
    motion.move(10f, 0f)
    clock += 16L
    motion.move(10f, 0f)
    callbacks.removeAt(0)("Conexão interrompida.")
    assertEquals(listOf("Conexão interrompida."), failures)
    // Late callback from second frame is ignored after cancel
    callbacks.removeAt(0)("Outro erro")
    assertEquals(1, failures.size)
  }

  @Test fun measuredActivationRoundTripKeeps200msLinkMovingAtFrameCadence() {
    motion.observeRoundTrip(200)
    repeat(14) {
      motion.move(3f, -2f)
      if (it < 13) advanceTime(16)
    }
    assertEquals(14, sent.size)
    assertEquals(42, sent.sumOf { it.first })
    assertEquals(-28, sent.sumOf { it.second })
  }

  @Test fun repeatedCancelledGesturesCannotResetRealOutstandingLimit() {
    repeat(5) {
      repeat(6) { motion.move(4f, 0f); advanceTime(16) }
      motion.cancel()
    }
    assertEquals(InputMotion.MIN_IN_FLIGHT, sent.size)
    motion.move(7f, 0f)
    callbacks.removeAt(0)(null)
    assertEquals(InputMotion.MIN_IN_FLIGHT + 1, sent.size)
    assertEquals(7 to 0, sent.last())
  }

  @Test fun adaptiveWindowRemainsBoundedWithoutAcknowledgements() {
    motion.observeRoundTrip(2000)
    repeat(50) { motion.move(4f, 0f); advanceTime(16) }
    assertEquals(InputMotion.MAX_IN_FLIGHT, sent.size)
    motion.cancel()
    repeat(10) { motion.move(4f, 0f); advanceTime(16) }
    assertEquals(InputMotion.MAX_IN_FLIGHT, sent.size)
  }

  @Test fun staleCongestedMotionIsNotReplayedWhenConnectionRecovers() {
    repeat(6) { motion.move(4f, 0f); advanceTime(16) }
    motion.move(100f, 0f)
    advanceTime(InputMotion.MAX_PENDING_AGE_MS)
    callbacks.removeAt(0)(null)
    assertEquals(6, sent.size)
    motion.move(2f, 0f)
    assertEquals(2 to 0, sent.last())
  }

  @Test fun pendingFinalFrameDrainsWithoutAnotherTouchEvent() {
    motion.move(4f, 0f)
    advanceTime(4)
    motion.move(7f, 2f)
    advanceTime(12)
    assertEquals(listOf(4 to 0, 7 to 2), sent)
  }

  @Test fun clickCannotOvertakePendingMovementAndLaterMovementStaysAfterClick() {
    motion.move(4f, 0f)
    advanceTime(4)
    motion.move(7f, 0f)
    var clickAt = -1
    motion.beforeAction { error -> assertEquals(null, error); clickAt = sent.size }
    motion.move(9f, 0f)
    assertEquals(-1, clickAt)
    advanceTime(12)
    assertEquals(2, clickAt)
    assertEquals(listOf(4 to 0, 7 to 0), sent)
    advanceTime(16)
    assertEquals(9 to 0, sent.last())
  }

  @Test fun cancellationRejectsQueuedActionAndNeverSendsQueuedMovement() {
    motion.move(4f, 0f)
    motion.move(7f, 0f)
    var result: String? = null
    motion.beforeAction { result = it }
    motion.cancel()
    advanceTime(100)
    assertEquals("Controle interrompido.", result)
    assertEquals(listOf(4 to 0), sent)
  }

  @Test fun slowSubpixelMotionAccumulatesWithoutExpiringPrecision() {
    motion.move(0.2f, 0f)
    advanceTime(1000)
    motion.move(0.2f, 0f)
    advanceTime(1000)
    motion.move(0.2f, 0f)
    assertEquals(listOf(1 to 0), sent)
    repeat(2) { advanceTime(1000); motion.move(0.2f, 0f) }
    assertEquals(1, sent.sumOf { it.first })
  }

  @Test fun duplicateCompletionCannotReleaseAnotherTransportSlot() {
    repeat(6) { motion.move(4f, 0f); advanceTime(16) }
    motion.cancel()
    val first = callbacks.removeAt(0)
    first(null)
    first(null)
    motion.move(9f, 0f)
    advanceTime(16)
    motion.move(10f, 0f)
    assertEquals(7, sent.size)
  }

  @Test fun synchronousCompletionsDoNotReenterOrStrandTheQueue() {
    val values = mutableListOf<Int>()
    val immediate = InputMotion(scheduler, { active }, { dx, _, done -> values += dx; done(null) }, { failures += it }, { clock })
    repeat(100) { immediate.move(1f, 0f); advanceTime(16) }
    var completed = false
    immediate.beforeAction { completed = it == null }
    assertEquals(100, values.sum())
    assertEquals(true, completed)
    assertEquals(emptyList<String>(), failures)
  }

  @Test fun inactiveAndInvalidMotionNeverReachesTransport() {
    motion.move(Float.NaN, 0f)
    motion.move(0f, Float.POSITIVE_INFINITY)
    active = false
    motion.move(1f, 1f)
    advanceTime(100)
    assertEquals(emptyList<Pair<Int, Int>>(), sent)
  }
}
