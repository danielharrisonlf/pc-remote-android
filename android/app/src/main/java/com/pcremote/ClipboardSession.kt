package com.pcremote

/** Clipboard revisions, deduplication and deferred previews belong to one connection. */
internal class ClipboardSession {
  private val events = ClipboardEvents()
  private var pending: Incoming.Clipboard? = null
  var revision = 0L
    private set

  fun advanceRevision(revision: Long) {
    this.revision = maxOf(this.revision, revision)
  }

  fun accept(event: Incoming.Clipboard, authoritativeRevision: Long): Boolean {
    if (!events.accept(event, authoritativeRevision)) return false
    advanceRevision(event.revision)
    return true
  }

  fun defer(event: Incoming.Clipboard) {
    pending = event
  }

  fun takePending(): Incoming.Clipboard? = pending.also { pending = null }

  fun retire() {
    pending = null
    revision = 0
    events.clear()
  }
}
