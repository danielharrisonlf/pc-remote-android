package com.pcremote

/** Keeps legacy NSD resolution serial and rejects callbacks from retired scans. */
internal class DiscoveryResolver<T>(private val limit: Int = 50) {
  data class Ticket<T>(val epoch: Int, val name: String, val service: T, val busyRetries: Int = 0)
  private val waiting = LinkedHashMap<String, Ticket<T>>()
  private var epoch = 0
  private var active = false
  private var current: Ticket<T>? = null
  private var invalidated: Ticket<T>? = null

  // A retired scan must still wait for the native resolve callback before reusing its slot.
  fun start() { epoch++; active = true; waiting.clear(); invalidated = null }
  fun stop() { epoch++; active = false; waiting.clear(); invalidated = null }
  fun found(name: String, service: T) {
    if (!active || name.isBlank() || (current?.epoch == epoch && current?.name == name && invalidated != current)) return
    if (waiting.containsKey(name)) waiting.remove(name)
    waiting[name] = Ticket(epoch, name, service)
    while (waiting.size > limit) waiting.remove(waiting.keys.first())
  }
  fun lost(name: String) { waiting.remove(name); if (current?.name == name) invalidated = current }
  fun isValid(ticket: Ticket<T>) = active && ticket.epoch == epoch && current == ticket && invalidated != ticket
  fun next(): Ticket<T>? {
    if (!active || current != null || waiting.isEmpty()) return null
    val first = waiting.entries.first()
    waiting.remove(first.key)
    return first.value.also { current = it }
  }
  fun complete(ticket: Ticket<T>): Boolean {
    if (ticket != current) return false
    current = null; invalidated = null
    return true
  }
  fun retryBusy(ticket: Ticket<T>): Boolean {
    if (!active || ticket.epoch != epoch || ticket.busyRetries >= 5 || waiting.containsKey(ticket.name)) return false
    val queued = LinkedHashMap<String, Ticket<T>>()
    queued[ticket.name] = ticket.copy(busyRetries = ticket.busyRetries + 1)
    queued.putAll(waiting)
    waiting.clear(); waiting.putAll(queued)
    while (waiting.size > limit) waiting.remove(waiting.keys.last())
    return true
  }
}

/** Discovery supplies an address only. The saved certificate and token remain authoritative. */
internal fun discoveredEndpoint(saved: PairedComputer, found: List<DiscoveredComputer>): PairedComputer {
  val match = found.lastOrNull { saved.pcId.isNotBlank() && it.id == saved.pcId }
  return if (match == null) saved else saved.copy(host = match.host, port = match.port)
}
