package com.pcremote

/**
 * Tracks the clipboard while the app may read it. [remembered] is the last text seen
 * in focus, applied from the PC or sent to it, in this process only: it lets a copy
 * made in another app reach the PC once the user comes back, without resending what
 * the PC itself wrote. No history is persisted.
 */
internal class ClipboardTracker {
  private var ready = false
  private var current: String? = null
  var remembered: String? = null
    private set

  /** Also the value the next [resume] compares with. */
  fun baseline(text: String?) {
    current = text
    ready = true
    if (!text.isNullOrEmpty()) remembered = text
  }

  fun suspend() {
    current = null
    ready = false
  }

  fun applied(text: String) {
    current = text
    ready = true
    remembered = text
  }

  /**
   * Focus came back. Returns the text copied while the app was away, once, when it
   * differs from [remembered]; the first time in a process there is nothing to compare
   * with, so it only becomes the reference.
   */
  fun resume(text: String?): String? {
    val previous = remembered
    baseline(text)
    return text?.takeIf { previous != null && it != previous && it.isNotEmpty() }
  }

  /** Returns only a fresh local nonempty value, after advancing the baseline. */
  fun observe(text: String?): String? {
    if (!ready) {
      baseline(text)
      return null
    }
    val changed = text != current
    current = text
    if (changed && !text.isNullOrEmpty()) remembered = text
    return text?.takeIf { changed && it.isNotEmpty() }
  }
}
