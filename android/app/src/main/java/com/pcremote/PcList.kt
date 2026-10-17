package com.pcremote

import org.json.JSONArray
import org.json.JSONObject

/** A paired PC as the Conexão screen lists it; [id] is the PC's pcId. */
data class SavedComputer(val id: String, val name: String, val os: String, val current: Boolean)

/** One saved pairing: credentials, the Windows name from info.v1 and when it was last used. */
internal data class PcEntry(val computer: PairedComputer, val os: String = "", val lastUsed: Long = 0)

/**
 * The paired PCs (at most [MAX]) and the one the app connects to. Pure data, so the
 * rules run in JVM tests; CredentialStore encrypts [toJson] with the Keystore key.
 */
internal data class PcList(val entries: List<PcEntry> = emptyList(), val currentId: String = "") {
  val current: PcEntry? get() = entries.firstOrNull { key(it.computer) == currentId }

  fun has(id: String) = entries.any { key(it.computer) == id }

  /** A new PC fits while fewer than [MAX] are saved; pairing a saved PC again always fits. */
  fun canAdd(id: String) = has(id) || entries.size < MAX

  /** Saves [computer] (replacing the entry with the same pcId) as the current PC; null when full. */
  fun upsert(computer: PairedComputer, now: Long): PcList? {
    val id = key(computer)
    if (!canAdd(id)) return null
    val old = entries.firstOrNull { key(it.computer) == id }
    val entry = PcEntry(computer, old?.os.orEmpty(), now)
    return PcList(if (old == null) entries + entry else entries.map { if (it === old) entry else it }, id)
  }

  fun select(id: String, now: Long): PcList =
    if (!has(id)) this else PcList(entries.map { if (key(it.computer) == id) it.copy(lastUsed = now) else it }, id)

  /** Name and Windows edition from a welcome; [now] marks the PC as just used. */
  fun touch(id: String, name: String, os: String, now: Long): PcList =
    PcList(entries.map { if (key(it.computer) == id) it.copy(computer = it.computer.copy(pcName = name), os = os, lastUsed = now) else it }, currentId)

  /** Removing the current PC makes the most recently used of the others current. */
  fun remove(id: String): PcList {
    val rest = entries.filterNot { key(it.computer) == id }
    val next = if (id != currentId && rest.any { key(it.computer) == currentId }) currentId
      else rest.maxByOrNull { it.lastUsed }?.let { key(it.computer) }.orEmpty()
    return PcList(rest, next)
  }

  /** The current PC first, then the others from the most recently used. */
  fun computers(): List<SavedComputer> = entries
    .sortedWith(compareByDescending<PcEntry> { key(it.computer) == currentId }.thenByDescending { it.lastUsed })
    .map { SavedComputer(key(it.computer), it.computer.pcName, it.os, key(it.computer) == currentId) }

  fun toJson(): String {
    val pcs = JSONArray()
    entries.forEach { e ->
      val c = e.computer
      pcs.put(JSONObject().put("host", c.host).put("port", c.port).put("fingerprint", c.fingerprint)
        .put("deviceId", c.deviceId).put("deviceName", c.deviceName).put("pcId", c.pcId).put("pcName", c.pcName)
        .put("token", c.token).put("os", e.os).put("lastUsed", e.lastUsed))
    }
    return JSONObject().put("v", 1).put("current", currentId).put("pcs", pcs).toString()
  }

  companion object {
    const val MAX = 8

    /** pcId, or the pinned identity for a record saved without one. */
    fun key(computer: PairedComputer): String = computer.pcId.ifEmpty { "fp:" + computer.fingerprint }

    /** Null when the text is not a list this version wrote. */
    fun fromJson(raw: String): PcList? = try {
      val o = JSONObject(raw)
      if (o.getInt("v") != 1) null else {
        val pcs = o.getJSONArray("pcs")
        val entries = (0 until pcs.length()).map { i ->
          val p = pcs.getJSONObject(i)
          PcEntry(PairedComputer(p.getString("host"), p.getInt("port"), p.getString("fingerprint"), p.getString("deviceId"), p.getString("deviceName"),
            p.getString("pcId"), p.getString("pcName"), p.getString("token")), p.optString("os"), p.optLong("lastUsed"))
        }.distinctBy { key(it.computer) }.take(MAX)
        val current = o.optString("current")
        PcList(entries, if (entries.any { key(it.computer) == current }) current else entries.maxByOrNull { it.lastUsed }?.let { key(it.computer) }.orEmpty())
      }
    } catch (_: Exception) { null }

    /** The single pairing of versions up to 0.6.0, with the Windows name they kept apart. */
    fun migrate(legacy: PairedComputer?, os: String, now: Long): PcList =
      if (legacy == null) PcList() else PcList(listOf(PcEntry(legacy, os, now)), key(legacy))
  }
}
