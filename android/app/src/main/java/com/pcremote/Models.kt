package com.pcremote

data class AudioState(val available: Boolean, val percent: Int, val muted: Boolean, val error: String?)
data class MediaCapabilities(val play: Boolean, val pause: Boolean, val previous: Boolean, val next: Boolean, val seek: Boolean)
data class MediaSession(val id: String, val app: String, val title: String, val playing: Boolean, val capabilities: MediaCapabilities, val positionSeconds: Double?)
data class InputState(val allowed: Boolean, val enabled: Boolean)
/** Present only when files.v1 was negotiated. */
data class FilesState(val allowed: Boolean, val maxBytes: Long)
data class Snapshot(val revision: Long, val audio: AudioState, val sessions: List<MediaSession>, val clipboardRevision: Long, val input: InputState?, val files: FilesState? = null, val power: PowerState? = null)
/** power.v1: [allowed] is false while Interromper is on in the PC; [scheduled] is the one pending action. */
data class PowerState(val allowed: Boolean, val scheduled: PowerSchedule?)
/** [action] is lock, sleep, shutdown or restart; [remainingSeconds] counts from the snapshot. */
data class PowerSchedule(val action: String, val remainingSeconds: Long)
/** The only connection preference: clipboard sync in both directions. Manual sends never depend on it. */
data class Preferences(val realtime: Boolean = true)
data class DiscoveredComputer(val name: String, val host: String, val port: Int, val id: String = "")
data class Invite(val host: String, val port: Int, val fingerprint: String, val code: String)
/** [received] counts what the other side confirmed (sending) or what was written (receiving). */
data class FileProgress(val name: String, val size: Long, val received: Long, val streaming: Boolean)
enum class FileOutcome { SENT, FAILED, CANCELLED }
data class FileResult(val message: String, val outcome: FileOutcome)
/** A finished transfer kept in RAM for the list; [uri] opens a file received on Android 10+. */
data class FileRecord(val name: String, val size: Long, val at: Long, val toPc: Boolean, val uri: String? = null, val mime: String = "")
data class FilesView(
    val active: FileProgress? = null, val queued: List<String> = emptyList(), val paused: Boolean = false, val last: FileResult? = null,
    /** A file the PC is sending to this phone (files.v2). */
    val incoming: FileProgress? = null,
    /** Newest first, at most 12. */
    val history: List<FileRecord> = emptyList(),
)
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
    /** file.receive: the PC speaks files.v2 (any file up to 1 GB, both directions). */
    val filesTwoWay: Boolean = false,
    /** search.youtube: the PC opens YouTube searches. */
    val searchSupported: Boolean = false,
    /** "Windows 11" from info.v1; kept while disconnected. */
    val pcOs: String = "",
    /** Every paired PC, the current one first (at most PcList.MAX). */
    val computers: List<SavedComputer> = emptyList(),
    /** power.control: the PC locks, sleeps, shuts down and restarts on request (power.v1). */
    val powerSupported: Boolean = false,
    /** Wall-clock time of the scheduled power action, from the last snapshot; 0 without one. */
    val powerDeadline: Long = 0L,
    /** clipboard.image: images on the clipboard go both ways (clipboard.v2). */
    val clipboardImages: Boolean = false,
    /** The image from the PC now on the phone clipboard (a file in the app cache), or "". */
    val receivedImage: String = "",
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
    /** [twoWay]: files.v2 rules (any type, 1 GB); otherwise files.v1. */
    data class FileOffer(val transferId: String, val name: String, val size: Long, val mime: String, val twoWay: Boolean = false, val clipboard: ClipOffer? = null) : RemoteAction
    data class FileCancel(val transferId: String) : RemoteAction
    /** files.v2: refuses a file.push; [code] is storage or unavailable. */
    data class FileDecline(val transferId: String, val code: String) : RemoteAction
    /** search.v1: the PC opens a YouTube search for [query]. */
    data class SearchOpen(val query: String) : RemoteAction
    /** power.v1: [action] lock, sleep, shutdown or restart, now (0) or in [delaySeconds]. */
    data class PowerRun(val action: String, val delaySeconds: Int) : RemoteAction
    data object PowerCancel : RemoteAction
}
/** clipboard.v2: the clipboard part of a file.offer; [baseRevision] is the clipboard revision the copy builds on. */
data class ClipOffer(val eventId: String, val automatic: Boolean, val baseRevision: Long)

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
    /** Makes a saved PC the current one and connects to it. */
    fun switchComputer(id: String)
    /** Removes a saved PC; removing the current one moves to the most recently used. */
    fun forgetComputer(id: String)
    fun startDiscovery()
    fun stopDiscovery()
    fun execute(action: RemoteAction, completion: (String?) -> Unit = {})
    fun selectSession(id: String)
    fun setPreferences(preferences: Preferences)
    /** Shared text fills the draft; shared files join the file queue. */
    fun receiveShare(intent: android.content.Intent)
    /** content:// documents from the picker or a share; each is checked before it joins the queue. */
    fun sendFiles(uris: List<android.net.Uri>)
    fun cancelFile()
    /** Stops the file the PC is sending to this phone. */
    fun cancelIncoming()
    fun clearFileQueue()
    /** Restarts a queue paused by a failure that would repeat on the next file. */
    fun resumeFiles()
    fun sendClipboard(text: String?, completion: (String?) -> Unit)
    /** Asks the PC for its clipboard and copies it on the phone. */
    fun getClipboard(completion: (String?) -> Unit)
    fun copyReceived(completion: (String?) -> Unit)
    /** Opens a YouTube search on the PC (search.v1). */
    fun search(query: String, completion: (String?) -> Unit)
    fun beginVolume(value: Int)
    fun updateVolume(value: Int)
    fun endVolume(value: Int)
    /** "Ativar touchpad": also lifts an earlier "Desligar". */
    fun enableInput(completion: (String?) -> Unit)
    /** "Desligar": input stays off until "Ativar touchpad", across reconnections. */
    fun disableInput()
    fun movePointer(dx: Float, dy: Float)
    fun cancelPointerMotion()
    /** Wheel steps from drag gestures; positive scrolls up, like the protocol. */
    fun scroll(steps: Int)
    fun cancelScroll()
    fun isInputTextAllowed(text: String): Boolean
    fun clearReceived()
    fun clearError()
}
