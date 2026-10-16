package com.pcremote

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fingerprint presentation shared with the Windows app (src/lib/fingerprint.ts in
 * pc-remote-desktop). Both sides derive the same eight colors so the user can
 * compare the PC and the phone at a glance while pairing.
 */
object Fingerprint {
  data class Swatch(val hue: Int, val saturation: Int, val lightness: Int) {
    fun css() = "hsl($hue, $saturation%, $lightness%)"
    fun argb(): Int = hslToArgb(hue.toDouble(), saturation / 100.0, lightness / 100.0)
  }

  fun normalize(value: String): String = value.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }.uppercase()

  fun groups(value: String): List<String> = normalize(value).chunked(4)

  /** XOR-folds the digest into eight bytes; each byte picks a hue. */
  fun swatches(value: String): List<Swatch> {
    val hex = normalize(value)
    if (hex.length < 16) return emptyList()
    val folded = IntArray(8)
    var index = 0
    while (index + 1 < hex.length) {
      val slot = (index / 2) % 8
      folded[slot] = folded[slot] xor hex.substring(index, index + 2).toInt(16)
      index += 2
    }
    return folded.map { byte -> Swatch((byte / 256.0 * 360).roundToInt(), 64, 46 + (byte % 3) * 6) }
  }

  internal fun hslToArgb(hue: Double, saturation: Double, lightness: Double): Int {
    val chroma = (1 - abs(2 * lightness - 1)) * saturation
    val section = (hue % 360) / 60.0
    val second = chroma * (1 - abs(section % 2 - 1))
    val (red, green, blue) = when (section.toInt()) {
      0 -> Triple(chroma, second, 0.0)
      1 -> Triple(second, chroma, 0.0)
      2 -> Triple(0.0, chroma, second)
      3 -> Triple(0.0, second, chroma)
      4 -> Triple(second, 0.0, chroma)
      else -> Triple(chroma, 0.0, second)
    }
    val match = lightness - chroma / 2
    fun channel(value: Double) = ((value + match) * 255).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
  }
}
