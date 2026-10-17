package com.pcremote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.StatFs
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

/**
 * clipboard.v2 rules shared with the PC (see PROTOCOL.md). Pure, so they run on
 * the JVM; [ClipImageFiles] does the Android side.
 */
internal object ClipImages {
  const val MAX_BYTES = 33_554_432L
  const val MAX_PIXELS = 50_000_000L
  /** Other formats are decoded whole to become PNG; this keeps the bitmap near 64 MB. */
  const val MAX_CONVERT_PIXELS = 16_000_000L
  val OFFER_MIMES = setOf("image/png", "image/jpeg")
  /** Tracker values: text as is, an image as its uri behind a NUL, which valid text never contains. */
  private const val IMAGE = "\u0000image\u0000"

  fun offerValid(name: String, size: Long, mime: String): Boolean =
    size in 1..MAX_BYTES && mime in OFFER_MIMES && Protocol.validText(name, 255, true)

  /**
   * A push the phone takes: a PNG within the limits; an automatic one only with sync
   * on and not older than the clipboard revision the phone already saw.
   */
  fun pushAccepted(push: Incoming.FilePush, realtime: Boolean, seenRevision: Long): Boolean {
    val clip = push.clipboard ?: return false
    return push.mime == "image/png" && push.size in 1..MAX_BYTES && (!clip.automatic || (realtime && clip.revision >= seenRevision))
  }

  sealed interface Plan {
    /** PNG or JPEG goes byte for byte. */
    data class AsIs(val mime: String) : Plan
    /** WebP, GIF, HEIC, BMP…: decoded and sent as PNG. */
    data object Convert : Plan
    data class Refused(val message: String) : Plan
  }

  /** [signature] is image/png or image/jpeg when the first bytes say so; [size] is -1 when unknown. */
  fun plan(signature: String?, width: Int, height: Int, size: Long): Plan {
    if (width <= 0 || height <= 0) return Plan.Refused("O Android não conseguiu ler a imagem copiada.")
    val pixels = width.toLong() * height
    return when {
      signature != null && pixels > MAX_PIXELS -> Plan.Refused("A imagem copiada passa de 50 megapixels.")
      signature != null && size > MAX_BYTES -> Plan.Refused("A imagem copiada passa de 32 MB.")
      signature != null -> Plan.AsIs(signature)
      pixels > MAX_CONVERT_PIXELS -> Plan.Refused("A imagem copiada é grande demais para enviar ao PC.")
      else -> Plan.Convert
    }
  }

  fun imageKey(uri: String) = IMAGE + uri
  fun imageUri(key: String?): String? = key?.takeIf { it.startsWith(IMAGE) }?.substring(IMAGE.length)

  /** A content:// this app wrote for an image from the PC: never sent back. */
  fun own(uri: String, authority: String) = uri.startsWith("content://$authority/")

  fun errorMessage(code: String?, fallback: String): String = when (code) {
    "too_large" -> "A imagem passa de 32 MB."
    "file_type" -> "O PC não conseguiu ler a imagem."
    "conflict" -> "Houve outra cópia no PC antes desta."
    "unavailable" -> "O PC não aceitou a imagem agora."
    else -> FileRules.errorMessage(code, fallback, twoWay = true)
  }
}

/** Serves the images from the PC; only the cache/clipboard folder is reachable (res/xml/clipboard_paths.xml). */
class ClipImageProvider : FileProvider()

/**
 * Files behind clipboard images: what came from the PC lives in cache/clipboard
 * (only the latest is kept) and is shared through [ClipImageProvider]; what goes to
 * the PC is copied to cache/clipboard-out first, so the upload does not depend on a
 * grant that ends when the clipboard changes.
 */
internal class ClipImageFiles(private val app: Context) {
  val authority = "${app.packageName}.clipboard"
  val received = File(app.cacheDir, "clipboard")
  private val outgoing = File(app.cacheDir, "clipboard-out")
  val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "pc-remote-clip-image").apply { isDaemon = true } }

  // Copies left behind by a process that died mid-upload.
  init { worker.execute { outgoing.listFiles()?.forEach { it.delete() } } }

  sealed interface Prepared {
    data class Ready(val file: File, val name: String, val mime: String, val size: Long) : Prepared
    data class Refused(val message: String) : Prepared
  }

  fun uriFor(file: File): Uri = FileProvider.getUriForFile(app, authority, file)

  /** The folder a push writes to, with room for it. */
  fun receiveFolder(size: Long): File? {
    if (!received.isDirectory && !received.mkdirs()) return null
    return received.takeIf { StatFs(it.path).availableBytes >= size + FileRules.FREE_MARGIN }
  }

  /** Only the image now on the clipboard stays. */
  fun keepOnly(file: File) {
    received.listFiles()?.forEach { if (it != file) it.delete() }
  }

  /** Worker thread: PNG and JPEG are copied as they are, other formats become PNG. */
  fun prepare(uri: Uri): Prepared {
    val resolver = app.contentResolver
    return try {
      val head = ByteArray(16)
      var read = 0
      (resolver.openInputStream(uri) ?: throw IOException("open")).use { while (read < head.size) { val n = it.read(head, read, head.size - read); if (n < 0) break; read += n } }
      val signature = ClipImages.OFFER_MIMES.firstOrNull { FileRules.signatureMatches(it, head, read) }
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
      val size = try { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (_: Exception) { -1L }
      if (!outgoing.isDirectory && !outgoing.mkdirs()) throw IOException("folder")
      when (val plan = ClipImages.plan(signature, bounds.outWidth, bounds.outHeight, size)) {
        is ClipImages.Plan.Refused -> Prepared.Refused(plan.message)
        is ClipImages.Plan.AsIs -> copy(uri, plan.mime)
        ClipImages.Plan.Convert -> convert(uri)
      }
    } catch (_: SecurityException) {
      Prepared.Refused("O Android não deu permissão para ler a imagem copiada.")
    } catch (_: OutOfMemoryError) {
      Prepared.Refused("A imagem copiada é grande demais para enviar ao PC.")
    } catch (_: Exception) {
      Prepared.Refused("Não foi possível ler a imagem copiada.")
    }
  }

  private fun copy(uri: Uri, mime: String): Prepared {
    val ext = if (mime == "image/png") "png" else "jpg"
    val file = File(outgoing, "${UUID.randomUUID()}.$ext")
    var total = 0L
    try {
      (app.contentResolver.openInputStream(uri) ?: throw IOException("open")).use { input ->
        FileOutputStream(file).use { output ->
          val buffer = ByteArray(65536)
          while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > ClipImages.MAX_BYTES) { file.delete(); return Prepared.Refused("A imagem copiada passa de 32 MB.") }
            output.write(buffer, 0, n)
          }
        }
      }
    } catch (e: Exception) { file.delete(); throw e }
    if (total == 0L) { file.delete(); return Prepared.Refused("A imagem copiada está vazia.") }
    return Prepared.Ready(file, "Imagem.$ext", mime, total)
  }

  private fun convert(uri: Uri): Prepared {
    val bitmap = app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
      ?: return Prepared.Refused("O Android não conseguiu ler a imagem copiada.")
    val file = File(outgoing, "${UUID.randomUUID()}.png")
    try {
      FileOutputStream(file).use { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("png") }
    } catch (e: Exception) { file.delete(); throw e } finally { bitmap.recycle() }
    val size = file.length()
    if (size !in 1..ClipImages.MAX_BYTES) { file.delete(); return Prepared.Refused("A imagem copiada passa de 32 MB em PNG.") }
    return Prepared.Ready(file, "Imagem.png", "image/png", size)
  }
}
