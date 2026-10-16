package com.pcremote

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
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

internal class ConnectionScreen(private val host: ScreenHost) : Screen {
  private val ui = host.ui
  private val client = host.client
  private val p = ui.palette
  private var state = UiState()
  private var editing = false
  private var latency: Long? = null
  private var renderedFingerprint: String? = null

  // Pairing in progress.
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
  private val pcName = ui.text("", TextStyle.HEADLINE).apply { maxLines = 1 }
  private val pcChip = ui.chip()
  private val latencyValue = ui.tabular(ui.text("—", TextStyle.TITLE))
  private val latencyQuality = ui.text("", TextStyle.CAPTION)
  private val address = ui.text("—", TextStyle.MONO).apply { maxLines = 1 }
  private val groups = GridLayout(ui.context).apply {
    columnCount = 4
    background = ui.rounded(p.surfaceSunken, 12f)
    setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
  }
  private val strip = FingerprintStripView(ui)
  private val disconnect = ui.button("Desconectar", ButtonStyle.OUTLINED, R.drawable.ic_unplug) { client.disconnect() }
  private val reconnect = ui.button("Reconectar", ButtonStyle.FILLED, R.drawable.ic_refresh_cw) { client.reconnect() }
  private val other = ui.button("Outro PC", ButtonStyle.TONAL, R.drawable.ic_scan_qr_code) { host.openPairingSheet() }
  private val addressField = ui.field("Endereço IP")
  private val portField = ui.field("Porta").apply { inputType = InputType.TYPE_CLASS_NUMBER }
  private val chevron = ui.icon(R.drawable.ic_chevron_down, p.textSecondary, 20)
  private val editForm = ui.column(12).apply {
    addView(ui.text("Use se o IP do PC mudou e a busca automática não o encontrou.", TextStyle.CAPTION, p.textSecondary))
    addView(ui.row(8).apply { addView(addressField, ui.weighted()); addView(portField, LinearLayout.LayoutParams(ui.dp(104), LinearLayout.LayoutParams.WRAP_CONTENT)) })
    addView(ui.button("Salvar e reconectar", ButtonStyle.FILLED) {
      val number = portField.text.toString().toIntOrNull()
      if (addressField.text.isBlank() || number == null || number !in 1..65535) { host.notify("Informe um endereço e uma porta válidos.", Tone.DANGER); return@button }
      client.updateAddress(addressField.text.toString().trim(), number) { error ->
        if (error == null) { editing = false; render(state) }
        host.result(error, "Endereço salvo. Reconectando…")
      }
    }, ui.matchWrap())
  }
  private val pcCard = ui.card().apply {
    addView(ui.row(16).apply {
      addView(FrameLayout(context).apply {
        background = ui.rounded(p.accentSoft, 16f)
        addView(ui.icon(R.drawable.ic_monitor, p.accentText, 24), FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
      }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))
      addView(ui.column(6).apply {
        addView(pcName)
        addView(FrameLayout(context).apply { addView(pcChip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)) })
      }, ui.weighted())
    })
    addView(ui.row(8).apply {
      addView(stat("Latência", latencyValue, latencyQuality), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
      addView(stat("Endereço salvo", address, null), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.6f))
    })
    addView(ui.column(8).apply {
      addView(ui.row(8).apply {
        addView(ui.icon(R.drawable.ic_shield_check, p.success, 18))
        addView(ui.text("Identidade conferida a cada conexão", TextStyle.LABEL), ui.weighted())
      })
      addView(groups, ui.matchWrap())
      addView(strip)
      addView(ui.text("As cores e os blocos devem ser iguais aos de Diagnóstico no PC.", TextStyle.CAPTION, p.textTertiary))
    })
    addView(ui.row(8).apply { addView(disconnect, ui.weighted()); addView(reconnect, ui.weighted()); addView(other, ui.weighted()) })
    addView(ui.divider())
    addView(ui.row(10).apply {
      minimumHeight = ui.dp(48)
      isClickable = true
      background = ui.ripple(null, 12f)
      contentDescription = "Corrigir endereço"
      addView(ui.icon(R.drawable.ic_pencil, p.textSecondary, 18))
      addView(ui.text("Corrigir endereço", TextStyle.LABEL), ui.weighted())
      addView(chevron)
      setOnClickListener {
        editing = !editing
        if (editing) client.savedAddress()?.let { (savedHost, savedPort) -> addressField.setText(savedHost); portField.setText(savedPort.toString()) }
        render(state)
      }
    })
    addView(editForm)
    addView(ui.button("Remover este PC", ButtonStyle.DANGER, R.drawable.ic_trash_2) { confirmForget() }, ui.matchWrap())
  }

  // Preferences and updates.
  private val theme = Segmented(ui, listOf(
    Triple(ThemeMode.SYSTEM, "Sistema", R.drawable.ic_monitor),
    Triple(ThemeMode.LIGHT, "Claro", R.drawable.ic_sun),
    Triple(ThemeMode.DARK, "Escuro", R.drawable.ic_moon),
  )) { mode -> host.setTheme(mode) }
  private val haptics = ui.switchRow("Vibração", "Vibra quando o PC confirma um comando.", R.drawable.ic_hand) { on ->
    client.setPreferences(state.preferences.copy(haptics = on))
  }
  private val updateStatus = ui.text("", TextStyle.CAPTION, p.textSecondary)
  private val updateChip = ui.chip()
  private val check = ui.button("Verificar agora", ButtonStyle.OUTLINED, R.drawable.ic_refresh_cw) { host.checkUpdates(manual = true) }
  private val news = ui.button("Ver novidades", ButtonStyle.FILLED, R.drawable.ic_circle_arrow_up) { host.openUpdate() }
  private val versionLabel = ui.text("", TextStyle.CAPTION, p.textSecondary)
  private val prefsCard = ui.card(12).apply {
    addView(ui.cardHeader("Preferências"))
    addView(ui.text("Tema", TextStyle.BODY_STRONG))
    addView(theme, ui.matchWrap())
    addView(haptics)
  }
  private val updatesCard = ui.card(12).apply {
    addView(ui.row(12).apply {
      gravity = Gravity.TOP
      addView(ui.column(2).apply { addView(ui.text("Atualizações", TextStyle.TITLE).apply { accessibilityHeading() }); addView(versionLabel) }, ui.weighted())
      addView(updateChip)
    })
    addView(updateStatus)
    addView(ui.row(8).apply { addView(check, ui.weighted()); addView(news, ui.weighted()) })
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

  private fun stat(label: String, value: View, extra: View?): View = ui.column(2).apply {
    background = ui.rounded(p.surfaceSunken, 12f, p.border)
    setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
    addView(ui.text(label, TextStyle.CAPTION, p.textSecondary))
    addView(value)
    if (extra != null) addView(extra)
  }

  private fun confirmForget() {
    AlertDialog.Builder(host.activity, dialogTheme(p))
      .setTitle("Remover este PC?")
      .setMessage("Para remover o acesso também no PC, revogue este celular em Dispositivos.")
      .setNegativeButton("Cancelar", null)
      .setPositiveButton("Remover") { _, _ -> client.forget() }
      .show()
  }

  fun setLatency(value: Long?) {
    latency = value
    render(state)
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
      val ms = if (connected) latency else null
      latencyValue.text = if (ms == null) "—" else "$ms ms"
      latencyQuality.text = when {
        ms == null -> ""
        ms < 40 -> "Excelente"
        ms < 120 -> "Boa"
        else -> "Lenta"
      }
      latencyQuality.setTextColor(if (ms != null && ms >= 120) p.warning else if (ms != null && ms < 40) p.success else p.textSecondary)
      address.text = client.savedAddress()?.let { (h, port) -> "$h:$port" } ?: "—"
      val fingerprint = client.savedFingerprint().orEmpty()
      if (fingerprint != renderedFingerprint) {
        renderedFingerprint = fingerprint
        groups.removeAllViews()
        val values = Fingerprint.groups(fingerprint).takeIf { it.size == 16 } ?: List(16) { "····" }
        values.forEach { group ->
          groups.addView(ui.text(group, TextStyle.MONO, if (fingerprint.isEmpty()) p.textTertiary else p.text).apply { setPadding(0, ui.dp(2), ui.dp(12), ui.dp(2)) })
        }
        strip.setFingerprint(fingerprint)
        groups.contentDescription = "SHA-256 ${values.joinToString(" ")}"
      }
      disconnect.visibility = if (connected) View.VISIBLE else View.GONE
      reconnect.visibility = if (connected) View.GONE else View.VISIBLE
      reconnect.isEnabled = !waiting
      editForm.visibility = if (editing) View.VISIBLE else View.GONE
      chevron.setImageDrawable(ui.drawable(if (editing) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down, p.textSecondary, 20))
    }

    theme.select(host.themeMode)
    haptics.bind(state.preferences.haptics)

    val update = host.update
    versionLabel.text = "Versão ${update.installed ?: "desconhecida"}"
    updateChip.visibility = if (update.available) View.VISIBLE else View.GONE
    update.release?.let { if (update.available) updateChip.set(Tone.ACCENT, "${it.version} disponível") }
    val lookup = update.lookup
    updateStatus.text = when {
      update.checking -> "Verificando…"
      lookup == null -> "A verificação roda ao abrir o app e a cada 6 horas."
      lookup is ReleaseLookup.Unavailable -> lookup.message
      update.available -> "Há uma versão nova."
      lookup is ReleaseLookup.None -> "Nenhuma versão publicada."
      else -> "Você está na versão mais recente."
    }
    updateStatus.setTextColor(if (lookup is ReleaseLookup.Unavailable) p.warning else p.textSecondary)
    check.setBusy(update.checking, "Verificando…")
    news.visibility = if (update.available) View.VISIBLE else View.GONE
  }
}

/** Release notes with a direct APK download through the browser. */
internal object UpdateSheet {
  private val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

  fun show(host: ScreenHost, release: Release, installed: AppVersion?) {
    val ui = host.ui
    val p = ui.palette
    val published = release.publishedAt?.let { runCatching { parser.parse(it) }.getOrNull() }
      ?.let { DateFormat.getDateInstance(DateFormat.LONG, Locale.forLanguageTag("pt-BR")).format(it) }
    val newer = Releases.isNewer(release, installed)
    val sheet = Sheet(host.activity, ui, if (newer) "PC Remote ${release.version}" else "Notas da versão ${release.version}",
      listOfNotNull(published?.let { "Publicada em $it." }, installed?.let { "Você usa a $it." }).joinToString(" "))
    sheet.body.addView(ui.text(ReleaseNotes.render(release.notes.ifBlank { "Esta versão não tem notas publicadas." }, p.textTertiary, ui.dp(10)), TextStyle.BODY).apply {
      setLineSpacing(ui.dp(2f), 1.2f)
    })
    if (newer) sheet.body.addView(ui.text("Na primeira vez, o Android pede para autorizar a instalação pelo navegador. Pareamentos e preferências são mantidos.", TextStyle.CAPTION, p.textSecondary).apply {
      background = ui.rounded(p.surfaceSunken, 12f)
      setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
    })
    if (newer) {
      sheet.footer.addView(ui.button("Agora não", ButtonStyle.TEXT) { host.skipUpdate(release.version.toString()); sheet.dismiss() })
      val apk = release.apk
      val label = if (apk == null) "Abrir download" else "Baixar APK${if (apk.size > 0) " (${String.format(Locale.forLanguageTag("pt-BR"), "%.1f", apk.size / 1_048_576.0)} MB)" else ""}"
      sheet.footer.addView(ui.button(label, ButtonStyle.FILLED, R.drawable.ic_download) {
        open(host, apk?.url ?: release.pageUrl)
        sheet.dismiss()
      }, ui.weighted())
    } else {
      sheet.footer.addView(ui.button("Ver no GitHub", ButtonStyle.OUTLINED, R.drawable.ic_external_link) { open(host, release.pageUrl) }, ui.weighted())
    }
    sheet.show()
  }

  private fun open(host: ScreenHost, url: String) {
    if (!url.startsWith("https://github.com/")) return
    try {
      host.activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
      host.notify("Download aberto no navegador. Toque no APK baixado para instalar.", Tone.ACCENT)
    } catch (_: Exception) {
      host.notify("Nenhum navegador disponível para baixar a atualização.", Tone.DANGER)
    }
  }
}
