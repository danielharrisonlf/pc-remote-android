package com.pcremote

/**
 * The user's wish to control the PC during one connection. Switching tabs leaves
 * it alone. Backgrounding the app or losing window focus disarms the PC but keeps
 * the wish, so input arms again once the app is usable. It ends with "Desligar",
 * the end of the connection, the PC blocking or switching off input, or an
 * activation the PC did not confirm.
 */
internal class InputIntent {
  var wanted = false
    private set

  /** The PC confirmed an activation, made by the user or by a re-arm. */
  fun confirmed() { wanted = true }

  /** "Desligar", a new or lost connection, or an activation the PC refused. */
  fun clear() { wanted = false }

  /**
   * A snapshot that blocks input, or switches it off while this phone is armed,
   * means the PC took control back. A switch-off this phone asked for arrives
   * while it is already disarmed, so it keeps the wish. Returns whether to drop input.
   */
  fun pcState(allowed: Boolean?, enabled: Boolean?, armed: Boolean): Boolean {
    val revoked = allowed == false || (enabled == false && armed)
    if (revoked) wanted = false
    return revoked
  }

  /**
   * Whether to arm the PC again now. [usable]: authenticated, in the foreground with
   * window focus, extension negotiated and allowed by the PC. [settling]: an
   * activation or a disarm is still in flight; its completion asks again.
   */
  fun shouldRearm(usable: Boolean, armed: Boolean, settling: Boolean): Boolean = wanted && usable && !armed && !settling
}
