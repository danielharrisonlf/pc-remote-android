package com.pcremote

import android.app.AlertDialog
import android.view.View
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** power.v1 wording, pure so it runs on the JVM. */
internal object PowerLabels {
  /** "Agora", "Em 15 min"…, with the delay each one sends. */
  val choices = listOf("Agora" to 0, "Em 15 min" to 900, "Em 30 min" to 1800, "Em 1 hora" to 3600, "Em 2 horas" to 7200)

  fun verb(action: String) = when (action) {
    "lock" -> "Bloqueia"
    "sleep" -> "Suspende"
    "restart" -> "Reinicia"
    else -> "Desliga"
  }

  /** "em 28 min", "em 1 h 05 min", "em instantes". */
  fun remaining(millis: Long): String {
    val minutes = (millis + 59_999) / 60_000
    return when {
      millis <= 0 -> "em instantes"
      minutes < 60 -> "em $minutes min"
      else -> "em ${minutes / 60} h %02d min".format(minutes % 60)
    }
  }

  /** "Desliga às 23:40 (em 28 min)". */
  fun pending(action: String, at: String, millisLeft: Long) = "${verb(action)} às $at (${remaining(millisLeft)})"

  /** What the PC is doing right after an accepted request. */
  fun started(action: String, delaySeconds: Int, at: String) = when {
    delaySeconds > 0 -> "Agendado: ${verb(action).lowercase(Locale.ROOT)} às $at."
    action == "lock" -> "PC bloqueado."
    action == "sleep" -> "Suspendendo o PC…"
    action == "restart" -> "Reiniciando o PC…"
    else -> "Desligando o PC…"
  }
}

/**
 * "Energia do PC" in Conexão (power.v1): lock now, sleep or shut down now or later,
 * restart after a confirmation, and the pending action with its countdown and Cancelar.
 */
internal class PowerPanel(private val host: ScreenHost) {
  private val ui = host.ui
  private val client = host.client
  private val p = ui.palette
  private var state = UiState()
  private var busy = false
  private val clock = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.forLanguageTag("pt-BR"))

  private val note = ui.text("", TextStyle.CAPTION, p.textSecondary)
  private val pendingText = ui.text("", TextStyle.BODY_STRONG)
  private val cancel = ui.button("Cancelar", ButtonStyle.OUTLINED, R.drawable.ic_x) { send(RemoteAction.PowerCancel, "Agendamento cancelado.") }
  private val pending = ui.row(12).apply {
    background = ui.rounded(p.surfaceSunken, 12f, p.border)
    setPadding(ui.dp(12), ui.dp(8), ui.dp(8), ui.dp(8))
    addView(pendingText, ui.weighted())
    addView(cancel)
  }
  private val lock = ui.button("Bloquear", ButtonStyle.TONAL, R.drawable.ic_lock) { run("lock", 0) }
  private val sleep = ui.button("Suspender", ButtonStyle.TONAL, R.drawable.ic_moon) { choose("sleep", "Suspender o PC") }
  private val shutdown = ui.button("Desligar", ButtonStyle.TONAL, R.drawable.ic_power) { choose("shutdown", "Desligar o PC") }
  private val restart = ui.button("Reiniciar", ButtonStyle.TONAL, R.drawable.ic_rotate_cw) { confirmRestart() }
  private val buttons = ui.column(8).apply {
    addView(ui.row(8).apply { addView(lock, ui.weighted()); addView(sleep, ui.weighted()) })
    addView(ui.row(8).apply { addView(shutdown, ui.weighted()); addView(restart, ui.weighted()) })
  }
  private val ticker = object : Runnable {
    override fun run() { renderPending(); view.postDelayed(this, 30_000) }
  }

  val view = ui.card(12).apply {
    addView(ui.cardHeader("Energia do PC"))
    addView(pending)
    addView(buttons)
    addView(note)
  }

  private fun choose(action: String, title: String) {
    AlertDialog.Builder(host.activity, dialogTheme(p))
      .setTitle(title)
      .setItems(PowerLabels.choices.map { it.first }.toTypedArray()) { _, which -> run(action, PowerLabels.choices[which].second) }
      .setNegativeButton("Cancelar", null)
      .show()
  }

  private fun confirmRestart() {
    AlertDialog.Builder(host.activity, dialogTheme(p))
      .setTitle("Reiniciar o PC agora?")
      .setMessage("Programas abertos podem pedir para salvar.")
      .setNegativeButton("Cancelar", null)
      .setPositiveButton("Reiniciar") { _, _ -> run("restart", 0) }
      .show()
  }

  private fun run(action: String, delaySeconds: Int) {
    val at = clock.format(Date(System.currentTimeMillis() + delaySeconds * 1000L))
    send(RemoteAction.PowerRun(action, delaySeconds), PowerLabels.started(action, delaySeconds, at))
  }

  private fun send(action: RemoteAction, success: String) {
    if (busy) return
    busy = true
    render(state)
    client.execute(action) { error ->
      busy = false
      render(state)
      host.result(error, success)
    }
  }

  private fun renderPending() {
    val scheduled = state.snapshot?.power?.scheduled
    val show = state.status == "Conectado" && state.powerSupported && scheduled != null && state.powerDeadline > 0
    pending.visibility = if (show) View.VISIBLE else View.GONE
    view.removeCallbacks(ticker)
    if (!show || scheduled == null) return
    val left = state.powerDeadline - System.currentTimeMillis()
    pendingText.text = PowerLabels.pending(scheduled.action, clock.format(Date(state.powerDeadline)), left)
    view.postDelayed(ticker, 30_000)
  }

  fun render(state: UiState) {
    this.state = state
    val paired = client.savedAddress() != null
    view.visibility = if (paired) View.VISIBLE else View.GONE
    if (!paired) { view.removeCallbacks(ticker); return }
    val connected = state.status == "Conectado"
    val power = state.snapshot?.power
    val usable = connected && state.powerSupported && power?.allowed == true && !busy
    buttons.visibility = if (connected && !state.powerSupported) View.GONE else View.VISIBLE
    listOf(lock, sleep, shutdown, restart).forEach { it.isEnabled = usable }
    cancel.isEnabled = connected && !busy
    note.text = when {
      !connected -> "Conecte-se ao PC para bloquear, suspender, desligar ou reiniciar."
      !state.powerSupported -> "Atualize o PC Remote no Windows para controlar a energia."
      power?.allowed == false -> "Bloqueado no PC. Libere no PC Remote do Windows."
      else -> "Programas abertos podem pedir para salvar antes de desligar ou reiniciar."
    }
    renderPending()
  }
}
