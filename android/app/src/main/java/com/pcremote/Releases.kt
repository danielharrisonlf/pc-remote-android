package com.pcremote

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
  override fun compareTo(other: AppVersion) = compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)
  override fun toString() = "$major.$minor.$patch"
}

internal data class ReleaseAsset(val name: String, val size: Long, val url: String)

internal data class Release(
  val tag: String,
  val version: AppVersion,
  val title: String,
  val notes: String,
  val publishedAt: String?,
  val pageUrl: String,
  val apk: ReleaseAsset?,
  /** `KOTLIN-SHA256SUMS-<version>.txt` of the same release; without it the app does not install. */
  val sums: ReleaseAsset? = null,
)

internal sealed interface ReleaseLookup {
  data class Found(val release: Release) : ReleaseLookup
  data object None : ReleaseLookup
  data class Unavailable(val message: String) : ReleaseLookup
}

/**
 * GitHub Releases for this app, tagged `vX.Y.Z-kotlin`. Builds up to 0.2.4 were
 * published as prereleases, which `/releases/latest` skips, so the list is read.
 */
internal object Releases {
  const val REPOSITORY = "danielharrisonlf/pc-remote-android"
  private val tagPattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)-kotlin$")
  private val versionPattern = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-kotlin)?$")

  /** Installed versionName such as "0.2.4-kotlin". */
  fun installed(versionName: String?): AppVersion? = versionName?.trim()?.let(versionPattern::matchEntire)?.let(::toVersion)

  fun tagVersion(tag: String): AppVersion? = tagPattern.matchEntire(tag.trim())?.let(::toVersion)

  private fun toVersion(match: MatchResult) = AppVersion(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())

  /** Only release APKs; the unminified evaluation build is a measurement artifact. */
  fun pickApk(assets: List<ReleaseAsset>): ReleaseAsset? {
    val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && !it.name.contains("unminified", ignoreCase = true) }
    return apks.firstOrNull { it.name.contains("kotlin", ignoreCase = true) } ?: apks.firstOrNull()
  }

  fun pickSums(assets: List<ReleaseAsset>, version: AppVersion): ReleaseAsset? = assets.firstOrNull { it.name == "KOTLIN-SHA256SUMS-$version.txt" }

  /** Assets outside [origin] (in production, this repository's release downloads) are dropped. */
  fun parse(json: String, origin: UpdateOrigin = UpdateOrigin.GITHUB): List<Release> {
    val array = JSONArray(json)
    return (0 until array.length()).mapNotNull { index ->
      val item = array.optJSONObject(index) ?: return@mapNotNull null
      if (item.optBoolean("draft", false)) return@mapNotNull null
      val tag = item.optString("tag_name")
      val version = tagVersion(tag) ?: return@mapNotNull null
      val page = item.optString("html_url")
      if (!page.startsWith("https://github.com/")) return@mapNotNull null
      val assets = item.optJSONArray("assets")?.let { list ->
        (0 until list.length()).mapNotNull { i ->
          val asset = list.optJSONObject(i) ?: return@mapNotNull null
          val url = asset.optString("browser_download_url")
          val name = asset.optString("name")
          if (name.isEmpty() || !origin.allowsAsset(url)) null else ReleaseAsset(name, asset.optLong("size", 0L), url)
        }
      }.orEmpty()
      Release(
        tag = tag,
        version = version,
        title = item.optString("name").ifBlank { "Versão $version" },
        notes = item.optString("body").replace("\r\n", "\n").trim(),
        publishedAt = item.optString("published_at").ifBlank { null },
        pageUrl = page,
        apk = pickApk(assets),
        sums = pickSums(assets, version),
      )
    }
  }

  fun newest(releases: List<Release>): Release? = releases.maxByOrNull { it.version }

  fun isNewer(release: Release, installed: AppVersion?) = installed != null && release.version > installed
}

/** One GitHub request at a time; callbacks run on OkHttp's thread. */
internal class ReleaseChecker(private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()) {
  private var inFlight: Call? = null

  fun check(origin: UpdateOrigin, completion: (ReleaseLookup) -> Unit) {
    if (inFlight != null) return
    val request = Request.Builder()
      .url(origin.releasesUrl)
      .header("Accept", "application/vnd.github+json")
      .build()
    val call = http.newCall(request)
    inFlight = call
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        inFlight = null
        completion(ReleaseLookup.Unavailable("Sem conexão com o GitHub."))
      }

      override fun onResponse(call: Call, response: Response) {
        inFlight = null
        val result = response.use {
          when {
            it.code == 403 || it.code == 429 -> ReleaseLookup.Unavailable("GitHub indisponível no momento. Tente mais tarde.")
            it.code == 404 -> ReleaseLookup.Unavailable("Nenhuma versão publicada encontrada.")
            !it.isSuccessful -> ReleaseLookup.Unavailable("O GitHub respondeu com o código ${it.code}.")
            else -> try {
              Releases.newest(Releases.parse(it.body?.string().orEmpty(), origin))?.let(ReleaseLookup::Found) ?: ReleaseLookup.None
            } catch (_: Exception) {
              ReleaseLookup.Unavailable("Resposta inválida do GitHub.")
            }
          }
        }
        completion(result)
      }
    })
  }

}
