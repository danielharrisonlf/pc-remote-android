package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardTrackerTest {
  @Test fun baselineAndRemoteApplySuppressOnlyTheCurrentValue() {
    val tracker = ClipboardTracker()

    tracker.baseline("text copied before tracking")
    assertNull(tracker.observe("text copied before tracking"))

    tracker.applied("A")
    assertNull(tracker.observe("A"))
    assertEquals("B", tracker.observe("B"))
    assertEquals("A", tracker.observe("A"))
  }

  @Test fun duplicateCallbacksForCoalescedClipboardReadDoNotHideLaterCopy() {
    val tracker = ClipboardTracker()
    tracker.baseline("A")

    assertEquals("B", tracker.observe("B"))
    assertNull(tracker.observe("B"))
    assertEquals("A", tracker.observe("A"))
  }

  @Test fun resumeUsesNewBaselineAndAllowsFreshCopyAfterReconnect() {
    val tracker = ClipboardTracker()
    tracker.baseline("old value")

    tracker.`suspend`()
    assertNull(tracker.observe("value already present on resume"))
    assertNull(tracker.observe("value already present on resume"))

    // A prior value becomes a fresh change once the resumed clipboard has a new baseline.
    assertEquals("old value", tracker.observe("old value"))
  }

  @Test fun absentOrEmptyValuesClearStaleSuppressionWithoutUploading() {
    val tracker = ClipboardTracker()
    tracker.applied("A")

    // Sensitive or invalid clipboard contents are supplied as null by the caller.
    assertNull(tracker.observe(null))
    assertEquals("A", tracker.observe("A"))

    assertNull(tracker.observe(""))
    assertNull(tracker.observe(""))
    assertEquals("B", tracker.observe("B"))
  }
}
