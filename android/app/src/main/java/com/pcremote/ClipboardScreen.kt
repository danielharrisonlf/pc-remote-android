package com.pcremote

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

internal class ClipboardScreen(private val host: ScreenHost) : Screen {
  private val ui = host.ui
  private val client = host.client
  private val p = ui.palette
  private var state = UiState()
  private var expanded = false
  private var lastShared = ""

  private val liveChip = ui.chip()
  private val receivedText = ui.text("", TextStyle.BODY).apply {
    setTextIsSelectable(true)
    contentDescription = "Texto recebido do PC"
  }
  private val viewer = LinearLayout(ui.context).apply {
    background = ui.rounded(p.surfaceSunken, 16f, p.border)
    setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
    addView(receivedText, ui.matchWrap())
  }
  private val expand = ui.button("Mostrar tudo", ButtonStyle.TEXT) { expanded = !expanded; render(state) }
  private val copy = ui.button("Copiar no celular", ButtonStyle.FILLED, R.drawable.ic_copy) {
    client.copyReceived { host.result(it, "Copiado no celular.") }
  }
  private val clear = ui.button("Limpar", ButtonStyle.OUTLINED, R.drawable.ic_eraser) { client.clearReceived() }
  private val receivedActions = ui.row(8).apply { addView(copy, ui.weighted()); addView(clear) }
  private val nothing = ui.emptyState(R.drawable.ic_clipboard_paste, "Nada recebido nesta sessão")
  private val receivedCard = ui.card().apply {
    addView(ui.cardHeader("Recebido do PC", trailing = liveChip))
    addView(viewer)
    addView(expand, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    addView(receivedActions)
    addView(nothing)
  }

  private val draft = ui.field("Texto para enviar ao PC").apply {
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    minLines = 3
    maxLines = 6
    gravity = Gravity.TOP or Gravity.START
    addTextChangedListener(SimpleWatcher { updateSendButtons() })
  }
  private val sharedHint = ui.text("Texto compartilhado. Revise e envie.", TextStyle.CAPTION, p.success)
  private val sendDraft = ui.button("Enviar texto", ButtonStyle.FILLED, R.drawable.ic_send) {
    client.sendClipboard(draft.text.toString()) { error ->
      if (error == null) draft.setText("")
      host.result(error, "Texto enviado ao PC.")
    }
  }
  private val sendCopied = ui.button("Enviar o que copiei", ButtonStyle.TONAL) {
    client.sendClipboard(null) { host.result(it, "Texto enviado ao PC.") }
  }
  private val fetch = ui.button("Buscar do PC", ButtonStyle.OUTLINED, R.drawable.ic_download) {
    client.getClipboard { host.result(it) }
  }
  private val sendCard = ui.card().apply {
    addView(ui.cardHeader("Enviar ao PC"))
    addView(draft, ui.matchWrap())
    addView(sharedHint)
    addView(ui.row(8).apply { addView(sendDraft, ui.weighted()); addView(sendCopied, ui.weighted()) })
    addView(fetch, ui.matchWrap())
  }

  private val live = ui.switchRow("Sincronizar em tempo real", "Desligue para pausar os dois sentidos.", R.drawable.ic_zap) { on ->
    client.setPreferences(state.preferences.copy(paused = !on))
  }
  private val upload = ui.switchRow("Celular → PC", "Envia o que você copiar com o app aberto.", R.drawable.ic_send) { on ->
    client.setPreferences(state.preferences.copy(upload = on))
  }
  private val download = ui.switchRow("PC → celular", "Recebe o que você copiar no PC.", R.drawable.ic_download) { on ->
    client.setPreferences(state.preferences.copy(download = on))
    if (on) requestNotificationPermission()
  }
  private val autoApply = ui.switchRow("Copiar automaticamente", "Copia no celular o texto recebido, com o app em foco.", R.drawable.ic_clipboard_copy) { on ->
    client.setPreferences(state.preferences.copy(autoApply = on))
  }
  private val syncCard = ui.card(8).apply {
    addView(ui.cardHeader("Sincronização"))
    addView(live); addView(ui.divider()); addView(upload); addView(ui.divider()); addView(download); addView(ui.divider()); addView(autoApply)
    addView(ui.text("O Android só lê cópias de outros apps com o PC Remote aberto. Para enviar de outro app, use Compartilhar.", TextStyle.CAPTION, p.textSecondary).apply { setPadding(0, ui.dp(8), 0, 0) })
  }

  override val view: View = ScrollView(ui.context).apply {
    isFillViewport = true
    addView(ui.column(16).apply {
      setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(32))
      addView(receivedCard)
      addView(sendCard)
      addView(syncCard)
    })
  }

  private fun updateSendButtons() {
    val connected = state.status == "Conectado"
    sendDraft.isEnabled = connected && draft.text.isNotBlank()
  }

  private fun requestNotificationPermission() {
    val activity = host.activity
    if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      AlertDialog.Builder(activity, dialogTheme(p))
        .setMessage("Permita notificações para saber quando chegar texto do PC.")
        .setNegativeButton("Agora não", null)
        .setPositiveButton("Permitir") { _, _ -> activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101) }
        .show()
    }
  }

  override fun render(state: UiState) {
    this.state = state
    val connected = state.status == "Conectado"
    val prefs = state.preferences
    val liveSync = !prefs.paused && (prefs.upload || prefs.download)
    liveChip.set(if (liveSync) Tone.SUCCESS else Tone.NEUTRAL, if (prefs.paused) "Pausada" else if (liveSync) "Em tempo real" else "Manual")

    val received = state.receivedText
    val has = received.isNotEmpty()
    viewer.visibility = if (has) View.VISIBLE else View.GONE
    receivedActions.visibility = viewer.visibility
    nothing.visibility = if (has) View.GONE else View.VISIBLE
    receivedText.text = received
    receivedText.maxLines = if (expanded) Int.MAX_VALUE else 8
    val long = received.lines().size > 8 || received.length > 400
    expand.visibility = if (has && long) View.VISIBLE else View.GONE
    expand.setLabel(if (expanded) "Mostrar menos" else "Mostrar tudo")

    if (state.sharedText.isNotEmpty() && state.sharedText != lastShared) {
      draft.setText(state.sharedText)
      draft.setSelection(draft.text.length)
      lastShared = state.sharedText
    }
    if (state.sharedText.isEmpty()) lastShared = ""
    sharedHint.visibility = if (state.sharedText.isNotEmpty()) View.VISIBLE else View.GONE
    updateSendButtons()
    sendCopied.isEnabled = connected
    fetch.isEnabled = connected

    live.bind(!prefs.paused)
    upload.bind(prefs.upload, enabled = !prefs.paused)
    download.bind(prefs.download, enabled = !prefs.paused)
    autoApply.bind(prefs.autoApply, enabled = !prefs.paused && prefs.download)
  }
}

internal fun dialogTheme(palette: Palette): Int =
  if (palette.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
