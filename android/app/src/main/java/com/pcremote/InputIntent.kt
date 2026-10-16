package com.pcremote

/**
 * The user's wish to control the PC. Since 0.6 it starts on with every connection,
 * so the touchpad is ready without a tap. Switching tabs leaves it alone.
 * Backgrounding the app or losing window focus disarms the PC but keeps the wish,
 * so input arms again once the app is usable. A block on the PC also keeps it and
 * input comes back when the PC lifts the block. It ends with the end of the
 * connection, the PC switching input off while armed, or an activation the PC did
 * not confirm. "Desligar" ends it until "Ativar touchpad", across reconnections,
 * for as long as the process lives.
 */
internal class InputIntent {
  var wanted = false
    private set
  /** "Desligar" was the user's last word. */
  var userOff = false
    private set

  /** A new authenticated connection: ready to arm unless the user switched input off. */
  fun connected() { wanted = !userOff }

  /** The PC confirmed an activation, made by the user or by a re-arm. */
  fun confirmed() { wanted = true }

  /** "Ativar touchpad". */
  fun userOn() { userOff = false; wanted = true }

  /** "Desligar". */
  fun userDisabled() { userOff = true; wanted = false }

  /** A lost connection or an activation the PC refused. */
  fun clear() { wanted = false }

  /**
   * Returns whether to drop input. A block on the PC drops it and keeps the wish, so
   * input arms again when the block is lifted. A switch-off while this phone is
   * armed means the PC took control back and ends the wish. A switch-off this phone
   * asked for arrives while it is already disarmed, so it keeps the wish.
   */
  fun pcState(allowed: Boolean?, enabled: Boolean?, armed: Boolean): Boolean {
    if (allowed == false) return true
    val switchedOff = enabled == false && armed
    if (switchedOff) wanted = false
    return switchedOff
  }

  /**
   * Whether to arm the PC again now. [usable]: authenticated, in the foreground with
   * window focus, extension negotiated and allowed by the PC. [settling]: an
   * activation or a disarm is still in flight; its completion asks again.
   */
  fun shouldRearm(usable: Boolean, armed: Boolean, settling: Boolean): Boolean = wanted && usable && !armed && !settling
}
