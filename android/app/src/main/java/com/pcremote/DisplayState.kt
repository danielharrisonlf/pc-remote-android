package com.pcremote

/** Revisions and playback position are tracked by the client, but are not displayed by these Views. */
internal fun UiState.forDisplay(): UiState = copy(snapshot = snapshot?.let { current ->
  current.copy(revision = 0, clipboardRevision = 0,
    sessions = current.sessions.map { it.copy(positionSeconds = null) })
})
