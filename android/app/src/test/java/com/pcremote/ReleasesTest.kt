package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
  private fun release(
    tag: String,
    assets: String = "[]",
    draft: Boolean = false,
    page: String = "https://github.com/danielharrisonlf/pc-remote-android/releases/tag/$tag",
  ) = """{"tag_name":"$tag","name":"","body":"- Item\r\n","draft":$draft,"prerelease":true,""" +
    """"published_at":"2026-10-01T12:00:00Z","html_url":"$page","assets":$assets}"""

  private fun list(vararg items: String) = items.joinToString(",", "[", "]")

  @Test fun onlyPublishedKotlinTagsCount() {
    val releases = Releases.parse(list(release("v0.2.1"), release("v0.2.4-kotlin"), release("v0.3.0-kotlin"), release("v9.0.0-kotlin", draft = true)))
    assertEquals(listOf("v0.2.4-kotlin", "v0.3.0-kotlin"), releases.map { it.tag })
    val newest = Releases.newest(releases)!!
    assertEquals(AppVersion(0, 3, 0), newest.version)
    assertEquals("Versão 0.3.0", newest.title)
    assertEquals("- Item", newest.notes)
  }

  @Test fun pagesOutsideGitHubAreDropped() {
    assertTrue(Releases.parse(list(release("v0.3.0-kotlin", page = "https://example.com/release"))).isEmpty())
  }

  @Test fun picksTheReleaseApkAndSkipsTheUnminifiedBuild() {
    val assets = listOf(
      ReleaseAsset("pc-remote-kotlin-unminified.apk", 2_000_000, "https://github.com/a"),
      ReleaseAsset("notes.txt", 10, "https://github.com/b"),
      ReleaseAsset("pc-remote-kotlin-0.3.0.apk", 600_000, "https://github.com/c"),
    )
    assertEquals("pc-remote-kotlin-0.3.0.apk", Releases.pickApk(assets)?.name)
    assertNull(Releases.pickApk(assets.take(2)))
  }

  @Test fun assetsOutsideThisRepositoryAreIgnored() {
    val assets = """[{"name":"app.apk","size":1,"browser_download_url":"https://example.com/app.apk"}]"""
    assertNull(Releases.parse(list(release("v0.3.0-kotlin", assets))).single().apk)
    val otherRepo = """[{"name":"app.apk","size":5,"browser_download_url":"https://github.com/x/app.apk"}]"""
    assertNull(Releases.parse(list(release("v0.3.0-kotlin", otherRepo))).single().apk)
    val url = "https://github.com/danielharrisonlf/pc-remote-android/releases/download/v0.3.0-kotlin/app.apk"
    val github = """[{"name":"app.apk","size":5,"browser_download_url":"$url"}]"""
    assertEquals(ReleaseAsset("app.apk", 5, url), Releases.parse(list(release("v0.3.0-kotlin", github))).single().apk)
  }

  @Test fun picksTheSumsFileOfTheSameVersion() {
    val base = "https://github.com/danielharrisonlf/pc-remote-android/releases/download/v0.5.0-kotlin/"
    val assets = listOf("pc-remote-android-kotlin-0.5.0-evaluation.apk", "pc-remote-android-kotlin-0.5.0-unminified-evaluation.apk",
      "KOTLIN-SHA256SUMS-0.4.0.txt", "KOTLIN-SHA256SUMS-0.5.0.txt", "KOTLIN-SIZE-0.5.0.md")
      .joinToString(",", "[", "]") { """{"name":"$it","size":10,"browser_download_url":"$base$it"}""" }
    val parsed = Releases.parse(list(release("v0.5.0-kotlin", assets))).single()
    assertEquals("pc-remote-android-kotlin-0.5.0-evaluation.apk", parsed.apk?.name)
    assertEquals("KOTLIN-SHA256SUMS-0.5.0.txt", parsed.sums?.name)
    val state = UpdateState(lookup = ReleaseLookup.Found(parsed), installed = AppVersion(0, 4, 0))
    assertTrue(state.inApp)
    assertFalse(state.copy(lookup = ReleaseLookup.Found(parsed.copy(sums = null))).inApp)
  }

  @Test fun debugOriginAcceptsOnlyItsOwnServer() {
    val origin = UpdateOrigin.debug("# test\nhttp://10.0.2.2:8765/releases.json\n")!!
    val url = "http://10.0.2.2:8765/danielharrisonlf/pc-remote-android/releases/download/v0.5.1-kotlin/app.apk"
    val assets = """[{"name":"app.apk","size":5,"browser_download_url":"$url"}]"""
    assertEquals(url, Releases.parse(list(release("v0.5.1-kotlin", assets)), origin).single().apk?.url)
    assertNull(Releases.parse(list(release("v0.5.1-kotlin", assets))).single().apk)
  }

  @Test fun installedVersionAcceptsTheKotlinSuffix() {
    assertEquals(AppVersion(0, 2, 4), Releases.installed("0.2.4-kotlin"))
    assertEquals(AppVersion(1, 0, 0), Releases.installed("v1.0.0"))
    assertNull(Releases.installed("dev"))
    assertNull(Releases.installed(null))
  }

  @Test fun versionsCompareNumerically() {
    val release = Releases.parse(list(release("v0.10.0-kotlin"))).single()
    assertTrue(Releases.isNewer(release, AppVersion(0, 9, 9)))
    assertFalse(Releases.isNewer(release, AppVersion(0, 10, 0)))
    assertFalse(Releases.isNewer(release, null))
  }

  @Test fun skippedVersionHidesTheBanner() {
    val release = Releases.parse(list(release("v0.3.0-kotlin"))).single()
    val state = UpdateState(lookup = ReleaseLookup.Found(release), installed = AppVersion(0, 2, 4))
    assertTrue(state.available)
    assertTrue(state.notify)
    assertFalse(state.copy(skipped = "0.3.0").notify)
    assertTrue(state.copy(skipped = "0.3.0").available)
  }
}
