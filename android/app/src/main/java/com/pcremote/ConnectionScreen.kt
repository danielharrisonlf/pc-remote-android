package com.pcremote

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Status values come from RemoteClient. */
internal fun statusTone(status: String): Pair<Tone, String> = when (status) {
  "Conectado" -> Tone.SUCCESS to "Conectado"
  "Conectando" -> Tone.WARNING to "Conectando"
  "Reconectando" -> Tone.WARNING to "Reconectando"
  "Pareando" -> Tone.ACCENT to "Pareando"
  "Aguardando aprovação" -> Tone.ACCENT to "Aguardando o PC"
  else -> Tone.NEUTRAL to "Desconectado"
}

internal fun pairingInProgress(state: UiState) = state.status == "Pareando" || state.status == "Aguardando aprovação"

/** Code, PC found on the network, and the QR as an alternative. No network fields. */
internal class PairingPanel(private val host: ScreenHost, private val onRequested: () -> Unit) {
  private val ui = host.ui
  private val p = ui.palette
  private var state = UiState()
  private var selected: String? = null
  private var rendered = emptyList<DiscoveredComputer>()

  private val code = ui.field("Código").apply {
    inputType = InputType.TYPE_CLASS_NUMBER
    filters = arrayOf(InputFilter.LengthFilter(8))
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
    letterSpacing = 0.15f
    gravity = Gravity.CENTER
    typeface = Typeface.MONOSPACE
    addTextChangedListener(SimpleWatcher { updateButton() })
  }
  private val searching = ui.row(10).apply {
    addView(ProgressBar(ui.context).apply { indeterminateTintList = ColorStateList.valueOf(p.textTertiary) }, LinearLayout.LayoutParams(ui.dp(18), ui.dp(18)))
    addView(ui.text("Procurando PCs nesta rede…", TextStyle.CAPTION, p.textSecondary))
  }
  private val choices = RadioGroup(ui.context)
  private val pair = ui.button("Parear", ButtonStyle.FILLED, R.drawable.ic_link, large = true) {
    val pc = chosen() ?: return@button
    host.client.pairWithCode(pc, code.text.toString())
    onRequested()
  }

  val view: LinearLayout = ui.column(14).apply {
    addView(ui.text("No PC, clique em Parear celular e digite aqui o código.", TextStyle.BODY, p.textSecondary))
    addView(code, ui.matchWrap())
    addView(searching)
    addView(choices, ui.matchWrap())
    addView(pair, ui.matchWrap())
    addView(ui.button("Ler QR Code", ButtonStyle.OUTLINED, R.drawable.ic_scan_qr_code) { host.scanQr() }, ui.matchWrap())
  }

  private fun key(pc: DiscoveredComputer) = pc.id.ifEmpty { "${pc.host}:${pc.port}" }
  private fun chosen() = state.discovered.firstOrNull { key(it) == selected }

  private fun updateButton() {
    pair.isEnabled = code.text.count(Char::isDigit) == 8 && chosen() != null && !pairingInProgress(state)
  }

  fun render(state: UiState) {
    this.state = state
    val pcs = state.discovered
    if (pcs != rendered) {
      rendered = pcs
      if (pcs.none { key(it) == selected }) selected = pcs.singleOrNull()?.let(::key)
      choices.setOnCheckedChangeListener(null)
      choices.removeAllViews()
      pcs.forEach { pc ->
        choices.addView(RadioButton(ui.context).apply {
          id = View.generateViewId()
          tag = key(pc)
          text = pc.name
          setTextColor(p.text)
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
          minHeight = ui.dp(52)
          isHapticFeedbackEnabled = false
          buttonTintList = ColorStateList.valueOf(p.accent)
          isChecked = key(pc) == selected
        })
      }
      choices.setOnCheckedChangeListener { group, id ->
        if (id != View.NO_ID) selected = group.findViewById<RadioButton>(id)?.tag as? String
        updateButton()
      }
    }
    searching.visibility = if (pcs.isEmpty()) View.VISIBLE else View.GONE
    updateButton()
  }
}

/**
 * The paired PC in plain terms (name, state, system) and the few things to do with
 * it. Identity checks still run on every connection; they just are not shown.
 */
internal class ConnectionScreen(private val host: ScreenHost) : Screen {
  private val ui = host.ui
  private val client = host.client
  private val p = ui.palette
  private var state = UiState()

  // Pairing in progress: the colors are the one identity check the user makes.
  private val waitingTitle = ui.text("", TextStyle.TITLE)
  private val waitingStrip = FingerprintStripView(ui)
  private val waitingHint = ui.text("As cores devem ser iguais às do PC.", TextStyle.CAPTION, p.textSecondary)
  private val waitingCard = ui.card(12, attention = true).apply {
    addView(ui.row(14).apply {
      addView(ProgressBar(ui.context).apply { indeterminateTintList = ColorStateList.valueOf(p.warning) }, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)))
      addView(waitingTitle, ui.weighted())
    })
    addView(waitingStrip)
    addView(waitingHint)
    addView(ui.button("Cancelar", ButtonStyle.OUTLINED) { client.disconnect() }, ui.matchWrap())
  }

  // Not paired yet.
  private val pairing = PairingPanel(host) {}
  private val pairCard = ui.card().apply {
    addView(ui.cardHeader("Conectar a um computador", "O celular e o PC precisam estar na mesma rede Wi-Fi."))
    addView(pairing.view)
  }

  // Paired PC.
  private val pcName = ui.text("", TextStyle.HEADLINE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
  private val pcSystem = ui.text("", TextStyle.CAPTION, p.textSecondary).apply { maxLines = 1 }
  private val pcChip = ui.chip()
  private val disconnect = ui.button("Desconectar", ButtonStyle.OUTLINED, R.drawable.ic_unplug) { client.disconnect() }
  private val reconnect = ui.button("Reconectar", ButtonStyle.FILLED, R.drawable.ic_refresh_cw) { client.reconnect() }
  private val other = ui.button("Outro PC", ButtonStyle.TONAL, R.drawable.ic_scan_qr_code) { host.openPairingSheet() }
  private val pcCard = ui.card().apply {
    addView(ui.row(16).apply {
      addView(FrameLayout(context).apply {
        background = ui.rounded(p.accentSoft, 16f)
        addView(ui.icon(R.drawable.ic_monitor, p.accentText, 24), FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
      }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))
      addView(ui.column(6).apply {
        addView(pcName)
        addView(ui.row(8).apply { addView(pcChip); addView(pcSystem, ui.weighted()) })
      }, ui.weighted())
    })
    addView(ui.row(8).apply { addView(disconnect, ui.weighted()); addView(reconnect, ui.weighted()); addView(other, ui.weighted()) })
    addView(ui.button("Remover este PC", ButtonStyle.DANGER, R.drawable.ic_trash_2) { confirmForget() }, ui.matchWrap())
  }

  // Preferences and updates.
  private val realtime = ui.switchRow("Sincronização em tempo real", "Copie no PC ou no celular e cole no outro. Desligada, enviar texto e arquivos continua manual.", R.drawable.ic_zap) { on ->
    client.setPreferences(state.preferences.copy(realtime = on))
  }
  private val theme = Segmented(ui, listOf(
    Triple(ThemeMode.SYSTEM, "Sistema", R.drawable.ic_monitor),
    Triple(ThemeMode.LIGHT, "Claro", R.drawable.ic_sun),
    Triple(ThemeMode.DARK, "Escuro", R.drawable.ic_moon),
  )) { mode -> host.setTheme(mode) }
  private val updateStatus = ui.text("", TextStyle.CAPTION, p.textSecondary)
  private val updateChip = ui.chip()
  private val check = ui.button("Verificar agora", ButtonStyle.OUTLINED, R.drawable.ic_refresh_cw) { host.checkUpdates(manual = true) }
  private val news = ui.button("Ver novidades", ButtonStyle.FILLED, R.drawable.ic_circle_arrow_up) { host.openUpdate() }
  private val autoDownload = ui.switchRow("Baixar atualizações automaticamente", "Só em Wi-Fi ou rede sem limite de dados. A instalação sempre espera o seu toque.", R.drawable.ic_download) { on ->
    host.setAutoDownload(on)
  }
  private val versionLabel = ui.text("", TextStyle.CAPTION, p.textSecondary)
  private val prefsCard = ui.card(12).apply {
    addView(ui.cardHeader("Preferências"))
    addView(realtime)
    addView(ui.divider())
    addView(ui.text("Tema", TextStyle.BODY_STRONG).apply { setPadding(0, ui.dp(4), 0, 0) })
    addView(theme, ui.matchWrap())
  }
  private val updatesCard = ui.card(12).apply {
    addView(ui.row(12).apply {
      gravity = Gravity.TOP
      addView(ui.column(2).apply { addView(ui.text("Atualizações", TextStyle.TITLE).apply { accessibilityHeading() }); addView(versionLabel) }, ui.weighted())
      addView(updateChip)
    })
    addView(updateStatus)
    addView(ui.row(8).apply { addView(check, ui.weighted()); addView(news, ui.weighted()) })
    addView(autoDownload)
  }

  override val view: View = ScrollView(ui.context).apply {
    isFillViewport = true
    addView(ui.column(16).apply {
      setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(32))
      addView(waitingCard)
      addView(pairCard)
      addView(pcCard)
      addView(prefsCard)
      addView(updatesCard)
    })
  }

  private fun confirmForget() {
    AlertDialog.Builder(host.activity, dialogTheme(p))
      .setTitle("Remover este PC?")
      .setMessage("Para remover o acesso também no PC, revogue este celular em Dispositivos.")
      .setNegativeButton("Cancelar", null)
      .setPositiveButton("Remover") { _, _ -> client.forget() }
      .show()
  }

  override fun render(state: UiState) {
    this.state = state
    val paired = client.savedAddress() != null
    val waiting = pairingInProgress(state)
    val connected = state.status == "Conectado"

    waitingCard.visibility = if (waiting) View.VISIBLE else View.GONE
    waitingTitle.text = if (state.status == "Aguardando aprovação") "Confirme no PC" else "Conectando…"
    waitingStrip.setFingerprint(state.pairingFingerprint)
    val showColors = waiting && state.pairingFingerprint.isNotEmpty()
    waitingStrip.visibility = if (showColors) View.VISIBLE else View.GONE
    waitingHint.visibility = waitingStrip.visibility

    pairCard.visibility = if (!paired) View.VISIBLE else View.GONE
    pairing.render(state)

    pcCard.visibility = if (paired) View.VISIBLE else View.GONE
    if (paired) {
      pcName.text = state.pcName.ifBlank { "PC pareado" }
      val (tone, label) = statusTone(state.status)
      pcChip.set(tone, label)
      pcSystem.text = state.pcOs
      pcSystem.visibility = if (state.pcOs.isEmpty()) View.GONE else View.VISIBLE
      disconnect.visibility = if (connected) View.VISIBLE else View.GONE
      reconnect.visibility = if (connected) View.GONE else View.VISIBLE
      reconnect.isEnabled = !waiting
    }

    realtime.bind(state.preferences.realtime)
    theme.select(host.themeMode)

    val update = host.update
    versionLabel.text = "Versão ${update.installed ?: "desconhecida"}"
    updateChip.visibility = if (update.available) View.VISIBLE else View.GONE
    update.release?.let { if (update.available) updateChip.set(Tone.ACCENT, "${it.version} disponível") }
    val lookup = update.lookup
    val progress = if (update.available) UpdateLabels.status(update) else null
    updateStatus.text = when {
      update.checking -> "Verificando…"
      progress != null -> progress
      lookup == null -> "A verificação roda ao abrir o app e a cada 6 horas."
      lookup is ReleaseLookup.Unavailable -> lookup.message
      update.available -> "Há uma versão nova."
      lookup is ReleaseLookup.None -> "Nenhuma versão publicada."
      else -> "Você está na versão mais recente."
    }
    val failed = lookup is ReleaseLookup.Unavailable || (progress != null && update.flow is UpdateFlow.Failed)
    updateStatus.setTextColor(if (failed && !update.checking) p.warning else p.textSecondary)
    check.setBusy(update.checking, "Verificando…")
    news.visibility = if (update.available) View.VISIBLE else View.GONE
    autoDownload.bind(host.autoDownload)
  }
}

/**
 * Release notes and the in-app update: download, SHA-256 and signature check,
 * then the system installer. "Ver no GitHub" stays as the fallback.
 */
internal class UpdateSheet(private val host: ScreenHost, private val release: Release) {
  private val ui = host.ui
  private val p = ui.palette
  private val sheet: Sheet
  private val bar = ProgressBar(ui.context, null, android.R.attr.progressBarStyleHorizontal).apply {
    max = 1000
    progressTintList = ColorStateList.valueOf(p.accent)
    progressBackgroundTintList = ColorStateList.valueOf(p.border)
    indeterminateTintList = ColorStateList.valueOf(p.accent)
  }
  private val barText = ui.text("", TextStyle.CAPTION, p.textSecondary)
  private val progress = ui.column(8).apply {
    addView(bar, ui.matchWrap())
    addView(barText)
    visibility = View.GONE
  }
  private var action: PillButton? = null

  val dialog get() = sheet.dialog

  init {
    val update = host.update
    val installed = update.installed
    val published = release.publishedAt?.let { runCatching { parser.parse(it) }.getOrNull() }
      ?.let { DateFormat.getDateInstance(DateFormat.LONG, Locale.forLanguageTag("pt-BR")).format(it) }
    val newer = Releases.isNewer(release, installed)
    sheet = Sheet(host.activity, ui, if (newer) "PC Remote ${release.version}" else "Notas da versão ${release.version}",
      listOfNotNull(published?.let { "Publicada em $it." }, installed?.let { "Você usa a $it." }).joinToString(" "))
    sheet.body.addView(ui.text(ReleaseNotes.render(release.notes.ifBlank { "Esta versão não tem notas publicadas." }, p.textTertiary, ui.dp(10)), TextStyle.BODY).apply {
      setLineSpacing(ui.dp(2f), 1.2f)
    })
    val github = { host.openReleasePage(release.pageUrl) }
    if (newer && update.inApp) {
      val later = if (Build.VERSION.SDK_INT >= 31) " Depois disso, as próximas atualizações instalam sem nova confirmação." else ""
      sheet.body.addView(ui.text("O app baixa o APK do GitHub, confere o SHA-256 publicado e a assinatura e pede ao Android para instalar. " +
        "Na primeira vez, autorize o PC Remote a instalar apps.$later O app fecha durante a instalação; pareamentos e preferências são mantidos.", TextStyle.CAPTION, p.textSecondary).apply {
        background = ui.rounded(p.surfaceSunken, 12f)
        setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
      })
      sheet.body.addView(progress, ui.matchWrap())
      sheet.body.addView(ui.button("Ver no GitHub", ButtonStyle.TEXT, R.drawable.ic_external_link) { github() })
      sheet.footer.addView(ui.button("Agora não", ButtonStyle.TEXT) { host.skipUpdate(release.version.toString()); sheet.dismiss() })
      val button = ui.button("Atualizar agora", ButtonStyle.FILLED, R.drawable.ic_download) { host.startUpdate() }
      action = button
      sheet.footer.addView(button, ui.weighted())
    } else if (newer) {
      sheet.body.addView(ui.text("Esta versão não publica o SHA-256 do APK, então o app não a instala sozinho. Baixe pelo GitHub.", TextStyle.CAPTION, p.textSecondary).apply {
        background = ui.rounded(p.surfaceSunken, 12f)
        setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
      })
      sheet.footer.addView(ui.button("Agora não", ButtonStyle.TEXT) { host.skipUpdate(release.version.toString()); sheet.dismiss() })
      sheet.footer.addView(ui.button("Ver no GitHub", ButtonStyle.FILLED, R.drawable.ic_external_link) { github() }, ui.weighted())
    } else {
      sheet.footer.addView(ui.button("Ver no GitHub", ButtonStyle.OUTLINED, R.drawable.ic_external_link) { github() }, ui.weighted())
    }
    render(update)
  }

  fun show() = sheet.show()
  fun dismiss() = sheet.dismiss()
  val showing: Boolean get() = sheet.dialog.isShowing

  /** The Dialog covers the snackbar, so one-off messages show here until the next change. */
  fun notice(text: String, danger: Boolean) {
    progress.visibility = View.VISIBLE
    barText.text = text
    barText.setTextColor(if (danger) p.warning else p.textSecondary)
  }

  fun render(update: UpdateState) {
    val button = action ?: return
    val (label, busy) = UpdateLabels.action(update)
    button.setLabel(label)
    button.setBusy(busy, label)
    button.setIcon(if (update.ready) R.drawable.ic_circle_check else R.drawable.ic_download)
    val text = UpdateLabels.progress(update)
    val flow = update.flow.takeIf { it.version == release.version } ?: UpdateFlow.Idle
    progress.visibility = if (text == null) View.GONE else View.VISIBLE
    barText.text = text.orEmpty()
    barText.setTextColor(if (flow is UpdateFlow.Failed) p.warning else p.textSecondary)
    val moving = flow is UpdateFlow.Downloading || flow is UpdateFlow.Verifying || (flow is UpdateFlow.Installing && !flow.confirming)
    bar.visibility = if (moving) View.VISIBLE else View.GONE
    bar.isIndeterminate = flow !is UpdateFlow.Downloading || flow.total <= 0
    if (flow is UpdateFlow.Downloading && flow.total > 0) bar.progress = (flow.received * 1000 / flow.total).toInt().coerceIn(0, 1000)
  }

  private companion object {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
  }
}
