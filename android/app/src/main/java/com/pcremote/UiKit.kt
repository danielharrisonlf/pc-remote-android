package com.pcremote

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

internal enum class TextStyle(val sizeSp: Float, val face: String, val style: Int) {
  DISPLAY(48f, "sans-serif", Typeface.BOLD),
  HEADLINE(24f, "sans-serif", Typeface.BOLD),
  TITLE(18f, "sans-serif-medium", Typeface.NORMAL),
  BODY(15f, "sans-serif", Typeface.NORMAL),
  BODY_STRONG(15f, "sans-serif-medium", Typeface.NORMAL),
  LABEL(14f, "sans-serif-medium", Typeface.NORMAL),
  CAPTION(12f, "sans-serif", Typeface.NORMAL),
  MONO(14f, "monospace", Typeface.NORMAL),
}

internal enum class ButtonStyle { FILLED, TONAL, OUTLINED, TEXT, DANGER }
internal enum class Tone { SUCCESS, WARNING, DANGER, ACCENT, NEUTRAL }

/** View factory bound to one palette. Every screen builds its Views through it. */
internal class Ui(val context: Context, val palette: Palette) {
  private val density = context.resources.displayMetrics.density

  fun dp(value: Int): Int = (value * density).roundToInt()
  fun dp(value: Float): Float = value * density

  fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
  fun weighted(weight: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)

  fun text(value: CharSequence = "", style: TextStyle = TextStyle.BODY, color: Int = palette.text): TextView = TextView(context).apply {
    text = value
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sizeSp)
    typeface = Typeface.create(style.face, style.style)
    includeFontPadding = false
    setLineSpacing(0f, if (style == TextStyle.BODY || style == TextStyle.CAPTION) 1.25f else 1.1f)
    if (style == TextStyle.DISPLAY) letterSpacing = -0.02f
  }

  fun tabular(view: TextView): TextView = view.apply { fontFeatureSettings = "tnum" }

  fun drawable(icon: Int, color: Int, sizeDp: Int = 24): Drawable = context.getDrawable(icon)!!.mutate().apply {
    setTint(color)
    setBounds(0, 0, dp(sizeDp), dp(sizeDp))
  }

  fun icon(icon: Int, color: Int, sizeDp: Int = 24): ImageView = ImageView(context).apply {
    setImageDrawable(drawable(icon, color, sizeDp))
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
  }

  fun rounded(color: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 1f): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp)
    if (stroke != null) setStroke(dp(strokeDp).roundToInt().coerceAtLeast(1), stroke)
  }

  fun ripple(content: Drawable?, radiusDp: Float): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(palette.ripple), content, rounded(Color.WHITE, radiusDp))

  private fun gap(sizeDp: Int) = GradientDrawable().apply { setColor(Color.TRANSPARENT); setSize(dp(sizeDp), dp(sizeDp)) }

  fun column(gapDp: Int = 0): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    if (gapDp > 0) { dividerDrawable = gap(gapDp); showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE }
  }

  fun row(gapDp: Int = 0): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (gapDp > 0) { dividerDrawable = gap(gapDp); showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE }
  }

  fun card(gapDp: Int = 16, attention: Boolean = false): LinearLayout = column(gapDp).apply {
    background = if (attention) rounded(palette.warningSoft, 20f, palette.warning) else rounded(palette.surface, 20f, palette.border)
    setPadding(dp(20), dp(20), dp(20), dp(20))
  }

  fun cardHeader(title: String, subtitle: String? = null, trailing: View? = null): LinearLayout = row(12).apply {
    gravity = Gravity.TOP
    addView(column(2).apply {
      addView(text(title, TextStyle.TITLE).apply { accessibilityHeading() })
      if (subtitle != null) addView(text(subtitle, TextStyle.CAPTION, palette.textSecondary))
    }, weighted())
    if (trailing != null) addView(trailing)
  }

  fun divider(): View = View(context).apply {
    setBackgroundColor(palette.border)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
  }

  fun button(label: String, style: ButtonStyle = ButtonStyle.FILLED, icon: Int? = null, large: Boolean = false, onClick: () -> Unit): PillButton =
    PillButton(this, style, large).apply {
      setLabel(label)
      setIcon(icon)
      setOnClickListener { onClick() }
    }

  fun iconButton(icon: Int, description: String, sizeDp: Int = 48, tonal: Boolean = false, onClick: () -> Unit): ImageButton = ImageButton(context).apply {
    val color = if (tonal) palette.accentText else palette.textSecondary
    setImageDrawable(drawable(icon, color, (sizeDp * 0.46f).roundToInt()))
    contentDescription = description
    scaleType = ImageView.ScaleType.CENTER
    val shape = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (tonal) palette.accentSoft else Color.TRANSPARENT) }
    background = RippleDrawable(ColorStateList.valueOf(palette.ripple), shape, GradientDrawable().apply { this.shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    minimumWidth = dp(48); minimumHeight = dp(48)
    setOnClickListener { onClick() }
  }

  fun field(hint: String, mono: Boolean = false): EditText = EditText(context).apply {
    this.hint = hint
    contentDescription = hint
    setTextColor(palette.text)
    setHintTextColor(palette.textTertiary)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (mono) 14f else 16f)
    if (mono) typeface = Typeface.MONOSPACE
    background = rounded(palette.surface, 12f, palette.borderStrong)
    setPadding(dp(16), dp(14), dp(16), dp(14))
    minHeight = dp(52)
    inputType = InputType.TYPE_CLASS_TEXT
  }

  fun chip(): StatusChip = StatusChip(this)

  fun switchRow(label: String, description: String? = null, icon: Int? = null, onChange: (Boolean) -> Unit): SwitchRow =
    SwitchRow(this, label, description, icon, onChange)

  fun emptyState(iconRes: Int, title: String, body: String? = null, action: View? = null): LinearLayout = column(8).apply {
    gravity = Gravity.CENTER_HORIZONTAL
    setPadding(dp(16), dp(20), dp(16), dp(20))
    addView(FrameLayout(context).apply {
      background = rounded(palette.surfaceSunken, 16f, palette.border)
      addView(this@Ui.icon(iconRes, palette.textTertiary, 26), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
    }, LinearLayout.LayoutParams(dp(56), dp(56)))
    addView(text(title, TextStyle.TITLE).apply { gravity = Gravity.CENTER })
    if (body != null) addView(text(body, TextStyle.BODY, palette.textSecondary).apply { gravity = Gravity.CENTER })
    if (action != null) addView(action, matchWrap().apply { topMargin = dp(8) })
  }

  fun toneColors(tone: Tone): Pair<Int, Int> = when (tone) {
    Tone.SUCCESS -> palette.successSoft to palette.success
    Tone.WARNING -> palette.warningSoft to palette.warning
    Tone.DANGER -> palette.dangerSoft to palette.danger
    Tone.ACCENT -> palette.accentSoft to palette.accentText
    Tone.NEUTRAL -> palette.surfaceSunken to palette.textSecondary
  }
}

internal fun View.accessibilityHeading() {
  if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
}

/** Icon and label centered together; announced as a button. */
internal class PillButton(private val ui: Ui, private val style: ButtonStyle, large: Boolean) : LinearLayout(ui.context) {
  private val iconView = ImageView(context)
  private val label = ui.text("", TextStyle.LABEL)
  private val fg: Int
  private var busy = false
  private var currentLabel = ""

  init {
    orientation = HORIZONTAL
    gravity = Gravity.CENTER
    isClickable = true
    isFocusable = true
    minimumHeight = ui.dp(if (large) 56 else 48)
    setPadding(ui.dp(if (large) 24 else 20), 0, ui.dp(if (large) 24 else 20), 0)
    val p = ui.palette
    val (fill, fg, stroke) = when (style) {
      ButtonStyle.FILLED -> Triple(p.accent, p.onAccent, null)
      ButtonStyle.TONAL -> Triple(p.accentSoft, p.accentText, null)
      ButtonStyle.OUTLINED -> Triple(Color.TRANSPARENT, p.text, p.borderStrong)
      ButtonStyle.TEXT -> Triple(Color.TRANSPARENT, p.accentText, null)
      ButtonStyle.DANGER -> Triple(p.dangerSoft, p.danger, null)
    }
    this.fg = fg
    background = ui.ripple(ui.rounded(fill, 999f, stroke), 999f)
    label.setTextColor(fg)
    label.maxLines = 1
    addView(iconView, LayoutParams(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(8) })
    addView(label)
    iconView.visibility = GONE
  }

  fun setLabel(value: String) {
    currentLabel = value
    if (!busy) label.text = value
    contentDescription = value
  }

  fun setIcon(icon: Int?) {
    if (icon == null) { iconView.visibility = GONE; return }
    iconView.setImageDrawable(ui.drawable(icon, fg, 20))
    iconView.visibility = VISIBLE
  }

  /** Shows a short busy label and blocks taps until the action settles. */
  fun setBusy(value: Boolean, busyLabel: String = "Aguarde…") {
    busy = value
    label.text = if (value) busyLabel else currentLabel
    isEnabled = !value
  }

  override fun setEnabled(enabled: Boolean) {
    super.setEnabled(enabled)
    alpha = if (enabled) 1f else 0.45f
  }

  override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
}

/** Dot plus label; color is never the only signal. */
internal class StatusChip(private val ui: Ui) : LinearLayout(ui.context) {
  private val dot = View(context)
  private val label = ui.text("", TextStyle.CAPTION)

  init {
    orientation = HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(ui.dp(10), ui.dp(5), ui.dp(12), ui.dp(5))
    addView(dot, LayoutParams(ui.dp(8), ui.dp(8)).apply { marginEnd = ui.dp(6) })
    addView(label)
    label.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
  }

  fun set(tone: Tone, text: String) {
    val (fill, fg) = ui.toneColors(tone)
    background = ui.rounded(fill, 999f)
    dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fg) }
    label.setTextColor(fg)
    label.text = text
  }
}

/** Whole row toggles the switch, so the target is the full width. */
internal class SwitchRow(ui: Ui, labelText: String, description: String?, icon: Int?, private val onChange: (Boolean) -> Unit) : LinearLayout(ui.context) {
  val toggle = Switch(context)
  private var binding = false

  init {
    orientation = HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    minimumHeight = ui.dp(64)
    setPadding(0, ui.dp(10), 0, ui.dp(10))
    background = ui.ripple(null, 12f)
    if (icon != null) {
      addView(FrameLayout(context).apply {
        background = ui.rounded(ui.palette.surfaceSunken, 10f)
        addView(ui.icon(icon, ui.palette.textSecondary, 18), FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER))
      }, LayoutParams(ui.dp(36), ui.dp(36)).apply { marginEnd = ui.dp(12) })
    }
    addView(ui.column(2).apply {
      addView(ui.text(labelText, TextStyle.BODY_STRONG))
      if (description != null) addView(ui.text(description, TextStyle.CAPTION, ui.palette.textSecondary))
    }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    val p = ui.palette
    toggle.contentDescription = labelText
    toggle.thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.onAccent, p.surfaceHigh))
    toggle.trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(p.accent, p.borderStrong))
    toggle.setOnCheckedChangeListener { _, checked -> if (!binding) onChange(checked) }
    addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(12) })
    setOnClickListener { if (isEnabled) toggle.toggle() }
  }

  fun bind(checked: Boolean, enabled: Boolean = true) {
    binding = true
    if (toggle.isChecked != checked) toggle.isChecked = checked
    binding = false
    isEnabled = enabled
    toggle.isEnabled = enabled
    alpha = if (enabled) 1f else 0.5f
  }
}

/** Material 3 single-choice segmented buttons. */
internal class Segmented<T>(private val ui: Ui, options: List<Triple<T, String, Int?>>, private val onChange: (T) -> Unit) : LinearLayout(ui.context) {
  private val segments = options.map { (value, label, icon) ->
    value to ui.row(6).apply {
      gravity = Gravity.CENTER
      minimumHeight = ui.dp(48)
      isClickable = true
      contentDescription = label
      tag = icon
      addView(ImageView(context).apply { layoutParams = LayoutParams(ui.dp(16), ui.dp(16)) })
      addView(ui.text(label, TextStyle.LABEL))
      setOnClickListener { onChange(value) }
    }
  }

  init {
    orientation = HORIZONTAL
    background = ui.rounded(Color.TRANSPARENT, 999f, ui.palette.borderStrong)
    clipToOutline = true
    segments.forEachIndexed { index, (_, view) ->
      if (index > 0) addView(View(context).apply { setBackgroundColor(ui.palette.borderStrong) }, LayoutParams(ui.dp(1), LayoutParams.MATCH_PARENT))
      addView(view, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }
  }

  fun select(selected: T) {
    val p = ui.palette
    segments.forEach { (value, view) ->
      val active = value == selected
      view.background = ui.ripple(if (active) GradientDrawable().apply { setColor(p.accentSoft) } else null, 0f)
      val iconView = view.getChildAt(0) as ImageView
      val label = view.getChildAt(1) as TextView
      val color = if (active) p.accentText else p.textSecondary
      val icon = if (active) R.drawable.ic_check else view.tag as Int?
      if (icon == null) iconView.visibility = GONE else { iconView.visibility = VISIBLE; iconView.setImageDrawable(ui.drawable(icon, color, 16)) }
      label.setTextColor(color)
      view.isSelected = active
    }
  }
}

/** Eight colors of the PC identity; the Windows app draws the same strip. */
internal class FingerprintStripView(private val ui: Ui) : View(ui.context) {
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val clip = Path()
  private val rect = RectF()
  private var colors = emptyList<Int>()

  init {
    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    layoutParams = LinearLayout.LayoutParams(ui.dp(8 * 14), ui.dp(18))
  }

  fun setFingerprint(value: String) {
    val swatches = Fingerprint.swatches(value)
    colors = if (swatches.isEmpty()) List(8) { ui.palette.border } else swatches.map { it.argb() }
    contentDescription = if (swatches.isEmpty()) "Identidade do PC ainda não disponível" else "Cores da identidade do PC"
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    if (colors.isEmpty()) return
    rect.set(0f, 0f, width.toFloat(), height.toFloat())
    clip.reset()
    clip.addRoundRect(rect, ui.dp(4f), ui.dp(4f), Path.Direction.CW)
    canvas.save()
    canvas.clipPath(clip)
    val step = width / colors.size.toFloat()
    colors.forEachIndexed { index, color ->
      paint.color = color
      canvas.drawRect(index * step, 0f, (index + 1) * step, height.toFloat(), paint)
    }
    canvas.restore()
  }
}
