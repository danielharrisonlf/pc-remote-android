package com.pcremote

/** Tracks the current clipboard value while the app may read it. No history is persisted. */
internal class ClipboardTracker {
  private var ready = false
  private var current: String? = null

  fun baseline(text: String?) {
    current = text
    ready = true
  }

  fun suspend() {
    current = null
    ready = false
  }

  fun applied(text: String) {
    current = text
    ready = true
  }

  /** Returns only a fresh local nonempty value, after advancing the baseline. */
  fun observe(text: String?): String? {
    if (!ready) {
      baseline(text)
      return null
    }
    val changed = text != current
    current = text
    return text?.takeIf { changed && it.isNotEmpty() }
  }
}
