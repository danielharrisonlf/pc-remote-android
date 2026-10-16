package com.pcremote

import java.util.Locale

/**
 * A document accepted for sending; [uri] stays a string so the rules run on the JVM.
 * [legacy] is the files.v1 form (PNG, JPG or PDF up to 50 MB), or null when a PC
 * without files.v2 cannot take this document.
 */
internal data class FileItem(val uri: String, val name: String, val size: Long, val mime: String, val legacy: FileRules.Check.Ok? = null)

/**
 * files.v1 and files.v2 rules shared with the PC. The phone checks them first to
 * fail fast; the PC checks again.
 */
internal object FileRules {
  /** files.v2: any file up to 1 GiB. */
  const val MAX_BYTES = 1_073_741_824L
  /** files.v1: PNG, JPG or PDF up to 50 MB. */
  const val LEGACY_MAX_BYTES = 52_428_800L
  /** Free space a received file must leave on the phone. */
  const val FREE_MARGIN = 64L * 1024 * 1024
  const val FALLBACK_MIME = "application/octet-stream"
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
  /** Files Explorer acts on just by listing the folder; files.v2 refuses them on both sides. */
  private val shellActive = setOf("lnk", "url", "scf", "library-ms", "searchconnector-ms", "settingcontent-ms", "appref-ms", "website")

  sealed interface Check {
    /** [legacy]: the files.v1 form of the same document, when a files.v1 PC could take it. */
    data class Ok(val name: String, val mime: String, val size: Long, val legacy: Ok? = null) : Check
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

  /**
   * files.v2 name: the same cleaning as the PC, keeping whatever extension the
   * document has. Only a name without extension gains one, and only for the three
   * types whose extension is certain.
   */
  fun cleanAnyName(raw: String?, mime: String): String {
    val trimmed = sanitize(raw)
    val dot = trimmed.lastIndexOf('.')
    var stem = (if (dot > 0) trimmed.substring(0, dot) else trimmed).ifEmpty { "arquivo" }
    val ext = if (dot > 0) trimmed.substring(dot + 1).lowercase(Locale.ROOT) else canonical[mime].orEmpty()
    if (device(stem)) stem = "_$stem"
    if (stem.codePointCount(0, stem.length) > 100) stem = stem.substring(0, stem.offsetByCodePoints(0, 100))
    fun join(s: String) = if (ext.isEmpty()) s else "$s.$ext"
    while (stem.isNotEmpty() && join(stem).toByteArray(Charsets.UTF_8).size > 255) stem = stem.substring(0, stem.offsetByCodePoints(0, stem.codePointCount(0, stem.length) - 1))
    return join(stem.ifEmpty { "arquivo" })
  }

  /** files.v2 refuses Windows shell files such as shortcuts and folder settings. */
  fun blocked(name: String): Boolean = name.lowercase(Locale.ROOT) == "desktop.ini" || extension(name) in shellActive

  /** The provider's type in files.v2 form, else application/octet-stream. */
  fun anyMime(reported: String?): String =
    reported?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.takeIf { Protocol.mimeValid(it) } ?: FALLBACK_MIME

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

  /** The same rules the PC applies to file.offer: files.v2 with [twoWay], files.v1 otherwise. */
  fun offerValid(name: String, size: Long, mime: String, twoWay: Boolean = false): Boolean =
    if (twoWay) size in 1..MAX_BYTES && Protocol.mimeValid(mime) && Protocol.validText(name, 255, true) && !blocked(name)
    else size in 1..LEGACY_MAX_BYTES && mime in MIME_TYPES && Protocol.validText(name, 255, true) && byExtension[extension(name)] == mime

  private fun label(clean: String) = (if (clean.codePointCount(0, clean.length) > 80) clean.substring(0, clean.offsetByCodePoints(0, 80)) + "…" else clean).ifEmpty { "Arquivo" }

  /** files.v1: decides from what the provider reported and the first bytes whether a PNG, JPG or PDF may go. */
  fun check(displayName: String?, reportedMime: String?, size: Long, head: ByteArray, length: Int): Check {
    val clean = sanitize(displayName)
    val label = label(clean)
    val mime = mime(reportedMime, clean) ?: sniff(head, length)
    return when {
      mime == null -> Check.Rejected("$label: tipo não permitido. Envie PNG, JPG ou PDF.")
      size < 0 -> Check.Rejected("$label: o Android não informou o tamanho do arquivo.")
      size == 0L -> Check.Rejected("$label está vazio.")
      size > LEGACY_MAX_BYTES -> Check.Rejected("$label passa de 50 MB.")
      !signatureMatches(mime, head, length) -> Check.Rejected("$label não é um ${kinds.getValue(mime)} válido.")
      else -> Check.Ok(cleanName(displayName, mime), mime, size)
    }
  }

  /**
   * files.v2: any document up to 1 GB joins the queue. The files.v1 form rides
   * along in [Check.Ok.legacy] for a PC that has not been updated.
   */
  fun checkAny(displayName: String?, reportedMime: String?, size: Long, head: ByteArray, length: Int): Check {
    val clean = sanitize(displayName)
    val label = label(clean)
    val legacy = check(displayName, reportedMime, size, head, length) as? Check.Ok
    // A typeless name still gets the right extension when the content is a PNG, JPG or PDF.
    val mime = legacy?.mime ?: anyMime(reportedMime)
    val name = cleanAnyName(displayName, mime)
    return when {
      size < 0 -> Check.Rejected("$label: o Android não informou o tamanho do arquivo.")
      size == 0L -> Check.Rejected("$label está vazio.")
      size > MAX_BYTES -> Check.Rejected("$label passa de 1 GB.")
      blocked(name) -> Check.Rejected("$label não pode ser enviado: o Windows usa esse tipo de arquivo como atalho ou configuração.")
      else -> Check.Ok(name, mime, size, legacy)
    }
  }

  fun megabytes(bytes: Long): String = String.format(ptBR, "%.1f", bytes / 1_048_576.0)

  private fun gigabytes(bytes: Long): String = String.format(ptBR, "%.1f", bytes / 1_073_741_824.0)

  /** "3,2 de 12,0 MB"; from 1 GB on the total is in GB and the part sent switches when it gets there: "43,5 MB de 1,0 GB", "0,3 de 1,0 GB". */
  fun progressLabel(received: Long, size: Long): String = when {
    size < 1_073_741_824L -> "${megabytes(received)} de ${megabytes(size)} MB"
    received < 104_857_600L -> "${megabytes(received)} MB de ${gigabytes(size)} GB"
    else -> "${gigabytes(received)} de ${gigabytes(size)} GB"
  }

  /** "850 KB", "12,4 MB", "1,0 GB" for the file list. */
  fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "${gigabytes(bytes)} GB"
    bytes >= 1_048_576L -> "${megabytes(bytes)} MB"
    else -> "${(bytes + 1023) / 1024} KB"
  }

  /** "1 GB" or "50 MB": the limit a PC announced in files.maxBytes. */
  fun limitLabel(maxBytes: Long): String = if (maxBytes >= MAX_BYTES) "1 GB" else "${maxBytes / 1_048_576} MB"

  fun errorMessage(code: String?, fallback: String, twoWay: Boolean = false): String = when (code) {
    "file_type" -> if (twoWay) "O Windows não aceita receber esse tipo de arquivo." else "Tipo de arquivo não permitido. Envie PNG, JPG ou PDF."
    "file_busy" -> "O PC está recebendo outro arquivo. Tente de novo em instantes."
    "file_integrity" -> "O arquivo chegou corrompido ao PC. Tente de novo."
    "storage" -> "O PC não conseguiu salvar o arquivo. Verifique o espaço livre no Windows."
    "too_large" -> if (twoWay) "O arquivo passa de 1 GB." else "O arquivo passa de 50 MB."
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
