package com.pcremote

/** One scanner launch at a time; a destroyed Activity cannot receive an old result. */
internal class QrScanGate {
  private var active = false
  private var detached = false

  fun begin(): Boolean {
    if (active || detached) return false
    active = true
    return true
  }

  fun finish(): Boolean {
    if (!active) return false
    active = false
    return !detached
  }

  fun detach() {
    detached = true
    active = false
  }
}
