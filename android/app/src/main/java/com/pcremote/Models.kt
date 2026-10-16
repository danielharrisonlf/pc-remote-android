package com.pcremote

data class AudioState(val available: Boolean, val percent: Int, val muted: Boolean, val error: String?)
data class MediaCapabilities(val play: Boolean, val pause: Boolean, val previous: Boolean, val next: Boolean, val seek: Boolean)
data class MediaSession(val id: String, val app: String, val title: String, val playing: Boolean, val capabilities: MediaCapabilities, val positionSeconds: Double?)
data class InputState(val allowed: Boolean, val enabled: Boolean)
data class Snapshot(val revision: Long, val audio: AudioState, val sessions: List<MediaSession>, val clipboardRevision: Long, val input: InputState?)
data class Preferences(val upload: Boolean = false, val download: Boolean = false, val paused: Boolean = false, val autoApply: Boolean = false, val haptics: Boolean = true)
data class DiscoveredComputer(val name: String, val host: String, val port: Int, val id: String = "")
data class Invite(val host: String, val port: Int, val fingerprint: String, val code: String)
data class UiState(
    val status: String = "Desconectado", val pcName: String = "", val error: String = "",
    val snapshot: Snapshot? = null, val selectedSessionId: String = "", val receivedText: String = "", val sharedText: String = "",
    val preferences: Preferences = Preferences(), val inputSupported: Boolean = false, val inputArmed: Boolean = false,
    val inputActivating: Boolean = false, val draggingVolume: Int? = null, val discovered: List<DiscoveredComputer> = emptyList(),
    /** SHA-256 of the PC certificate during pairing, shown as a color strip to compare with the PC. */
    val pairingFingerprint: String = "",
)
sealed interface RemoteAction {
    data object StateGet : RemoteAction
    data class VolumeSet(val percent: Int) : RemoteAction
    data class VolumeStep(val delta: Int) : RemoteAction
    data class VolumeMute(val muted: Boolean) : RemoteAction
    data class MediaControl(val sessionId: String, val command: String, val offsetSeconds: Int? = null) : RemoteAction
    data class ClipboardSet(val eventId: String, val text: String, val automatic: Boolean, val baseRevision: Long) : RemoteAction
    data object ClipboardGet : RemoteAction
    data class SyncSet(val upload: Boolean, val download: Boolean, val paused: Boolean) : RemoteAction
    data class InputEnable(val enabled: Boolean) : RemoteAction
    data class PointerMove(val dx: Int, val dy: Int) : RemoteAction
    data class PointerClick(val button: String, val count: Int) : RemoteAction
    data class PointerScroll(val steps: Int) : RemoteAction
    data class KeyboardText(val text: String) : RemoteAction
    data class KeyboardKey(val key: String) : RemoteAction
}

interface RemoteController {
    val view: UiState
    fun observe(listener: (UiState) -> Unit): () -> Unit
    fun foreground(activity: android.app.Activity)
    fun focusChanged(hasFocus: Boolean)
    fun background()
    fun pair(host: String, port: Int, fingerprint: String, code: String)
    /** Pairs with a PC found on the network using only the code shown on the PC. */
    fun pairWithCode(pc: DiscoveredComputer, code: String)
    fun reconnect()
    fun disconnect()
    fun forget()
    fun savedAddress(): Pair<String, Int>?
    fun updateAddress(host: String, port: Int, completion: (String?) -> Unit)
    fun startDiscovery()
    fun stopDiscovery()
    fun execute(action: RemoteAction, completion: (String?) -> Unit = {})
    fun selectSession(id: String)
    fun setPreferences(preferences: Preferences)
    fun receiveShare(intent: android.content.Intent)
    fun sendClipboard(text: String?, completion: (String?) -> Unit)
    fun getClipboard(completion: (String?) -> Unit)
    fun copyReceived(completion: (String?) -> Unit)
    fun beginVolume(value: Int)
    fun updateVolume(value: Int)
    fun endVolume(value: Int)
    fun enableInput(completion: (String?) -> Unit)
    fun disableInput()
    fun movePointer(dx: Float, dy: Float)
    fun cancelPointerMotion()
    fun isInputTextAllowed(text: String): Boolean
}
