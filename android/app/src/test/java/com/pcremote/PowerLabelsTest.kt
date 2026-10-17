package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerLabelsTest {
  @Test fun choicesAreTheOnlyDelaysOffered() {
    assertEquals(listOf(0, 900, 1800, 3600, 7200), PowerLabels.choices.map { it.second })
    assertTrue(PowerLabels.choices.all { (_, delay) -> Protocol.powerValid("shutdown", delay) && Protocol.powerValid("sleep", delay) })
  }

  @Test fun countdownAndMessages() {
    assertEquals("em instantes", PowerLabels.remaining(0))
    assertEquals("em 1 min", PowerLabels.remaining(1_000))
    assertEquals("em 28 min", PowerLabels.remaining(27 * 60_000L + 10_000))
    assertEquals("em 1 h 05 min", PowerLabels.remaining(65 * 60_000L))
    assertEquals("Desliga", PowerLabels.verb("shutdown"))
    assertEquals("Suspende", PowerLabels.verb("sleep"))
    assertEquals("Agendado: desliga às 23:40.", PowerLabels.started("shutdown", 1800, "23:40"))
    assertEquals("Desligando o PC…", PowerLabels.started("shutdown", 0, "23:40"))
    assertEquals("PC bloqueado.", PowerLabels.started("lock", 0, "23:40"))
    assertEquals("Desliga às 23:40 (em 28 min)", PowerLabels.pending("shutdown", "23:40", 28 * 60_000L))
  }
}
