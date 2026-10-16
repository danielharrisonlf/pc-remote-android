package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageNavigationTest {
  private class Input {
    var armed = true
    val queuedMotion = mutableListOf(12 to 7)
    var disarms = 0
    fun disarm() {
      armed = false
      queuedMotion.clear()
      disarms++
    }
  }

  @Test fun settingsAndPairingBothDisarmAndCancelPendingMotion() {
    for (destination in listOf(Page.SETTINGS, Page.PAIR)) {
      val input = Input()
      val navigation = PageNavigation(input::disarm)
      navigation.goTo(destination)
      assertEquals(destination, navigation.page)
      assertFalse(input.armed)
      assertTrue(input.queuedMotion.isEmpty())
      assertEquals(1, input.disarms)
      navigation.goTo(Page.HOME)
      assertFalse(input.armed)
      assertTrue(input.queuedMotion.isEmpty())
      assertEquals(1, input.disarms)
    }
  }

  @Test fun samePageRefreshDoesNotInterruptAnActiveHomeSession() {
    val input = Input()
    val navigation = PageNavigation(input::disarm)
    navigation.goTo(Page.HOME)
    assertTrue(input.armed)
    assertEquals(0, input.disarms)
  }

  @Test fun newIntentDisarmsEvenIfHomeWasAlreadyVisible() {
    val input = Input()
    val navigation = PageNavigation(input::disarm)
    navigation.newIntent()
    assertEquals(Page.HOME, navigation.page)
    assertFalse(input.armed)
    assertTrue(input.queuedMotion.isEmpty())
    assertEquals(1, input.disarms)
  }
}
