package com.pcremote

data class AudioState(val available: Boolean, val percent: Int, val muted: Boolean, val error: String?)
data class MediaCapabilities(val play: Boolean, val pause: Boolean, val previous: Boolean, val next: Boolean, val seek: Boolean)
data class MediaSession(val id: String, val app: String, val title: String, val playing: Boolean, val capabilities: MediaCapabilities, val positionSeconds: Double?)
data class InputState(val allowed: Boolean, val enabled: Boolean)
/** Present only when files.v1 was negotiated. */
data class FilesState(val allowed: Boolean, val maxBytes: Long)
data class Snapshot(val revision: Long, val audio: AudioState, val sessions: List<MediaSession>, val clipboardRevision: Long, val input: InputState?, val files: FilesState? = null)
data class Preferences(val upload: Boolean = false, val download: Boolean = false, val paused: Boolean = false, val autoApply: Boolean = false, val haptics: Boolean = true)
data class DiscoveredComputer(val name: String, val host: String, val port: Int, val id: String = "")
data class Invite(val host: String, val port: Int, val fingerprint: String, val code: String)
/** [received] counts what the PC confirmed, not what is queued on the socket. */
data class FileProgress(val name: String, val size: Long, val received: Long, val streaming: Boolean)
enum class FileOutcome { SENT, FAILED, CANCELLED }
data class FileResult(val message: String, val outcome: FileOutcome)
data class FilesView(val active: FileProgress? = null, val queued: List<String> = emptyList(), val paused: Boolean = false, val last: FileResult? = null)
data class UiState(
    val status: String = "Desconectado", val pcName: String = "", val error: String = "",
    val snapshot: Snapshot? = null, val selectedSessionId: String = "", val receivedText: String = "", val sharedText: String = "",
    val preferences: Preferences = Preferences(), val inputSupported: Boolean = false, val inputArmed: Boolean = false,
    val inputActivating: Boolean = false, val draggingVolume: Int? = null, val discovered: List<DiscoveredComputer> = emptyList(),
    /** The user left input on, but it is paused (background, focus loss) until the PC arms it again. */
    val inputResuming: Boolean = false,
    /** SHA-256 of the PC certificate during pairing, shown as a color strip to compare with the PC. */
    val pairingFingerprint: String = "",
    /** The PC advertised file.transfer on this connection. */
    val filesSupported: Boolean = false, val files: FilesView = FilesView(),
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
    data class FileOffer(val transferId: String, val name: String, val size: Long, val mime: String) : RemoteAction
    data class FileCancel(val transferId: String) : RemoteAction
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
    /** Shared text fills the draft; shared images and PDFs join the file queue. */
    fun receiveShare(intent: android.content.Intent)
    /** content:// documents from the picker or a share; each is checked before it joins the queue. */
    fun sendFiles(uris: List<android.net.Uri>)
    fun cancelFile()
    fun clearFileQueue()
    /** Restarts a queue paused by a failure that would repeat on the next file. */
    fun resumeFiles()
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
    /** Wheel steps from drag gestures; positive scrolls up, like the protocol. */
    fun scroll(steps: Int)
    fun cancelScroll()
    fun isInputTextAllowed(text: String): Boolean
    /** Round trip of a state read in milliseconds, or null when it fails. */
    fun ping(completion: (Long?) -> Unit)
    fun clearReceived()
    fun clearError()
    fun savedFingerprint(): String?
}
