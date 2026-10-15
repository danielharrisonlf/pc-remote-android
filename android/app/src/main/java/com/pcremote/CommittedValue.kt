package com.pcremote

/** A failed disk commit may still change the backing store's in-memory value. */
internal class CommittedValue(
  private val readBacking: () -> String?,
  private val commitBacking: (String?) -> Boolean,
) {
  private var overridden = false
  private var previousValue: String? = null

  fun read(): String? = if (overridden) previousValue else readBacking()

  fun write(value: String?) {
    val previous = read()
    try {
      if (!commitBacking(value)) throw IllegalStateException("Could not commit protected storage")
    } catch (error: Exception) {
      previousValue = previous
      overridden = true
      // Restore the backing cache too, but never trust it after another failure.
      try { commitBacking(previous) } catch (_: Exception) {}
      throw error
    }
    overridden = false
    previousValue = null
  }
}
