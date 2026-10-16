package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeInteractionTest {
  private class Fixture {
    val commands = mutableListOf<String>()
    private var now = 0L
    private val tasks = linkedMapOf<Runnable, Long>()
    val interaction = VolumeInteraction(
      { commands += "begin:$it" }, { commands += "update:$it" }, { commands += "end:$it" },
      { task, delay -> tasks[task] = now + delay }, { tasks.remove(it); Unit },
    )
    fun advance(ms: Long) {
      now += ms
      while (true) {
        val task = tasks.entries.firstOrNull { it.value <= now }?.key ?: return
        tasks.remove(task)
        task.run()
      }
    }
    fun pendingCount() = tasks.size
  }

  @Test fun accessibilityOrKeyboardChangeFinishesWithoutTouchCallbacks() {
    val f = Fixture()
    f.interaction.change(45)
    assertEquals(listOf("begin:45"), f.commands)
    f.advance(99)
    assertEquals(1, f.commands.size)
    f.advance(1)
    assertEquals(listOf("begin:45", "end:45"), f.commands)
    assertEquals(0, f.pendingCount())
  }

  @Test fun repeatedNonTouchChangesShareOneBurstAndConfirmOnlyTheLatestValue() {
    val f = Fixture()
    f.interaction.change(40)
    f.advance(75)
    f.interaction.change(45)
    f.advance(75)
    assertEquals(listOf("begin:40", "update:45"), f.commands)
    assertEquals(1, f.pendingCount())
    f.advance(25)
    assertEquals(listOf("begin:40", "update:45", "end:45"), f.commands)
    f.advance(1000)
    assertEquals(3, f.commands.size)
  }

  @Test fun touchDragKeepsIntermediateUpdatesAndOneExplicitFinalValue() {
    val f = Fixture()
    f.interaction.startTouch(30)
    f.interaction.change(35)
    f.advance(1000)
    assertEquals(listOf("begin:30", "update:35"), f.commands)
    assertEquals(0, f.pendingCount())
    f.interaction.stopTouch(36)
    assertEquals(listOf("begin:30", "update:35", "end:36"), f.commands)
  }

  @Test fun navigationOrPauseSettlesPendingBurstOnceAndCancelsDelayedCommit() {
    val f = Fixture()
    f.interaction.change(60)
    f.interaction.finish()
    f.interaction.finish()
    f.advance(1000)
    assertEquals(listOf("begin:60", "end:60"), f.commands)
    assertEquals(0, f.pendingCount())
  }

  @Test fun touchTakingOverKeyboardBurstCannotReceiveItsOldDelayedFinalValue() {
    val f = Fixture()
    f.interaction.change(50)
    f.interaction.startTouch(55)
    f.advance(1000)
    assertEquals(listOf("begin:50", "begin:55"), f.commands)
    f.interaction.stopTouch(65)
    assertEquals(listOf("begin:50", "begin:55", "end:65"), f.commands)
  }

  @Test fun disconnectCancelsPendingFinalValueAndDoesNotReplayItOnNewSession() {
    val f = Fixture()
    f.interaction.change(50)
    f.interaction.cancel()
    f.interaction.finish()
    f.advance(1000)
    assertEquals(listOf("begin:50"), f.commands)
    f.interaction.change(70)
    f.advance(100)
    assertEquals(listOf("begin:50", "begin:70", "end:70"), f.commands)
  }
}
