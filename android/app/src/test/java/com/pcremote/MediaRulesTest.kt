package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRulesTest {
  private val all = MediaCapabilities(play = true, pause = true, previous = true, next = true, seek = true)
  private fun session(id: String, playing: Boolean, caps: MediaCapabilities = all, title: String = "Música") = MediaSession(id, "Spotify", title, playing, caps, null)
  private fun snapshot(vararg sessions: MediaSession) = Snapshot(1, AudioState(true, 30, false, null), sessions.toList(), 0, null)
  private val pcs = listOf(SavedComputer("pc-1", "Escritório", "Windows 11", true))

  @Test fun followsThePickedSessionElseThePcsCurrentOne() {
    val s = snapshot(session("a", false), session("b", true))
    assertEquals("a", MediaRules.followed(s, "")!!.id)
    assertEquals("b", MediaRules.followed(s, "b")!!.id)
    assertEquals("a", MediaRules.followed(s, "gone")!!.id)
    assertNull(MediaRules.followed(snapshot(), ""))
    assertNull(MediaRules.followed(null, ""))
  }

  @Test fun toggleBecomesPauseWhilePlayingAndPlayOtherwise() {
    assertEquals("pause" to null, MediaRules.command(MediaRules.TOGGLE, session("a", true)))
    assertEquals("play" to null, MediaRules.command(MediaRules.TOGGLE, session("a", false)))
    assertNull(MediaRules.command(MediaRules.TOGGLE, session("a", true, all.copy(pause = false))))
  }

  @Test fun commandsFollowTheSessionCapabilities() {
    assertEquals("seek" to 10, MediaRules.command(MediaRules.FORWARD, session("a", true)))
    assertEquals("seek" to -10, MediaRules.command(MediaRules.REWIND, session("a", true)))
    assertEquals("next" to null, MediaRules.command(MediaRules.NEXT, session("a", true)))
    val bare = MediaCapabilities(play = true, pause = true, previous = false, next = false, seek = false)
    assertNull(MediaRules.command(MediaRules.NEXT, session("a", true, bare)))
    assertNull(MediaRules.command(MediaRules.PREVIOUS, session("a", true, bare)))
    assertNull(MediaRules.command(MediaRules.FORWARD, session("a", true, bare)))
    assertNull(MediaRules.command("stop", session("a", true)))
  }

  @Test fun startsOnlyWhileTheAppIsOpenAndThePcPlays() {
    assertTrue(MediaRules.shouldStart(true, session("a", true), foreground = true, suppressed = false))
    assertFalse(MediaRules.shouldStart(true, session("a", false), foreground = true, suppressed = false))
    assertFalse(MediaRules.shouldStart(true, session("a", true), foreground = false, suppressed = false))
    assertFalse(MediaRules.shouldStart(true, session("a", true), foreground = true, suppressed = true))
    assertFalse(MediaRules.shouldStart(false, session("a", true), foreground = true, suppressed = false))
    assertFalse(MediaRules.shouldStart(true, null, foreground = true, suppressed = false))
  }

  @Test fun widgetTextForEachState() {
    assertEquals(WidgetContent("PC Remote", "Pareie um PC no app", false), MediaRules.widget(UiState()))
    val idle = UiState(pcName = "Escritório", computers = pcs)
    assertEquals(WidgetContent("Escritório", "Toque ⏯ para conectar", false), MediaRules.widget(idle))
    val connected = idle.copy(status = "Conectado", snapshot = snapshot())
    assertEquals(WidgetContent("Nada tocando", "Escritório", false), MediaRules.widget(connected))
    val playing = connected.copy(snapshot = snapshot(session("a", true, title = "")))
    assertEquals(WidgetContent("Spotify", "Spotify · Escritório", true), MediaRules.widget(playing))
  }

  @Test fun keepPolicyEndsWithoutSessionsAfter30Seconds() {
    val policy = KeepPolicy()
    assertNull(policy.update(0, true, listOf(session("a", true)), false))
    assertNull(policy.update(1_000, true, emptyList(), false))
    assertNull(policy.update(30_999, true, emptyList(), false))
    assertEquals(KeepPolicy.Stop.NO_SESSION, policy.update(31_000, true, emptyList(), false))
  }

  @Test fun keepPolicyCountsPausesOnlyWithTheAppClosed() {
    val policy = KeepPolicy()
    val paused = listOf(session("a", false))
    assertNull(policy.update(0, true, paused, foreground = true))
    assertNull(policy.update(20 * 60_000L, true, paused, foreground = true))
    assertNull(policy.update(20 * 60_000L + 1, true, paused, foreground = false))
    assertNull(policy.update(30 * 60_000L, true, paused, foreground = false))
    assertEquals(KeepPolicy.Stop.PAUSED, policy.update(35 * 60_000L + 1, true, paused, foreground = false))
    // Playing again resets the count.
    val fresh = KeepPolicy()
    assertNull(fresh.update(0, true, paused, false))
    assertNull(fresh.update(10 * 60_000L, true, listOf(session("a", true)), false))
    assertNull(fresh.update(20 * 60_000L, true, paused, false))
    assertNull(fresh.update(34 * 60_000L, true, paused, false))
  }

  @Test fun keepPolicyGivesTheConnectionAMinuteToComeBack() {
    val policy = KeepPolicy()
    assertNull(policy.update(0, false, null, false))
    assertNull(policy.update(59_999, false, null, false))
    assertNull(policy.update(60_000, true, null, false))
    assertNull(policy.update(70_000, false, null, false))
    assertEquals(KeepPolicy.Stop.DISCONNECTED, policy.update(130_000, false, null, false))
  }
}
