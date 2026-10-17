package com.pcremote

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

internal class MediaScreen(private val host: ScreenHost, private val volume: VolumeInteraction) : Screen {
  private val ui = host.ui
  private val client = host.client
  private val p = ui.palette
  private var state = UiState()
  private val voice = VoiceInput(ui.context)
  private var voicePanel: PanelSheet? = null
  /** The play state just asked for, shown until a snapshot agrees or [OPTIMISTIC_MS] pass. */
  private var expected: Pair<String, Boolean>? = null
  private var expectedAt = 0L

  private val empty = ui.card().apply {
    addView(ui.emptyState(R.drawable.ic_audio_lines, "Mídia e volume do PC", "Conecte-se ao computador para ajustar o volume e controlar o que está tocando.",
      ui.button("Abrir Conexão", ButtonStyle.TONAL) { host.navigate(Tab.CONNECTION) }))
  }

  // YouTube search on the PC, typed or spoken.
  private val query = ui.field("Pesquisar no YouTube").apply {
    imeOptions = EditorInfo.IME_ACTION_SEARCH
    maxLines = 1
    isSingleLine = true
    setOnEditorActionListener { _, actionId, event ->
      val go = actionId == EditorInfo.IME_ACTION_SEARCH || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
      if (go) search(text.toString())
      go
    }
  }
  private val mic = ui.iconButton(R.drawable.ic_mic, "Comandos de voz e pesquisa", 52, tonal = true) { listen() }
  private val searchButton = ui.iconButton(R.drawable.ic_search, "Pesquisar no YouTube", 52) { search(query.text.toString()) }
  private val searchWarning = ui.text("Atualize o PC Remote no Windows para pesquisar.", TextStyle.CAPTION, p.textSecondary)
  private val searchCard = ui.card(10).apply {
    addView(ui.row(8).apply {
      addView(query, ui.weighted())
      addView(searchButton)
      addView(mic)
    })
    addView(searchWarning)
  }

  private val volumeValue = ui.tabular(ui.text("0", TextStyle.DISPLAY))
  private val volumeState = ui.text("", TextStyle.CAPTION, p.textSecondary)
  private val volumeIcon = ImageView(ui.context)
  private val seek = SeekBar(ui.context).apply {
    max = 100
    minimumHeight = ui.dp(48)
    contentDescription = "Volume do PC"
    isHapticFeedbackEnabled = false
    progressTintList = ColorStateList.valueOf(p.accent)
    thumbTintList = ColorStateList.valueOf(p.accent)
    progressBackgroundTintList = ColorStateList.valueOf(p.borderStrong)
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
      override fun onStartTrackingTouch(bar: SeekBar) = volume.startTouch(bar.progress)
      override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
        if (!fromUser) return
        volumeValue.text = progress.toString()
        volume.change(progress)
      }
      override fun onStopTrackingTouch(bar: SeekBar) = volume.stopTouch(bar.progress)
    })
  }
  private val minus = ui.button("−5%", ButtonStyle.TONAL) { command(RemoteAction.VolumeStep(-5)) }
  private val mute = ui.button("Mudo", ButtonStyle.OUTLINED, R.drawable.ic_volume_x) { command(RemoteAction.VolumeMute(state.snapshot?.audio?.muted != true)) }
  private val plus = ui.button("+5%", ButtonStyle.TONAL) { command(RemoteAction.VolumeStep(5)) }
  private val volumeCard = ui.card().apply {
    addView(ui.row(12).apply {
      gravity = Gravity.BOTTOM
      addView(ui.row(2).apply {
        gravity = Gravity.BOTTOM
        addView(volumeValue)
        addView(ui.text("%", TextStyle.TITLE, p.textSecondary).apply { setPadding(0, 0, 0, ui.dp(8)) })
      }, ui.weighted())
      addView(ui.row(6).apply {
        setPadding(0, 0, 0, ui.dp(10))
        addView(volumeIcon, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
        addView(volumeState)
      })
    })
    addView(seek, ui.matchWrap())
    addView(ui.row(8).apply {
      addView(minus, ui.weighted())
      addView(mute, ui.weighted(1.2f))
      addView(plus, ui.weighted())
    })
  }

  private val sessionRow = ui.row(8)
  private val sessionScroll = HorizontalScrollView(ui.context).apply { isHorizontalScrollBarEnabled = false; addView(sessionRow) }
  private var renderedSessions = emptyList<Pair<String, String>>()
  private val app = ui.text("", TextStyle.CAPTION, p.textSecondary).apply { maxLines = 1 }
  private val title = ui.text("", TextStyle.HEADLINE).apply { maxLines = 2 }
  private val nothing = ui.emptyState(R.drawable.ic_music, "Nada tocando", "Abra uma música ou um vídeo no PC.")
  private val back10 = ui.iconButton(R.drawable.ic_rotate_ccw, "Voltar 10 segundos", 52) { media("seek", -10) }
  private val previous = ui.iconButton(R.drawable.ic_skip_back, "Faixa anterior", 52) { media("previous") }
  private val playIcon = ImageView(ui.context)
  private val play = FrameLayout(ui.context).apply {
    val oval = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.accent) }
    background = RippleDrawable(ColorStateList.valueOf(p.ripple), oval, null)
    isClickable = true
    isHapticFeedbackEnabled = false
    addView(playIcon, FrameLayout.LayoutParams(ui.dp(32), ui.dp(32), Gravity.CENTER))
    setOnClickListener { selected()?.let { toggle(it) } }
  }
  private val next = ui.iconButton(R.drawable.ic_skip_forward, "Próxima faixa", 52) { media("next") }
  private val forward10 = ui.iconButton(R.drawable.ic_rotate_cw, "Avançar 10 segundos", 52) { media("seek", 10) }
  private val player = ui.column(20).apply {
    addView(ui.column(4).apply { addView(app); addView(title) })
    addView(LinearLayout(ui.context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      listOf<View>(back10, previous).forEach { addView(it, LinearLayout.LayoutParams(0, ui.dp(52), 1f)) }
      addView(play, LinearLayout.LayoutParams(ui.dp(76), ui.dp(76)).apply { marginStart = ui.dp(8); marginEnd = ui.dp(8) })
      listOf<View>(next, forward10).forEach { addView(it, LinearLayout.LayoutParams(0, ui.dp(52), 1f)) }
    })
  }
  private val playerCard = ui.card().apply {
    addView(ui.cardHeader("Tocando agora"))
    addView(sessionScroll)
    addView(nothing)
    addView(player)
  }

  override val view: View = ScrollView(ui.context).apply {
    isFillViewport = true
    addView(ui.column(16).apply {
      setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(32))
      addView(empty)
      addView(searchCard)
      addView(playerCard)
      addView(volumeCard)
    })
  }

  private fun selectedId(): String = state.selectedSessionId.ifEmpty { state.snapshot?.sessions?.firstOrNull()?.id.orEmpty() }
  private fun selected(): MediaSession? = state.snapshot?.sessions?.firstOrNull { it.id == selectedId() }

  /** What the play button shows: the state just asked for wins for a moment over a stale snapshot. */
  private fun playing(session: MediaSession): Boolean {
    val (id, value) = expected ?: return session.playing
    if (id != session.id || session.playing == value || SystemClock.elapsedRealtime() - expectedAt > OPTIMISTIC_MS) { expected = null; return session.playing }
    return value
  }

  private fun command(action: RemoteAction, success: String? = null) {
    client.execute(action) { host.result(it, success) }
  }

  private fun toggle(session: MediaSession, success: String? = null) {
    val playing = playing(session)
    media(if (playing) "pause" else "play", success = success, expect = !playing)
  }

  private fun media(command: String, offset: Int? = null, success: String? = null, expect: Boolean? = null) {
    val id = selectedId()
    if (id.isEmpty()) { host.notify("Nada tocando no PC.", Tone.DANGER); return }
    if (expect != null) {
      expected = id to expect
      expectedAt = SystemClock.elapsedRealtime()
      render(state)
      // Falls back to the PC's state if no snapshot settles it.
      view.postDelayed({ render(state) }, OPTIMISTIC_MS + 50)
    }
    client.execute(RemoteAction.MediaControl(id, command, offset)) { error ->
      host.activity.runOnUiThread {
        if (error != null && expect != null) { expected = null; render(state) }
        host.result(error, success)
      }
    }
  }

  private fun search(text: String) {
    val value = text.trim()
    if (value.isEmpty()) { host.notify("Digite ou fale o que pesquisar.", Tone.DANGER); return }
    query.setText(value)
    query.setSelection(value.length)
    host.activity.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(query.windowToken, 0)
    client.search(value) { error -> host.result(error, "Pesquisando \"$value\" no YouTube do PC.") }
  }

  private fun listen() = host.requestMicrophone { startListening() }

  /** An in-window panel, so the window keeps focus and the touchpad stays armed. */
  private fun startListening() {
    voicePanel?.dismiss()
    val heard = ui.text("Preparando o microfone…", TextStyle.TITLE).apply { minLines = 2 }
    val speak = "Pode falar."
    val meter = ProgressBar(ui.context, null, android.R.attr.progressBarStyleHorizontal).apply {
      max = 100
      progressTintList = ColorStateList.valueOf(p.accent)
      progressBackgroundTintList = ColorStateList.valueOf(p.border)
      importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    val panel = PanelSheet(host.panels, ui, "Ouvindo…")
    panel.body.addView(heard, ui.matchWrap())
    panel.body.addView(meter, ui.matchWrap())
    heard.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    panel.footer.addView(ui.button("Cancelar", ButtonStyle.OUTLINED) { panel.dismiss() }, ui.weighted())
    voicePanel = panel
    panel.show { voice.cancel(); if (voicePanel === panel) voicePanel = null }
    voice.start(object : VoiceInput.Listener {
      override fun ready() { heard.text = speak }
      override fun level(value: Float) { meter.progress = (value * 100).toInt() }
      override fun partial(text: String) { heard.text = text }
      override fun result(text: String) { panel.dismiss(); handle(text) }
      override fun failed(message: String) { panel.dismiss(); host.notify(message, Tone.DANGER) }
    })
  }

  private fun handle(text: String) {
    when (val intent = VoiceCommands.parse(text)) {
      is VoiceIntent.Search -> {
        if (!state.searchSupported) { query.setText(intent.query); host.notify("Atualize o PC Remote no Windows para pesquisar.", Tone.DANGER); return }
        search(intent.query)
      }
      is VoiceIntent.Command -> perform(intent.command)
      VoiceIntent.Unknown -> host.notify("Não entendi. Toque no microfone e tente de novo.", Tone.DANGER)
    }
  }

  private fun perform(voiceCommand: VoiceCommand) {
    val audio = state.snapshot?.audio
    val session = selected()
    val caps = session?.capabilities
    fun needs(available: Boolean?, name: String, action: () -> Unit) = when {
      session == null -> host.notify("Nada tocando no PC.", Tone.DANGER)
      available != true -> host.notify("Este player não permite $name.", Tone.DANGER)
      else -> action()
    }
    fun volumeTo(percent: Int) {
      if (audio?.available != true) { host.notify("Saída de áudio do PC indisponível.", Tone.DANGER); return }
      val value = percent.coerceIn(0, 100)
      command(RemoteAction.VolumeSet(value), "Volume do PC: $value%.")
    }
    when (voiceCommand) {
      VoiceCommand.PAUSE -> needs(caps?.pause, "pausar") { if (playing(session!!)) toggle(session, "Pausado.") else host.notify("Já está pausado.") }
      VoiceCommand.PLAY -> needs(caps?.play, "reproduzir") { if (!playing(session!!)) toggle(session, "Reproduzindo.") else host.notify("Já está tocando.") }
      VoiceCommand.NEXT -> needs(caps?.next, "pular para a próxima") { media("next", success = "Próxima faixa.") }
      VoiceCommand.PREVIOUS -> needs(caps?.previous, "voltar para a anterior") { media("previous", success = "Faixa anterior.") }
      VoiceCommand.BACK10 -> needs(caps?.seek, "voltar 10 segundos") { media("seek", -10, "Voltou 10 segundos.") }
      VoiceCommand.FORWARD10 -> needs(caps?.seek, "avançar 10 segundos") { media("seek", 10, "Avançou 10 segundos.") }
      VoiceCommand.VOLUME_UP -> volumeTo((audio?.percent ?: 0) + 10)
      VoiceCommand.VOLUME_DOWN -> volumeTo((audio?.percent ?: 0) - 10)
      VoiceCommand.MUTE -> command(RemoteAction.VolumeMute(true), "PC sem som.")
      VoiceCommand.UNMUTE -> command(RemoteAction.VolumeMute(false), "Som do PC ativado.")
    }
  }

  override fun pause() {
    voice.cancel()
    voicePanel?.dismiss()
  }

  override fun render(state: UiState) {
    this.state = state
    val connected = state.status == "Conectado"
    empty.visibility = if (connected) View.GONE else View.VISIBLE
    searchCard.visibility = if (connected) View.VISIBLE else View.GONE
    volumeCard.visibility = if (connected) View.VISIBLE else View.GONE
    playerCard.visibility = if (connected) View.VISIBLE else View.GONE
    if (!connected) return

    searchWarning.visibility = if (state.searchSupported) View.GONE else View.VISIBLE
    query.isEnabled = state.searchSupported
    searchButton.isEnabled = state.searchSupported
    searchButton.alpha = if (state.searchSupported) 1f else 0.4f

    val audio = state.snapshot?.audio
    val ready = audio?.available == true
    val percent = state.draggingVolume ?: audio?.percent ?: 0
    // The pending value also follows the phone's volume keys; a finger on the slider wins.
    if (!seek.isPressed) {
      volumeValue.text = percent.toString()
      seek.progress = percent
    }
    volumeValue.contentDescription = "Volume $percent por cento"
    val muted = audio?.muted == true
    volumeState.text = when {
      audio?.error?.isNotBlank() == true -> audio.error
      !ready -> "Saída de áudio indisponível"
      muted -> "Sem som"
      else -> "Volume do PC"
    }
    volumeState.setTextColor(if (muted) p.warning else p.textSecondary)
    volumeIcon.setImageDrawable(ui.drawable(if (muted) R.drawable.ic_volume_x else R.drawable.ic_volume_2, if (muted) p.warning else p.textSecondary, 20))
    seek.isEnabled = ready
    listOf(minus, plus, mute).forEach { it.isEnabled = ready }
    mute.setLabel(if (muted) "Som" else "Mudo")
    mute.setIcon(if (muted) R.drawable.ic_volume_2 else R.drawable.ic_volume_x)

    val sessions = state.snapshot?.sessions.orEmpty()
    val chosen = selected()
    val labels = sessions.map { it.id to it.app.ifBlank { it.title.ifBlank { "Player" } } }
    if (labels != renderedSessions || sessionRow.childCount == 0 && sessions.size > 1) {
      renderedSessions = labels
      sessionRow.removeAllViews()
      labels.forEach { (id, label) -> sessionRow.addView(sessionChip(id, label)) }
    }
    for (i in 0 until sessionRow.childCount) styleChip(sessionRow.getChildAt(i), sessionRow.getChildAt(i).tag == chosen?.id)
    sessionScroll.visibility = if (sessions.size > 1) View.VISIBLE else View.GONE
    nothing.visibility = if (chosen == null) View.VISIBLE else View.GONE
    player.visibility = if (chosen == null) View.GONE else View.VISIBLE
    if (chosen == null) return
    app.text = chosen.app.ifBlank { "Player do Windows" }
    title.text = chosen.title.ifBlank { "Mídia sem título" }
    val cap = chosen.capabilities
    val playing = playing(chosen)
    val canToggle = if (playing) cap.pause else cap.play
    playIcon.setImageDrawable(ui.drawable(if (playing) R.drawable.ic_pause else R.drawable.ic_play, p.onAccent, 32))
    play.contentDescription = if (playing) "Pausar" else "Reproduzir"
    setAvailable(play, canToggle)
    setAvailable(back10, cap.seek)
    setAvailable(forward10, cap.seek)
    setAvailable(previous, cap.previous)
    setAvailable(next, cap.next)
  }

  private fun setAvailable(view: View, available: Boolean) {
    view.isEnabled = available
    view.alpha = if (available) 1f else 0.35f
  }

  private fun sessionChip(id: String, label: String): View = ui.row(6).apply {
    tag = id
    minimumHeight = ui.dp(40)
    setPadding(ui.dp(14), 0, ui.dp(14), 0)
    isClickable = true
    contentDescription = label
    addView(ImageView(context), LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)))
    addView(ui.text(label, TextStyle.LABEL).apply { maxLines = 1 })
    setOnClickListener { client.selectSession(id) }
  }

  private fun styleChip(chip: View, selected: Boolean) {
    val row = chip as LinearLayout
    val icon = row.getChildAt(0) as ImageView
    val label = row.getChildAt(1) as TextView
    row.background = ui.ripple(ui.rounded(if (selected) p.accentSoft else android.graphics.Color.TRANSPARENT, 8f, if (selected) null else p.borderStrong), 8f)
    label.setTextColor(if (selected) p.accentText else p.textSecondary)
    icon.visibility = if (selected) View.VISIBLE else View.GONE
    if (selected) icon.setImageDrawable(ui.drawable(R.drawable.ic_check, p.accentText, 16))
    row.isSelected = selected
  }

  private companion object {
    const val OPTIMISTIC_MS = 2500L
  }
}
