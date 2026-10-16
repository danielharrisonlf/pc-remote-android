package com.pcremote

import android.content.Context
import android.content.res.Configuration

internal enum class ThemeMode(val key: String) {
  SYSTEM("system"), LIGHT("light"), DARK("dark");

  companion object {
    fun from(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
  }
}

/**
 * Color roles shared with the Windows app (src/styles/tokens.css). Dark is the
 * same roles with lighter surfaces for elevation instead of shadows.
 */
internal class Palette(val dark: Boolean) {
  private fun pick(light: Long, dark: Long): Int = (if (this.dark) dark else light).toInt()

  val bg = pick(0xFFF3F5F8, 0xFF0E1114)
  val surface = pick(0xFFFFFFFF, 0xFF161A1F)
  val surfaceSunken = pick(0xFFEEF1F5, 0xFF121519)
  val surfaceHigh = pick(0xFFFFFFFF, 0xFF1F242B)
  val border = pick(0xFFE2E7EC, 0xFF252B32)
  val borderStrong = pick(0xFFCBD3DB, 0xFF353D46)
  val text = pick(0xFF0F172A, 0xFFE8ECF0)
  val textSecondary = pick(0xFF4B5866, 0xFFA4AEB9)
  val textTertiary = pick(0xFF66727F, 0xFF87919C)
  val accent = pick(0xFF4F50D6, 0xFF9FA3FF)
  val accentPressed = pick(0xFF393AA8, 0xFF8E92F0)
  val accentSoft = pick(0xFFE6E8FD, 0xFF23264A)
  val accentText = pick(0xFF3E3FB8, 0xFFB6B9FF)
  val onAccent = pick(0xFFFFFFFF, 0xFF12133A)
  val success = pick(0xFF12804F, 0xFF5AD497)
  val successSoft = pick(0xFFE5F5EC, 0xFF14261E)
  val warning = pick(0xFF8F5300, 0xFFF2B862)
  val warningSoft = pick(0xFFFDF3E3, 0xFF2A2114)
  val danger = pick(0xFFC0352B, 0xFFFF8F84)
  val dangerSoft = pick(0xFFFDEEED, 0xFF2C1717)
  val pad = pick(0xFFE9EDF2, 0xFF13171B)
  val padDot = pick(0xFFC2CBD5, 0xFF2B323A)
  val ripple = pick(0x244F50D6, 0x299FA3FF)

  companion object {
    fun resolve(context: Context, mode: ThemeMode): Palette {
      val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
      return Palette(mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && systemDark))
    }
  }
}

/** Display-only choices. Connection preferences stay in RemoteClient. */
internal class UiPreferences(context: Context) {
  private val prefs = context.getSharedPreferences("pc_remote_ui", Context.MODE_PRIVATE)

  var theme: ThemeMode
    get() = ThemeMode.from(prefs.getString("theme", null))
    set(value) = prefs.edit().putString("theme", value.key).apply()

  /** Release the user dismissed in the update banner. */
  var skippedUpdate: String
    get() = prefs.getString("skippedUpdate", "").orEmpty()
    set(value) = prefs.edit().putString("skippedUpdate", value.take(64)).apply()

  var lastUpdateCheck: Long
    get() = prefs.getLong("lastUpdateCheck", 0L)
    set(value) = prefs.edit().putLong("lastUpdateCheck", value).apply()
}
