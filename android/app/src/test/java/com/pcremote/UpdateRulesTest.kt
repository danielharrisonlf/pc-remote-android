package com.pcremote

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {
  private val github = UpdateOrigin.GITHUB
  private val download = "https://github.com/danielharrisonlf/pc-remote-android/releases/download/v0.5.0-kotlin/"
  private val v050 = AppVersion(0, 5, 0)
  private val v051 = AppVersion(0, 5, 1)

  @Test fun installResultsRequireTheCommittedSessionAndPrivateToken() {
    val attempt = UpdateInstallAttempt(42, "private-token")
    assertTrue(attempt.matches(42, "private-token"))
    assertFalse(attempt.matches(43, "private-token"))
    assertFalse(attempt.matches(42, "forged-token"))
    assertFalse(attempt.matches(42, null))
    assertFalse(UpdateInstallAttempt(-1, null).matches(-1, null))
    assertFalse(UpdateInstallAttempt(42, "").matches(42, ""))
  }

  @Test fun assetsMustBeReleaseFilesOfThisRepository() {
    assertTrue(github.allowsAsset("${download}pc-remote-android-kotlin-0.5.0-evaluation.apk"))
    assertTrue(github.allowsAsset("${download}KOTLIN-SHA256SUMS-0.5.0.txt"))
    assertFalse(github.allowsAsset("https://github.com/someone/pc-remote-android/releases/download/v0.5.0-kotlin/app.apk"))
    assertFalse(github.allowsAsset("${download}../../../../evil/repo/releases/download/x/app.apk"))
    assertFalse(github.allowsAsset("${download}%2e%2e/app.apk"))
    assertFalse(github.allowsAsset("${download}app.apk?x=1"))
    assertFalse(github.allowsAsset("${download}app.apk#x"))
    assertFalse(github.allowsAsset(download))
    assertFalse(github.allowsAsset("http://github.com/danielharrisonlf/pc-remote-android/releases/download/v0.5.0-kotlin/app.apk"))
    assertFalse(github.allowsAsset("https://objects.githubusercontent.com/app.apk"))
  }

  @Test fun redirectsStayOnGitHubOverTls() {
    assertTrue(github.allowsHop("https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sp=r&sig=abc"))
    assertTrue(github.allowsHop("https://objects.githubusercontent.com/github-production-release-asset-2e65be/1"))
    assertTrue(github.allowsHop("https://GITHUB.com/danielharrisonlf/pc-remote-android/releases/download/v1/app.apk"))
    assertTrue(github.allowsHop("https://github.com:443/x"))
    assertFalse(github.allowsHop("http://objects.githubusercontent.com/x"))
    assertFalse(github.allowsHop("https://github.com:8443/x"))
    assertFalse(github.allowsHop("https://evil.example/x"))
    assertFalse(github.allowsHop("https://github.com.evil.example/x"))
    assertFalse(github.allowsHop("https://user@github.com/x"))
    assertFalse(github.allowsHop("/relative/path"))
    assertFalse(github.allowsHop("not a url"))
  }

  @Test fun debugOriginIsOneServer() {
    val origin = UpdateOrigin.debug(Char(0xFEFF) + "# fake GitHub\n\n  http://10.0.2.2:8765/repos/x/releases  \n")!!
    assertEquals("http://10.0.2.2:8765/repos/x/releases", origin.releasesUrl)
    assertEquals("http://10.0.2.2:8765/", origin.assetPrefix)
    assertTrue(origin.allowsAsset("http://10.0.2.2:8765/x/releases/download/v0.5.1-kotlin/app.apk"))
    assertFalse(origin.allowsHop("http://10.0.2.2:8766/x"))
    assertFalse(origin.allowsHop("https://10.0.2.2:8765/x"))
    assertFalse(origin.allowsHop("https://github.com/x"))
    assertNull(UpdateOrigin.debug(""))
    assertNull(UpdateOrigin.debug("# only a comment"))
    assertNull(UpdateOrigin.debug("ftp://10.0.2.2/releases"))
    assertNull(UpdateOrigin.debug("http://user:pw@10.0.2.2/releases"))
    assertNull(UpdateOrigin.debug("10.0.2.2:8765/releases"))
  }

  @Test fun sumsFollowTheSha256sumFormat() {
    val apk = "918b7958c5990f4ca48abedf962720bf6ac5572e4e8188ed6c676b6b08ba342b"
    val other = "3729F3CC9E653E926B5BB77DE6A50C020E4160DBE5D1FBAEA80D5A4181A21B40"
    val text = "$apk  pc-remote-android-kotlin-0.4.0-evaluation.apk\r\n$other *pc-remote-android-kotlin-0.4.0-unminified-evaluation.apk\r\n" +
      "# comment\nnot a line\n${apk.take(63)}  short.apk\n$apk\tTAB.apk\n"
    val sums = Sha256Sums.parse(text)
    assertEquals(mapOf("pc-remote-android-kotlin-0.4.0-evaluation.apk" to apk, "pc-remote-android-kotlin-0.4.0-unminified-evaluation.apk" to other.lowercase()), sums)
  }

  @Test fun aNameListedWithTwoHashesIsDropped() {
    val a = "a".repeat(64)
    val b = "b".repeat(64)
    assertEquals(mapOf("same.apk" to a), Sha256Sums.parse("$a  app.apk\n$b  app.apk\n$a  same.apk\n$a  same.apk\n"))
  }

  @Test fun shaMustMatchThePublishedLine() {
    val hash = "c".repeat(64)
    assertNull(UpdateCheck.sha(hash, hash.uppercase()))
    assertEquals("A release não traz o SHA-256 deste APK.", UpdateCheck.sha(null, hash))
    assertNotNull(UpdateCheck.sha(hash, "d".repeat(64)))
  }

  @Test fun archiveMustBeTheSameAppNewerAndSameSigner() {
    val installed = ApkIdentity("com.pcremote.nativeandroid", 9, setOf("70c09aad"))
    val good = installed.copy(versionCode = 10)
    assertNull(UpdateCheck.archive(installed, good))
    assertEquals("O arquivo baixado não é um APK válido.", UpdateCheck.archive(installed, null))
    assertNotNull(UpdateCheck.archive(installed, good.copy(packageName = "com.example.other")))
    assertNotNull(UpdateCheck.archive(installed, good.copy(versionCode = 9)))
    assertNotNull(UpdateCheck.archive(installed, good.copy(versionCode = 8)))
    assertTrue(UpdateCheck.archive(installed, good.copy(signers = setOf("deadbeef")))!!.contains("assinatura"))
    assertNotNull(UpdateCheck.archive(installed, good.copy(signers = emptySet())))
    assertNotNull(UpdateCheck.archive(installed, good.copy(signers = setOf("70c09aad", "deadbeef"))))
    assertNotNull(UpdateCheck.archive(null, good))
    assertNotNull(UpdateCheck.archive(installed.copy(signers = emptySet()), good.copy(signers = emptySet())))
  }

  @Test fun cleanupKeepsOnlyNewerCompleteDownloads() {
    val names = listOf("0.5.1.apk", "0.5.1.apk.part", "0.5.0.apk", "0.4.9.apk", "0.10.0.apk", "notes.txt")
    assertEquals(listOf("0.5.1.apk.part", "0.5.0.apk", "0.4.9.apk", "notes.txt"), UpdateCheck.staleFiles(names, v050))
    assertEquals(names, UpdateCheck.staleFiles(names, null))
    assertEquals("0.5.1.apk", UpdateCheck.fileName(v051))
  }

  @Test fun installFailuresHaveWords() {
    assertEquals("Instalação cancelada.", UpdateCheck.installFailure(PackageInstaller.STATUS_FAILURE_ABORTED))
    assertEquals("Falta espaço para instalar a atualização.", UpdateCheck.installFailure(PackageInstaller.STATUS_FAILURE_STORAGE))
    assertEquals("O Android não instalou a atualização.", UpdateCheck.installFailure(99))
  }

  @Test fun automaticDownloadStopsAtReady() {
    var (flow, command) = UpdateFlows.request(UpdateFlow.Idle, v051, install = false)
    assertEquals(UpdateFlow.Downloading(v051, 0, 0, false), flow)
    assertEquals(UpdateCommand.DOWNLOAD, command)
    flow = UpdateFlows.progress(flow, 50, 100)
    assertEquals(UpdateFlow.Downloading(v051, 50, 100, false), flow)
    flow = UpdateFlows.downloaded(flow)
    assertEquals(UpdateFlow.Verifying(v051, false), flow)
    val verified = UpdateFlows.verified(flow)
    assertEquals(UpdateFlow.Ready(v051) to UpdateCommand.NONE, verified)
    // Only the tap installs.
    assertEquals(UpdateFlow.Ready(v051) to UpdateCommand.NONE, UpdateFlows.request(verified.first, v051, install = false))
    assertEquals(UpdateFlow.Installing(v051) to UpdateCommand.INSTALL, UpdateFlows.request(verified.first, v051, install = true))
  }

  @Test fun aTapDuringTheDownloadInstallsAfterVerification() {
    val downloading = UpdateFlow.Downloading(v051, 10, 100, false)
    val (tapped, command) = UpdateFlows.request(downloading, v051, install = true)
    assertEquals(downloading.copy(install = true), tapped)
    assertEquals(UpdateCommand.NONE, command)
    assertEquals(UpdateFlow.Installing(v051) to UpdateCommand.INSTALL, UpdateFlows.verified(UpdateFlows.downloaded(tapped)))
    // "Agora não" keeps the download but drops the install.
    assertEquals(UpdateFlow.Ready(v051) to UpdateCommand.NONE, UpdateFlows.verified(UpdateFlows.downloaded(UpdateFlows.keep(tapped))))
    assertEquals(UpdateFlow.Verifying(v051, false), UpdateFlows.keep(UpdateFlow.Verifying(v051, true)))
  }

  @Test fun sessionResults() {
    val installing = UpdateFlow.Installing(v051)
    val confirming = UpdateFlows.session(installing, PackageInstaller.STATUS_PENDING_USER_ACTION)
    assertEquals(UpdateFlow.Installing(v051, confirming = true), confirming)
    assertEquals(confirming to UpdateCommand.NONE, UpdateFlows.request(confirming, v051, install = true))
    assertEquals(UpdateFlow.Ready(v051), UpdateFlows.session(confirming, PackageInstaller.STATUS_FAILURE_ABORTED))
    assertEquals(UpdateFlow.Idle, UpdateFlows.session(installing, PackageInstaller.STATUS_SUCCESS))
    assertEquals(UpdateFlow.Failed(v051, "A atualização conflita com o app instalado."), UpdateFlows.session(installing, PackageInstaller.STATUS_FAILURE_CONFLICT))
    // Results for no session in progress change nothing.
    assertEquals(UpdateFlow.Idle, UpdateFlows.session(UpdateFlow.Idle, PackageInstaller.STATUS_SUCCESS))
  }

  @Test fun failuresAndOtherVersionsStartOver() {
    val failed = UpdateFlows.failed(UpdateFlow.Downloading(v051, 1, 2, true), "Falha no download.")
    assertEquals(UpdateFlow.Failed(v051, "Falha no download."), failed)
    assertEquals(UpdateCommand.DOWNLOAD, UpdateFlows.request(failed, v051, install = true).second)
    val newer = AppVersion(0, 5, 2)
    assertEquals(UpdateFlow.Downloading(newer, 0, 0, false) to UpdateCommand.DOWNLOAD, UpdateFlows.request(UpdateFlow.Ready(v051), newer, install = false))
    assertEquals(UpdateFlow.Idle, UpdateFlows.failed(UpdateFlow.Idle, "x"))
  }

  private fun offer(flow: UpdateFlow): UpdateState {
    val release = Release("v0.5.1-kotlin", v051, "Versão 0.5.1", "", null, "https://github.com/x",
      ReleaseAsset("a.apk", 1, "${download}a.apk"), ReleaseAsset("KOTLIN-SHA256SUMS-0.5.1.txt", 1, "${download}s.txt"))
    return UpdateState(lookup = ReleaseLookup.Found(release), installed = v050, flow = flow)
  }

  @Test fun bannerAndButtonFollowTheFlow() {
    assertEquals("Versão 0.5.1 disponível", UpdateLabels.banner(offer(UpdateFlow.Idle)).first)
    assertEquals("Atualizar agora" to false, UpdateLabels.action(offer(UpdateFlow.Idle)))
    assertNull(UpdateLabels.progress(offer(UpdateFlow.Idle)))
    assertEquals("Baixando a versão 0.5.1… 25%", UpdateLabels.banner(offer(UpdateFlow.Downloading(v051, 25, 100, false))).first)
    assertEquals("Baixando… 25%" to true, UpdateLabels.action(offer(UpdateFlow.Downloading(v051, 25, 100, true))))
    val ready = offer(UpdateFlow.Ready(v051))
    assertTrue(ready.ready)
    assertEquals("Atualização pronta — toque para instalar", UpdateLabels.banner(ready).first)
    assertEquals("Instalar agora" to false, UpdateLabels.action(ready))
    assertEquals("Instalando…" to true, UpdateLabels.action(offer(UpdateFlow.Installing(v051))))
    assertEquals("Instalar agora" to false, UpdateLabels.action(offer(UpdateFlow.Installing(v051, confirming = true))))
    assertEquals("Falha no download.", UpdateLabels.progress(offer(UpdateFlow.Failed(v051, "Falha no download."))))
    // A flow for another version does not describe this offer.
    val stale = offer(UpdateFlow.Ready(AppVersion(0, 4, 9)))
    assertFalse(stale.ready)
    assertEquals("Versão 0.5.1 disponível", UpdateLabels.banner(stale).first)
    assertNull(UpdateLabels.status(stale))
  }
}
