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
    repeat(InputMotion.MAX_IN_FLIGHT) {
      motion.move(5f, 0f)
      clock += InputMotion.FRAME_MS
    }
    assertEquals(InputMotion.MAX_IN_FLIGHT, sent.size)

    // Window is full: additional movements accumulate without dispatching until an ACK arrives
    motion.move(20f, 10f)
    motion.move(15f, 5f)
    assertEquals(InputMotion.MAX_IN_FLIGHT, sent.size)

    // One ACK frees a slot and flushes the accumulated (35, 15) delta immediately
    callbacks.removeAt(0)(null)
    assertEquals(InputMotion.MAX_IN_FLIGHT + 1, sent.size)
    assertEquals(35 to 15, sent.last())
  }

  @Test fun cancelResetsWindowAndIgnoresStaleAcks() {
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
}
