package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollMotionTest {
  private var active = true
  private val sent = mutableListOf<Int>()
  private val replies = mutableListOf<(String?) -> Unit>()
  private val failures = mutableListOf<String>()
  private val motion = ScrollMotion({ active }, { steps, done -> sent += steps; replies += done }, { failures += it })

  private fun reply(error: String? = null) = replies.removeAt(0)(error)

  @Test fun stepsArrivingDuringARequestJoinTheNextOne() {
    motion.add(2)
    motion.add(3)
    motion.add(1)
    assertEquals(listOf(2), sent)
    reply()
    assertEquals(listOf(2, 4), sent)
    reply()
    assertEquals(listOf(2, 4), sent)
  }

  @Test fun requestsAreCappedAndAFlingDoesNotQueueForever() {
    motion.add(1)
    repeat(10) { motion.add(-10) }
    reply()
    reply()
    reply()
    assertEquals(listOf(1, -10, -10, -10), sent)
    reply()
    assertEquals(4, sent.size)
  }

  @Test fun cancelDropsPendingStepsAndIgnoresALateReply() {
    motion.add(5)
    motion.add(5)
    motion.cancel()
    reply()
    assertEquals(listOf(5), sent)
    motion.add(2)
    assertEquals(listOf(5, 2), sent)
  }

  @Test fun failureStopsTheBatchAndReportsOnce() {
    motion.add(4)
    motion.add(4)
    reply("Sem conexão")
    assertEquals(listOf(4), sent)
    assertEquals(listOf("Sem conexão"), failures)
  }

  @Test fun inactiveInputSendsNothing() {
    active = false
    motion.add(3)
    assertTrue(sent.isEmpty())
  }
}
