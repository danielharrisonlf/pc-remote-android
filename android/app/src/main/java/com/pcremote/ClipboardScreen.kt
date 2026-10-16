package com.pcremote

import android.content.res.ColorStateList
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Transferir: files in both directions first, then text. Clipboard sync itself has no controls here. */
internal class ClipboardScreen(private val host: ScreenHost) : Screen {
  private val ui = host.ui
  private val client = host.client
  private val p = ui.palette
  private var state = UiState()
  private var expanded = false
  private var lastShared = ""
  private var renderedHistory: List<FileRecord>? = null
  private val clock = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.forLanguageTag("pt-BR"))

  // Files, one at a time in each direction.
  private val filesSubtitle = ui.text("", TextStyle.CAPTION, p.textSecondary)
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
  private val outgoing = Progress("Enviando ao PC") { client.cancelFile() }
  private val incoming = Progress("Recebendo do PC") { client.cancelIncoming() }
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
  private val pick = ui.button("Enviar arquivo", ButtonStyle.FILLED, R.drawable.ic_file_up) { host.pickFiles() }
  private val historyTitle = ui.text("Recentes", TextStyle.LABEL, p.textSecondary)
  private val historyList = ui.column(0)
  private val filesCard = ui.card().apply {
    addView(ui.column(2).apply {
      addView(ui.text("Arquivos", TextStyle.TITLE).apply { accessibilityHeading() })
      addView(filesSubtitle)
    })
    addView(blocked)
    addView(outgoing.view)
    addView(incoming.view)
    addView(queueRow)
    addView(result)
    addView(pick, ui.matchWrap())
    addView(historyTitle)
    addView(historyList, ui.matchWrap())
  }

  // Text received from the PC; with real-time sync it is already on the phone clipboard.
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
  private val copy = ui.button("Copiar no celular", ButtonStyle.TONAL, R.drawable.ic_copy) {
    client.copyReceived { host.result(it, "Copiado no celular.") }
  }
  private val clear = ui.button("Limpar", ButtonStyle.OUTLINED, R.drawable.ic_eraser) { client.clearReceived() }
  private val receivedActions = ui.row(8).apply { addView(copy, ui.weighted()); addView(clear) }
  private val nothing = ui.text("Nada recebido nesta conexão.", TextStyle.BODY, p.textSecondary)
  private val receivedCard = ui.card().apply {
    addView(ui.cardHeader("Texto do PC", trailing = liveChip))
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
    client.getClipboard { host.result(it, "Texto do PC copiado no celular.") }
  }
  private val sendCard = ui.card().apply {
    addView(ui.cardHeader("Enviar texto ao PC"))
    addView(draft, ui.matchWrap())
    addView(sharedHint)
    addView(ui.row(8).apply { addView(sendDraft, ui.weighted()); addView(sendCopied, ui.weighted()) })
    addView(fetch, ui.matchWrap())
  }

  override val view: View = ScrollView(ui.context).apply {
    isFillViewport = true
    addView(ui.column(16).apply {
      setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(32))
      addView(filesCard)
      addView(receivedCard)
      addView(sendCard)
    })
  }

  /** Name, bar, bytes and Cancelar for one direction. */
  private inner class Progress(label: String, cancel: () -> Unit) {
    private val heading = ui.text(label, TextStyle.CAPTION, p.textSecondary)
    private val name = ui.text("", TextStyle.BODY_STRONG).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE }
    private val percent = ui.tabular(ui.text("", TextStyle.LABEL, p.textSecondary))
    private val bar = ProgressBar(ui.context, null, android.R.attr.progressBarStyleHorizontal).apply {
      max = 1000
      progressTintList = ColorStateList.valueOf(p.accent)
      indeterminateTintList = ColorStateList.valueOf(p.accent)
      progressBackgroundTintList = ColorStateList.valueOf(p.borderStrong)
    }
    private val bytes = ui.tabular(ui.text("", TextStyle.CAPTION, p.textSecondary))
    private val cancelButton = ui.button("Cancelar", ButtonStyle.TEXT, R.drawable.ic_x) { cancel() }
    private val verb = label
    val view = ui.column(8).apply {
      background = ui.rounded(p.surfaceSunken, 16f, p.border)
      setPadding(ui.dp(16), ui.dp(14), ui.dp(8), ui.dp(6))
      addView(heading)
      addView(ui.row(12).apply {
        setPadding(0, 0, ui.dp(8), 0)
        addView(name, ui.weighted())
        addView(percent)
      })
      addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(8) })
      addView(ui.row(8).apply {
        addView(bytes, ui.weighted())
        addView(cancelButton)
      })
    }

    fun bind(file: FileProgress?) {
      view.visibility = if (file == null) View.GONE else View.VISIBLE
      if (file == null) return
      val permille = if (file.size > 0) (file.received * 1000 / file.size).toInt().coerceIn(0, 1000) else 0
      name.text = file.name
      bar.isIndeterminate = !file.streaming
      bar.progress = permille
      percent.text = if (file.streaming) "${permille / 10}%" else ""
      bytes.text = if (file.streaming) FileRules.progressLabel(file.received, file.size) else "Preparando ${FileRules.sizeLabel(file.size)}…"
      bar.contentDescription = if (file.streaming) "$verb: ${file.name}, ${permille / 10} por cento" else "$verb: preparando ${file.name}"
      cancelButton.contentDescription = "Cancelar ${file.name}"
    }
  }

  private fun updateSendButtons() {
    val connected = state.status == "Conectado"
    sendDraft.isEnabled = connected && draft.text.isNotBlank()
  }

  override fun render(state: UiState) {
    this.state = state
    val connected = state.status == "Conectado"
    liveChip.set(if (state.preferences.realtime) Tone.SUCCESS else Tone.NEUTRAL, if (state.preferences.realtime) "Em tempo real" else "Manual")

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
    // With real-time sync the text is already copied; the button stays for after a manual copy elsewhere.
    copy.setLabel(if (state.preferences.realtime) "Copiar de novo" else "Copiar no celular")

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
    renderFiles(state)
  }

  private fun renderFiles(state: UiState) {
    val files = state.files
    val connected = state.status == "Conectado"
    val allowed = state.snapshot?.files?.allowed
    val ready = connected && state.filesSupported && allowed == true
    filesSubtitle.text = when {
      connected && state.filesSupported && !state.filesTwoWay -> "Este PC aceita PNG, JPG ou PDF de até 50 MB. Atualize o PC Remote no Windows para qualquer arquivo de até 1 GB, nos dois sentidos."
      else -> "Até 1 GB por arquivo, nos dois sentidos. Ficam em Downloads\\PC Remote no PC e Download/PC Remote no celular."
    }
    // Old PCs never advertise file.transfer, so nothing here sends a request to them.
    val block = when {
      !connected -> R.drawable.ic_unplug to "Conecte-se ao PC para enviar e receber arquivos."
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

    outgoing.bind(files.active)
    incoming.bind(files.incoming)

    val waiting = files.queued.size
    queueRow.visibility = if (waiting == 0) View.GONE else View.VISIBLE
    val count = if (waiting == 1) "1 arquivo" else "$waiting arquivos"
    queueText.text = when {
      files.paused -> "Envio pausado. $count na fila."
      !ready -> "$count aguardando o PC."
      files.active != null -> "Mais $count na fila."
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

    if (files.history != renderedHistory) {
      renderedHistory = files.history
      historyList.removeAllViews()
      files.history.take(5).forEach { historyList.addView(historyRow(it), ui.matchWrap()) }
    }
    historyTitle.visibility = if (files.history.isEmpty()) View.GONE else View.VISIBLE
  }

  /** Direction, name, size and time; a file received on Android 10+ opens with a tap. */
  private fun historyRow(record: FileRecord): View = ui.row(12).apply {
    minimumHeight = ui.dp(52)
    setPadding(0, ui.dp(6), 0, ui.dp(6))
    val openable = record.uri != null
    addView(ui.icon(if (record.toPc) R.drawable.ic_file_up else R.drawable.ic_file_down, if (record.toPc) p.textSecondary else p.accentText, 20))
    addView(ui.column(2).apply {
      addView(ui.text(record.name, TextStyle.BODY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
      addView(ui.text("${if (record.toPc) "Enviado ao PC" else "Recebido do PC"} · ${FileRules.sizeLabel(record.size)} · ${clock.format(Date(record.at))}", TextStyle.CAPTION, p.textSecondary))
    }, ui.weighted())
    if (openable) {
      addView(ui.icon(R.drawable.ic_external_link, p.textTertiary, 18))
      isClickable = true
      background = ui.ripple(null, 12f)
      contentDescription = "Abrir ${record.name}"
      setOnClickListener { host.openFile(record) }
    }
  }
}

internal fun dialogTheme(palette: Palette): Int =
  if (palette.dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
