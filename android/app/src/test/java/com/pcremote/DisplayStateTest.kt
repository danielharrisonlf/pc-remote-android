package com.pcremote

import org.junit.Assert.*
import org.junit.Test

class DisplayStateTest {
  private val media = MediaSession("player", "Player", "Track A", true,
    MediaCapabilities(true, true, true, true, true), 10.0)
  private val snapshot = Snapshot(1, AudioState(true, 50, false, null), listOf(media), 0, InputState(true, false))
  private val state = UiState(status = "Conectado", snapshot = snapshot)

  @Test fun pollingWithoutVisibleChangesDoesNotRequireAnotherRender() {
    val next = state.copy(snapshot = snapshot.copy(revision = 2, clipboardRevision = 3,
      sessions = listOf(media.copy(positionSeconds = 11.0))))
    assertEquals(state.forDisplay(), next.forDisplay())
    assertEquals(2L, next.snapshot!!.revision)
    assertEquals(3L, next.snapshot.clipboardRevision)
    assertEquals(11.0, next.snapshot.sessions.single().positionSeconds)
  }

  @Test fun realControlChangesAndNewTrackWithSameSessionIdStillRender() {
    val changes = listOf(
      state.copy(snapshot = snapshot.copy(audio = snapshot.audio.copy(percent = 51))),
      state.copy(snapshot = snapshot.copy(audio = snapshot.audio.copy(muted = true))),
      state.copy(snapshot = snapshot.copy(sessions = listOf(media.copy(title = "Track B")))) ,
      state.copy(snapshot = snapshot.copy(sessions = listOf(media.copy(capabilities = media.capabilities.copy(seek = false))))),
      state.copy(inputArmed = true), state.copy(receivedText = "New text"),
      state.copy(status = "Desconectado"), state.copy(error = "Unavailable"),
    )
    changes.forEach { assertNotEquals(state.forDisplay(), it.forDisplay()) }
  }
}
