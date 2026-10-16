package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileRulesTest {
  private val pngHead = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
  private val jpegHead = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
  private fun pdfHead(offset: Int, size: Int = FileRules.HEAD_BYTES) = ByteArray(size).also { "%PDF-".toByteArray().copyInto(it, offset) }

  @Test fun namesFollowThePcSanitizerAndAlwaysCarryTheMatchingExtension() {
    assertEquals("IMG_0001.jpg", FileRules.cleanName("IMG_0001.JPG", "image/jpeg"))
    assertEquals("relatório final.pdf", FileRules.cleanName("relatório   final.pdf", "application/pdf"))
    assertEquals("foto.jpeg", FileRules.cleanName("foto.jpeg", "image/jpeg"))
    assertEquals("IMG_20261016.jpg", FileRules.cleanName("IMG_20261016", "image/jpeg"))
    assertEquals("Captura 2026.10.16.png", FileRules.cleanName("Captura 2026.10.16", "image/png"))
    assertEquals("foto.png.jpg", FileRules.cleanName("foto.png", "image/jpeg"))
    assertEquals("x.png", FileRules.cleanName("pasta/sub\\x.png", "image/png"))
    assertEquals("abc.png", FileRules.cleanName("  a\u0000b<c>:*?.png. ", "image/png"))
    assertEquals("arquivo.pdf", FileRules.cleanName("...", "application/pdf"))
    assertEquals("arquivo.pdf", FileRules.cleanName(null, "application/pdf"))
    assertEquals("ab.jpg", FileRules.cleanName("a\uD800b\t.jpg", "image/jpeg"))
    // A right-to-left override could show "gpj.exe" as "exe.jpg".
    assertEquals("fotoexe.jpg", FileRules.cleanName("foto‮exe.jpg", "image/jpeg"))
    assertEquals("_con.png", FileRules.cleanName("con.png", "image/png"))
    assertEquals("_com1.pdf", FileRules.cleanName("com1.pdf", "application/pdf"))
    assertEquals("COM10.pdf", FileRules.cleanName("COM10.pdf", "application/pdf"))
  }

  @Test fun longNamesKeepAHundredCharactersAndFitTheByteLimit() {
    val ascii = FileRules.cleanName("a".repeat(300) + ".pdf", "application/pdf")
    assertEquals("a".repeat(100) + ".pdf", ascii)
    val emoji = FileRules.cleanName("🙂".repeat(120) + ".png", "image/png")
    assertTrue(emoji.toByteArray(Charsets.UTF_8).size <= 255)
    assertTrue(emoji.endsWith(".png"))
    assertTrue(Protocol.validText(emoji, 255, true))
    assertTrue(FileRules.offerValid(emoji, 1, "image/png"))
  }

  @Test fun mimeComesFromTheProviderThenTheExtension() {
    assertEquals("image/jpeg", FileRules.mime("IMAGE/JPEG", "x"))
    assertEquals("image/png", FileRules.mime("image/png; charset=binary", "x"))
    assertEquals("application/pdf", FileRules.mime(null, "x.PDF"))
    assertEquals("image/png", FileRules.mime("application/octet-stream", "x.png"))
    assertNull(FileRules.mime("image/heic", "x.heic"))
    assertNull(FileRules.mime(null, "sem extensão"))
  }

  @Test fun signaturesMatchThePcChecks() {
    assertTrue(FileRules.signatureMatches("image/png", pngHead, pngHead.size))
    assertFalse(FileRules.signatureMatches("image/png", pngHead, 7))
    assertTrue(FileRules.signatureMatches("image/jpeg", jpegHead, 3))
    assertFalse(FileRules.signatureMatches("image/jpeg", pngHead, pngHead.size))
    assertTrue(FileRules.signatureMatches("application/pdf", pdfHead(0), 1024))
    assertTrue(FileRules.signatureMatches("application/pdf", pdfHead(1019), 1024))
    assertFalse(FileRules.signatureMatches("application/pdf", pdfHead(1020, 2048), 2048))
    assertFalse(FileRules.signatureMatches("application/pdf", pdfHead(10), 12))
    assertFalse(FileRules.signatureMatches("image/gif", pngHead, pngHead.size))
    assertEquals("image/png", FileRules.sniff(pngHead, pngHead.size))
    assertNull(FileRules.sniff(byteArrayOf(1, 2, 3), 3))
  }

  @Test fun offersFollowTheFileOfferRules() {
    assertTrue(FileRules.offerValid("foto.jpg", 1, "image/jpeg"))
    assertTrue(FileRules.offerValid("foto.jpeg", FileRules.LEGACY_MAX_BYTES, "image/jpeg"))
    assertFalse(FileRules.offerValid("foto.jpg", 0, "image/jpeg"))
    assertFalse(FileRules.offerValid("foto.jpg", FileRules.LEGACY_MAX_BYTES + 1, "image/jpeg"))
    assertFalse(FileRules.offerValid("foto.png", 10, "image/jpeg"))
    assertFalse(FileRules.offerValid("anim.gif", 10, "image/gif"))
    assertFalse(FileRules.offerValid("", 10, "image/png"))
    assertFalse(FileRules.offerValid("a\nb.png", 10, "image/png"))
    assertFalse(FileRules.offerValid("a".repeat(252) + ".png", 10, "image/png"))
  }

  @Test fun checkAcceptsMatchingDocumentsAndExplainsRejections() {
    assertEquals(FileRules.Check.Ok("foto.jpg", "image/jpeg", 2048), FileRules.check("foto.jpg", "image/jpeg", 2048, jpegHead, jpegHead.size))
    // No type and no extension: the content decides.
    assertEquals(FileRules.Check.Ok("Scan.png", "image/png", 10), FileRules.check("Scan", null, 10, pngHead, pngHead.size))
    assertEquals(FileRules.Check.Rejected("foto.heic: tipo não permitido. Envie PNG, JPG ou PDF."), FileRules.check("foto.heic", "image/heic", 10, jpegHead, 1))
    assertEquals(FileRules.Check.Rejected("doc.pdf está vazio."), FileRules.check("doc.pdf", "application/pdf", 0, pdfHead(0), 0))
    assertEquals(FileRules.Check.Rejected("doc.pdf passa de 50 MB."), FileRules.check("doc.pdf", "application/pdf", FileRules.LEGACY_MAX_BYTES + 1, pdfHead(0), 1024))
    assertEquals(FileRules.Check.Rejected("doc.pdf: o Android não informou o tamanho do arquivo."), FileRules.check("doc.pdf", "application/pdf", -1, pdfHead(0), 1024))
    assertEquals(FileRules.Check.Rejected("foto.png não é um PNG válido."), FileRules.check("foto.png", "image/png", 10, jpegHead, jpegHead.size))
  }

  @Test fun progressUsesBrazilianDecimals() {
    assertEquals("3,2 de 12,0 MB", FileRules.progressLabel(3_355_443, 12_582_912))
    assertEquals("0,0 de 50,0 MB", FileRules.progressLabel(0, FileRules.LEGACY_MAX_BYTES))
  }

  @Test fun serverCodesBecomeClearMessagesAndOnlySystemicOnesPauseTheQueue() {
    assertEquals("Tipo de arquivo não permitido. Envie PNG, JPG ou PDF.", FileRules.errorMessage("file_type", "x"))
    assertEquals("O PC não conseguiu salvar o arquivo. Verifique o espaço livre no Windows.", FileRules.errorMessage("storage", "x"))
    assertEquals("Conexão interrompida.", FileRules.errorMessage(null, "Conexão interrompida."))
    assertEquals("Mensagem nova", FileRules.errorMessage("future_code", "Mensagem nova"))
    for (code in listOf("file_type", "too_large", "file_integrity", "invalid_message")) assertFalse(code, FileRules.pausesQueue(code))
    for (code in listOf("unavailable", "file_busy", "storage", "unauthorized", "internal", null)) assertTrue("$code", FileRules.pausesQueue(code))
  }

  @Test fun queueKeepsOrderRejectsDuplicatesAndCountsTheFileBeingSent() {
    val queue = FileQueue(limit = 3)
    fun item(n: Int) = FileItem("content://docs/$n", "f$n.png", 10, "image/png")
    assertTrue(queue.add(item(1), sending = false))
    assertFalse(queue.add(item(1), sending = false))
    assertTrue(queue.add(item(2), sending = true))
    assertEquals(0, queue.room(sending = true, checking = 0))
    assertFalse(queue.add(item(3), sending = true))
    assertEquals(1, queue.room(sending = false, checking = 0))
    assertEquals(0, queue.room(sending = false, checking = 1))
    assertEquals(listOf("f1.png", "f2.png"), queue.names)
    assertEquals(item(1), queue.next())
    assertEquals(item(2), queue.next())
    assertNull(queue.next())
    assertTrue(queue.isEmpty())
    assertEquals(20, FileQueue().room(sending = false, checking = 0))
  }

  @Test fun twoWayNamesKeepAnyExtensionAndStillFollowThePcSanitizer() {
    assertEquals("Viagem 2026.mp4", FileRules.cleanAnyName("Viagem   2026.MP4", "video/mp4"))
    assertEquals("notas", FileRules.cleanAnyName("notas", FileRules.FALLBACK_MIME))
    assertEquals("Scan.png", FileRules.cleanAnyName("Scan", "image/png"))
    assertEquals("backup.tar.gz", FileRules.cleanAnyName("pasta/backup.tar.gz", "application/gzip"))
    assertEquals("_con.zip", FileRules.cleanAnyName("con.zip", "application/zip"))
    assertEquals("setup.exe", FileRules.cleanAnyName("setup.exe", "application/vnd.microsoft.portable-executable"))
    assertEquals("arquivo", FileRules.cleanAnyName("...", FileRules.FALLBACK_MIME))
    assertEquals("fotoexe.jpg", FileRules.cleanAnyName("foto‮exe.jpg", "image/jpeg"))
    val long = FileRules.cleanAnyName("🙂".repeat(120) + ".mkv", "video/x-matroska")
    assertTrue(long.toByteArray(Charsets.UTF_8).size <= 255)
    assertTrue(long.endsWith(".mkv"))
  }

  @Test fun twoWayRefusesOnlyShellFilesAndChecksTheMimeSyntax() {
    for (name in listOf("atalho.lnk", "site.URL", "x.scf", "b.library-ms", "c.searchconnector-ms", "d.settingcontent-ms", "e.appref-ms", "f.website", "desktop.ini", "Desktop.INI"))
      assertTrue(name, FileRules.blocked(name))
    for (name in listOf("setup.exe", "script.ps1", "lnk", "desktop.ini.txt", "notas")) assertFalse(name, FileRules.blocked(name))
    assertEquals("video/mp4", FileRules.anyMime("VIDEO/MP4; codecs=avc1"))
    assertEquals(FileRules.FALLBACK_MIME, FileRules.anyMime(null))
    assertEquals(FileRules.FALLBACK_MIME, FileRules.anyMime("not a mime"))
    assertTrue(FileRules.offerValid("filme.mkv", FileRules.MAX_BYTES, "video/x-matroska", twoWay = true))
    assertFalse(FileRules.offerValid("filme.mkv", FileRules.MAX_BYTES + 1, "video/x-matroska", twoWay = true))
    assertFalse(FileRules.offerValid("filme.mkv", 10, "Video/MKV", twoWay = true))
    assertFalse(FileRules.offerValid("atalho.lnk", 10, FileRules.FALLBACK_MIME, twoWay = true))
    assertTrue(FileRules.offerValid("documento.exe", 10, "application/pdf", twoWay = true))
    assertFalse(FileRules.offerValid("documento.exe", 10, "application/pdf"))
  }

  @Test fun anyDocumentJoinsTheQueueWithItsFilesV1FormWhenThereIsOne() {
    val movie = FileRules.checkAny("Viagem.mp4", "video/mp4", 734_003_200, byteArrayOf(0, 0, 0, 0x18), 4) as FileRules.Check.Ok
    assertEquals(FileRules.Check.Ok("Viagem.mp4", "video/mp4", 734_003_200), movie)
    val photo = FileRules.checkAny("foto.jpg", "image/jpeg", 2048, jpegHead, jpegHead.size) as FileRules.Check.Ok
    assertEquals(FileRules.Check.Ok("foto.jpg", "image/jpeg", 2048), photo.legacy)
    // A typeless name gets the extension the content proves.
    assertEquals("Scan.png", (FileRules.checkAny("Scan", null, 10, pngHead, pngHead.size) as FileRules.Check.Ok).name)
    // Too big or disguised for files.v1: no legacy form, still fine for files.v2.
    assertNull((FileRules.checkAny("doc.pdf", "application/pdf", FileRules.LEGACY_MAX_BYTES + 1, pdfHead(0), 1024) as FileRules.Check.Ok).legacy)
    assertNull((FileRules.checkAny("foto.png", "image/png", 10, jpegHead, jpegHead.size) as FileRules.Check.Ok).legacy)
    assertEquals(FileRules.Check.Rejected("filme.mkv passa de 1 GB."), FileRules.checkAny("filme.mkv", "video/x-matroska", FileRules.MAX_BYTES + 1, ByteArray(4), 4))
    assertEquals(FileRules.Check.Rejected("vazio.txt está vazio."), FileRules.checkAny("vazio.txt", "text/plain", 0, ByteArray(0), 0))
    assertTrue(FileRules.checkAny("atalho.lnk", null, 10, ByteArray(4), 4) is FileRules.Check.Rejected)
  }

  @Test fun sizesAndLimitsReadNaturally() {
    assertEquals("0,3 de 1,0 GB", FileRules.progressLabel(322_122_547, FileRules.MAX_BYTES))
    // Early in a large file the part sent stays readable instead of "0,0 de 1,0 GB".
    assertEquals("43,5 MB de 1,0 GB", FileRules.progressLabel(45_612_646, FileRules.MAX_BYTES))
    assertEquals("0,0 MB de 1,0 GB", FileRules.progressLabel(0, FileRules.MAX_BYTES))
    assertEquals("12,4 MB", FileRules.sizeLabel(13_002_342))
    assertEquals("1,0 GB", FileRules.sizeLabel(FileRules.MAX_BYTES))
    assertEquals("2 KB", FileRules.sizeLabel(2048))
    assertEquals("1 GB", FileRules.limitLabel(FileRules.MAX_BYTES))
    assertEquals("50 MB", FileRules.limitLabel(FileRules.LEGACY_MAX_BYTES))
    assertEquals("O arquivo passa de 1 GB.", FileRules.errorMessage("too_large", "x", twoWay = true))
    assertEquals("O arquivo passa de 50 MB.", FileRules.errorMessage("too_large", "x"))
  }
}
