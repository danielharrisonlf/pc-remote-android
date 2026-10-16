package com.pcremote

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

internal class MediaScreen(private val host: ScreenHost, private val volume: VolumeInteraction) : Screen {
  private val ui = host.ui
  private val client = host.client
  private val p = ui.palette
  private var state = UiState()

  private val empty = ui.card().apply {
    addView(ui.emptyState(R.drawable.ic_audio_lines, "Mídia e volume do PC", "Conecte-se ao computador para ajustar o volume e controlar o que está tocando.",
      ui.button("Abrir Conexão", ButtonStyle.TONAL) { host.navigate(Tab.CONNECTION) }))
  }

  private val volumeValue = ui.tabular(ui.text("0", TextStyle.DISPLAY))
  private val volumeState = ui.text("", TextStyle.CAPTION, p.textSecondary)
  private val volumeIcon = ImageView(ui.context)
  private val seek = SeekBar(ui.context).apply {
    max = 100
    minimumHeight = ui.dp(48)
    contentDescription = "Volume do PC"
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
      override fun onStopTrackingTouch(bar: SeekBar) {
        host.haptic()
        volume.stopTouch(bar.progress)
      }
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
    addView(playIcon, FrameLayout.LayoutParams(ui.dp(32), ui.dp(32), Gravity.CENTER))
    setOnClickListener { media(if (state.snapshot?.sessions?.firstOrNull { it.id == selectedId() }?.playing == true) "pause" else "play") }
  }
  private val next = ui.iconButton(R.drawable.ic_skip_forward, "Próxima faixa", 52) { media("next") }
  private val forward10 = ui.iconButton(R.drawable.ic_rotate_cw, "Avançar 10 segundos", 52) { media("seek", 10) }
  private val limited = ui.text("Controles apagados não são oferecidos por este player.", TextStyle.CAPTION, p.textTertiary).apply { gravity = Gravity.CENTER }
  private val player = ui.column(20).apply {
    addView(ui.column(4).apply { addView(app); addView(title) })
    addView(LinearLayout(ui.context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      listOf<View>(back10, previous).forEach { addView(it, LinearLayout.LayoutParams(0, ui.dp(52), 1f)) }
      addView(play, LinearLayout.LayoutParams(ui.dp(76), ui.dp(76)).apply { marginStart = ui.dp(8); marginEnd = ui.dp(8) })
      listOf<View>(next, forward10).forEach { addView(it, LinearLayout.LayoutParams(0, ui.dp(52), 1f)) }
    })
    addView(limited, ui.matchWrap())
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
      addView(volumeCard)
      addView(playerCard)
    })
  }

  private fun selectedId(): String = state.selectedSessionId.ifEmpty { state.snapshot?.sessions?.firstOrNull()?.id.orEmpty() }

  private fun command(action: RemoteAction) {
    host.haptic()
    client.execute(action) { host.result(it) }
  }

  private fun media(command: String, offset: Int? = null) {
    val id = selectedId()
    if (id.isNotEmpty()) command(RemoteAction.MediaControl(id, command, offset))
  }

  override fun render(state: UiState) {
    this.state = state
    val connected = state.status == "Conectado"
    empty.visibility = if (connected) View.GONE else View.VISIBLE
    volumeCard.visibility = if (connected) View.VISIBLE else View.GONE
    playerCard.visibility = if (connected) View.VISIBLE else View.GONE
    if (!connected) return

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
      else -> "Saída padrão do Windows"
    }
    volumeState.setTextColor(if (muted) p.warning else p.textSecondary)
    volumeIcon.setImageDrawable(ui.drawable(if (muted) R.drawable.ic_volume_x else R.drawable.ic_volume_2, if (muted) p.warning else p.textSecondary, 20))
    seek.isEnabled = ready
    listOf(minus, plus, mute).forEach { it.isEnabled = ready }
    mute.setLabel(if (muted) "Som" else "Mudo")
    mute.setIcon(if (muted) R.drawable.ic_volume_2 else R.drawable.ic_volume_x)

    val sessions = state.snapshot?.sessions.orEmpty()
    val chosen = sessions.firstOrNull { it.id == selectedId() }
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
    val canToggle = if (chosen.playing) cap.pause else cap.play
    playIcon.setImageDrawable(ui.drawable(if (chosen.playing) R.drawable.ic_pause else R.drawable.ic_play, p.onAccent, 32))
    play.contentDescription = if (chosen.playing) "Pausar" else "Reproduzir"
    setAvailable(play, canToggle)
    setAvailable(back10, cap.seek)
    setAvailable(forward10, cap.seek)
    setAvailable(previous, cap.previous)
    setAvailable(next, cap.next)
    limited.visibility = if (cap.seek && cap.previous && cap.next) View.GONE else View.VISIBLE
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

}
