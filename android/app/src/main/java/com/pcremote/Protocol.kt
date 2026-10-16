package com.pcremote

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal sealed interface Incoming {
  data class Welcome(val id: String, val pcId: String, val pcName: String, val input: Boolean, val files: Boolean = false) : Incoming
  /** Sent before the ack of a file.offer; [id] is the offer request id. */
  data class FileTicket(val id: String, val transferId: String, val ticket: String, val chunkBytes: Int, val windowBytes: Int) : Incoming
  data class PairPending(val id: String) : Incoming
  data class PairResult(val id: String, val pcId: String, val pcName: String, val token: String) : Incoming
  data class State(val snapshot: Snapshot) : Incoming
  data class Clipboard(val eventId: String, val revision: Long, val text: String, val automatic: Boolean) : Incoming
  data class Ack(val id: String) : Incoming
  data class Error(val id: String, val code: String, val message: String, val retryable: Boolean) : Incoming
}

/** Text messages of the short-lived /v1/files socket. */
internal sealed interface FileMessage {
  data class Accept(val id: String, val transferId: String) : FileMessage
  data class Progress(val transferId: String, val received: Long) : FileMessage
  data class Done(val id: String, val transferId: String, val name: String) : FileMessage
  data class Error(val id: String, val code: String, val message: String, val retryable: Boolean) : FileMessage
}

internal class ClipboardEvents {
  private val ids=LinkedHashSet<String>()
  private var latest=-1L
  fun clear() { ids.clear(); latest=-1 }
  fun accept(event:Incoming.Clipboard,authoritativeRevision:Long):Boolean {
    if(event.eventId in ids || event.revision<latest || (event.automatic && event.revision<authoritativeRevision)) return false
    ids.add(event.eventId); if(ids.size>256) ids.remove(ids.first())
    latest=maxOf(latest,event.revision)
    return true
  }
}

internal object Protocol {
  const val MAX_FRAME = 524288
  /** Text limit the PC applies on /v1/files; nothing legitimate comes close. */
  const val MAX_FILE_FRAME = 4096
  const val MAX_CHUNK = 65536
  private const val MAX_WINDOW = 16777216
  private val tokenPattern = Regex("[a-fA-F0-9]{64}")
  private val lowerHex = Regex("[a-f0-9]{64}")
  private val errorCodes = setOf("invalid_message", "version_mismatch", "unauthorized", "pairing_expired", "pairing_denied", "rate_limited", "unavailable", "conflict", "sensitive", "too_large", "clipboard_busy", "internal", "file_type", "file_busy", "file_integrity", "storage")
  private val capabilities = setOf("audio", "media", "clipboard.text", "input.control", "file.transfer")
  private val keys = setOf("enter", "backspace", "tab", "escape", "left", "right", "up", "down", "delete", "home", "end")
  fun uuid(value: String): Boolean = try { UUID.fromString(value).toString().equals(value, true) } catch (_: Exception) { false }
  fun validText(value: String, limit: Int = 65536, input: Boolean = false): Boolean {
    if (input && value.isEmpty()) return false
    var size = 0
    var i = 0
    while (i < value.length) {
      val c = value[i]
      val cp: Int
      if (c.isHighSurrogate()) {
        if (i + 1 == value.length || !value[i + 1].isLowSurrogate()) return false
        cp = Character.toCodePoint(c, value[++i])
      } else if (c.isLowSurrogate()) return false else cp = c.code
      if (cp == 0 || (input && (cp < 32 || cp in 127..159))) return false
      size += when { cp <= 127 -> 1; cp <= 2047 -> 2; cp <= 65535 -> 3; else -> 4 }
      if (size > limit) return false
      i++
    }
    return true
  }
  private fun exact(o: JSONObject, vararg names: String) {
    val expected = names.toSet()
    if (o.length() != expected.size || o.keys().asSequence().toSet() != expected) throw IllegalArgumentException("fields")
  }
  private fun exactOptional(o: JSONObject, required: Set<String>, optional: Set<String>) {
    val actual = o.keys().asSequence().toSet()
    if (!actual.containsAll(required) || !((actual - required) - optional).isEmpty()) throw IllegalArgumentException("fields")
  }
  private fun str(o: JSONObject, k: String): String = o.opt(k) as? String ?: throw IllegalArgumentException(k)
  private fun bool(o: JSONObject, k: String): Boolean = o.opt(k) as? Boolean ?: throw IllegalArgumentException(k)
  private fun number(o: JSONObject, k: String): Long {
    val n = o.opt(k)
    if (n !is Number || n is Double && (!n.isFinite() || n % 1.0 != 0.0) || n is Float && (!n.isFinite() || n % 1f != 0f)) throw IllegalArgumentException(k)
    val d = n.toDouble()
    if (d < 0 || d > 9007199254740991.0 || d.toLong().toDouble() != d) throw IllegalArgumentException(k)
    return d.toLong()
  }
  private fun signed(o: JSONObject, k: String, min: Int, max: Int): Int {
    val n = o.opt(k)
    if (n !is Number || n.toDouble() % 1.0 != 0.0 || n.toDouble() !in min.toDouble()..max.toDouble()) throw IllegalArgumentException(k)
    return n.toInt()
  }
  private fun uid(o: JSONObject, k: String) = str(o, k).also { if (!uuid(it)) throw IllegalArgumentException(k) }
  private fun obj(o: JSONObject, k: String): JSONObject = o.opt(k) as? JSONObject ?: throw IllegalArgumentException(k)
  private fun array(o: JSONObject, k: String): JSONArray = o.opt(k) as? JSONArray ?: throw IllegalArgumentException(k)
  fun parseInvite(raw: String): Invite? = try {
    if (!validText(raw, 2048)) null else {
      val o = JSONObject(raw)
      exact(o, "v", "host", "port", "pcId", "fingerprint", "code")
      if (number(o, "v") != 1L) throw IllegalArgumentException("version")
      uid(o, "pcId")
      val host = str(o, "host")
      val port = signed(o, "port", 1, 65535)
      val fingerprint = str(o, "fingerprint")
      val code = str(o, "code")
      if (!host.matches(Regex("[a-zA-Z0-9.-]{1,253}")) || host.contains("..") ||
          !tokenPattern.matches(fingerprint) || !code.matches(Regex("[0-9]{8}")))
        throw IllegalArgumentException("invite")
      Invite(host, port, fingerprint.lowercase(), code)
    }
  } catch (_: Exception) { null }
  fun parse(raw: String): Incoming? { return try {
    if (!validText(raw, MAX_FRAME)) return null
    val o = JSONObject(raw)
    if (number(o, "v") != 1L) return null
    when (str(o, "type")) {
      "welcome" -> { exact(o, "v", "type", "id", "pcId", "pcName", "capabilities"); val arr = array(o,"capabilities"); if (arr.length() > 16) return null; val caps = (0 until arr.length()).map { arr.opt(it) as? String ?: return null }; if (!caps.all { it in capabilities }) return null; Incoming.Welcome(uid(o,"id"),uid(o,"pcId"),str(o,"pcName").also { if (!validText(it, 256)) return null },"input.control" in caps,"file.transfer" in caps) }
      "file.ticket" -> { exact(o,"v","type","id","transferId","ticket","chunkBytes","windowBytes"); val chunk=signed(o,"chunkBytes",1,MAX_CHUNK); Incoming.FileTicket(uid(o,"id"),uid(o,"transferId"),str(o,"ticket").also { if (!lowerHex.matches(it)) return null },chunk,signed(o,"windowBytes",chunk,MAX_WINDOW)) }
      "pair.pending" -> { exact(o,"v","type","id"); Incoming.PairPending(uid(o,"id")) }
      "pair.result" -> { exact(o,"v","type","id","pcId","pcName","token"); Incoming.PairResult(uid(o,"id"),uid(o,"pcId"),str(o,"pcName").also { if (!validText(it,256)) return null },str(o,"token").also { if (!tokenPattern.matches(it)) return null }) }
      "ack" -> { exact(o,"v","type","id"); Incoming.Ack(uid(o,"id")) }
      "error" -> { exact(o,"v","type","id","code","message","retryable"); Incoming.Error(uid(o,"id"),str(o,"code").also { if (it !in errorCodes) return null },str(o,"message").also { if (!validText(it,4096)) return null },bool(o,"retryable")) }
      "clipboard.event" -> { exact(o,"v","type","eventId","origin","revision","text","automatic"); if (str(o,"origin") != "windows") return null; Incoming.Clipboard(uid(o,"eventId"),number(o,"revision"),str(o,"text").also { if (!validText(it)) return null },bool(o,"automatic")) }
      "state" -> {
        exactOptional(o,setOf("v","type","revision","audio","media","clipboardRevision","sync"),setOf("input","files"))
        val audio = obj(o,"audio"); exact(audio,"available","percent","muted","error")
        val error = audio.opt("error"); if (error !== JSONObject.NULL && error !is String) return null
        if (error is String && !validText(error,4096)) return null
        val a = AudioState(bool(audio,"available"),signed(audio,"percent",0,100),bool(audio,"muted"),if(error === JSONObject.NULL) null else error as String)
        val media = obj(o,"media"); exact(media,"sessions"); val arr = array(media,"sessions"); if(arr.length()>128) return null
        val sessions = (0 until arr.length()).map { index ->
          val s = arr.opt(index) as? JSONObject ?: return null
          exactOptional(s,setOf("id","app","title","playing","capabilities"),setOf("positionSeconds"))
          val cap = obj(s,"capabilities"); exact(cap,"play","pause","previous","next","seek")
          val position = s.opt("positionSeconds")
          if(position != null && position !== JSONObject.NULL && (position !is Number || !position.toDouble().isFinite() || position.toDouble()<0)) return null
          MediaSession(str(s,"id").also { if (it.isEmpty() || !validText(it,256)) return null },str(s,"app").also { if(!validText(it,256)) return null },str(s,"title").also { if(!validText(it,4096)) return null },bool(s,"playing"),MediaCapabilities(bool(cap,"play"),bool(cap,"pause"),bool(cap,"previous"),bool(cap,"next"),bool(cap,"seek")),(position as? Number)?.toDouble())
        }
        val sync=obj(o,"sync"); exact(sync,"upload","download","paused"); bool(sync,"upload"); bool(sync,"download"); bool(sync,"paused")
        val input = if(o.has("input")) { val i=obj(o,"input"); exact(i,"allowed","enabled"); InputState(bool(i,"allowed"),bool(i,"enabled")) } else null
        val files = if(o.has("files")) { val f=obj(o,"files"); exact(f,"allowed","maxBytes"); FilesState(bool(f,"allowed"),number(f,"maxBytes").also { if(it<1 || it>FileRules.MAX_BYTES) return null }) } else null
        Incoming.State(Snapshot(number(o,"revision"),a,sessions,number(o,"clipboardRevision"),input,files))
      }
      else -> null
    }
  } catch (_: Exception) { null } }
  /** Strict parser for text frames of /v1/files; anything else ends the transfer. */
  fun parseFile(raw: String): FileMessage? { return try {
    if (!validText(raw, MAX_FILE_FRAME)) return null
    val o = JSONObject(raw)
    if (number(o, "v") != 1L) return null
    when (str(o, "type")) {
      "file.accept" -> { exact(o,"v","type","id","transferId"); FileMessage.Accept(uid(o,"id"),uid(o,"transferId")) }
      "file.progress" -> { exact(o,"v","type","transferId","received"); FileMessage.Progress(uid(o,"transferId"),number(o,"received").also { if (it>FileRules.MAX_BYTES) return null }) }
      "file.done" -> { exact(o,"v","type","id","transferId","name"); FileMessage.Done(uid(o,"id"),uid(o,"transferId"),str(o,"name").also { if (!validText(it,1024,true)) return null }) }
      "error" -> { exact(o,"v","type","id","code","message","retryable"); FileMessage.Error(uid(o,"id"),str(o,"code").also { if (it !in errorCodes) return null },str(o,"message").also { if (!validText(it,4096)) return null },bool(o,"retryable")) }
      else -> null
    }
  } catch (_: Exception) { null } }
  fun fileBegin(id: String, transferId: String, ticket: String): String? =
    if (uuid(id) && uuid(transferId) && lowerHex.matches(ticket)) JSONObject().put("v",1).put("type","file.begin").put("id",id).put("transferId",transferId).put("ticket",ticket).toString() else null
  fun fileEnd(id: String, transferId: String, sha256: String): String? =
    if (uuid(id) && uuid(transferId) && lowerHex.matches(sha256)) JSONObject().put("v",1).put("type","file.end").put("id",id).put("transferId",transferId).put("sha256",sha256).toString() else null
  fun action(a: RemoteAction): JSONObject? = try {
    when(a) {
      RemoteAction.StateGet -> JSONObject().put("kind","state.get")
      is RemoteAction.VolumeSet -> if(a.percent in 0..100) JSONObject().put("kind","volume.set").put("percent",a.percent) else null
      is RemoteAction.VolumeStep -> if(a.delta == -5 || a.delta == 5) JSONObject().put("kind","volume.step").put("delta",a.delta) else null
      is RemoteAction.VolumeMute -> JSONObject().put("kind","volume.mute").put("muted",a.muted)
      is RemoteAction.MediaControl -> if(a.sessionId.isNotEmpty() && validText(a.sessionId,256) && a.command in setOf("play","pause","previous","next","seek") && (a.command == "seek") == (a.offsetSeconds != null) && (a.offsetSeconds == null || a.offsetSeconds == -10 || a.offsetSeconds == 10)) JSONObject().put("kind","media.control").put("sessionId",a.sessionId).put("command",a.command).apply { if(a.offsetSeconds != null) put("offsetSeconds",a.offsetSeconds) } else null
      is RemoteAction.ClipboardSet -> if(uuid(a.eventId) && validText(a.text) && a.baseRevision >= 0) JSONObject().put("kind","clipboard.set").put("eventId",a.eventId).put("origin","android").put("text",a.text).put("automatic",a.automatic).put("baseRevision",a.baseRevision) else null
      RemoteAction.ClipboardGet -> JSONObject().put("kind","clipboard.get")
      is RemoteAction.SyncSet -> JSONObject().put("kind","sync.set").put("upload",a.upload).put("download",a.download).put("paused",a.paused)
      is RemoteAction.InputEnable -> JSONObject().put("kind","input.enable").put("enabled",a.enabled)
      is RemoteAction.PointerMove -> if(a.dx in -512..512 && a.dy in -512..512 && (a.dx != 0 || a.dy != 0)) JSONObject().put("kind","pointer.move").put("dx",a.dx).put("dy",a.dy) else null
      is RemoteAction.PointerClick -> if(a.button in setOf("left","right") && a.count in 1..2 && (a.button != "right" || a.count == 1)) JSONObject().put("kind","pointer.click").put("button",a.button).put("count",a.count) else null
      is RemoteAction.PointerScroll -> if(a.steps in -10..10 && a.steps != 0) JSONObject().put("kind","pointer.scroll").put("steps",a.steps) else null
      is RemoteAction.KeyboardText -> if(validText(a.text,1024,true)) JSONObject().put("kind","keyboard.text").put("text",a.text) else null
      is RemoteAction.KeyboardKey -> if(a.key in keys) JSONObject().put("kind","keyboard.key").put("key",a.key) else null
      is RemoteAction.FileOffer -> if(uuid(a.transferId) && FileRules.offerValid(a.name,a.size,a.mime)) JSONObject().put("kind","file.offer").put("transferId",a.transferId).put("name",a.name).put("size",a.size).put("mime",a.mime) else null
      is RemoteAction.FileCancel -> if(uuid(a.transferId)) JSONObject().put("kind","file.cancel").put("transferId",a.transferId) else null
    }
  } catch (_: Exception) { null }
}
