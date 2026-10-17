package com.pcremote

import org.junit.Assert.*
import org.junit.Test

class ClipImagesTest {
  private fun push(mime: String = "image/png", size: Long = 1024, revision: Long = 5, automatic: Boolean = true) =
    Incoming.FilePush("7d0b5f6e-2c1a-4e8b-9f3d-5a6b7c8d9e61", "Imagem do PC.png", size, mime, "c0ffee00".repeat(8), ClipPush(revision, automatic))

  @Test fun pushesFollowTheLimitsSyncAndRevision() {
    assertTrue(ClipImages.pushAccepted(push(), realtime = true, seenRevision = 5))
    assertTrue(ClipImages.pushAccepted(push(size = ClipImages.MAX_BYTES), realtime = true, seenRevision = 0))
    assertFalse(ClipImages.pushAccepted(push(size = ClipImages.MAX_BYTES + 1), realtime = true, seenRevision = 0))
    assertFalse(ClipImages.pushAccepted(push(mime = "image/jpeg"), realtime = true, seenRevision = 0))
    // Automatic: only with sync on and not older than what the phone saw.
    assertFalse(ClipImages.pushAccepted(push(), realtime = false, seenRevision = 0))
    assertFalse(ClipImages.pushAccepted(push(revision = 4), realtime = true, seenRevision = 5))
    // "Buscar do PC" always copies.
    assertTrue(ClipImages.pushAccepted(push(revision = 1, automatic = false), realtime = false, seenRevision = 9))
    // A plain file push is not a clipboard push.
    assertFalse(ClipImages.pushAccepted(push().copy(clipboard = null), realtime = true, seenRevision = 0))
  }

  @Test fun plansSendPngAndJpegAsTheyAreAndConvertTheRest() {
    assertEquals(ClipImages.Plan.AsIs("image/png"), ClipImages.plan("image/png", 1920, 1080, 2_000_000))
    assertEquals(ClipImages.Plan.AsIs("image/jpeg"), ClipImages.plan("image/jpeg", 8000, 6250, -1))
    assertTrue(ClipImages.plan("image/jpeg", 8000, 6251, -1) is ClipImages.Plan.Refused)
    assertTrue(ClipImages.plan("image/png", 100, 100, ClipImages.MAX_BYTES + 1) is ClipImages.Plan.Refused)
    assertEquals(ClipImages.Plan.Convert, ClipImages.plan(null, 4000, 4000, 50_000_000))
    assertTrue(ClipImages.plan(null, 4000, 4001, 1) is ClipImages.Plan.Refused)
    assertTrue(ClipImages.plan(null, 0, 0, 10) is ClipImages.Plan.Refused)
    assertTrue(ClipImages.plan("image/png", -1, 10, 10) is ClipImages.Plan.Refused)
  }

  @Test fun imageKeysNeverCollideWithTextAndOwnImagesAreRecognized() {
    val uri = "content://com.pcremote.nativeandroid.clipboard/clipboard/abc.png"
    val key = ClipImages.imageKey(uri)
    assertEquals(uri, ClipImages.imageUri(key))
    assertNull(ClipImages.imageUri(uri))
    assertNull(ClipImages.imageUri(null))
    // Valid clipboard text never contains NUL, so no text can pass for an image.
    assertFalse(Protocol.validText(key))
    assertTrue(ClipImages.own(uri, "com.pcremote.nativeandroid.clipboard"))
    assertFalse(ClipImages.own("content://com.pcremote.nativeandroid.clipboardx/a.png", "com.pcremote.nativeandroid.clipboard"))
    assertFalse(ClipImages.own("content://media/external/images/1", "com.pcremote.nativeandroid.clipboard"))
  }

  @Test fun trackerHandlesImagesLikeText() {
    val tracker = ClipboardTracker()
    val fromPc = ClipImages.imageKey("content://app.clipboard/clipboard/1.png")
    val screenshot = ClipImages.imageKey("content://media/external/images/7")
    tracker.baseline("texto")
    // What the app put there from the PC is the reference: it does not go back.
    tracker.applied(fromPc)
    assertNull(tracker.observe(fromPc))
    assertEquals(screenshot, tracker.observe(screenshot))
    tracker.suspend()
    // Copied in another app while away: sent once on return.
    assertEquals(fromPc, tracker.resume(fromPc))
    assertNull(tracker.resume(fromPc))
  }

  @Test fun offerRulesAndWording() {
    assertTrue(ClipImages.offerValid("Imagem.png", 1, "image/png"))
    assertFalse(ClipImages.offerValid("", 1, "image/png"))
    assertFalse(ClipImages.offerValid("Imagem.gif", 1, "image/gif"))
    assertEquals("Houve outra cópia no PC antes desta.", ClipImages.errorMessage("conflict", "x"))
    assertEquals("A imagem passa de 32 MB.", ClipImages.errorMessage("too_large", "x"))
  }
}
