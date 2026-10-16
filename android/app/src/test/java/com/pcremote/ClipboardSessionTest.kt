package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ClipboardSessionTest {
  private fun event(revision: Long, text: String, automatic: Boolean = true) =
    Incoming.Clipboard(UUID.randomUUID().toString(), revision, text, automatic)

  @Test fun sameSessionResumeDeliversTheOriginalAcceptedEventExactlyOnce() {
    val session = ClipboardSession()
    val received = event(7, "owned background sample")
    assertTrue(session.accept(received, 7))
    session.defer(received)
    assertSame(received, session.takePending())
    assertNull(session.takePending())
    // Delivery does not mint a new event ID or turn an automatic event into a manual one.
    assertFalse(session.accept(received, 7))
    assertEquals(7L, session.revision)
  }

  @Test fun retiringConnectionDropsDeferredContentAndItsRevision() {
    val session = ClipboardSession()
    val received = event(12, "owned retired sample")
    assertTrue(session.accept(received, 12))
    session.defer(received)
    session.retire()
    assertNull(session.takePending())
    assertEquals(0L, session.revision)
    // A PC restart/new connection can legitimately start again at a lower revision.
    val fresh = event(1, "owned current sample")
    assertTrue(session.accept(fresh, 1))
    session.defer(fresh)
    assertSame(fresh, session.takePending())
  }

  @Test fun retiringConnectionClearsDedupWithoutResurrectingItsPendingEvent() {
    val session = ClipboardSession()
    val received = event(3, "owned sample", false)
    assertTrue(session.accept(received, 3))
    session.defer(received)
    session.retire()
    assertNull(session.takePending())
    assertTrue(session.accept(received, 0))
    assertNull(session.takePending())
  }

  @Test fun latestAcceptedBackgroundPreviewWinsAndRevisionNeverRegresses() {
    val session = ClipboardSession()
    val first = event(4, "owned first sample")
    val latest = event(5, "owned latest sample")
    assertTrue(session.accept(first, 4)); session.defer(first)
    assertTrue(session.accept(latest, 5)); session.defer(latest)
    session.advanceRevision(3)
    assertFalse(session.accept(event(3, "owned stale sample"), 5))
    assertSame(latest, session.takePending())
    assertEquals(5L, session.revision)
  }
}
