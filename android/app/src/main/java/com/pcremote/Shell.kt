package com.pcremote

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal enum class Tab(val label: String, val icon: Int) {
  TOUCHPAD("Touchpad", R.drawable.ic_touchpad),
  CONTROLS("Controles", R.drawable.ic_zap),
  MEDIA("Mídia", R.drawable.ic_audio_lines),
  CLIPBOARD("Transferir", R.drawable.ic_clipboard),
  CONNECTION("Conexão", R.drawable.ic_monitor_smartphone),
}

/**
 * Current tab. Switching tabs or receiving an Intent never touches remote input:
 * the touchpad stays on until "Desligar" or the end of the connection (see InputIntent).
 */
internal class TabNavigation(initial: Tab) {
  var tab = initial
    private set

  fun goTo(destination: Tab) {
    tab = destination
  }

  /** Shared text or files open the transfer tab; other Intents keep the current one. */
  fun newIntent(shared: Boolean) {
    if (shared) tab = Tab.CLIPBOARD
  }
}

/** Material 3 navigation bar: five destinations with an active pill behind the icon. */
internal class NavigationBar(private val ui: Ui, private val onSelect: (Tab) -> Unit) : LinearLayout(ui.context) {
  private class Item(val pill: FrameLayout, val icon: ImageView, val label: TextView, val badge: View, val container: View)
  private val items = HashMap<Tab, Item>()

  init {
    orientation = HORIZONTAL
    setBackgroundColor(ui.palette.surface)
    Tab.entries.forEach { tab ->
      val icon = ImageView(context)
      val badge = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.palette.accent); setStroke(ui.dp(2), ui.palette.surface) }
        visibility = GONE
      }
      val pill = FrameLayout(context).apply {
        background = ui.rounded(Color.TRANSPARENT, 16f)
        addView(icon, FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
        addView(badge, FrameLayout.LayoutParams(ui.dp(10), ui.dp(10), Gravity.TOP or Gravity.END).apply { topMargin = ui.dp(2); marginEnd = ui.dp(10) })
      }
      val label = ui.text(tab.label, TextStyle.CAPTION).apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
      }
      val container = ui.column(4).apply {
        gravity = Gravity.CENTER
        minimumHeight = ui.dp(80)
        isClickable = true
        contentDescription = tab.label
        background = ui.ripple(null, 0f)
        addView(pill, LayoutParams(ui.dp(56), ui.dp(32)))
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        setOnClickListener { onSelect(tab) }
      }
      addView(container, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
      items[tab] = Item(pill, icon, label, badge, container)
    }
  }

  fun select(selected: Tab) {
    val p = ui.palette
    items.forEach { (tab, item) ->
      val active = tab == selected
      item.pill.background = ui.rounded(if (active) p.accentSoft else Color.TRANSPARENT, 16f)
      item.icon.setImageDrawable(ui.drawable(tab.icon, if (active) p.accentText else p.textSecondary, 22))
      item.label.setTextColor(if (active) p.text else p.textSecondary)
      item.label.typeface = Typeface.create(if (active) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
      item.container.isSelected = active
      item.container.contentDescription = if (active) "${tab.label}, aba selecionada" else tab.label
    }
  }

  fun setBadge(tab: Tab, visible: Boolean) {
    items[tab]?.badge?.visibility = if (visible) VISIBLE else GONE
  }
}

/** Transient feedback above the navigation bar; it never moves the content. */
internal class Snackbar(private val ui: Ui) : LinearLayout(ui.context) {
  private val timer = Handler(Looper.getMainLooper())
  private val icon = ImageView(context)
  private val message = ui.text("", TextStyle.BODY)
  private val hide = Runnable { animate().alpha(0f).translationY(ui.dp(16f)).setDuration(160).withEndAction { visibility = GONE }.start() }

  init {
    orientation = HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    background = ui.rounded(ui.palette.surfaceHigh, 12f, ui.palette.borderStrong)
    elevation = ui.dp(6f)
    setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
    addView(icon, LayoutParams(ui.dp(20), ui.dp(20)).apply { marginEnd = ui.dp(12) })
    addView(message, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    visibility = GONE
  }

  fun show(text: String, tone: Tone) {
    val p = ui.palette
    val (res, color) = when (tone) {
      Tone.DANGER -> R.drawable.ic_circle_alert to p.danger
      Tone.SUCCESS -> R.drawable.ic_circle_check to p.success
      else -> R.drawable.ic_info to p.accentText
    }
    icon.setImageDrawable(ui.drawable(res, color, 20))
    message.text = text.take(200)
    timer.removeCallbacks(hide)
    animate().cancel()
    if (visibility != VISIBLE) { alpha = 0f; translationY = ui.dp(16f) }
    visibility = VISIBLE
    animate().alpha(1f).translationY(0f).setDuration(200).start()
    timer.postDelayed(hide, if (tone == Tone.DANGER) 5000L else 2800L)
  }
}

/** Handle, title, close button, scrolling body and footer shared by both kinds of sheet. */
internal class SheetFrame(private val ui: Ui, title: String, subtitle: String?, onClose: () -> Unit) {
  val body: LinearLayout = ui.column(16)
  val footer: LinearLayout = ui.row(8)
  val root: LinearLayout = ui.column(0)

  init {
    val p = ui.palette
    root.apply {
      background = GradientDrawable().apply {
        setColor(p.surfaceHigh)
        val r = ui.dp(28f)
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
      }
      setPadding(0, ui.dp(8), 0, ui.dp(16))
      addView(View(context).apply { background = ui.rounded(p.borderStrong, 2f) }, LinearLayout.LayoutParams(ui.dp(36), ui.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(8) })
      addView(ui.row(12).apply {
        setPadding(ui.dp(20), 0, ui.dp(8), ui.dp(8))
        addView(ui.column(2).apply {
          addView(ui.text(title, TextStyle.TITLE).apply { accessibilityHeading() })
          if (!subtitle.isNullOrEmpty()) addView(ui.text(subtitle, TextStyle.CAPTION, p.textSecondary))
        }, ui.weighted())
        addView(ui.iconButton(R.drawable.ic_x, "Fechar", 44) { onClose() })
      })
      addView(ScrollView(context).apply {
        isFillViewport = true
        addView(body.apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(16)) })
      }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
      addView(footer.apply { setPadding(ui.dp(20), ui.dp(12), ui.dp(20), 0) })
    }
  }

  fun setBottomInset(px: Int) = root.setPadding(0, ui.dp(8), 0, ui.dp(16) + px)
}

/**
 * Bottom sheet on a Dialog: system Back and the scrim close it, and the window
 * fits system bars and the keyboard so fields stay visible. The Dialog takes
 * window focus, which pauses remote input until it closes; use [PanelSheet] while
 * controlling the PC.
 */
internal class Sheet(activity: Activity, ui: Ui, title: String, subtitle: String? = null) {
  val dialog = Dialog(activity)
  private val frame = SheetFrame(ui, title, subtitle) { dialog.dismiss() }
  val body: LinearLayout get() = frame.body
  val footer: LinearLayout get() = frame.footer

  init {
    val root = frame.root
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setContentView(root)
    dialog.window?.apply {
      setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
      setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      setGravity(Gravity.BOTTOM)
      addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
      setDimAmount(if (ui.palette.dark) 0.6f else 0.42f)
      setWindowAnimations(android.R.style.Animation_InputMethod)
      @Suppress("DEPRECATION")
      setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
      @Suppress("DEPRECATION")
      if (Build.VERSION.SDK_INT >= 30) setDecorFitsSystemWindows(true)
    }
    // Keep the sheet below 88% of the screen so the scrim stays visible.
    val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.88f).toInt()
    root.viewTreeObserver.addOnGlobalLayoutListener {
      if (root.height > maxHeight) root.layoutParams = root.layoutParams.apply { height = maxHeight }
    }
  }

  fun show() = dialog.show()
  fun dismiss() = dialog.dismiss()
}

/**
 * Layer for bottom panels inside the main window. RemoteClient pauses input
 * when the window loses focus, and a Dialog takes focus, so panels used while
 * controlling the PC open here instead.
 */
internal class PanelLayer(private val ui: Ui) : FrameLayout(ui.context) {
  private var current: SheetFrame? = null
  private var onDismiss: (() -> Unit)? = null
  private var navigationInset = 0
  var onOpenChanged: (Boolean) -> Unit = {}
  val isOpen: Boolean get() = current != null

  init {
    setBackgroundColor(if (ui.palette.dark) 0x99000000.toInt() else 0x6B000000)
    visibility = GONE
    isClickable = true
    setOnClickListener { dismiss() }
  }

  fun open(frame: SheetFrame, onDismiss: () -> Unit) {
    dismiss()
    current = frame
    this.onDismiss = onDismiss
    frame.root.isClickable = true // Taps inside the panel must not reach the scrim.
    frame.setBottomInset(navigationInset)
    addView(frame.root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    visibility = VISIBLE
    alpha = 0f
    animate().alpha(1f).setDuration(160).start()
    frame.root.translationY = ui.dp(48f)
    frame.root.animate().translationY(0f).setDuration(200).start()
    onOpenChanged(true)
  }

  /** Closes [frame], or whatever is open when [frame] is null. */
  fun dismiss(frame: SheetFrame? = null) {
    val open = current ?: return
    if (frame != null && frame !== open) return
    current = null
    removeView(open.root)
    visibility = GONE
    onOpenChanged(false)
    onDismiss?.also { onDismiss = null }?.invoke()
  }

  /** Above the keyboard while typing; otherwise the panel paints under the navigation bar. */
  fun setInsets(top: Int, navigation: Int, keyboard: Int) {
    setPadding(0, top, 0, keyboard)
    navigationInset = if (keyboard > 0) 0 else navigation
    current?.setBottomInset(navigationInset)
  }
}

/** Same look as [Sheet], drawn in a [PanelLayer] so the window keeps focus. */
internal class PanelSheet(private val layer: PanelLayer, ui: Ui, title: String, subtitle: String? = null) {
  private val frame = SheetFrame(ui, title, subtitle) { dismiss() }
  val body: LinearLayout get() = frame.body
  val footer: LinearLayout get() = frame.footer
  var showing = false
    private set

  fun show(onDismiss: () -> Unit = {}) {
    showing = true
    layer.open(frame) { showing = false; onDismiss() }
  }

  fun dismiss() { layer.dismiss(frame) }
}
