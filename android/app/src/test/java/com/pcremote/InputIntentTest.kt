package com.pcremote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputIntentTest {
  private fun armed() = InputIntent().apply { connected(); confirmed() }

  @Test fun nothingArmsBeforeAConnectionAndEveryConnectionWantsInput() {
    val intent = InputIntent()
    assertFalse(intent.shouldRearm(usable = true, armed = false, settling = false))
    intent.connected()
    assertTrue(intent.wanted)
    assertTrue(intent.shouldRearm(usable = true, armed = false, settling = false))
    // A lost connection ends it; the next one starts it again.
    intent.clear()
    assertFalse(intent.wanted)
    intent.connected()
    assertTrue(intent.wanted)
  }

  @Test fun desligarSticksAcrossConnectionsUntilAtivar() {
    val intent = armed()
    intent.userDisabled()
    assertFalse(intent.wanted)
    intent.clear(); intent.connected()
    assertFalse(intent.wanted)
    assertFalse(intent.shouldRearm(usable = true, armed = false, settling = false))
    intent.userOn()
    assertTrue(intent.wanted)
    intent.clear(); intent.connected()
    assertTrue(intent.wanted)
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

  @Test fun aBlockOnThePcDropsInputButKeepsTheWishForWhenItIsLifted() {
    val blocked = armed()
    assertTrue(blocked.pcState(allowed = false, enabled = false, armed = true))
    assertTrue(blocked.wanted)
    assertFalse(blocked.pcState(allowed = true, enabled = false, armed = false))
    assertTrue(blocked.shouldRearm(usable = true, armed = false, settling = false))
  }

  @Test fun switchingOffWhileArmedEndsTheWishAndOldPcsChangeNothing() {
    val switchedOff = armed()
    assertTrue(switchedOff.pcState(allowed = true, enabled = false, armed = true))
    assertFalse(switchedOff.wanted)
    val legacy = armed()
    assertFalse(legacy.pcState(allowed = null, enabled = null, armed = true))
    assertTrue(legacy.wanted)
  }

  @Test fun refusedActivationEndsTheWishWithoutRetrying() {
    val intent = armed()
    intent.clear()
    assertFalse(intent.wanted)
    assertFalse(intent.shouldRearm(usable = true, armed = false, settling = false))
  }
}
