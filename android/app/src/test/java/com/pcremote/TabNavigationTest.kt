package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Test

class TabNavigationTest {
  @Test fun everyTabSwitchOnlyChangesTheTab() {
    for (destination in Tab.entries) {
      val navigation = TabNavigation(Tab.TOUCHPAD)
      navigation.goTo(destination)
      assertEquals(destination, navigation.tab)
    }
  }

  @Test fun leavingAndReturningToTheTouchpadKeepsItsTab() {
    val navigation = TabNavigation(Tab.TOUCHPAD)
    navigation.goTo(Tab.MEDIA)
    navigation.goTo(Tab.CONNECTION)
    navigation.goTo(Tab.TOUCHPAD)
    assertEquals(Tab.TOUCHPAD, navigation.tab)
  }

  @Test fun sharedTextOrFilesOpenTransferAndOtherIntentsKeepTheTab() {
    val navigation = TabNavigation(Tab.TOUCHPAD)
    navigation.newIntent(shared = false)
    assertEquals(Tab.TOUCHPAD, navigation.tab)
    navigation.newIntent(shared = true)
    assertEquals(Tab.CLIPBOARD, navigation.tab)
  }
}
