package com.pcremote

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal data class PairedComputer(val host: String, val port: Int, val fingerprint: String, val deviceId: String, val deviceName: String, val pcId: String = "", val pcName: String = "", val token: String = "")

/**
 * The paired PCs (PcList), encrypted with a Keystore AES-GCM key. Versions up to
 * 0.6.0 kept one PC under "credential" and its Windows name under "pcOs"; the first
 * read moves both into the list and removes them only after the list is saved.
 */
internal class CredentialStore(private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) {
  private val listStore = CommittedValue(
    { prefs.getString(LIST, null) },
    { value -> prefs.edit().apply {
      if (value == null) remove(LIST) else putString(LIST, value)
    }.commit() },
  )
  private var cache: PcList? = null

  private fun key(): java.security.Key {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    store.getKey("pc_remote_credentials", null)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(KeyGenParameterSpec.Builder("pc_remote_credentials", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
    return generator.generateKey()
  }
  private fun encrypt(value: String): String {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key())
    return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
  }
  private fun decrypt(blob: String): String? = try {
    val bytes = Base64.decode(blob, Base64.DEFAULT)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
    String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
  } catch (_: Exception) { null }

  private fun list(): PcList {
    cache?.let { return it }
    val saved = listStore.read()
    if (saved != null) return (decrypt(saved)?.let(PcList::fromJson) ?: PcList()).also { cache = it }
    val legacy = prefs.getString(LEGACY, null)?.let(::decrypt)?.let(::legacyComputer)
    val migrated = PcList.migrate(legacy, prefs.getString(LEGACY_OS, "").orEmpty(), now())
    cache = migrated
    if (legacy != null) try { save(migrated) } catch (_: Exception) { /* The old record stays; the next start tries again. */ }
    return migrated
  }

  /** Throws when the list could not be committed; the previous list stays in effect. */
  private fun save(next: PcList) {
    listStore.write(if (next.entries.isEmpty()) null else encrypt(next.toJson()))
    cache = next
    // The list now holds everything the single record of 0.6.0 and earlier had.
    if (prefs.contains(LEGACY) || prefs.contains(LEGACY_OS)) prefs.edit().remove(LEGACY).remove(LEGACY_OS).apply()
  }

  /** The current PC. */
  fun restore(): PairedComputer? = list().current?.computer
  fun currentOs(): String = list().current?.os.orEmpty()
  fun computers(): List<SavedComputer> = list().computers()
  fun computer(id: String): PairedComputer? = list().entries.firstOrNull { PcList.key(it.computer) == id }?.computer
  fun canAdd(id: String): Boolean = list().canAdd(id)

  /** Saves [e] (new, or the same pcId again) as the current PC. */
  fun persist(e: PairedComputer) {
    save(list().upsert(e, now()) ?: throw IllegalStateException("full"))
  }
  fun select(id: String) = save(list().select(id, now()))
  /** Name and Windows edition from the welcome; the PC counts as just used. */
  fun touch(id: String, name: String, os: String) {
    val current = list()
    val entry = current.entries.firstOrNull { PcList.key(it.computer) == id } ?: return
    if (entry.computer.pcName == name && entry.os == os && now() - entry.lastUsed < TOUCH_INTERVAL_MS) return
    save(current.touch(id, name, os, now()))
  }
  fun remove(id: String) = save(list().remove(id))
  /** Removes the current PC; the most recently used of the others becomes current. */
  fun clear() { list().current?.let { remove(PcList.key(it.computer)) } }

  private fun legacyComputer(raw: String): PairedComputer? = try {
    val json = JSONObject(raw)
    PairedComputer(json.getString("host"), json.getInt("port"), json.getString("fingerprint"), json.getString("deviceId"), json.getString("deviceName"), json.getString("pcId"), json.getString("pcName"), json.getString("token"))
  } catch (_: Exception) { null }

  private companion object {
    const val LIST = "computers"
    const val LEGACY = "credential"
    const val LEGACY_OS = "pcOs"
    /** "Last used" only orders the list; one write per hour of use is plenty. */
    const val TOUCH_INTERVAL_MS = 60 * 60 * 1000L
  }
}
