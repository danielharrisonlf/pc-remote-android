package com.pcremote

internal val DISCOVERY_RETRY_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L)

/** Keeps legacy NSD resolution serial and rejects callbacks from retired scans. */
internal class DiscoveryResolver<T>(private val limit: Int = 50) {
  data class Ticket<T>(val epoch: Int, val name: String, val service: T, val retries: Int = 0, val readyAtMillis: Long = 0L)
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
    waiting[name]?.let { waiting[name] = it.copy(service = service); return }
    waiting[name] = Ticket(epoch, name, service)
    while (waiting.size > limit) waiting.remove(waiting.keys.first())
  }
  fun lost(name: String) { waiting.remove(name); if (current?.name == name) invalidated = current }
  fun isValid(ticket: Ticket<T>) = active && ticket.epoch == epoch && current == ticket && invalidated != ticket
  fun next(nowMillis: Long = 0L): Ticket<T>? {
    if (!active || current != null || waiting.isEmpty()) return null
    val ready = waiting.entries.firstOrNull { it.value.readyAtMillis <= nowMillis } ?: return null
    waiting.remove(ready.key)
    return ready.value.also { current = it }
  }
  fun hasActiveResolve() = current != null
  fun nextWakeAt(nowMillis: Long = 0L): Long? {
    if (!active || current != null) return null
    return waiting.values.map { it.readyAtMillis }.filter { it > nowMillis }.minOrNull()
  }
  fun complete(ticket: Ticket<T>): Boolean {
    if (ticket != current) return false
    current = null; invalidated = null
    return true
  }
  /** Retry callback failures after other PCs. Never release the native slot without its callback. */
  fun retryFailed(ticket: Ticket<T>, nowMillis: Long = 0L): Boolean {
    if (!active || ticket.epoch != epoch || ticket.retries >= 3 || waiting.containsKey(ticket.name) || invalidated == ticket) return false
    val delay = DISCOVERY_RETRY_BACKOFF_MS[ticket.retries]
    waiting[ticket.name] = ticket.copy(retries = ticket.retries + 1, readyAtMillis = nowMillis + delay)
    while (waiting.size > limit) waiting.remove(waiting.keys.first())
    return true
  }
}

/**
 * Tracks the independent API 34+ service-info callbacks. A failed registration is
 * appended behind services already waiting, while old callback tickets are fenced
 * after loss, retry, or a new scan.
 */
internal class ServiceInfoQueue<T>(private val limit: Int = 50, private val maxRetries: Int = 3) {
  data class Ticket<T>(val epoch: Int, val key: String, val service: T, val retries: Int = 0, val readyAtMillis: Long = 0L)
  private val waiting = LinkedHashMap<String, Ticket<T>>()
  private val current = LinkedHashMap<String, Ticket<T>>()
  private val exhausted = HashSet<String>()
  private var epoch = 0
  private var active = false

  fun start() { epoch++; active = true; waiting.clear(); current.clear(); exhausted.clear() }

  /** Return callbacks which should be unregistered before their scan is retired. */
  fun stop(): List<Ticket<T>> {
    epoch++; active = false; waiting.clear(); exhausted.clear()
    return current.values.toList().also { current.clear() }
  }

  fun found(key: String, service: T): Boolean {
    if (!active || key.isBlank() || key in exhausted) return false
    if (key in current) return false
    waiting[key]?.let { waiting[key] = it.copy(service = service); return false }
    waiting[key] = Ticket(epoch, key, service)
    trimWaiting()
    return key in waiting
  }

  fun next(nowMillis: Long = 0L): Ticket<T>? {
    if (!active || waiting.isEmpty() || current.size >= limit) return null
    val ready = waiting.entries.firstOrNull { it.value.readyAtMillis <= nowMillis } ?: return null
    waiting.remove(ready.key)
    return ready.value.also { current[it.key] = it }
  }

  fun nextWakeAt(nowMillis: Long = 0L): Long? {
    if (!active || current.size >= limit) return null
    return waiting.values.map { it.readyAtMillis }.filter { it > nowMillis }.minOrNull()
  }

  fun isValid(ticket: Ticket<T>) = active && ticket.epoch == epoch && current[ticket.key] == ticket

  /** A lost callback can only retire the registration that produced it. */
  fun lost(ticket: Ticket<T>): Boolean {
    if (!isValid(ticket)) return false
    lost(ticket.key)
    return true
  }

  /** API 34's service watcher can report temporary unavailability and later update again. */
  fun temporarilyLost(ticket: Ticket<T>): Boolean = isValid(ticket)

  /** Removes a service announcement, including one still waiting for registration. */
  fun lost(key: String): Ticket<T>? {
    waiting.remove(key)
    exhausted.remove(key)
    return current.remove(key)
  }

  /** Registration failure frees that callback, then retries it behind other PCs. */
  fun retryFailed(ticket: Ticket<T>, nowMillis: Long = 0L): Boolean {
    if (!isValid(ticket)) return false
    current.remove(ticket.key)
    if (ticket.retries >= minOf(maxRetries, DISCOVERY_RETRY_BACKOFF_MS.size) || ticket.key in waiting) {
      exhausted.add(ticket.key)
      return false
    }
    val delay = DISCOVERY_RETRY_BACKOFF_MS[ticket.retries]
    waiting[ticket.key] = ticket.copy(retries = ticket.retries + 1, readyAtMillis = nowMillis + delay)
    trimWaiting()
    return ticket.key in waiting
  }

  private fun trimWaiting() {
    while (waiting.size + current.size > limit) {
      val oldest = waiting.keys.firstOrNull() ?: break
      waiting.remove(oldest)
    }
  }
}

internal fun pairingFailureMessage(code: String): String = when (code) {
  "pairing_code_incorrect" -> "Código incorreto para o PC selecionado. Confira o PC e os 8 dígitos exibidos."
  "pairing_expired" -> "Este código expirou. Gere um novo código no PC."
  "pairing_timeout" -> "O PC não respondeu ao pareamento a tempo. Gere um novo código e tente novamente."
  "pairing_denied" -> "Pareamento recusado no PC."
  "pairing_invalidated" -> "O código de pareamento foi cancelado no PC. Gere outro."
  "pairing_failed" -> "O PC não concluiu o pareamento. Tente novamente."
  "rate_limited" -> "Muitas tentativas. Aguarde um pouco."
  "unavailable" -> "Outro celular já está conectado a este PC."
  "unauthorized" -> "Acesso revogado. Pareie de novo."
  else -> "Não foi possível conectar."
}

/** Discovery supplies an address only. The saved certificate and token remain authoritative. */
internal fun discoveredEndpoint(saved: PairedComputer, found: List<DiscoveredComputer>): PairedComputer {
  val match = found.lastOrNull { saved.pcId.isNotBlank() && it.id == saved.pcId }
  return if (match == null) saved else saved.copy(host = match.host, port = match.port)
}
