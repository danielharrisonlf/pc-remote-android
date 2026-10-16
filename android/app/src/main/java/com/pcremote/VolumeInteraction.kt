package com.pcremote

/** Gives touch drags and keyboard/accessibility bursts the same confirmed final value. */
internal class VolumeInteraction(
  private val begin: (Int) -> Unit,
  private val update: (Int) -> Unit,
  private val end: (Int) -> Unit,
  private val schedule: (Runnable, Long) -> Unit,
  private val cancel: (Runnable) -> Unit,
) {
  private var touching = false
  private var active = false
  private var value = 0
  private var settling: Runnable? = null

  fun startTouch(value: Int) {
    cancelSettling()
    touching = true
    active = true
    this.value = value
    begin(value)
  }

  fun change(value: Int) {
    this.value = value
    if (active) update(value) else {
      active = true
      begin(value)
    }
    if (!touching) {
      cancelSettling()
      val task = Runnable { settling = null; finish() }
      settling = task
      schedule(task, 100)
    }
  }

  fun stopTouch(value: Int) {
    if (!touching) return
    this.value = value
    finish()
  }

  /** Also commits a burst interrupted by navigation or Activity pause. */
  fun finish() {
    cancelSettling()
    touching = false
    if (!active) return
    active = false
    end(value)
  }

  /** A retired connection must not receive the delayed final command after reconnecting. */
  fun cancel() {
    cancelSettling()
    touching = false
    active = false
  }

  private fun cancelSettling() {
    settling?.let(cancel)
    settling = null
  }
}
