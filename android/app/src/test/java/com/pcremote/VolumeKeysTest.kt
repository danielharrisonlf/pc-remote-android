package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeKeysTest {
  @Test fun stepsGoToTheNextMultipleOfFiveInThePressedDirection() {
    assertEquals(35, VolumeKeys.step(33, up = true))
    assertEquals(30, VolumeKeys.step(33, up = false))
    assertEquals(40, VolumeKeys.step(35, up = true))
    assertEquals(30, VolumeKeys.step(35, up = false))
    assertEquals(5, VolumeKeys.step(0, up = true))
    assertEquals(0, VolumeKeys.step(4, up = false))
  }

  @Test fun stepsStayWithinZeroAndOneHundred() {
    assertEquals(100, VolumeKeys.step(100, up = true))
    assertEquals(100, VolumeKeys.step(98, up = true))
    assertEquals(0, VolumeKeys.step(0, up = false))
    assertEquals(100, VolumeKeys.step(250, up = true))
    assertEquals(0, VolumeKeys.step(-7, up = false))
  }

  @Test fun repeatedPressesWalkFromTheLatestPendingValue() {
    var value = 42
    repeat(3) { value = VolumeKeys.step(value, up = true) }
    assertEquals(55, value)
  }

  @Test fun onlyTouchpadAndMediaTakeTheKeysWhileConnectedWithAudio() {
    assertTrue(VolumeKeys.controlsPc(Tab.TOUCHPAD, resumed = true, connected = true, audio = true, panelOpen = false))
    assertTrue(VolumeKeys.controlsPc(Tab.MEDIA, resumed = true, connected = true, audio = true, panelOpen = false))
    assertFalse(VolumeKeys.controlsPc(Tab.CONTROLS, resumed = true, connected = true, audio = true, panelOpen = false))
    assertFalse(VolumeKeys.controlsPc(Tab.CLIPBOARD, resumed = true, connected = true, audio = true, panelOpen = false))
    assertFalse(VolumeKeys.controlsPc(Tab.CONNECTION, resumed = true, connected = true, audio = true, panelOpen = false))
    assertFalse(VolumeKeys.controlsPc(Tab.TOUCHPAD, resumed = true, connected = false, audio = true, panelOpen = false))
    assertFalse(VolumeKeys.controlsPc(Tab.TOUCHPAD, resumed = true, connected = true, audio = false, panelOpen = false))
    assertFalse(VolumeKeys.controlsPc(Tab.TOUCHPAD, resumed = false, connected = true, audio = true, panelOpen = false))
    assertFalse(VolumeKeys.controlsPc(Tab.TOUCHPAD, resumed = true, connected = true, audio = true, panelOpen = true))
  }
}
