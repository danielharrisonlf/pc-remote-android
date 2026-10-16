package com.pcremote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputIntentTest {
  private fun armed() = InputIntent().apply { confirmed() }

  @Test fun nothingRearmsBeforeTheUserActivates() {
    assertFalse(InputIntent().shouldRearm(usable = true, armed = false, settling = false))
  }

  @Test fun pauseKeepsTheWishAndRearmsOnlyWhenUsableAgain() {
    val intent = armed()
    // Background or focus loss: the PC was disarmed locally, the wish stays.
    assertTrue(intent.wanted)
    assertFalse(intent.shouldRearm(usable = false, armed = false, settling = false))
    assertTrue(intent.shouldRearm(usable = true, armed = false, settling = false))
    // Already armed again: nothing more to do.
    assertFalse(intent.shouldRearm(usable = true, armed = true, settling = false))
  }

  @Test fun disarmStillInFlightDefersTheRearmUntilItSettles() {
    val intent = armed()
    assertFalse(intent.shouldRearm(usable = true, armed = false, settling = true))
    assertTrue(intent.shouldRearm(usable = true, armed = false, settling = false))
  }

  @Test fun ourOwnDisarmSnapshotKeepsTheWish() {
    val intent = armed()
    assertFalse(intent.pcState(allowed = true, enabled = false, armed = false))
    assertTrue(intent.wanted)
  }

  @Test fun pcBlockingOrSwitchingOffWhileArmedEndsTheWish() {
    val blocked = armed()
    assertTrue(blocked.pcState(allowed = false, enabled = false, armed = false))
    assertFalse(blocked.wanted)
    val switchedOff = armed()
    assertTrue(switchedOff.pcState(allowed = true, enabled = false, armed = true))
    assertFalse(switchedOff.wanted)
    val legacy = armed()
    assertFalse(legacy.pcState(allowed = null, enabled = null, armed = true))
    assertTrue(legacy.wanted)
  }

  @Test fun manualOffConnectionEndOrRefusedActivationEndsTheWish() {
    val intent = armed()
    intent.clear()
    assertFalse(intent.wanted)
    assertFalse(intent.shouldRearm(usable = true, armed = false, settling = false))
  }
}
