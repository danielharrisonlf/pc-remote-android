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

internal class CredentialStore(private val prefs: SharedPreferences) {
  private fun key(): java.security.Key {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    store.getKey("pc_remote_credentials", null)?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(KeyGenParameterSpec.Builder("pc_remote_credentials", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
    return generator.generateKey()
  }
  fun persist(e: PairedComputer) {
    val value = JSONObject().put("host", e.host).put("port", e.port).put("fingerprint", e.fingerprint)
      .put("deviceId", e.deviceId).put("deviceName", e.deviceName).put("pcId", e.pcId).put("pcName", e.pcName).put("token", e.token).toString().toByteArray(Charsets.UTF_8)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val blob = cipher.iv + cipher.doFinal(value)
    // Pairing must only proceed when the encrypted credential reached disk.
    if (!prefs.edit().putString("credential", Base64.encodeToString(blob, Base64.NO_WRAP)).commit())
      throw IllegalStateException("Could not save encrypted credential")
  }
  fun clear() {
    if (!prefs.edit().remove("credential").commit())
      throw IllegalStateException("Could not remove encrypted credential")
  }
  fun restore(): PairedComputer? {
    val blob = prefs.getString("credential", null) ?: return null
    return try {
      val bytes = Base64.decode(blob, Base64.DEFAULT)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
      val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
      PairedComputer(json.getString("host"), json.getInt("port"), json.getString("fingerprint"), json.getString("deviceId"), json.getString("deviceName"), json.getString("pcId"), json.getString("pcName"), json.getString("token"))
    } catch (_: Exception) { null }
  }
}
