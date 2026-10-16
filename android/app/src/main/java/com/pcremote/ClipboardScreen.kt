package com.pcremote

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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

  // Images and PDFs, one at a time through files.v1.
  private val blockedIcon = ImageView(ui.context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
  private val blockedText = ui.text("", TextStyle.BODY, p.textSecondary)
  private val openConnection = ui.button("Abrir Conexão", ButtonStyle.TONAL) { host.navigate(Tab.CONNECTION) }
  private val blocked = ui.column(12).apply {
    background = ui.rounded(p.surfaceSunken, 12f, p.border)
    setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
    addView(ui.row(10).apply {
      gravity = Gravity.TOP
      addView(blockedIcon, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
      addView(blockedText, ui.weighted())
    })
    addView(openConnection, ui.matchWrap())
  }
  private val activeName = ui.text("", TextStyle.BODY_STRONG).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
  private val activePercent = ui.tabular(ui.text("", TextStyle.LABEL, p.textSecondary))
  private val bar = ProgressBar(ui.context, null, android.R.attr.progressBarStyleHorizontal).apply {
    max = 1000
    progressTintList = ColorStateList.valueOf(p.accent)
    indeterminateTintList = ColorStateList.valueOf(p.accent)
    progressBackgroundTintList = ColorStateList.valueOf(p.borderStrong)
  }
  private val activeBytes = ui.tabular(ui.text("", TextStyle.CAPTION, p.textSecondary))
  private val cancelFile = ui.button("Cancelar", ButtonStyle.TEXT, R.drawable.ic_x) { client.cancelFile() }
  private val activeFile = ui.column(8).apply {
    background = ui.rounded(p.surfaceSunken, 16f, p.border)
    setPadding(ui.dp(16), ui.dp(14), ui.dp(8), ui.dp(6))
    addView(ui.row(12).apply {
      setPadding(0, 0, ui.dp(8), 0)
      addView(activeName, ui.weighted())
      addView(activePercent)
    })
    addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(8) })
    addView(ui.row(8).apply {
      addView(activeBytes, ui.weighted())
      addView(cancelFile)
    })
  }
  private val queueText = ui.text("", TextStyle.CAPTION, p.textSecondary)
  private val resumeQueue = ui.button("Continuar", ButtonStyle.TONAL) { client.resumeFiles() }
  private val clearQueue = ui.button("Limpar fila", ButtonStyle.TEXT) { client.clearFileQueue() }
  private val queueRow = ui.row(8).apply {
    addView(queueText, ui.weighted())
    addView(resumeQueue)
    addView(clearQueue)
  }
  private val resultIcon = ImageView(ui.context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
  private val resultText = ui.text("", TextStyle.BODY)
  private val result = ui.row(10).apply {
    gravity = Gravity.TOP
    // Announces each finished, failed or cancelled file once.
    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    addView(resultIcon, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
    addView(resultText, ui.weighted())
  }
  private val pick = ui.button("Escolher imagem ou PDF", ButtonStyle.FILLED, R.drawable.ic_file_up) { host.pickFiles() }
  private val filesCard = ui.card().apply {
    addView(ui.cardHeader("Arquivos", "PNG, JPG ou PDF de até 50 MB. O PC salva em Downloads\\PC Remote."))
    addView(blocked)
    addView(activeFile)
    addView(queueRow)
    addView(result)
    addView(pick, ui.matchWrap())
  }

  override val view: View = ScrollView(ui.context).apply {
    isFillViewport = true
    addView(ui.column(16).apply {
      setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(32))
      addView(filesCard)
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
    renderFiles(state)
  }

  private fun renderFiles(state: UiState) {
    val files = state.files
    val connected = state.status == "Conectado"
    val allowed = state.snapshot?.files?.allowed
    val ready = connected && state.filesSupported && allowed == true
    // Old PCs never advertise file.transfer, so nothing here sends a request to them.
    val block = when {
      !connected -> R.drawable.ic_unplug to "Conecte-se ao PC para enviar arquivos."
      !state.filesSupported -> R.drawable.ic_circle_arrow_up to "Atualize o PC Remote no Windows para enviar arquivos."
      allowed == false -> R.drawable.ic_lock to "No Windows, abra Transferência e ligue Receber imagens e PDFs do celular."
      else -> null
    }
    blocked.visibility = if (block == null) View.GONE else View.VISIBLE
    block?.let { (icon, message) ->
      blockedIcon.setImageDrawable(ui.drawable(icon, p.textSecondary, 20))
      blockedText.text = message
    }
    openConnection.visibility = if (!connected) View.VISIBLE else View.GONE
    pick.isEnabled = ready

    val active = files.active
    activeFile.visibility = if (active == null) View.GONE else View.VISIBLE
    if (active != null) {
      val permille = if (active.size > 0) (active.received * 1000 / active.size).toInt().coerceIn(0, 1000) else 0
      activeName.text = active.name
      bar.isIndeterminate = !active.streaming
      bar.progress = permille
      activePercent.text = if (active.streaming) "${permille / 10}%" else ""
      activeBytes.text = if (active.streaming) FileRules.progressLabel(active.received, active.size) else "Preparando ${FileRules.megabytes(active.size)} MB…"
      bar.contentDescription = if (active.streaming) "Enviando ${active.name}, ${permille / 10} por cento" else "Preparando o envio de ${active.name}"
      cancelFile.contentDescription = "Cancelar o envio de ${active.name}"
    }

    val waiting = files.queued.size
    queueRow.visibility = if (waiting == 0) View.GONE else View.VISIBLE
    val count = if (waiting == 1) "1 arquivo" else "$waiting arquivos"
    queueText.text = when {
      files.paused -> "Envio pausado. $count na fila."
      !ready -> "$count aguardando o PC."
      active != null -> "Mais $count na fila."
      else -> "$count na fila."
    }
    resumeQueue.visibility = if (files.paused && ready) View.VISIBLE else View.GONE

    val last = files.last
    result.visibility = if (last == null) View.GONE else View.VISIBLE
    if (last != null) {
      val (icon, color) = when (last.outcome) {
        FileOutcome.SENT -> R.drawable.ic_circle_check to p.success
        FileOutcome.FAILED -> R.drawable.ic_circle_alert to p.danger
        FileOutcome.CANCELLED -> R.drawable.ic_info to p.textSecondary
      }
      resultIcon.setImageDrawable(ui.drawable(icon, color, 20))
      resultText.text = last.message
      resultText.setTextColor(if (last.outcome == FileOutcome.FAILED) p.danger else p.text)
    }
  }
}

internal fun dialogTheme(palette: Palette): Int =
  if (palette.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
