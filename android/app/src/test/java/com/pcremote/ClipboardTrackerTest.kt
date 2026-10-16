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

  @Test fun aCopyMadeInAnotherAppIsSentOnceOnReturn() {
    val tracker = ClipboardTracker()
    // First focus of the process: only a reference, nothing is sent.
    assertNull(tracker.resume("copied before the app opened"))
    tracker.suspend()
    assertEquals("copied in the browser", tracker.resume("copied in the browser"))
    tracker.suspend()
    assertNull(tracker.resume("copied in the browser"))
    // A copy in focus becomes the reference too.
    assertEquals("B", tracker.observe("B"))
    tracker.suspend()
    assertNull(tracker.resume("B"))
  }

  @Test fun textAppliedFromThePcNeverEchoesBack() {
    val tracker = ClipboardTracker()
    tracker.resume("A")
    tracker.suspend()
    // Arrived in the background and was written to the phone clipboard.
    tracker.applied("from the PC")
    assertNull(tracker.resume("from the PC"))
    assertNull(tracker.observe("from the PC"))
  }

  @Test fun sensitiveOrMissingTextKeepsTheReference() {
    val tracker = ClipboardTracker()
    tracker.resume("A")
    tracker.suspend()
    assertNull(tracker.resume(null))
    tracker.suspend()
    assertNull(tracker.resume("A"))
    tracker.suspend()
    assertNull(tracker.resume(""))
    assertEquals("A", tracker.remembered)
  }
}
