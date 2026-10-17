package com.pcremote

import android.app.Activity
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView

internal data class UpdateState(
  val checking: Boolean = false,
  val lookup: ReleaseLookup? = null,
  val installed: AppVersion? = null,
  val skipped: String = "",
  val flow: UpdateFlow = UpdateFlow.Idle,
) {
  val release: Release? get() = (lookup as? ReleaseLookup.Found)?.release
  val available: Boolean get() = release?.let { Releases.isNewer(it, installed) } == true
  /** Newer and not dismissed with "Agora não". */
  val notify: Boolean get() = available && release?.version?.toString() != skipped
  /** APK and SHA256SUMS published: the app downloads, verifies and installs by itself. */
  val inApp: Boolean get() = release?.let { it.apk != null && it.sums != null } == true
  /** The offered version is downloaded and verified. */
  val ready: Boolean get() = available && flow is UpdateFlow.Ready && flow.version == release?.version
}

/** What every tab needs from the Activity. */
internal interface ScreenHost {
  val activity: Activity
  val ui: Ui
  val client: RemoteController
  val themeMode: ThemeMode
  val update: UpdateState
  /** In-window panels; a Dialog would take focus and pause input. */
  val panels: PanelLayer
  fun notify(message: String, tone: Tone = Tone.ACCENT)
  /** Errors become snackbars; success shows one only when it says something new. */
  fun result(error: String?, success: String? = null)
  fun navigate(tab: Tab)
  /** System document picker for any file; the result joins the file queue. */
  fun pickFiles()
  /** Opens a file received from the PC with the app Android picks for it. */
  fun openFile(record: FileRecord)
  /** Asks for RECORD_AUDIO on first use only; [granted] runs once it is allowed. */
  fun requestMicrophone(granted: () -> Unit)
  fun scanQr()
  fun openPairingSheet()
  fun openUpdate()
  fun checkUpdates(manual: Boolean)
  /** "Atualizar agora" / "Instalar agora": always a user's tap. */
  fun startUpdate()
  val autoDownload: Boolean
  fun setAutoDownload(enabled: Boolean)
  fun openReleasePage(url: String)
  fun setTheme(mode: ThemeMode)
  fun skipUpdate(version: String)
}

internal interface Screen {
  val view: View
  fun render(state: UiState)
  /** The Activity paused: release anything that should not outlive the visit, such as the microphone. */
  fun pause() {}
  /** True while this screen intercepts hardware volume keys (e.g. Presentation Remote slide stepping). */
  fun interceptsVolumeKeys(): Boolean = false
  fun onVolumeKey(up: Boolean) {}
}

/**
 * Focus mode for remote input: a full-height pad with no page scroll, the click
 * buttons in the thumb zone and the frequent keys right above them.
 */
internal class TouchpadScreen(private val host: ScreenHost) : Screen {
  private enum class Blocker { NONE, DISCONNECTED, OUTDATED, BLOCKED, OFF }

  private val ui = host.ui
  private val client = host.client
  private var enabled = false
  private var blocker: Blocker? = null
  private var activating = false
  private val keyboard = KeyboardSheet(host)
  private val numpad = NumpadSheet(host)
  private val presentation = PresentationSheet(host)
  private val deck = QuickDeckSheet(host)
  private val numpadBtn = modeChip("123", null, "Teclado numérico") { numpad.show() }
  private val presentationBtn = modeChip("Slides", R.drawable.ic_play, "Apresentador de slides") { presentation.show() }
  private val deckBtn = modeChip("Deck", R.drawable.ic_zap, "Deck de ações rápidas") { deck.show() }
  private val stop = ui.button("Desligar", ButtonStyle.TEXT, R.drawable.ic_hand) { client.disableInput() }
  private val pad = TouchPadView(ui, client, { enabled }) { button ->
    client.execute(RemoteAction.PointerClick(button, 1)) { host.result(it) }
  }
  private val strip = ScrollStripView(ui, client, { enabled }) {}
  private val lockContent = FrameLayout(ui.context).apply { setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)) }
  // Scrolls when a short pad (small phone, update banner) cannot fit the message and button.
  private val lockLayer = ScrollView(ui.context).apply {
    setBackgroundColor(ui.palette.pad)
    isFillViewport = true
    isClickable = true
    addView(lockContent)
  }
  private val inputViews = mutableListOf<View>()

  private val keys = listOf(
    Triple("Esc", null, RemoteAction.KeyboardKey("escape")),
    Triple("Tab", null, RemoteAction.KeyboardKey("tab")),
    Triple("Enter", R.drawable.ic_corner_down_left, RemoteAction.KeyboardKey("enter")),
    Triple("Espaço", R.drawable.ic_space, RemoteAction.KeyboardText(" ")),
    Triple("", R.drawable.ic_delete, RemoteAction.KeyboardKey("backspace")),
    Triple("", R.drawable.ic_arrow_left, RemoteAction.KeyboardKey("left")),
    Triple("", R.drawable.ic_arrow_up, RemoteAction.KeyboardKey("up")),
    Triple("", R.drawable.ic_arrow_down, RemoteAction.KeyboardKey("down")),
    Triple("", R.drawable.ic_arrow_right, RemoteAction.KeyboardKey("right")),
    Triple("Del", null, RemoteAction.KeyboardKey("delete")),
    Triple("Home", null, RemoteAction.KeyboardKey("home")),
    Triple("End", null, RemoteAction.KeyboardKey("end")),
    Triple("Duplo clique", R.drawable.ic_mouse_pointer_click, RemoteAction.PointerClick("left", 2)),
  )
  private val keyNames = mapOf("backspace" to "Apagar", "left" to "Seta para a esquerda", "up" to "Seta para cima", "down" to "Seta para baixo", "right" to "Seta para a direita")

  override val view: View = ui.column(12).apply {
    setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(12))
    // Fixed height: mode chips on the left, "Desligar" on the right so the pad never jumps.
    addView(ui.row(6).apply {
      gravity = Gravity.CENTER_VERTICAL
      minimumHeight = ui.dp(48)
      addView(numpadBtn)
      addView(presentationBtn)
      addView(deckBtn)
      addView(View(context), ui.weighted())
      addView(stop)
    })
    addView(FrameLayout(context).apply {
      background = ui.rounded(ui.palette.pad, 28f, ui.palette.border)
      clipToOutline = true
      addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(pad, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(View(context).apply { setBackgroundColor(ui.palette.border) }, LinearLayout.LayoutParams(ui.dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(strip, LinearLayout.LayoutParams(ui.dp(52), ViewGroup.LayoutParams.MATCH_PARENT))
      }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
      addView(lockLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    addView(HorizontalScrollView(context).apply {
      isHorizontalScrollBarEnabled = false
      addView(ui.row(8).apply { keys.forEach { (label, icon, action) -> addView(key(label, icon, action)) } })
    })
    addView(ui.row(8).apply {
      addView(clickButton("Esquerdo", "left"), LinearLayout.LayoutParams(0, ui.dp(64), 1f))
      addView(ui.iconButton(R.drawable.ic_keyboard, "Abrir teclado", 64, tonal = true) { keyboard.show() }.also { inputViews += it })
      addView(clickButton("Direito", "right"), LinearLayout.LayoutParams(0, ui.dp(64), 1f))
    })
  }

  private fun modeChip(label: String, icon: Int?, description: String, onClick: () -> Unit): View = ui.row(6).apply {
    gravity = Gravity.CENTER
    minimumHeight = ui.dp(38)
    setPadding(ui.dp(12), 0, ui.dp(12), 0)
    background = ui.ripple(ui.rounded(ui.palette.surface, 12f, ui.palette.border), 12f)
    isClickable = true
    contentDescription = description
    if (icon != null) addView(ui.icon(icon, ui.palette.accentText, 16))
    addView(ui.text(label, TextStyle.LABEL, ui.palette.text))
    setOnClickListener { onClick() }
  }

  private fun key(label: String, icon: Int?, action: RemoteAction): View = ui.row(6).apply {
    gravity = Gravity.CENTER
    minimumHeight = ui.dp(44)
    minimumWidth = ui.dp(52)
    setPadding(ui.dp(14), 0, ui.dp(14), 0)
    background = ui.ripple(ui.rounded(ui.palette.surface, 12f, ui.palette.border), 12f)
    isClickable = true
    val description = (action as? RemoteAction.KeyboardKey)?.let { keyNames[it.key] } ?: label
    contentDescription = description
    if (icon != null) addView(ui.icon(icon, ui.palette.text, 18))
    if (label.isNotEmpty()) addView(ui.text(label, TextStyle.LABEL))
    setOnClickListener {
      client.execute(action) { host.result(it) }
    }
    inputViews += this
  }

  @android.annotation.SuppressLint("ClickableViewAccessibility")
  private fun clickButton(label: String, button: String): View = FrameLayout(ui.context).apply {
    val idleBg = ui.ripple(ui.rounded(ui.palette.surface, 20f, ui.palette.borderStrong), 20f)
    val pressedBg = ui.ripple(ui.rounded(ui.palette.accentSoft, 20f, ui.palette.accent, 1.5f), 20f)
    background = idleBg
    isClickable = true
    isFocusable = true
    isHapticFeedbackEnabled = false
    contentDescription = "Clique ${label.lowercase()}"
    val labelView = ui.text(label, TextStyle.BODY_STRONG, ui.palette.text)
    addView(labelView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    setOnTouchListener { v, event ->
      when (event.actionMasked) {
        android.view.MotionEvent.ACTION_DOWN -> {
          v.background = pressedBg
          labelView.setTextColor(ui.palette.accentText)
          v.scaleX = 0.98f
          v.scaleY = 0.98f
        }
        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
          v.background = idleBg
          labelView.setTextColor(ui.palette.text)
          v.scaleX = 1f
          v.scaleY = 1f
        }
      }
      false
    }
    setOnClickListener {
      client.execute(RemoteAction.PointerClick(button, 1)) { host.result(it) }
    }
    inputViews += this
  }

  override fun interceptsVolumeKeys(): Boolean = enabled && presentation.showing

  override fun onVolumeKey(up: Boolean) {
    if (enabled && presentation.showing) presentation.stepSlide(next = up)
  }

  override fun render(state: UiState) {
    val connected = state.status == "Conectado"
    val allowed = state.snapshot?.input?.allowed
    enabled = connected && state.inputArmed && allowed == true && state.snapshot?.input?.enabled == true
    // A paused session (focus loss, background) re-arms by itself; it shows as activating.
    val waking = state.inputActivating || state.inputResuming
    stop.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    listOf(numpadBtn, presentationBtn, deckBtn).forEach {
      it.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
      it.isEnabled = enabled
    }
    inputViews.forEach { it.isEnabled = enabled; it.alpha = if (enabled) 1f else 0.4f }
    pad.isEnabled = enabled
    strip.isEnabled = enabled
    if (!enabled && !state.inputResuming) {
      keyboard.dismiss()
      numpad.dismiss()
      presentation.dismiss()
      deck.dismiss()
    }

    val next = when {
      !connected -> Blocker.DISCONNECTED
      !state.inputSupported -> Blocker.OUTDATED
      allowed == false -> Blocker.BLOCKED
      !enabled -> Blocker.OFF
      else -> Blocker.NONE
    }
    if (next != blocker || (next == Blocker.OFF && activating != waking)) {
      blocker = next
      activating = waking
      lockContent.removeAllViews()
      val content = when (next) {
        Blocker.DISCONNECTED -> ui.emptyState(R.drawable.ic_unplug, "Sem conexão com o PC", "Conecte-se a um computador pareado.",
          ui.button("Abrir Conexão", ButtonStyle.TONAL) { host.navigate(Tab.CONNECTION) })
        Blocker.OUTDATED -> ui.emptyState(R.drawable.ic_circle_arrow_up, "Atualize o PC Remote no Windows", "A versão do PC ainda não aceita mouse e teclado.")
        Blocker.BLOCKED -> ui.emptyState(R.drawable.ic_lock, "Bloqueado no PC", "No Windows, abra Transferência e libere o controle.")
        Blocker.OFF -> ui.emptyState(R.drawable.ic_touchpad, if (waking) "Preparando o touchpad…" else "Touchpad desligado", null,
          ui.button(if (waking) "Ativando…" else "Ativar touchpad", ButtonStyle.FILLED, large = true) {
            client.enableInput { error -> host.result(error) }
          }.apply { isEnabled = !waking })
        Blocker.NONE -> null
      }
      if (content != null) lockContent.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
      lockLayer.visibility = if (content == null) View.GONE else View.VISIBLE
    }
  }
}

/** Compose text on the phone, check it, then type it into the focused PC window. */
internal class KeyboardSheet(private val host: ScreenHost) {
  private val ui = host.ui
  private var sheet: PanelSheet? = null

  fun show() {
    val sheet = PanelSheet(host.panels, ui, "Digitar no PC")
    this.sheet = sheet
    val feedback = ui.text("", TextStyle.CAPTION, ui.palette.textSecondary)
    val counter = ui.tabular(ui.text("0/1024", TextStyle.CAPTION, ui.palette.textTertiary))
    val field = ui.field("Escreva aqui").apply {
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
      background = ui.rounded(ui.palette.surface, 16f, ui.palette.accent, 1.5f)
      imeOptions = EditorInfo.IME_ACTION_SEND
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
      minHeight = ui.dp(56)
    }
    lateinit var sendButton: PillButton
    lateinit var sendEnter: PillButton
    fun valid() = host.client.isInputTextAllowed(field.text.toString())
    fun refresh() {
      val bytes = field.text.toString().toByteArray(Charsets.UTF_8).size
      counter.text = "$bytes/1024"
      counter.setTextColor(if (bytes > 1024) ui.palette.danger else ui.palette.textTertiary)
      val ok = valid()
      sendButton.isEnabled = ok
      sendEnter.isEnabled = ok
      if (field.text.isNotEmpty() && !ok) {
        feedback.text = if (bytes > 1024) "Limite de 1024 bytes excedido." else "Quebras de linha não são permitidas."
        feedback.setTextColor(ui.palette.danger)
      }
    }
    fun send(withEnter: Boolean) {
      if (!valid()) return
      val text = field.text.toString()
      sendButton.setBusy(true, "Enviando…")
      host.client.execute(RemoteAction.KeyboardText(text)) { error ->
        if (error != null || !withEnter) finishSend(error, field, feedback, withEnter, sendButton)
        else host.client.execute(RemoteAction.KeyboardKey("enter")) { second -> finishSend(second, field, feedback, true, sendButton) }
      }
    }
    sendButton = ui.button("Enviar", ButtonStyle.FILLED, R.drawable.ic_send) { send(false) }
    sendEnter = ui.button("Enviar + Enter", ButtonStyle.TONAL) { send(true) }
    field.addTextChangedListener(SimpleWatcher {
      feedback.text = ""
      feedback.setTextColor(ui.palette.textSecondary)
      refresh()
    })
    field.setOnEditorActionListener { _, actionId, event ->
      val enter = actionId == EditorInfo.IME_ACTION_SEND || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
      if (enter) send(false)
      enter
    }
    sheet.body.addView(field, ui.matchWrap())
    sheet.body.addView(ui.row(12).apply {
      gravity = Gravity.TOP
      addView(feedback, ui.weighted())
      addView(counter)
    })
    sheet.footer.addView(sendEnter, ui.weighted())
    sheet.footer.addView(sendButton, ui.weighted())
    refresh()
    val keyboard = host.activity.getSystemService(InputMethodManager::class.java)
    sheet.show {
      keyboard?.hideSoftInputFromWindow(host.activity.window.decorView.windowToken, 0)
      if (this.sheet === sheet) this.sheet = null
    }
    field.requestFocus()
    field.post { keyboard?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
  }

  private fun finishSend(error: String?, field: android.widget.EditText, feedback: android.widget.TextView, enter: Boolean, button: PillButton) {
    host.activity.runOnUiThread {
      button.setBusy(false)
      if (error == null) {
        field.setText("")
        feedback.text = if (enter) "Enviado com Enter." else "Enviado."
        feedback.setTextColor(ui.palette.success)
      } else {
        feedback.text = error
        feedback.setTextColor(ui.palette.danger)
      }
    }
  }

  fun dismiss() {
    sheet?.dismiss()
    sheet = null
  }
}

/** Dedicated numeric keypad (`numpad.v1`) with fallback to text/key actions for older PCs. */
internal class NumpadSheet(private val host: ScreenHost) {
  private val ui = host.ui
  private var sheet: PanelSheet? = null

  private data class PadKey(val label: String, val numpadKey: String?, val fallbackText: String?, val fallbackKey: String? = null, val accent: Boolean = false)

  private val rows = listOf(
    listOf(
      PadKey("7", "numpad_7", "7"),
      PadKey("8", "numpad_8", "8"),
      PadKey("9", "numpad_9", "9"),
      PadKey("÷", "numpad_divide", "/"),
    ),
    listOf(
      PadKey("4", "numpad_4", "4"),
      PadKey("5", "numpad_5", "5"),
      PadKey("6", "numpad_6", "6"),
      PadKey("×", "numpad_multiply", "*"),
    ),
    listOf(
      PadKey("1", "numpad_1", "1"),
      PadKey("2", "numpad_2", "2"),
      PadKey("3", "numpad_3", "3"),
      PadKey("−", "numpad_subtract", "-"),
    ),
    listOf(
      PadKey("0", "numpad_0", "0"),
      PadKey(",", "numpad_comma", ","),
      PadKey(".", "numpad_decimal", "."),
      PadKey("+", "numpad_add", "+"),
    ),
    listOf(
      PadKey("Tab", null, null, fallbackKey = "tab"),
      PadKey("=", null, "="),
      PadKey("⌫", null, null, fallbackKey = "backspace"),
      PadKey("Enter", "numpad_enter", null, fallbackKey = "enter", accent = true),
    ),
  )

  fun show() {
    val sheet = PanelSheet(host.panels, ui, "Teclado Numérico")
    this.sheet = sheet
    val grid = ui.column(8)
    rows.forEach { row ->
      grid.addView(ui.row(8).apply {
        row.forEach { k ->
          addView(buildKey(k), LinearLayout.LayoutParams(0, ui.dp(56), 1f))
        }
      }, ui.matchWrap())
    }
    sheet.body.addView(grid, ui.matchWrap())
    sheet.show { if (this.sheet === sheet) this.sheet = null }
  }

  private fun buildKey(k: PadKey): View = FrameLayout(ui.context).apply {
    val p = ui.palette
    val bgColor = if (k.accent) p.accent else p.surface
    val borderColor = if (k.accent) p.accent else p.borderStrong
    val textColor = if (k.accent) p.onAccent else p.text
    background = ui.ripple(ui.rounded(bgColor, 16f, borderColor), 16f)
    isClickable = true
    isFocusable = true
    contentDescription = when (k.label) {
      "⌫" -> "Apagar"
      "÷" -> "Dividir"
      "×" -> "Multiplicar"
      "−" -> "Subtrair"
      "+" -> "Somar"
      else -> k.label
    }
    addView(
      ui.tabular(ui.text(k.label, if (k.label.length == 1) TextStyle.TITLE else TextStyle.BODY_STRONG, textColor)),
      FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
    )
    setOnClickListener { v ->
      v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
      val action = when {
        k.numpadKey != null && host.client.view.numpadSupported -> RemoteAction.KeyboardKey(k.numpadKey)
        k.fallbackKey != null -> RemoteAction.KeyboardKey(k.fallbackKey)
        else -> RemoteAction.KeyboardText(k.fallbackText.orEmpty())
      }
      host.client.execute(action) { host.result(it) }
    }
  }

  fun dismiss() {
    sheet?.dismiss()
    sheet = null
  }
}

/** Presentation remote (`presentation.v1`) with slide navigation, screen blackout/whiteout, and tactile timer. */
internal class PresentationSheet(private val host: ScreenHost) {
  private val ui = host.ui
  private var sheet: PanelSheet? = null
  private val handler = android.os.Handler(android.os.Looper.getMainLooper())
  private var running = false
  private var elapsedSeconds = 0
  private var timerLabel: android.widget.TextView? = null
  private var toggleBtn: PillButton? = null

  val showing: Boolean get() = sheet?.showing == true

  private val ticker = object : Runnable {
    override fun run() {
      if (!running) return
      elapsedSeconds++
      updateTimerUi()
      if (elapsedSeconds > 0 && elapsedSeconds % 300 == 0) {
        timerLabel?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
      }
      handler.postDelayed(this, 1000L)
    }
  }

  fun show() {
    val sheet = PanelSheet(host.panels, ui, "Apresentador")
    this.sheet = sheet
    val p = ui.palette

    val clock = ui.tabular(ui.text(formatTime(elapsedSeconds), TextStyle.HEADLINE, p.text))
    timerLabel = clock
    val playPause = ui.button(if (running) "Pausar" else "Timer", ButtonStyle.TONAL, if (running) R.drawable.ic_pause else R.drawable.ic_play) {
      running = !running
      handler.removeCallbacks(ticker)
      if (running) handler.postDelayed(ticker, 1000L)
      updateTimerUi()
    }
    toggleBtn = playPause
    val resetBtn = ui.iconButton(R.drawable.ic_rotate_ccw, "Zerar cronômetro", 44) {
      running = false
      elapsedSeconds = 0
      handler.removeCallbacks(ticker)
      updateTimerUi()
    }

    sheet.body.addView(ui.row(12).apply {
      background = ui.rounded(p.surfaceSunken, 16f, p.border)
      setPadding(ui.dp(16), ui.dp(10), ui.dp(12), ui.dp(10))
      addView(clock, ui.weighted())
      addView(playPause)
      addView(resetBtn)
    }, ui.matchWrap())

    sheet.body.addView(ui.row(12).apply {
      addView(slideButton("Anterior", R.drawable.ic_arrow_left, primary = false) { stepSlide(next = false) }, LinearLayout.LayoutParams(0, ui.dp(84), 1f))
      addView(slideButton("Próximo", R.drawable.ic_arrow_right, primary = true) { stepSlide(next = true) }, LinearLayout.LayoutParams(0, ui.dp(84), 1f))
    }, ui.matchWrap())

    sheet.body.addView(ui.row(8).apply {
      addView(ui.button("Início (F5)", ButtonStyle.TONAL, R.drawable.ic_play) { sendCommand("start") }, ui.weighted())
      addView(ui.button("Atual", ButtonStyle.TONAL) { sendCommand("start_current") }, ui.weighted())
      addView(ui.button("Sair (Esc)", ButtonStyle.OUTLINED, R.drawable.ic_x) { sendCommand("stop") }, ui.weighted())
    }, ui.matchWrap())

    sheet.body.addView(ui.row(8).apply {
      addView(ui.button("Tela preta", ButtonStyle.OUTLINED, R.drawable.ic_moon) { sendCommand("blackout") }, ui.weighted())
      addView(ui.button("Tela branca", ButtonStyle.OUTLINED, R.drawable.ic_sun) { sendCommand("whiteout") }, ui.weighted())
    }, ui.matchWrap())

    sheet.show {
      if (this.sheet === sheet) {
        this.sheet = null
        timerLabel = null
        toggleBtn = null
      }
    }
  }

  private fun slideButton(label: String, icon: Int, primary: Boolean, onClick: () -> Unit): View = ui.column(6).apply {
    val p = ui.palette
    gravity = Gravity.CENTER
    val bg = if (primary) p.accent else p.surface
    val border = if (primary) p.accent else p.borderStrong
    val fg = if (primary) p.onAccent else p.text
    background = ui.ripple(ui.rounded(bg, 20f, border), 20f)
    isClickable = true
    isFocusable = true
    contentDescription = "Slide ${label.lowercase()}"
    addView(ui.icon(icon, fg, 24))
    addView(ui.text(label, TextStyle.BODY_STRONG, fg))
    setOnClickListener { v ->
      v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
      onClick()
    }
  }

  fun stepSlide(next: Boolean) {
    sendCommand(if (next) "next" else "previous")
  }

  private fun sendCommand(command: String) {
    val action = if (host.client.view.presentationSupported) {
      RemoteAction.PresentationControl(command)
    } else {
      when (command) {
        "next" -> RemoteAction.KeyboardKey("right")
        "previous" -> RemoteAction.KeyboardKey("left")
        "stop" -> RemoteAction.KeyboardKey("escape")
        "blackout" -> RemoteAction.KeyboardText("b")
        "whiteout" -> RemoteAction.KeyboardText("w")
        else -> RemoteAction.PresentationControl(command)
      }
    }
    host.client.execute(action) { host.result(it) }
  }

  private fun updateTimerUi() {
    timerLabel?.text = formatTime(elapsedSeconds)
    toggleBtn?.setLabel(if (running) "Pausar" else "Timer")
    toggleBtn?.setIcon(if (running) R.drawable.ic_pause else R.drawable.ic_play)
  }

  private fun formatTime(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)

  fun dismiss() {
    sheet?.dismiss()
    sheet = null
  }
}

/** Tactile 2-column macro grid (`deck.v1`) for instant OS shortcuts on Windows and Linux. */
internal class QuickDeckSheet(private val host: ScreenHost) {
  private val ui = host.ui
  private var sheet: PanelSheet? = null

  private data class DeckItem(val id: String, val label: String, val icon: Int)

  private val items = listOf(
    DeckItem("show_desktop", "Mostrar Desktop", R.drawable.ic_monitor),
    DeckItem("switch_window", "Alternar Janela", R.drawable.ic_refresh_cw),
    DeckItem("task_view", "Visão de Tarefas", R.drawable.ic_monitor_smartphone),
    DeckItem("screenshot", "Capturar Tela", R.drawable.ic_scan_qr_code),
    DeckItem("fullscreen", "Tela Cheia (F11)", R.drawable.ic_external_link),
    DeckItem("calculator", "Calculadora", R.drawable.ic_keyboard),
    DeckItem("downloads", "Downloads", R.drawable.ic_download),
    DeckItem("browser", "Navegador", R.drawable.ic_search),
    DeckItem("task_manager", "Gerenciador", R.drawable.ic_zap),
    DeckItem("emoji_picker", "Emojis", R.drawable.ic_sun),
    DeckItem("close_window", "Fechar Janela", R.drawable.ic_x),
    DeckItem("lock_pc", "Bloquear PC", R.drawable.ic_lock),
  )

  fun show() {
    val sheet = PanelSheet(host.panels, ui, "Deck de Ações")
    this.sheet = sheet
    val grid = ui.column(10)
    items.chunked(2).forEach { pair ->
      grid.addView(ui.row(10).apply {
        pair.forEach { item ->
          addView(buildCard(item), LinearLayout.LayoutParams(0, ui.dp(60), 1f))
        }
      }, ui.matchWrap())
    }
    sheet.body.addView(grid, ui.matchWrap())
    sheet.show { if (this.sheet === sheet) this.sheet = null }
  }

  private fun buildCard(item: DeckItem): View = ui.row(10).apply {
    val p = ui.palette
    gravity = Gravity.CENTER_VERTICAL
    setPadding(ui.dp(14), ui.dp(10), ui.dp(12), ui.dp(10))
    background = ui.ripple(ui.rounded(p.surface, 16f, p.borderStrong), 16f)
    isClickable = true
    isFocusable = true
    contentDescription = item.label
    addView(FrameLayout(context).apply {
      background = ui.rounded(p.accentSoft, 10f)
      addView(ui.icon(item.icon, p.accentText, 18), FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER))
    }, LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)))
    addView(ui.text(item.label, TextStyle.LABEL, p.text).apply {
      maxLines = 2
      ellipsize = android.text.TextUtils.TruncateAt.END
    }, ui.weighted())
    setOnClickListener { v ->
      v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
      host.client.execute(RemoteAction.DeckTrigger(item.id)) { host.result(it) }
    }
  }

  fun dismiss() {
    sheet?.dismiss()
    sheet = null
  }
}

/** TextWatcher that only cares about the final text. */
internal class SimpleWatcher(private val action: () -> Unit) : android.text.TextWatcher {
  override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
  override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
  override fun afterTextChanged(s: android.text.Editable?) = action()
}
