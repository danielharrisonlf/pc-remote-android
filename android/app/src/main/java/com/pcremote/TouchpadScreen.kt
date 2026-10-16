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
  fun haptic()
  /** Errors become snackbars; success shows one only when it says something new. */
  fun result(error: String?, success: String? = null)
  fun navigate(tab: Tab)
  /** System document picker for images and PDFs; the result joins the file queue. */
  fun pickFiles()
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
  private val stop = ui.button("Desligar", ButtonStyle.TEXT, R.drawable.ic_hand) { client.disableInput() }
  private val pad = TouchPadView(ui, client, { enabled }) { button ->
    host.haptic()
    client.execute(RemoteAction.PointerClick(button, 1)) { host.result(it) }
  }
  private val strip = ScrollStripView(ui, client, { enabled }) { host.haptic() }
  private val hint = ui.column(2).apply {
    gravity = Gravity.CENTER_HORIZONTAL
    background = ui.rounded(ui.palette.pad, 12f)
    setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
    addView(ui.text("Um dedo move o cursor, um toque clica", TextStyle.CAPTION, ui.palette.textTertiary).apply { gravity = Gravity.CENTER })
    addView(ui.text("Dois dedos rolam, toque com dois dedos abre o menu", TextStyle.CAPTION, ui.palette.textTertiary).apply { gravity = Gravity.CENTER })
  }
  private val lockContent = FrameLayout(ui.context).apply { setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16)) }
  // Scrolls when a short pad (small phone, update banner) cannot fit the message and button.
  private val lockLayer = ScrollView(ui.context).apply {
    setBackgroundColor(ui.palette.pad)
    isFillViewport = true
    isClickable = true
    addView(lockContent)
  }
  private val inputViews = mutableListOf<View>()
  private val keyboard = KeyboardSheet(host)

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
    // Fixed height: "Desligar" turns invisible, not gone, so the pad never jumps.
    addView(ui.row(8).apply {
      gravity = Gravity.CENTER_VERTICAL or Gravity.END
      minimumHeight = ui.dp(48)
      addView(stop)
    })
    addView(FrameLayout(context).apply {
      background = ui.rounded(ui.palette.pad, 28f, ui.palette.border)
      clipToOutline = true
      addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(FrameLayout(context).apply {
          addView(pad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
          addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(16) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
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
      addView(clickButton("Clique esquerdo", "left"), LinearLayout.LayoutParams(0, ui.dp(64), 1f))
      addView(ui.iconButton(R.drawable.ic_keyboard, "Abrir teclado", 64, tonal = true) { keyboard.show() }.also { inputViews += it })
      addView(clickButton("Clique direito", "right"), LinearLayout.LayoutParams(0, ui.dp(64), 1f))
    })
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
      host.haptic()
      client.execute(action) { host.result(it) }
    }
    inputViews += this
  }

  private fun clickButton(label: String, button: String): View = FrameLayout(ui.context).apply {
    background = ui.ripple(ui.rounded(ui.palette.surface, 20f, ui.palette.border), 20f)
    isClickable = true
    contentDescription = label
    addView(ui.text(label, TextStyle.LABEL, ui.palette.textSecondary), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    setOnClickListener {
      host.haptic()
      client.execute(RemoteAction.PointerClick(button, 1)) { host.result(it) }
    }
    inputViews += this
  }

  override fun render(state: UiState) {
    val connected = state.status == "Conectado"
    val allowed = state.snapshot?.input?.allowed
    enabled = connected && state.inputArmed && allowed == true && state.snapshot?.input?.enabled == true
    // A paused session (focus loss, background) re-arms by itself; it shows as activating.
    val waking = state.inputActivating || state.inputResuming
    stop.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    hint.visibility = if (enabled) View.VISIBLE else View.GONE
    inputViews.forEach { it.isEnabled = enabled; it.alpha = if (enabled) 1f else 0.4f }
    pad.isEnabled = enabled
    strip.isEnabled = enabled
    if (!enabled && !state.inputResuming) keyboard.dismiss()

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
        Blocker.DISCONNECTED -> ui.emptyState(R.drawable.ic_unplug, "Sem conexão com o PC", "Conecte-se a um computador pareado para usar o touchpad.",
          ui.button("Abrir Conexão", ButtonStyle.TONAL) { host.navigate(Tab.CONNECTION) })
        Blocker.OUTDATED -> ui.emptyState(R.drawable.ic_circle_arrow_up, "Atualize o PC Remote no Windows", "A versão do PC ainda não aceita mouse e teclado.")
        Blocker.BLOCKED -> ui.emptyState(R.drawable.ic_lock, "Bloqueado no PC", "No Windows, abra Transferência e ligue Permitir mouse e teclado pelo celular.")
        Blocker.OFF -> ui.emptyState(R.drawable.ic_touchpad, "Touchpad desligado", "Os cliques e o texto vão para a janela em foco no Windows.",
          ui.button(if (waking) "Ativando…" else "Ativar touchpad", ButtonStyle.FILLED, large = true) {
            client.enableInput { error -> if (error == null) host.haptic() else host.result(error) }
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
    val sheet = PanelSheet(host.panels, ui, "Digitar no PC", "O texto vai para a janela em foco no Windows.")
    this.sheet = sheet
    val feedback = ui.text("Revise antes de enviar: o texto é digitado exatamente como aparece aqui.", TextStyle.CAPTION, ui.palette.textSecondary)
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
        feedback.text = if (bytes > 1024) "Texto acima de 1024 bytes. Envie em partes." else "Quebras de linha e caracteres de controle não são enviados."
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
      feedback.text = "Revise antes de enviar: o texto é digitado exatamente como aparece aqui."
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
        host.haptic()
        field.setText("")
        feedback.text = if (enter) "Texto enviado e Enter pressionado." else "Texto enviado ao PC."
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

/** TextWatcher that only cares about the final text. */
internal class SimpleWatcher(private val action: () -> Unit) : android.text.TextWatcher {
  override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
  override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
  override fun afterTextChanged(s: android.text.Editable?) = action()
}
