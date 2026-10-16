package com.pcremote

import java.util.Locale

/** A document accepted for sending; [uri] stays a string so the rules run on the JVM. */
internal data class FileItem(val uri: String, val name: String, val size: Long, val mime: String)

/** files.v1 rules shared with the PC. The phone checks them first to fail fast; the PC checks again. */
internal object FileRules {
  const val MAX_BYTES = 52_428_800L
  const val MAX_QUEUE = 20
  /** Bytes read before queueing; a PDF may start its header anywhere in them. */
  const val HEAD_BYTES = 1024
  const val CHANGED = "O arquivo mudou durante o envio."
  val MIME_TYPES = listOf("image/png", "image/jpeg", "application/pdf")
  private val byExtension = mapOf("png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "pdf" to "application/pdf")
  private val canonical = mapOf("image/png" to "png", "image/jpeg" to "jpg", "application/pdf" to "pdf")
  private val kinds = mapOf("image/png" to "PNG", "image/jpeg" to "JPG", "application/pdf" to "PDF")
  private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
  private val pdf = "%PDF-".toByteArray(Charsets.US_ASCII)
  private val forbidden = "<>:\"/\\|?*".toSet()
  /** Bidirectional controls can disguise the real extension; the PC drops them too. */
  private fun bidi(cp: Int) = cp == 0x061C || cp == 0x200E || cp == 0x200F || cp in 0x202A..0x202E || cp in 0x2066..0x2069
  private val devices = setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$")
  private val ptBR = Locale.forLanguageTag("pt-BR")
  /** Server codes that would fail the next file the same way; anything else is about this file. */
  private val itemErrors = setOf("file_type", "too_large", "file_integrity", "invalid_message")

  sealed interface Check {
    data class Ok(val name: String, val mime: String, val size: Long) : Check
    data class Rejected(val message: String) : Check
  }

  fun extension(name: String): String = if ('.' in name) name.substringAfterLast('.').lowercase(Locale.ROOT) else ""

  /** The provider's type when it is one we send, else the one the extension implies. */
  fun mime(reported: String?, name: String): String? =
    reported?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { it in MIME_TYPES } ?: byExtension[extension(name)]

  fun signatureMatches(mime: String, head: ByteArray, length: Int): Boolean {
    val n = length.coerceIn(0, head.size)
    return when (mime) {
      "image/png" -> n >= png.size && png.indices.all { head[it] == png[it] }
      "image/jpeg" -> n >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
      "application/pdf" -> (0..minOf(n, HEAD_BYTES) - pdf.size).any { start -> pdf.indices.all { head[start + it] == pdf[it] } }
      else -> false
    }
  }

  /** Used only when neither the provider nor the name says what the document is. */
  fun sniff(head: ByteArray, length: Int): String? = MIME_TYPES.firstOrNull { signatureMatches(it, head, length) }

  /**
   * Mirrors the PC sanitizer so the phone shows the name the PC will use: last path
   * component, no control, reserved or bidirectional characters, single spaces, no
   * dots or spaces at the ends, "_" before Windows device names, an extension that
   * matches [mime], stem of at most 100 characters and the whole name within 255
   * UTF-8 bytes.
   */
  fun cleanName(raw: String?, mime: String): String {
    val trimmed = sanitize(raw)
    val keeps = byExtension[extension(trimmed)] == mime
    var stem = (if (keeps) trimmed.substringBeforeLast('.') else trimmed).ifEmpty { "arquivo" }
    if (device(stem)) stem = "_$stem"
    val suffix = if (keeps) extension(trimmed) else canonical.getValue(mime)
    if (stem.codePointCount(0, stem.length) > 100) stem = stem.substring(0, stem.offsetByCodePoints(0, 100))
    while (stem.isNotEmpty() && "$stem.$suffix".toByteArray(Charsets.UTF_8).size > 255) stem = stem.substring(0, stem.offsetByCodePoints(0, stem.codePointCount(0, stem.length) - 1))
    return "${stem.ifEmpty { "arquivo" }}.$suffix"
  }

  private fun sanitize(raw: String?): String {
    val clean = StringBuilder()
    val last = raw.orEmpty().split('/', '\\').last()
    var i = 0
    var space = false
    while (i < last.length) {
      val c = last[i]
      val cp = when {
        c.isHighSurrogate() && i + 1 < last.length && last[i + 1].isLowSurrogate() -> Character.toCodePoint(c, last[++i])
        c.isSurrogate() -> -1
        else -> c.code
      }
      i++
      if (cp < 32 || cp in 127..159 || bidi(cp) || (cp < 128 && cp.toChar() in forbidden)) continue
      if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) { space = true; continue }
      if (space && clean.isNotEmpty()) clean.append(' ')
      space = false
      clean.appendCodePoint(cp)
    }
    return clean.toString().trim(' ', '.')
  }

  /** CON, NUL, COM1, LPT¹… also with an extension, as Windows reads them. */
  private fun device(stem: String): Boolean {
    val base = stem.substringBefore('.').trimEnd(' ').uppercase(Locale.ROOT)
    if (base in devices) return true
    val rest = if (base.startsWith("COM") || base.startsWith("LPT")) base.substring(3) else return false
    return rest.length == 1 && (rest[0] in '0'..'9' || rest[0] in "¹²³")
  }

  /** The same rules the PC applies to file.offer. */
  fun offerValid(name: String, size: Long, mime: String): Boolean =
    size in 1..MAX_BYTES && mime in MIME_TYPES && Protocol.validText(name, 255, true) && byExtension[extension(name)] == mime

  /** Decides from what the provider reported and the first bytes whether a document may join the queue. */
  fun check(displayName: String?, reportedMime: String?, size: Long, head: ByteArray, length: Int): Check {
    val clean = sanitize(displayName)
    val label = (if (clean.codePointCount(0, clean.length) > 80) clean.substring(0, clean.offsetByCodePoints(0, 80)) + "…" else clean).ifEmpty { "Arquivo" }
    val mime = mime(reportedMime, clean) ?: sniff(head, length)
    return when {
      mime == null -> Check.Rejected("$label: tipo não permitido. Envie PNG, JPG ou PDF.")
      size < 0 -> Check.Rejected("$label: o Android não informou o tamanho do arquivo.")
      size == 0L -> Check.Rejected("$label está vazio.")
      size > MAX_BYTES -> Check.Rejected("$label passa de 50 MB.")
      !signatureMatches(mime, head, length) -> Check.Rejected("$label não é um ${kinds.getValue(mime)} válido.")
      else -> Check.Ok(cleanName(displayName, mime), mime, size)
    }
  }

  fun megabytes(bytes: Long): String = String.format(ptBR, "%.1f", bytes / 1_048_576.0)

  /** "3,2 de 12,0 MB" */
  fun progressLabel(received: Long, size: Long): String = "${megabytes(received)} de ${megabytes(size)} MB"

  fun errorMessage(code: String?, fallback: String): String = when (code) {
    "file_type" -> "Tipo de arquivo não permitido. Envie PNG, JPG ou PDF."
    "file_busy" -> "O PC está recebendo outro arquivo. Tente de novo em instantes."
    "file_integrity" -> "O arquivo chegou corrompido ao PC. Tente de novo."
    "storage" -> "O PC não conseguiu salvar o arquivo. Verifique o espaço livre no Windows."
    "too_large" -> "O arquivo passa de 50 MB."
    "unavailable" -> "O PC não está recebendo arquivos agora."
    "unauthorized" -> "O PC não autorizou este envio."
    "rate_limited" -> "Muitos envios seguidos. Aguarde um pouco."
    "invalid_message" -> "O PC recusou o arquivo."
    "internal" -> "Falha no PC ao receber o arquivo."
    else -> fallback
  }

  /** True when the failure would repeat on the next file, so the queue waits for the user. */
  fun pausesQueue(code: String?): Boolean = code !in itemErrors
}

/** Files waiting their turn; one is sent at a time, and the one being sent counts toward the limit. */
internal class FileQueue(private val limit: Int = FileRules.MAX_QUEUE) {
  private val items = ArrayDeque<FileItem>()
  val size: Int get() = items.size
  val names: List<String> get() = items.map { it.name }

  fun isEmpty() = items.isEmpty()

  /** Free places, counting the file being sent and documents still being checked. */
  fun room(sending: Boolean, checking: Int) = (limit - items.size - (if (sending) 1 else 0) - checking).coerceAtLeast(0)

  /** False when full, or when the same document is already waiting. */
  fun add(item: FileItem, sending: Boolean): Boolean {
    if (room(sending, 0) == 0 || items.any { it.uri == item.uri }) return false
    items.addLast(item)
    return true
  }

  fun next(): FileItem? = items.removeFirstOrNull()

  fun clear() = items.clear()
}
