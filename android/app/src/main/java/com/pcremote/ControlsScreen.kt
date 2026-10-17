package com.pcremote

import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal enum class ControlTool { DECK, NUMPAD, SLIDES }

/**
 * Dedicated tab for remote productivity tools: Quick Action Deck (`deck.v1`),
 * Numeric Keypad (`numpad.v1`), and Presentation Remote (`presentation.v1`).
 */
internal class ControlsScreen(private val host: ScreenHost) : Screen {
  private enum class Blocker { NONE, DISCONNECTED, OUTDATED, BLOCKED, OFF }

  private val ui = host.ui
  private val client = host.client
  private val p = ui.palette
  private var enabled = false
  private var currentTool = ControlTool.DECK
  private var blocker: Blocker? = null
  private var activating = false

  private val handler = Handler(Looper.getMainLooper())
  private var timerRunning = false
  private var elapsedSeconds = 0
  private lateinit var timerLabel: TextView
  private lateinit var timerToggleBtn: PillButton

  private val ticker = object : Runnable {
    override fun run() {
      if (!timerRunning) return
      elapsedSeconds++
      updateTimerUi()
      if (elapsedSeconds > 0 && elapsedSeconds % 300 == 0) {
        timerLabel.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
      }
      handler.postDelayed(this, 1000L)
    }
  }

  private val segmented = Segmented(
    ui,
    listOf(
      Triple(ControlTool.DECK, "Deck", R.drawable.ic_zap),
      Triple(ControlTool.NUMPAD, "123", R.drawable.ic_keyboard),
      Triple(ControlTool.SLIDES, "Slides", R.drawable.ic_play),
    ),
  ) { selected ->
    currentTool = selected
    syncToolVisibility()
  }.apply {
    select(currentTool)
  }

  private val stopBtn = ui.button("Desligar", ButtonStyle.TEXT, R.drawable.ic_hand) { client.disableInput() }
  private val blockerCard = ui.card()
  private val deckView = buildDeckCard()
  private val numpadView = buildNumpadCard()
  private val slidesView = buildSlidesCard()

  private val activeContainer = ui.column(12).apply {
    addView(deckView, ui.matchWrap())
    addView(numpadView, ui.matchWrap())
    addView(slidesView, ui.matchWrap())
  }

  override val view: View = ScrollView(ui.context).apply {
    isFillViewport = true
    addView(ui.column(12).apply {
      setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(28))
      addView(segmented, ui.matchWrap())
      addView(blockerCard, ui.matchWrap())
      addView(activeContainer, ui.matchWrap())
    })
  }

  init {
    syncToolVisibility()
  }

  private fun syncToolVisibility() {
    segmented.select(currentTool)
    deckView.visibility = if (currentTool == ControlTool.DECK) View.VISIBLE else View.GONE
    numpadView.visibility = if (currentTool == ControlTool.NUMPAD) View.VISIBLE else View.GONE
    slidesView.visibility = if (currentTool == ControlTool.SLIDES) View.VISIBLE else View.GONE
  }

  override fun interceptsVolumeKeys(): Boolean =
    enabled && currentTool == ControlTool.SLIDES && !host.panels.isOpen

  override fun onVolumeKey(up: Boolean) {
    if (enabled && currentTool == ControlTool.SLIDES) stepSlide(next = up)
  }

  override fun render(state: UiState) {
    val connected = state.status == "Conectado"
    val allowed = state.snapshot?.input?.allowed
    enabled = connected && state.inputArmed && allowed == true && state.snapshot?.input?.enabled == true
    val waking = state.inputActivating || state.inputResuming

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
      blockerCard.removeAllViews()
      val content = when (next) {
        Blocker.DISCONNECTED -> ui.emptyState(
          R.drawable.ic_unplug,
          "Sem conexão com o PC",
          "Conecte-se a um computador pareado para usar o Deck, teclado numérico e slides.",
          ui.button("Abrir Conexão", ButtonStyle.TONAL) { host.navigate(Tab.CONNECTION) },
        )
        Blocker.OUTDATED -> ui.emptyState(
          R.drawable.ic_circle_arrow_up,
          "Atualize o PC Remote no Windows",
          "A versão do PC ainda não aceita comandos de controle remoto.",
        )
        Blocker.BLOCKED -> ui.emptyState(
          R.drawable.ic_lock,
          "Bloqueado no PC",
          "No Windows, abra Transferência e libere o controle.",
        )
        Blocker.OFF -> ui.emptyState(
          R.drawable.ic_zap,
          if (waking) "Preparando os controles…" else "Controles desligados",
          "Ative o controle de entrada para enviar atalhos, números e passar slides.",
          ui.button(if (waking) "Ativando…" else "Ativar controles", ButtonStyle.FILLED, large = true) {
            client.enableInput { error -> host.result(error) }
          }.apply { isEnabled = !waking },
        )
        Blocker.NONE -> null
      }
      if (content != null) blockerCard.addView(content, ui.matchWrap())
      blockerCard.visibility = if (content == null) View.GONE else View.VISIBLE
      activeContainer.visibility = if (content == null) View.VISIBLE else View.GONE
    }
  }

  // --- Quick Deck ---

  private data class DeckItem(val id: String, val label: String, val icon: Int)

  private val deckItems = listOf(
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

  private fun buildDeckCard(): View = ui.card(12).apply {
    addView(ui.cardHeader("Deck de Ações", "Atalhos instantâneos do sistema no PC", stopBtn))
    val grid = ui.column(10)
    deckItems.chunked(2).forEach { pair ->
      grid.addView(ui.row(10).apply {
        pair.forEach { item ->
          addView(buildDeckTile(item), LinearLayout.LayoutParams(0, ui.dp(62), 1f))
        }
      }, ui.matchWrap())
    }
    addView(grid, ui.matchWrap())
  }

  private fun buildDeckTile(item: DeckItem): View = ui.row(10).apply {
    gravity = Gravity.CENTER_VERTICAL
    setPadding(ui.dp(14), ui.dp(10), ui.dp(12), ui.dp(10))
    background = ui.ripple(ui.rounded(p.surfaceSunken, 16f, p.borderStrong), 16f)
    isClickable = true
    isFocusable = true
    contentDescription = item.label
    addView(FrameLayout(context).apply {
      background = ui.rounded(p.accentSoft, 10f)
      addView(ui.icon(item.icon, p.accentText, 18), FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER))
    }, LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)))
    addView(ui.text(item.label, TextStyle.LABEL, p.text).apply {
      maxLines = 2
      ellipsize = TextUtils.TruncateAt.END
    }, ui.weighted())
    setOnClickListener { v ->
      v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
      client.execute(RemoteAction.DeckTrigger(item.id)) { host.result(it) }
    }
  }

  // --- Numpad (123) ---

  private data class PadKey(
    val label: String,
    val numpadKey: String?,
    val fallbackText: String?,
    val fallbackKey: String? = null,
    val accent: Boolean = false,
  )

  private val numpadRows = listOf(
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

  private fun buildNumpadCard(): View = ui.card(12).apply {
    addView(ui.cardHeader(
      "Teclado Numérico",
      "Planilhas, calculadora e entrada rápida de números",
      ui.button("Desligar", ButtonStyle.TEXT, R.drawable.ic_hand) { client.disableInput() },
    ))
    val grid = ui.column(8)
    numpadRows.forEach { row ->
      grid.addView(ui.row(8).apply {
        row.forEach { k ->
          addView(buildNumpadKey(k), LinearLayout.LayoutParams(0, ui.dp(60), 1f))
        }
      }, ui.matchWrap())
    }
    addView(grid, ui.matchWrap())
  }

  private fun buildNumpadKey(k: PadKey): View = FrameLayout(ui.context).apply {
    val bgColor = if (k.accent) p.accent else p.surfaceSunken
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
      v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
      val action = when {
        k.numpadKey != null && client.view.numpadSupported -> RemoteAction.KeyboardKey(k.numpadKey)
        k.fallbackKey != null -> RemoteAction.KeyboardKey(k.fallbackKey)
        else -> RemoteAction.KeyboardText(k.fallbackText.orEmpty())
      }
      client.execute(action) { host.result(it) }
    }
  }

  // --- Presentation Remote (Slides) ---

  private fun buildSlidesCard(): View = ui.card(14).apply {
    addView(ui.cardHeader(
      "Apresentador",
      "Botões de volume do celular também passam os slides",
      ui.button("Desligar", ButtonStyle.TEXT, R.drawable.ic_hand) { client.disableInput() },
    ))

    timerLabel = ui.tabular(ui.text(formatTime(elapsedSeconds), TextStyle.HEADLINE, p.text))
    timerToggleBtn = ui.button(
      if (timerRunning) "Pausar" else "Timer",
      ButtonStyle.TONAL,
      if (timerRunning) R.drawable.ic_pause else R.drawable.ic_play,
    ) {
      timerRunning = !timerRunning
      handler.removeCallbacks(ticker)
      if (timerRunning) handler.postDelayed(ticker, 1000L)
      updateTimerUi()
    }
    val resetBtn = ui.iconButton(R.drawable.ic_rotate_ccw, "Zerar cronômetro", 44) {
      timerRunning = false
      elapsedSeconds = 0
      handler.removeCallbacks(ticker)
      updateTimerUi()
    }

    addView(ui.row(12).apply {
      background = ui.rounded(p.surfaceSunken, 16f, p.border)
      setPadding(ui.dp(16), ui.dp(10), ui.dp(12), ui.dp(10))
      addView(timerLabel, ui.weighted())
      addView(timerToggleBtn)
      addView(resetBtn)
    }, ui.matchWrap())

    addView(ui.row(12).apply {
      addView(slideButton("Anterior", R.drawable.ic_arrow_left, primary = false) { stepSlide(next = false) }, LinearLayout.LayoutParams(0, ui.dp(92), 1f))
      addView(slideButton("Próximo", R.drawable.ic_arrow_right, primary = true) { stepSlide(next = true) }, LinearLayout.LayoutParams(0, ui.dp(92), 1f))
    }, ui.matchWrap())

    addView(ui.row(8).apply {
      addView(ui.button("Início (F5)", ButtonStyle.TONAL, R.drawable.ic_play) { sendSlideCommand("start") }, ui.weighted())
      addView(ui.button("Atual", ButtonStyle.TONAL) { sendSlideCommand("start_current") }, ui.weighted())
      addView(ui.button("Sair (Esc)", ButtonStyle.OUTLINED, R.drawable.ic_x) { sendSlideCommand("stop") }, ui.weighted())
    }, ui.matchWrap())

    addView(ui.row(8).apply {
      addView(ui.button("Tela preta", ButtonStyle.OUTLINED, R.drawable.ic_moon) { sendSlideCommand("blackout") }, ui.weighted())
      addView(ui.button("Tela branca", ButtonStyle.OUTLINED, R.drawable.ic_sun) { sendSlideCommand("whiteout") }, ui.weighted())
    }, ui.matchWrap())
  }

  private fun slideButton(label: String, icon: Int, primary: Boolean, onClick: () -> Unit): View = ui.column(6).apply {
    gravity = Gravity.CENTER
    val bg = if (primary) p.accent else p.surfaceSunken
    val border = if (primary) p.accent else p.borderStrong
    val fg = if (primary) p.onAccent else p.text
    background = ui.ripple(ui.rounded(bg, 20f, border), 20f)
    isClickable = true
    isFocusable = true
    contentDescription = "Slide ${label.lowercase()}"
    addView(ui.icon(icon, fg, 24))
    addView(ui.text(label, TextStyle.BODY_STRONG, fg))
    setOnClickListener { v ->
      v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
      onClick()
    }
  }

  fun stepSlide(next: Boolean) {
    sendSlideCommand(if (next) "next" else "previous")
  }

  private fun sendSlideCommand(command: String) {
    val action = if (client.view.presentationSupported) {
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
    client.execute(action) { host.result(it) }
  }

  private fun updateTimerUi() {
    timerLabel.text = formatTime(elapsedSeconds)
    timerToggleBtn.setLabel(if (timerRunning) "Pausar" else "Timer")
    timerToggleBtn.setIcon(if (timerRunning) R.drawable.ic_pause else R.drawable.ic_play)
  }

  private fun formatTime(seconds: Int): String = "%02d:%02d".format(seconds / 60, seconds % 60)
}
