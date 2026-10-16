package com.pcremote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.text.TextWatcher
import android.text.Editable
import kotlin.math.roundToInt

/** The Activity owns Views only. All connection and command state lives in RemoteClient. */
class MainActivity : Activity() {
    private val client: RemoteController get() = (application as RemoteApplication).client
    private var unsubscribe: (() -> Unit)? = null
    private var state = UiState()
    private val navigation = PageNavigation { client.disableInput(); volumeInteraction.finish() }
    private var page: Page
        get() = navigation.page
        set(value) { navigation.goTo(value) }
    private var rendering = false
    private var lastSharedText = ""
    private var qrError = ""
    private var selectedSessionIds = emptyList<String>()
    private var sessionLabels = emptyList<String>()
    private var unregisterBack: (() -> Unit)? = null
    private val qrScanner = QrScanner()

    private lateinit var root: LinearLayout
    private lateinit var home: LinearLayout
    private lateinit var pair: LinearLayout
    private lateinit var settings: LinearLayout
    private lateinit var status: TextView
    private lateinit var pcName: TextView
    private lateinit var error: TextView
    private lateinit var volumeValue: TextView
    private lateinit var audioHint: TextView
    private lateinit var volume: SeekBar
    private lateinit var mute: Button
    private lateinit var session: Spinner
    private lateinit var mediaTitle: TextView
    private lateinit var mediaHint: TextView
    private lateinit var play: Button
    private lateinit var pause: Button
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var seekBack: Button
    private lateinit var seekForward: Button
    private lateinit var clipboardDraft: EditText
    private lateinit var received: TextView
    private lateinit var sharedHint: TextView
    private lateinit var sendClipboard: Button
    private lateinit var sendCopiedText: Button
    private lateinit var getClipboard: Button
    private lateinit var copyClipboard: Button
    private lateinit var upload: CheckBox
    private lateinit var download: CheckBox
    private lateinit var paused: CheckBox
    private lateinit var autoApply: CheckBox
    private lateinit var haptics: CheckBox
    private lateinit var inputHint: TextView
    private lateinit var inputArm: Button
    private lateinit var touchPad: TouchPadView
    private lateinit var inputText: EditText
    private lateinit var sendText: Button
    private lateinit var pairCode: EditText
    private lateinit var pairStatus: TextView
    private lateinit var pairError: TextView
    private lateinit var pairButton: Button
    private lateinit var pcChoices: RadioGroup
    private lateinit var pcSearching: TextView
    private lateinit var pairWaiting: LinearLayout
    private lateinit var pairStrip: LinearLayout
    private lateinit var pairStripHint: TextView
    private var selectedPc: String? = null
    private var renderedPcs = emptyList<DiscoveredComputer>()
    private var renderedStrip = ""
    private lateinit var addressHost: EditText
    private lateinit var addressPort: EditText
    private lateinit var reconnect: Button

    private val volumeInteraction by lazy {
        VolumeInteraction(client::beginVolume, client::updateVolume, client::endVolume,
            { task, delay -> root.postDelayed(task, delay); Unit },
            { task -> root.removeCallbacks(task); Unit })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = client.view
        buildUi()
        unsubscribe = client.observe { next -> runOnUiThread {
            val changed = state.forDisplay() != next.forDisplay()
            state = next // Always retain the real revisions for actions and state reconciliation.
            if (next.status != "Conectado" || next.snapshot?.audio?.available != true) volumeInteraction.cancel()
            if (changed) render()
        } }
        client.receiveShare(intent)
        if (state.status == "Desconectado" && client.savedAddress() == null) page = Page.PAIR
        render()
        unregisterBack = BackNavigation.register(this) { navigateBack() }
    }

    override fun onResume() {
        super.onResume()
        client.foreground(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        client.focusChanged(hasFocus)
    }

    override fun onPause() {
        volumeInteraction.finish()
        client.background()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterBack?.invoke()
        unregisterBack = null
        qrScanner.detach()
        unsubscribe?.invoke()
        unsubscribe = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_SEND) lastSharedText = ""
        client.receiveShare(intent)
        navigation.newIntent()
        volumeInteraction.finish()
        render()
    }

    @Deprecated("Handled for devices before Android 13")
    @SuppressLint("GestureBackNavigation") // API 24–32 fallback; API 33+ uses OnBackInvokedCallback above.
    override fun onBackPressed() { navigateBack() }

    private fun navigateBack() {
        if (page != Page.HOME) { page = Page.HOME; client.stopDiscovery(); render() }
        else finish()
    }

    private fun buildUi() {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        Palette.dark = dark
        window.statusBarColor = Palette.background
        window.navigationBarColor = Palette.background
        window.decorView.systemUiVisibility = if (dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            (if (Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        val scroller = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        root = column().apply { setPadding(dp(16), dp(16), dp(16), dp(32)) }
        scroller.addView(root)
        setContentView(scroller)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            scroller.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                val keyboard = insets.getInsets(android.view.WindowInsets.Type.ime())
                root.setPadding(dp(16) + bars.left, dp(16) + bars.top,
                    dp(16) + bars.right, dp(32) + maxOf(bars.bottom, keyboard.bottom))
                insets
            }
            scroller.requestApplyInsets()
        }
        home = column(); pair = column(); settings = column()
        root.addView(home); root.addView(pair); root.addView(settings)
        buildHome(); buildPair(); buildSettings()
    }

    private fun buildHome() {
        home.addView(row().apply {
            addView(label("PC Remote", 26f, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("Ajustes") { loadSavedAddress(); page = Page.SETTINGS; render() })
        })
        home.addView(card().apply {
            pcName = label("Nenhum PC", 20f, true); addView(pcName)
            status = label("Desconectado", 15f); addView(status)
            error = label("", 14f).apply { setTextColor(Palette.error) }; addView(error)
            reconnect = button("Reconectar") { client.reconnect() }; addView(reconnect)
            addView(button("Parear outro PC") { page = Page.PAIR; render(); client.startDiscovery() })
        })

        home.addView(section("Volume"))
        home.addView(card().apply {
            volumeValue = label("—", 26f, true); addView(volumeValue)
            audioHint = label("", 13f); addView(audioHint)
            volume = SeekBar(this@MainActivity).apply {
                max = 100
                minimumHeight = dp(52)
                contentDescription = "Volume do PC, de zero a cem por cento"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(seekBar: SeekBar) { volumeInteraction.startTouch(seekBar.progress) }
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) { volumeValue.text = "$progress%"; volumeInteraction.change(progress) }
                    }
                    override fun onStopTrackingTouch(seekBar: SeekBar) { volumeInteraction.stopTouch(seekBar.progress) }
                })
            }
            addView(volume)
            addView(row().apply {
                addView(button("−5") { command(RemoteAction.VolumeStep(-5)) }.apply { tag = "audio" }, weighted())
                mute = button("Mudo") { command(RemoteAction.VolumeMute(state.snapshot?.audio?.muted != true)) }
                addView(mute, weighted())
                addView(button("+5") { command(RemoteAction.VolumeStep(5)) }.apply { tag = "audio" }, weighted())
            })
        })

        home.addView(section("Mídia"))
        home.addView(card().apply {
            session = Spinner(this@MainActivity).apply {
                minimumHeight = dp(52)
                contentDescription = "Sessão de mídia"
                onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                        if (!rendering) selectedSessionIds.getOrNull(position)?.let { client.selectSession(it) }
                    }
                }
            }; addView(session)
            mediaTitle = label("Nenhuma sessão", 18f, true); addView(mediaTitle)
            mediaHint = label("", 14f); addView(mediaHint)
            addView(row().apply {
                play = button("Reproduzir") { media("play") }; addView(play, weighted())
                pause = button("Pausar") { media("pause") }; addView(pause, weighted())
            })
            addView(row().apply {
                previous = button("Faixa anterior") { media("previous") }; addView(previous, weighted())
                next = button("Próxima") { media("next") }; addView(next, weighted())
            })
            addView(row().apply {
                seekBack = button("−10 s") { media("seek", -10) }; addView(seekBack, weighted())
                seekForward = button("+10 s") { media("seek", 10) }; addView(seekForward, weighted())
            })
        })

        home.addView(section("Área de transferência"))
        home.addView(card().apply {
            clipboardDraft = edit("Texto para enviar ao PC", true).apply {
                minLines = 3; maxLines = 6
                addTextChangedListener(simpleWatcher { if (::sendClipboard.isInitialized) sendClipboard.isEnabled = state.status == "Conectado" && text.isNotBlank() })
            }
            addView(clipboardDraft)
            sharedHint = label("", 13f); addView(sharedHint)
            sendClipboard = button("Enviar ao PC") {
                client.sendClipboard(clipboardDraft.text.toString()) { result -> finishCommand(result, "Texto enviado") }
            }; addView(sendClipboard)
            sendCopiedText = button("Enviar texto copiado") {
                client.sendClipboard(null) { result -> finishCommand(result, "Texto enviado") }
            }; addView(sendCopiedText)
            getClipboard = button("Buscar do PC") {
                client.getClipboard { result -> finishCommand(result, null) }
            }; addView(getClipboard)
            received = label("Nada recebido ainda", 15f).apply {
                setTextIsSelectable(true); minHeight = dp(52); contentDescription = "Texto recebido do PC"
            }; addView(received)
            copyClipboard = button("Copiar no celular") {
                client.copyReceived { result -> finishCommand(result, "Copiado") }
            }; addView(copyClipboard)
        })

        home.addView(section("Sincronização"))
        home.addView(card().apply {
            upload = check("Celular → PC", "Envia o que você copiar no celular") { p -> client.setPreferences(state.preferences.copy(upload = p)) }
            addView(upload)
            download = check("PC → celular", "Recebe o que você copiar no PC") { p ->
                client.setPreferences(state.preferences.copy(download = p))
                if (p) requestNotificationPermission()
            }; addView(download)
            paused = check("Pausar sincronização", "Pausa os dois sentidos") { p -> client.setPreferences(state.preferences.copy(paused = p)) }
            addView(paused)
        })

        home.addView(section("Mouse e teclado"))
        home.addView(card().apply {
            inputHint = label("", 14f); addView(inputHint)
            inputArm = button("Ativar controle") {
                if (state.inputArmed) client.disableInput()
                else client.enableInput { result -> finishCommand(result, null) }
            }; addView(inputArm)
            touchPad = TouchPadView(this@MainActivity, client) { result -> finishCommand(result, null) }.apply {
                minimumHeight = dp(190)
                contentDescription = "Touchpad. Deslize para mover, toque para clicar"
            }; addView(touchPad, LinearLayout.LayoutParams(-1, dp(190)).apply { topMargin = dp(8); bottomMargin = dp(8) })
            addView(row().apply {
                addView(inputButton("Clique esquerdo", RemoteAction.PointerClick("left", 1)), weighted())
                addView(inputButton("Clique direito", RemoteAction.PointerClick("right", 1)), weighted())
                addView(inputButton("Duplo clique", RemoteAction.PointerClick("left", 2)), weighted())
            })
            addView(row().apply {
                addView(inputButton("Rolar ↑", RemoteAction.PointerScroll(3)), weighted())
                addView(inputButton("Rolar ↓", RemoteAction.PointerScroll(-3)), weighted())
            })
            addView(label("O texto vai para a janela em foco no PC.", 13f).apply { setTextColor(Palette.muted) })
            inputText = edit("Texto para digitar no PC", false).apply {
                isSingleLine = true; maxLines = 1
                addTextChangedListener(simpleWatcher { if (::sendText.isInitialized) sendText.isEnabled = state.inputArmed && text.isNotBlank() })
            }
            addView(inputText)
            sendText = button("Digitar") {
                val value = inputText.text.toString()
                if (!client.isInputTextAllowed(value)) { toast("Texto inválido ou acima de 1024 bytes"); return@button }
                client.execute(RemoteAction.KeyboardText(value)) { result -> finishCommand(result, null) }
            }; addView(sendText)
            addView(row().apply {
                listOf("Enter" to "enter", "Apagar" to "backspace", "Tab" to "tab", "Esc" to "escape").forEach { (name, key) ->
                    addView(inputButton(name, RemoteAction.KeyboardKey(key)), weighted())
                }
            })
            addView(row().apply {
                listOf("←" to "left", "↑" to "up", "↓" to "down", "→" to "right").forEach { (name, key) ->
                    addView(inputButton(name, RemoteAction.KeyboardKey(key)), weighted())
                }
            })
            addView(row().apply {
                listOf("Del" to "delete", "Home" to "home", "End" to "end").forEach { (name, key) ->
                    addView(inputButton(name, RemoteAction.KeyboardKey(key)), weighted())
                }
            })
        })
    }

    private fun buildPair() {
        pair.addView(row().apply {
            addView(label("Parear PC", 26f, true), weighted())
            addView(button("Voltar") { navigateBack() })
        })
        pair.addView(label("No PC, clique em Parear celular e digite aqui o código.", 15f).apply { setTextColor(Palette.muted) })
        pairError = label("", 14f).apply { setTextColor(Palette.error) }; pair.addView(pairError)
        pair.addView(card().apply {
            pairCode = edit("Código", false).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(android.text.InputFilter.LengthFilter(8))
                textSize = 28f; letterSpacing = 0.15f; gravity = Gravity.CENTER
                addTextChangedListener(simpleWatcher { updatePairButton() })
            }; addView(pairCode)
            pcSearching = label("Procurando PCs na rede…", 14f).apply { setTextColor(Palette.muted) }; addView(pcSearching)
            pcChoices = RadioGroup(this@MainActivity).apply {
                setOnCheckedChangeListener { group, id ->
                    if (id != View.NO_ID) selectedPc = group.findViewById<RadioButton>(id)?.tag as? String
                    updatePairButton()
                }
            }; addView(pcChoices)
            pairButton = button("Parear") {
                val pc = chosenPc() ?: return@button
                qrError = ""
                client.pairWithCode(pc, pairCode.text.toString())
                hideKeyboard()
            }; addView(pairButton)
        })
        pairWaiting = card().apply {
            pairStatus = label("", 18f, true); addView(pairStatus)
            pairStrip = row(); addView(pairStrip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            pairStripHint = label("As cores devem ser iguais às do PC.", 14f).apply { setTextColor(Palette.muted) }; addView(pairStripHint)
        }; pair.addView(pairWaiting)
        pair.addView(button("Ler QR Code") {
            qrError = ""
            qrScanner.start(this@MainActivity) { invite, problem ->
                if (problem != null) {
                    qrError = problem
                    render()
                    toast(problem)
                }
                if (invite != null) {
                    qrError = ""
                    client.pair(invite.host, invite.port, invite.fingerprint, invite.code)
                    render()
                }
            }
        })
    }

    private fun pcKey(pc: DiscoveredComputer) = pc.id.ifEmpty { "${pc.host}:${pc.port}" }
    private fun chosenPc() = state.discovered.firstOrNull { pcKey(it) == selectedPc }
    private fun pairingInProgress() = state.status == "Pareando" || state.status == "Aguardando aprovação"

    private fun updatePairButton() {
        if (!::pairButton.isInitialized) return
        pairButton.isEnabled = pairCode.text.count(Char::isDigit) == 8 && chosenPc() != null && !pairingInProgress()
    }

    private fun renderPair() {
        val pcs = state.discovered
        if (pcs != renderedPcs) {
            renderedPcs = pcs
            if (pcs.none { pcKey(it) == selectedPc }) selectedPc = pcs.singleOrNull()?.let(::pcKey)
            pcChoices.setOnCheckedChangeListener(null)
            pcChoices.removeAllViews()
            pcs.forEach { pc ->
                pcChoices.addView(RadioButton(this).apply {
                    id = View.generateViewId(); tag = pcKey(pc); text = pc.name
                    textSize = 16f; minHeight = dp(52); setTextColor(Palette.text)
                    isChecked = pcKey(pc) == selectedPc
                })
            }
            pcChoices.setOnCheckedChangeListener { group, id ->
                if (id != View.NO_ID) selectedPc = group.findViewById<RadioButton>(id)?.tag as? String
                updatePairButton()
            }
        }
        pcSearching.visibility = if (pcs.isEmpty()) View.VISIBLE else View.GONE
        val waiting = pairingInProgress()
        pairWaiting.visibility = if (waiting) View.VISIBLE else View.GONE
        pairStatus.text = if (state.status == "Aguardando aprovação") "Confirme no PC" else "Conectando…"
        if (state.pairingFingerprint != renderedStrip) {
            renderedStrip = state.pairingFingerprint
            pairStrip.removeAllViews()
            Fingerprint.swatches(renderedStrip).forEach { swatch ->
                pairStrip.addView(View(this).apply { setBackgroundColor(swatch.argb()) }, LinearLayout.LayoutParams(dp(18), dp(22)))
            }
            pairStrip.contentDescription = "Cores da identidade do PC"
        }
        val showColors = waiting && renderedStrip.isNotEmpty()
        pairStrip.visibility = if (showColors) View.VISIBLE else View.GONE
        pairStripHint.visibility = pairStrip.visibility
        updatePairButton()
    }

    private fun buildSettings() {
        settings.addView(row().apply {
            addView(label("Configurações", 26f, true), weighted())
            addView(button("Voltar") { navigateBack() })
        })
        settings.addView(card().apply {
            autoApply = check("Copiar automaticamente", "Copia no celular o texto recebido do PC") { p ->
                client.setPreferences(state.preferences.copy(autoApply = p))
            }; addView(autoApply)
            haptics = check("Vibração", "Vibra quando o PC confirma um comando") { p ->
                client.setPreferences(state.preferences.copy(haptics = p))
            }; addView(haptics)
        })
        settings.addView(section("Endereço do PC"))
        settings.addView(card().apply {
            addressHost = edit("Endereço do PC", false); addView(addressHost)
            addressPort = edit("Porta", false).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }; addView(addressPort)
            addView(button("Salvar endereço") {
                val port = addressPort.text.toString().toIntOrNull()
                if (addressHost.text.isBlank() || port == null || port !in 1..65535) { toast("Endereço ou porta inválida"); return@button }
                client.updateAddress(addressHost.text.toString().trim(), port) { result -> finishCommand(result, "Endereço salvo") }
            })
        })
        settings.addView(card().apply {
            addView(button("Desconectar") { client.disconnect(); page = Page.HOME; render() })
            addView(button("Esquecer este PC") {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Esquecer este PC?")
                    .setMessage("Para remover o acesso também no PC, revogue este celular em Dispositivos.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Esquecer") { _, _ -> client.forget(); page = Page.PAIR; render() }
                    .show()
            })
        })
    }

    private fun loadSavedAddress() {
        val address = client.savedAddress()
        addressHost.setText(address?.first.orEmpty())
        addressPort.setText(address?.second?.toString().orEmpty())
    }

    private fun render() {
        if (!::root.isInitialized) return
        rendering = true
        home.visibility = if (page == Page.HOME) View.VISIBLE else View.GONE
        pair.visibility = if (page == Page.PAIR) View.VISIBLE else View.GONE
        settings.visibility = if (page == Page.SETTINGS) View.VISIBLE else View.GONE
        val connected = state.status == "Conectado"
        pcName.text = state.pcName.ifBlank { "Nenhum PC" }
        status.text = state.status
        val pairingProblem = qrError.ifBlank { state.error }
        pairError.text = pairingProblem
        pairError.visibility = if (pairingProblem.isBlank()) View.GONE else View.VISIBLE
        error.text = state.error
        error.visibility = if (state.error.isBlank()) View.GONE else View.VISIBLE
        reconnect.isEnabled = !connected && client.savedAddress() != null

        val audio = state.snapshot?.audio
        val audioReady = connected && audio?.available == true
        volume.isEnabled = audioReady
        volumeValue.text = if (audioReady) "${state.draggingVolume ?: audio!!.percent}%" else "—"
        audioHint.text = when {
            !connected -> "Sem conexão"
            audio?.error?.isNotBlank() == true -> audio.error
            !audioReady -> "Áudio indisponível"
            else -> if (audio?.muted == true) "Sem som" else ""
        }
        if (state.draggingVolume == null && !volume.isPressed) volume.progress = audio?.percent ?: 0
        mute.isEnabled = audioReady
        mute.text = if (audio?.muted == true) "Ativar som" else "Mudo"
        setTaggedButtonState(home, "audio", audioReady)

        val sessions = state.snapshot?.sessions.orEmpty()
        val ids = sessions.map { it.id }
        val labels = sessions.map { "${it.app}: ${it.title.ifBlank { "Sem título" }}" }.ifEmpty { listOf("Nenhuma sessão") }
        if (ids != selectedSessionIds || labels != sessionLabels) {
            selectedSessionIds = ids
            sessionLabels = labels
            session.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        }
        val index = sessions.indexOfFirst { it.id == state.selectedSessionId }.let { if (it < 0) 0 else it }
        if (sessions.isNotEmpty() && session.selectedItemPosition != index) session.setSelection(index)
        session.isEnabled = connected && sessions.size > 1
        val chosen = sessions.getOrNull(index)
        mediaTitle.text = chosen?.title?.ifBlank { "Sem título" } ?: "Nenhuma sessão"
        mediaHint.text = if (!connected) "Sem conexão" else chosen?.app ?: "Nada tocando"
        play.isEnabled = connected && chosen?.capabilities?.play == true
        pause.isEnabled = connected && chosen?.capabilities?.pause == true
        previous.isEnabled = connected && chosen?.capabilities?.previous == true
        next.isEnabled = connected && chosen?.capabilities?.next == true
        seekBack.isEnabled = connected && chosen?.capabilities?.seek == true
        seekForward.isEnabled = seekBack.isEnabled

        sendClipboard.isEnabled = connected && clipboardDraft.text.isNotBlank()
        sendCopiedText.isEnabled = connected
        getClipboard.isEnabled = connected
        copyClipboard.isEnabled = state.receivedText.isNotEmpty()
        received.text = state.receivedText.ifEmpty { "Nada recebido ainda" }
        if (state.sharedText.isNotEmpty() && state.sharedText != lastSharedText) {
            clipboardDraft.setText(state.sharedText)
            clipboardDraft.setSelection(clipboardDraft.text.length)
            lastSharedText = state.sharedText
        }
        if (state.sharedText.isEmpty()) lastSharedText = ""
        sharedHint.text = if (state.sharedText.isNotEmpty()) "Texto compartilhado. Revise e envie." else ""
        upload.isChecked = state.preferences.upload
        download.isChecked = state.preferences.download
        paused.isChecked = state.preferences.paused
        autoApply.isChecked = state.preferences.autoApply
        haptics.isChecked = state.preferences.haptics

        val inputAllowed = connected && state.inputSupported && state.snapshot?.input?.allowed == true
        inputHint.text = when {
            !connected -> "Sem conexão"
            !state.inputSupported -> "Atualize o PC Remote no Windows."
            state.snapshot?.input?.allowed != true -> "Libere mouse e teclado no PC, em Transferência."
            state.inputActivating -> "Aguardando o PC…"
            state.inputArmed -> "Ativo"
            else -> ""
        }
        inputArm.text = if (state.inputArmed) "Desativar controle" else if (state.inputActivating) "Ativando…" else "Ativar controle"
        inputArm.isEnabled = inputAllowed && !state.inputActivating
        val armed = inputAllowed && state.inputArmed
        touchPad.isEnabled = armed
        inputText.isEnabled = armed
        sendText.isEnabled = armed && inputText.text.isNotBlank()
        setTaggedButtonState(home, "input", armed)

        renderPair()
        rendering = false
    }

    private fun setTaggedButtonState(view: View, tagValue: String, enabled: Boolean) {
        if (view is ViewGroup) for (i in 0 until view.childCount) setTaggedButtonState(view.getChildAt(i), tagValue, enabled)
        if (view is Button && view.tag == tagValue) view.isEnabled = enabled
    }

    private fun media(command: String, offset: Int? = null) {
        val id = state.selectedSessionId.ifEmpty { state.snapshot?.sessions?.firstOrNull()?.id.orEmpty() }
        if (id.isNotEmpty()) command(RemoteAction.MediaControl(id, command, offset))
    }

    private fun command(action: RemoteAction) {
        client.execute(action) { result -> finishCommand(result, null) }
    }

    /** Vibrates on success; a toast appears only for errors or when [success] says something new. */
    private fun finishCommand(result: String?, success: String?) {
        runOnUiThread {
            if (result == null) {
                if (state.preferences.haptics) root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                success?.let(::toast)
            } else toast(result)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this)
                .setMessage("Permita notificações para saber quando chegar texto do PC.")
                .setNegativeButton("Agora não", null)
                .setPositiveButton("Permitir") { _, _ -> requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101) }
                .show()
        }
    }

    private fun hideKeyboard() { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0) }
    private fun toast(message: String) { Toast.makeText(this, message.take(180), Toast.LENGTH_SHORT).show() }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()
    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Palette.text)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setPadding(dp(2), dp(5), dp(2), dp(5))
    }
    private fun section(title: String) = column().apply {
        setPadding(0, dp(20), 0, dp(5))
        addView(label(title, 21f, true))
    }
    private fun card() = column().apply {
        background = GradientDrawable().apply { setColor(Palette.card); cornerRadius = dp(16).toFloat() }
        setPadding(dp(14), dp(12), dp(14), dp(12))
        homeOrPageMargin(this)
    }
    private fun homeOrPageMargin(view: View) { view.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) } }
    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; textSize = 14f; minHeight = dp(52); minWidth = 0
        setOnClickListener { action() }
    }
    private fun inputButton(value: String, action: RemoteAction) = button(value) { command(action) }.apply { tag = "input" }
    private fun simpleWatcher(action: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) { action() }
    }
    private fun edit(hintValue: String, multiline: Boolean) = EditText(this).apply {
        hint = hintValue; contentDescription = hintValue; textSize = 16f; minHeight = dp(52)
        inputType = if (multiline) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else android.text.InputType.TYPE_CLASS_TEXT
        setTextColor(Palette.text); setHintTextColor(Palette.muted)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Palette.accent)
    }
    private fun check(value: String, description: String, action: (Boolean) -> Unit) = CheckBox(this).apply {
        text = value; textSize = 16f; minHeight = dp(52); contentDescription = "$value. $description"
        setTextColor(Palette.text)
        setOnCheckedChangeListener { _, checked -> if (!rendering) action(checked) }
    }
}

private object Palette {
    var dark = false
    val background get() = if (dark) Color.rgb(16, 20, 28) else Color.rgb(246, 247, 251)
    val card get() = if (dark) Color.rgb(32, 38, 49) else Color.WHITE
    val text get() = if (dark) Color.rgb(241, 244, 248) else Color.rgb(22, 29, 43)
    val muted get() = if (dark) Color.rgb(179, 189, 203) else Color.rgb(82, 93, 111)
    val accent get() = if (dark) Color.rgb(133, 169, 255) else Color.rgb(36, 90, 232)
    val error get() = if (dark) Color.rgb(255, 163, 163) else Color.rgb(172, 32, 47)
}
