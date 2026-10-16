package com.pcremote

import android.content.pm.PackageInstaller
import java.net.URI

/** Some editors start text files with it. */
private val BOM = Char(0xFEFF).toString()

/**
 * Where the releases list and its files may come from. Production is this
 * repository on GitHub only; a debuggable build can point to a test server.
 */
internal data class UpdateOrigin(
  val releasesUrl: String,
  /** Every APK and SHA256SUMS URL starts with this. */
  val assetPrefix: String,
  /** Hosts a download may reach, redirects included, all over [scheme]. */
  val hosts: Set<String>,
  val scheme: String = "https",
  val port: Int = -1,
) {
  /** A file under [assetPrefix]: plain path segments, no query, no escapes, no dot segments. */
  fun allowsAsset(url: String): Boolean {
    if (!url.startsWith(assetPrefix) || !allowsHop(url)) return false
    val segments = url.substring(assetPrefix.length).split('/')
    return segments.all { it.matches(SEGMENT) && it != "." && it != ".." }
  }

  /** One request of a download, the first one or a redirect. */
  fun allowsHop(url: String): Boolean {
    val uri = try { URI(url) } catch (_: Exception) { return false }
    if (!uri.isAbsolute || uri.rawUserInfo != null || !scheme.equals(uri.scheme, ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    return host in hosts && effectivePort(uri.port) == effectivePort(port)
  }

  private fun effectivePort(value: Int) = if (value != -1) value else if (scheme == "https") 443 else 80

  companion object {
    private val SEGMENT = Regex("[A-Za-z0-9._+-]+")

    val GITHUB = UpdateOrigin(
      releasesUrl = "https://api.github.com/repos/${Releases.REPOSITORY}/releases?per_page=30",
      assetPrefix = "https://github.com/${Releases.REPOSITORY}/releases/download/",
      hosts = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"),
    )

    /**
     * Debug test hook: the first non-comment line is the releases URL of a test
     * server, whose scheme, host and port become the only allowed origin.
     */
    fun debug(text: String): UpdateOrigin? {
      val url = text.removePrefix(BOM).lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
      val uri = try { URI(url) } catch (_: Exception) { return null }
      val scheme = uri.scheme?.lowercase()
      if (scheme != "http" && scheme != "https") return null
      val host = uri.host?.lowercase() ?: return null
      if (uri.rawUserInfo != null) return null
      val authority = if (uri.port == -1) host else "$host:${uri.port}"
      return UpdateOrigin(url, "$scheme://$authority/", setOf(host), scheme, uri.port)
    }
  }
}

/** `sha256sum` output: `<64 hex>  <file name>` per line. */
internal object Sha256Sums {
  private val line = Regex("^([0-9a-fA-F]{64}) [ *](\\S.*)$")

  /** File name to lowercase hash. A name listed twice with different hashes is dropped. */
  fun parse(text: String): Map<String, String> {
    val hashes = HashMap<String, String>()
    val conflicts = HashSet<String>()
    text.removePrefix(BOM).lineSequence().map { it.trimEnd('\r', ' ', '\t') }.forEach { raw ->
      val match = line.matchEntire(raw) ?: return@forEach
      val hash = match.groupValues[1].lowercase()
      val name = match.groupValues[2]
      val previous = hashes.put(name, hash)
      if (previous != null && previous != hash) conflicts += name
    }
    return hashes - conflicts
  }
}

/** What both the app and Android compare before replacing the installed APK. */
internal data class ApkIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

internal object UpdateCheck {
  const val MAX_APK_BYTES = 50L * 1024 * 1024
  const val MAX_SUMS_BYTES = 64L * 1024

  fun sha(expected: String?, actual: String): String? = when {
    expected == null -> "A release não traz o SHA-256 deste APK."
    !expected.equals(actual, ignoreCase = true) -> "O APK baixado não confere com o SHA-256 publicado. Nada foi instalado."
    else -> null
  }

  fun archive(installed: ApkIdentity?, archive: ApkIdentity?): String? = when {
    installed == null || installed.signers.isEmpty() -> "Não foi possível ler a assinatura do app instalado."
    archive == null -> "O arquivo baixado não é um APK válido."
    archive.packageName != installed.packageName -> "O APK baixado é de outro aplicativo. Nada foi instalado."
    archive.versionCode <= installed.versionCode -> "O APK baixado não é mais novo que a versão instalada."
    archive.signers.isEmpty() || archive.signers != installed.signers -> "O APK baixado tem assinatura diferente da do app instalado. Nada foi instalado."
    else -> null
  }

  /** PackageInstaller status of a failed session, in words for the user. */
  fun installFailure(status: Int): String = when (status) {
    PackageInstaller.STATUS_FAILURE_ABORTED -> "Instalação cancelada."
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "O Android bloqueou a instalação neste celular."
    PackageInstaller.STATUS_FAILURE_CONFLICT -> "A atualização conflita com o app instalado."
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "A atualização não é compatível com este celular."
    PackageInstaller.STATUS_FAILURE_INVALID -> "O Android recusou o APK."
    PackageInstaller.STATUS_FAILURE_STORAGE -> "Falta espaço para instalar a atualização."
    else -> "O Android não instalou a atualização."
  }

  private val apkFile = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)\\.apk$")

  /** Downloads are kept as `<version>.apk`; partial files and versions already installed go. */
  fun staleFiles(names: List<String>, installed: AppVersion?): List<String> = names.filter { name ->
    val version = apkFile.matchEntire(name)?.let { AppVersion(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }
    version == null || installed == null || version <= installed
  }

  fun fileName(version: AppVersion) = "$version.apk"
}

/** Download, verification and installation of one version. Changes happen on the main thread. */
internal sealed interface UpdateFlow {
  val version: AppVersion?

  data object Idle : UpdateFlow {
    override val version: AppVersion? get() = null
  }

  /** [install] is set once the user tapped "Atualizar agora": the install follows verification. */
  data class Downloading(override val version: AppVersion, val received: Long, val total: Long, val install: Boolean) : UpdateFlow
  data class Verifying(override val version: AppVersion, val install: Boolean) : UpdateFlow
  /** Downloaded and verified; installs only after a tap on "Instalar agora". */
  data class Ready(override val version: AppVersion) : UpdateFlow
  /** Session committed; [confirming] while the system asks the user. */
  data class Installing(override val version: AppVersion, val confirming: Boolean = false) : UpdateFlow
  data class Failed(override val version: AppVersion, val message: String) : UpdateFlow
}

internal enum class UpdateCommand { NONE, DOWNLOAD, INSTALL }

internal object UpdateFlows {
  /** Automatic download (install = false) or a tap on the update button (install = true). */
  fun request(flow: UpdateFlow, version: AppVersion, install: Boolean): Pair<UpdateFlow, UpdateCommand> = when {
    flow is UpdateFlow.Downloading && flow.version == version -> flow.copy(install = flow.install || install) to UpdateCommand.NONE
    flow is UpdateFlow.Verifying && flow.version == version -> flow.copy(install = flow.install || install) to UpdateCommand.NONE
    flow is UpdateFlow.Ready && flow.version == version -> if (install) UpdateFlow.Installing(version) to UpdateCommand.INSTALL else flow to UpdateCommand.NONE
    flow is UpdateFlow.Installing && flow.version == version -> flow to UpdateCommand.NONE
    else -> UpdateFlow.Downloading(version, 0, 0, install) to UpdateCommand.DOWNLOAD
  }

  /** "Agora não": a download in progress continues but no longer installs by itself. */
  fun keep(flow: UpdateFlow): UpdateFlow = when (flow) {
    is UpdateFlow.Downloading -> flow.copy(install = false)
    is UpdateFlow.Verifying -> flow.copy(install = false)
    else -> flow
  }

  fun progress(flow: UpdateFlow, received: Long, total: Long): UpdateFlow =
    if (flow is UpdateFlow.Downloading) flow.copy(received = received, total = total) else flow

  fun downloaded(flow: UpdateFlow): UpdateFlow =
    if (flow is UpdateFlow.Downloading) UpdateFlow.Verifying(flow.version, flow.install) else flow

  fun verified(flow: UpdateFlow): Pair<UpdateFlow, UpdateCommand> = when {
    flow is UpdateFlow.Verifying && flow.install -> UpdateFlow.Installing(flow.version) to UpdateCommand.INSTALL
    flow is UpdateFlow.Verifying -> UpdateFlow.Ready(flow.version) to UpdateCommand.NONE
    else -> flow to UpdateCommand.NONE
  }

  fun failed(flow: UpdateFlow, message: String): UpdateFlow = flow.version?.let { UpdateFlow.Failed(it, message) } ?: flow

  /** Session result. A cancelled confirmation keeps the verified file for another try. */
  fun session(flow: UpdateFlow, status: Int): UpdateFlow {
    if (flow !is UpdateFlow.Installing) return flow
    return when (status) {
      PackageInstaller.STATUS_PENDING_USER_ACTION -> flow.copy(confirming = true)
      PackageInstaller.STATUS_SUCCESS -> UpdateFlow.Idle
      PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateFlow.Ready(flow.version)
      else -> UpdateFlow.Failed(flow.version, UpdateCheck.installFailure(status))
    }
  }
}

/** Banner, button and status texts for the release on offer. */
internal object UpdateLabels {
  private fun percent(flow: UpdateFlow.Downloading) = if (flow.total > 0) " ${(flow.received * 100 / flow.total).coerceIn(0, 100)}%" else ""

  private fun current(state: UpdateState): UpdateFlow {
    val flow = state.flow
    return if (flow.version != null && flow.version == state.release?.version) flow else UpdateFlow.Idle
  }

  fun banner(state: UpdateState): Pair<String, String> {
    val version = state.release?.version ?: return "" to ""
    return when (val flow = current(state)) {
      is UpdateFlow.Downloading -> "Baixando a versão $version…${percent(flow)}" to "Toque para ver as novidades."
      is UpdateFlow.Verifying -> "Conferindo a versão $version…" to "SHA-256 e assinatura."
      is UpdateFlow.Ready -> "Atualização pronta — toque para instalar" to "Versão $version baixada e conferida."
      is UpdateFlow.Installing -> "Instalando a versão $version…" to "O app fecha e volta atualizado."
      else -> "Versão $version disponível" to "Toque para ver as novidades."
    }
  }

  /** Main button of the update sheet, and whether it is busy. */
  fun action(state: UpdateState): Pair<String, Boolean> = when (val flow = current(state)) {
    is UpdateFlow.Downloading -> "Baixando…${percent(flow)}" to true
    is UpdateFlow.Verifying -> "Conferindo…" to true
    is UpdateFlow.Ready -> "Instalar agora" to false
    is UpdateFlow.Installing -> (if (flow.confirming) "Instalar agora" else "Instalando…") to !flow.confirming
    else -> "Atualizar agora" to false
  }

  /** Line under the progress bar, or null when there is nothing in progress. */
  fun progress(state: UpdateState): String? = when (val flow = current(state)) {
    is UpdateFlow.Downloading -> if (flow.total > 0) "${mb(flow.received)} de ${mb(flow.total)} MB" else "Baixando…"
    is UpdateFlow.Verifying -> "Conferindo SHA-256 e assinatura…"
    is UpdateFlow.Ready -> "Baixada e conferida: SHA-256 publicado e mesma assinatura do app instalado."
    is UpdateFlow.Installing -> if (flow.confirming) "Confirme a instalação na tela do Android." else "Instalando…"
    is UpdateFlow.Failed -> flow.message
    UpdateFlow.Idle -> null
  }

  /** Status line of Conexão → Atualizações while a version is on offer. */
  fun status(state: UpdateState): String? {
    val version = state.release?.version ?: return null
    return when (val flow = current(state)) {
      is UpdateFlow.Downloading -> "Baixando a versão $version…${percent(flow)}"
      is UpdateFlow.Verifying -> "Conferindo a versão $version…"
      is UpdateFlow.Ready -> "Versão $version baixada e conferida, pronta para instalar."
      is UpdateFlow.Installing -> "Instalando a versão $version…"
      is UpdateFlow.Failed -> flow.message
      UpdateFlow.Idle -> null
    }
  }

  private fun mb(bytes: Long) = String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f", bytes / 1_048_576.0)
}
