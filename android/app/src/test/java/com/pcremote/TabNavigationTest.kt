package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Test

class TabNavigationTest {
  private var disarms = 0
  private fun navigation(initial: Tab = Tab.TOUCHPAD) = TabNavigation({ disarms++ }, initial)

  @Test fun leavingTheTouchpadDisarmsInput() {
    for (destination in listOf(Tab.MEDIA, Tab.CLIPBOARD, Tab.CONNECTION)) {
      disarms = 0
      val navigation = navigation()
      navigation.goTo(destination)
      assertEquals(destination, navigation.tab)
      assertEquals(1, disarms)
    }
  }

  @Test fun refreshingTheTouchpadKeepsAnActiveSession() {
    val navigation = navigation()
    navigation.goTo(Tab.TOUCHPAD)
    assertEquals(0, disarms)
  }

  @Test fun movingBetweenOtherTabsDoesNotDisarm() {
    val navigation = navigation(Tab.MEDIA)
    navigation.goTo(Tab.CLIPBOARD)
    navigation.goTo(Tab.TOUCHPAD)
    assertEquals(Tab.TOUCHPAD, navigation.tab)
    assertEquals(0, disarms)
  }

  @Test fun newIntentDisarmsAndSharedTextOpensTransfer() {
    val navigation = navigation()
    navigation.newIntent(shared = false)
    assertEquals(Tab.TOUCHPAD, navigation.tab)
    assertEquals(1, disarms)
    navigation.newIntent(shared = true)
    assertEquals(Tab.CLIPBOARD, navigation.tab)
    assertEquals(2, disarms)
  }
}
