package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintTest {
  // sha256("pc-remote"); the Windows app asserts the same values in src/lib/display.test.ts.
  private val digest = "6fab114e66015573129c77331ae7a7a6f891e79d840750f32bcd7a3d55bd3d53"

  @Test fun colorsMatchTheWindowsApp() {
    assertEquals(
      listOf(
        "hsl(245, 64%, 46%)", "hsl(150, 64%, 58%)", "hsl(353, 64%, 58%)", "hsl(311, 64%, 58%)",
        "hsl(243, 64%, 58%)", "hsl(129, 64%, 58%)", "hsl(224, 64%, 46%)", "hsl(165, 64%, 46%)",
      ),
      Fingerprint.swatches(digest).map { it.css() },
    )
  }

  @Test fun anyChangedByteChangesTheStrip() {
    assertNotEquals(Fingerprint.swatches(digest), Fingerprint.swatches(digest.dropLast(2) + "54"))
    assertEquals(Fingerprint.swatches(digest), Fingerprint.swatches(digest.uppercase()))
    assertTrue(Fingerprint.swatches("abc").isEmpty())
  }

  @Test fun groupsAreFourCharacters() {
    val groups = Fingerprint.groups("6f:ab:11:4e")
    assertEquals(listOf("6FAB", "114E"), groups)
    assertEquals(16, Fingerprint.groups(digest).size)
  }

  @Test fun hslConversionMatchesKnownColors() {
    assertEquals(0xFFFF0000.toInt(), Fingerprint.hslToArgb(0.0, 1.0, 0.5))
    assertEquals(0xFF00FF00.toInt(), Fingerprint.hslToArgb(120.0, 1.0, 0.5))
    assertEquals(0xFF808080.toInt(), Fingerprint.hslToArgb(200.0, 0.0, 0.5))
  }
}
