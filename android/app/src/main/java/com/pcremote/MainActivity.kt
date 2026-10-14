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
    private var page = Page.HOME
    private var rendering = false
    private var lastSharedText = ""
    private var selectedSessionIds = emptyList<String>()
    private var backCallback: android.window.OnBackInvokedCallback? = null

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
    private lateinit var quickPause: Button
    private lateinit var inputHint: TextView
    private lateinit var inputArm: Button
    private lateinit var touchPad: TouchPadView
    private lateinit var inputText: EditText
    private lateinit var sendText: Button
    private lateinit var pairHost: EditText
    private lateinit var pairPort: EditText
    private lateinit var pairFingerprint: EditText
    private lateinit var pairCode: EditText
    private lateinit var pairStatus: TextView
    private lateinit var pairError: TextView
    private lateinit var discovered: LinearLayout
    private lateinit var addressHost: EditText
    private lateinit var addressPort: EditText
    private lateinit var reconnect: Button

    private enum class Page { HOME, PAIR, SETTINGS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = client.view
        buildUi()
        unsubscribe = client.observe { next -> runOnUiThread { state = next; render() } }
        client.receiveShare(intent)
        if (state.status == "Desconectado" && client.savedAddress() == null) page = Page.PAIR
        render()
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = android.window.OnBackInvokedCallback { runOnUiThread { navigateBack() } }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        }
    }

    override fun onResume() {
        super.onResume()
        client.foreground(this)
    }

    override fun onPause() {
        client.background()
        super.onPause()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        unsubscribe?.invoke()
        unsubscribe = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_SEND) lastSharedText = ""
        client.receiveShare(intent)
        page = Page.HOME
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
            addView(button("Config.") { loadSavedAddress(); page = Page.SETTINGS; render() })
        })
        home.addView(card().apply {
            pcName = label("Nenhum PC", 20f, true); addView(pcName)
            status = label("Desconectado", 15f); addView(status)
            error = label("", 14f).apply { setTextColor(Palette.error) }; addView(error)
            reconnect = button("Reconectar") { client.reconnect() }; addView(reconnect)
            addView(button("Parear outro PC") { page = Page.PAIR; render(); client.startDiscovery() })
        })

        home.addView(section("Volume do PC", "Controle do áudio principal do Windows."))
        home.addView(card().apply {
            volumeValue = label("—", 26f, true); addView(volumeValue)
            audioHint = label("", 13f); addView(audioHint)
            volume = SeekBar(this@MainActivity).apply {
                max = 100
                minimumHeight = dp(52)
                contentDescription = "Volume do PC, de zero a cem por cento"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(seekBar: SeekBar) { client.beginVolume(seekBar.progress) }
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) { volumeValue.text = "$progress%"; client.updateVolume(progress) }
                    }
                    override fun onStopTrackingTouch(seekBar: SeekBar) { client.endVolume(seekBar.progress) }
                })
            }
            addView(volume)
            addView(row().apply {
                addView(button("−5") { command(RemoteAction.VolumeStep(-5)) }.apply { tag = "audio" }, weighted())
                mute = button("Silenciar") { command(RemoteAction.VolumeMute(state.snapshot?.audio?.muted != true)) }
                addView(mute, weighted())
                addView(button("+5") { command(RemoteAction.VolumeStep(5)) }.apply { tag = "audio" }, weighted())
            })
        })

        home.addView(section("Mídia", "Escolha uma sessão em execução no PC."))
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

        home.addView(section("Área de transferência", "Envio e leitura manuais."))
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
                client.sendClipboard(null) { result -> finishCommand(result, "Texto copiado enviado") }
            }; addView(sendCopiedText)
            getClipboard = button("Ler do PC") {
                client.getClipboard { result -> finishCommand(result, "Texto recebido") }
            }; addView(getClipboard)
            received = label("Nada recebido ainda", 15f).apply {
                setTextIsSelectable(true); minHeight = dp(52); contentDescription = "Texto recebido do PC"
            }; addView(received)
            copyClipboard = button("Copiar no celular") {
                client.copyReceived { result -> finishCommand(result, "Copiado") }
            }; addView(copyClipboard)
        })

        home.addView(section("Sincronização", "Escolha cada direção separadamente."))
        home.addView(card().apply {
            upload = check("Celular → PC", "Enviar novas cópias do celular") { p -> client.setPreferences(state.preferences.copy(upload = p)) }
            addView(upload)
            download = check("PC → celular", "Receber novas cópias do PC") { p ->
                client.setPreferences(state.preferences.copy(download = p))
                if (p) requestNotificationPermission()
            }; addView(download)
            paused = check("Sincronização pausada", "Pausar as duas direções") { p -> client.setPreferences(state.preferences.copy(paused = p)) }
            addView(paused)
            quickPause = button("Pausar sincronização") { client.setPreferences(state.preferences.copy(paused = !state.preferences.paused)) }
            addView(quickPause)
        })

        home.addView(section("Mouse e teclado", "Ative manualmente. O PC precisa permitir entrada remota."))
        home.addView(card().apply {
            inputHint = label("", 14f); addView(inputHint)
            inputArm = button("Ativar entrada") {
                if (state.inputArmed) client.disableInput()
                else client.enableInput { result -> finishCommand(result, "Entrada ativada") }
            }; addView(inputArm)
            touchPad = TouchPadView(this@MainActivity, client) { result -> finishCommand(result, "Clique enviado") }.apply {
                minimumHeight = dp(190)
                contentDescription = "Área de toque do mouse; deslize para mover, toque para clicar"
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
            addView(label("O texto digitado vai para o aplicativo em foco no Windows.", 13f))
            inputText = edit("Texto para digitar no PC", false).apply {
                isSingleLine = true; maxLines = 1
                addTextChangedListener(simpleWatcher { if (::sendText.isInitialized) sendText.isEnabled = state.inputArmed && text.isNotBlank() })
            }
            addView(inputText)
            sendText = button("Digitar no PC") {
                val value = inputText.text.toString()
                if (!client.isInputTextAllowed(value)) { toast("Texto inválido ou maior que 1024 bytes"); return@button }
                client.execute(RemoteAction.KeyboardText(value)) { result -> finishCommand(result, "Texto digitado") }
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
        pair.addView(label("No PC, abra o convite de pareamento. A aprovação deve ser feita no PC.", 15f))
        pairStatus = label(state.status, 16f, true); pair.addView(pairStatus)
        pairError = label("", 14f).apply { setTextColor(Palette.error) }; pair.addView(pairError)
        pair.addView(card().apply {
            addView(button("Ler QR code") {
                client.scanQr(this@MainActivity) { invite, problem ->
                    runOnUiThread {
                        if (problem != null) toast(problem)
                        if (invite != null) fillInvite(invite)
                    }
                }
            })
            pairHost = edit("Endereço do PC", false); addView(pairHost)
            pairPort = edit("Porta", false).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }; addView(pairPort)
            pairFingerprint = edit("Impressão digital do certificado", false); addView(pairFingerprint)
            pairCode = edit("Código de pareamento", false).apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }; addView(pairCode)
            addView(button("Solicitar pareamento") {
                val port = pairPort.text.toString().toIntOrNull()
                if (pairHost.text.isBlank() || port == null || port !in 1..65535 || pairFingerprint.text.isBlank() || pairCode.text.isBlank()) {
                    toast("Preencha endereço, porta, impressão digital e código válidos"); return@button
                }
                client.pair(pairHost.text.toString().trim(), port, pairFingerprint.text.toString().trim(), pairCode.text.toString().trim())
                hideKeyboard()
            })
        })
        pair.addView(section("PCs encontrados", "Use a descoberta apenas para preencher endereço e porta."))
        discovered = column(); pair.addView(discovered)
        pair.addView(button("Procurar novamente") { client.startDiscovery() })
    }

    private fun buildSettings() {
        settings.addView(row().apply {
            addView(label("Configurações", 26f, true), weighted())
            addView(button("Voltar") { navigateBack() })
        })
        settings.addView(card().apply {
            autoApply = check("Aplicar cópias automaticamente", "Substituir a área de transferência do celular ao receber") { p ->
                client.setPreferences(state.preferences.copy(autoApply = p))
            }; addView(autoApply)
            haptics = check("Resposta tátil", "Vibrar após comandos confirmados") { p ->
                client.setPreferences(state.preferences.copy(haptics = p))
            }; addView(haptics)
        })
        settings.addView(section("Endereço salvo", "Altere o endereço sem substituir a identidade verificada do PC."))
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
                    .setMessage("Remove a credencial local deste celular. Para revogar o acesso no PC, remova este celular também nas configurações do PC.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Esquecer") { _, _ -> client.forget(); page = Page.PAIR; render() }
                    .show()
            })
        })
    }

    private fun fillInvite(invite: Invite) {
        pairHost.setText(invite.host); pairPort.setText(invite.port.toString())
        pairFingerprint.setText(invite.fingerprint); pairCode.setText(invite.code)
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
        pairStatus.text = state.status
        pairError.text = state.error
        pairError.visibility = if (state.error.isBlank()) View.GONE else View.VISIBLE
        error.text = state.error
        error.visibility = if (state.error.isBlank()) View.GONE else View.VISIBLE
        reconnect.isEnabled = !connected && client.savedAddress() != null

        val audio = state.snapshot?.audio
        val audioReady = connected && audio?.available == true
        volume.isEnabled = audioReady
        volumeValue.text = if (audioReady) "${state.draggingVolume ?: audio!!.percent}%" else "—"
        audioHint.text = when {
            !connected -> "Conecte ao PC para ajustar o volume."
            audio?.error?.isNotBlank() == true -> audio.error
            !audioReady -> "O controle de áudio não está disponível neste PC."
            else -> if (audio?.muted == true) "Áudio silenciado" else "Áudio ativo"
        }
        if (state.draggingVolume == null && !volume.isPressed) volume.progress = audio?.percent ?: 0
        mute.isEnabled = audioReady
        mute.text = if (audio?.muted == true) "Ativar som" else "Silenciar"
        setTaggedButtonState(home, "audio", audioReady)

        val sessions = state.snapshot?.sessions.orEmpty()
        val ids = sessions.map { it.id }
        if (ids != selectedSessionIds) {
            selectedSessionIds = ids
            session.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                sessions.map { "${it.app}: ${it.title.ifBlank { "Sem título" }}" }.ifEmpty { listOf("Nenhuma sessão") })
        }
        val index = sessions.indexOfFirst { it.id == state.selectedSessionId }.let { if (it < 0) 0 else it }
        if (sessions.isNotEmpty() && session.selectedItemPosition != index) session.setSelection(index)
        session.isEnabled = connected && sessions.size > 1
        val chosen = sessions.getOrNull(index)
        mediaTitle.text = chosen?.title?.ifBlank { "Sem título" } ?: "Nenhuma sessão"
        mediaHint.text = if (!connected) "Conecte ao PC para controlar mídia" else if (chosen == null) "Nenhuma sessão de mídia disponível" else {
            val unavailable = listOfNotNull(
                "reproduzir".takeUnless { chosen.capabilities.play },
                "pausar".takeUnless { chosen.capabilities.pause },
                "anterior".takeUnless { chosen.capabilities.previous },
                "próxima".takeUnless { chosen.capabilities.next },
                "avançar/voltar".takeUnless { chosen.capabilities.seek }
            )
            if (unavailable.isEmpty()) chosen.app else "${chosen.app} · Indisponível nesta sessão: ${unavailable.joinToString(", ")}"
        }
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
        sharedHint.text = if (state.sharedText.isNotEmpty()) "Texto compartilhado recebido. Confira antes de enviar." else ""
        upload.isChecked = state.preferences.upload
        download.isChecked = state.preferences.download
        paused.isChecked = state.preferences.paused
        autoApply.isChecked = state.preferences.autoApply
        haptics.isChecked = state.preferences.haptics
        quickPause.text = if (state.preferences.paused) "Retomar sincronização" else "Pausar sincronização"
        quickPause.isEnabled = connected

        val inputAllowed = connected && state.inputSupported && state.snapshot?.input?.allowed == true
        inputHint.text = when {
            !connected -> "Conecte ao PC para usar mouse e teclado."
            !state.inputSupported -> "Esta versão do PC não oferece entrada remota."
            state.snapshot?.input?.allowed != true -> "Permita entrada remota nas configurações do PC."
            state.inputActivating -> "Aguardando autorização do PC…"
            state.inputArmed -> "Entrada ativada. Será desativada ao sair desta tela."
            else -> "Toque em Ativar entrada para esta sessão."
        }
        inputArm.text = if (state.inputArmed) "Desativar entrada" else if (state.inputActivating) "Ativando…" else "Ativar entrada"
        inputArm.isEnabled = inputAllowed && !state.inputActivating
        val armed = inputAllowed && state.inputArmed
        touchPad.isEnabled = armed
        inputText.isEnabled = armed
        sendText.isEnabled = armed && inputText.text.isNotBlank()
        setTaggedButtonState(home, "input", armed)

        discovered.removeAllViews()
        if (state.discovered.isEmpty()) discovered.addView(label("Nenhum PC encontrado", 14f))
        state.discovered.forEach { pc ->
            discovered.addView(button("${pc.name} · ${pc.host}:${pc.port}") {
                pairHost.setText(pc.host); pairPort.setText(pc.port.toString())
            })
        }
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
        client.execute(action) { result -> finishCommand(result, "Comando enviado") }
    }

    private fun finishCommand(result: String?, success: String) {
        runOnUiThread {
            if (result == null) {
                if (state.preferences.haptics) root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                toast(success)
            } else toast(result)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this)
                .setMessage("Permita notificações para saber quando um texto novo chegar do PC.")
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
    private fun section(title: String, subtitle: String) = column().apply {
        setPadding(0, dp(20), 0, dp(5))
        addView(label(title, 21f, true)); addView(label(subtitle, 14f).apply { setTextColor(Palette.muted) })
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
