package com.pcremote

import java.text.Normalizer
import java.util.Locale

internal enum class VoiceCommand { PLAY, PAUSE, NEXT, PREVIOUS, BACK10, FORWARD10, VOLUME_UP, VOLUME_DOWN, MUTE, UNMUTE }

internal sealed interface VoiceIntent {
  data class Command(val command: VoiceCommand) : VoiceIntent
  /** [query] keeps the user's own spelling, accents included. */
  data class Search(val query: String) : VoiceIntent
  data object Unknown : VoiceIntent
}

/**
 * Turns what the recognizer heard into a media command or a YouTube search. Matching
 * ignores case and accents. A whole utterance must be a command, give or take filler
 * words, so "próxima música do Coldplay" is a search and "próxima música" is not.
 */
internal object VoiceCommands {
  private val ptBR = Locale.forLanguageTag("pt-BR")
  private val searchVerbs = setOf("pesquisar", "pesquise", "pesquisa", "buscar", "busque", "busca", "procurar", "procure", "procura")
  private val playVerbs = setOf("tocar", "toca", "toque", "ouvir", "assistir")
  /** Words that do not change a command: "pausar o vídeo", "próxima música, por favor". */
  private val filler = setOf("o", "a", "os", "as", "do", "da", "de", "no", "na", "um", "uma", "por", "favor", "agora", "ai", "ei", "ok", "pc", "computador")
  private val nouns = setOf("video", "videos", "musica", "musicas", "faixa", "cancao", "reproducao", "midia", "player", "som")
  private val pause = setOf("pausar", "pausa", "pause", "parar", "pare", "pausei")
  private val play = setOf("continuar", "continua", "continue", "retomar", "retoma", "retome", "reproduzir", "reproduz", "play", "tocar", "toca", "despausar", "despausa")
  private val next = setOf("proxima", "proximo", "pular", "pula", "pule", "seguinte", "passar", "passa")
  private val previous = setOf("anterior", "voltar", "volta", "volte")
  private val back = setOf("voltar", "volta", "volte", "retroceder", "retrocede", "recuar", "recua")
  private val forward = setOf("avancar", "avanca", "avance", "adiantar", "adianta", "adiante", "pular", "pula", "pule")
  private val up = setOf("aumentar", "aumenta", "aumente", "subir", "sobe", "suba")
  private val down = setOf("diminuir", "diminui", "diminua", "abaixar", "abaixa", "abaixe", "baixar", "baixa", "baixe")
  private val trailing = listOf(listOf("no", "youtube"), listOf("no", "you", "tube"), listOf("pelo", "youtube"), listOf("do", "youtube"), listOf("no", "yt"), listOf("youtube"))

  fun normalize(word: String): String =
    Normalizer.normalize(word.lowercase(ptBR), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

  fun parse(text: String): VoiceIntent {
    // Original words for the query, normalized ones for matching.
    val words = text.trim().split(Regex("\\s+")).map { it.trim(',', '.', '!', '?', ';', ':', '"', '“', '”') }.filter { it.isNotEmpty() }
    if (words.isEmpty()) return VoiceIntent.Unknown
    val keys = words.map(::normalize)
    if (keys.first() in searchVerbs) return search(words.drop(1), keys.drop(1))
    command(keys)?.let { return VoiceIntent.Command(it) }
    if (keys.first() in playVerbs && keys.drop(1).any { it !in filler && it !in nouns }) return search(words.drop(1), keys.drop(1))
    return search(words, keys)
  }

  private fun command(keys: List<String>): VoiceCommand? {
    val core = keys.filter { it !in filler }
    val noun = core.any { it in nouns }
    val verbs = core.filter { it !in nouns }
    val first = verbs.firstOrNull()
    val ten = verbs.size == 3 && (verbs[1] == "10" || verbs[1] == "dez") && verbs[2].startsWith("segundo")
    return when {
      verbs.isEmpty() -> null
      ten && first in back -> VoiceCommand.BACK10
      ten && first in forward -> VoiceCommand.FORWARD10
      verbs == listOf("mais", "alto") -> VoiceCommand.VOLUME_UP
      verbs == listOf("mais", "baixo") -> VoiceCommand.VOLUME_DOWN
      verbs.size == 2 && verbs[1] == "volume" && first in up -> VoiceCommand.VOLUME_UP
      verbs.size == 2 && verbs[1] == "volume" && first in down -> VoiceCommand.VOLUME_DOWN
      verbs == listOf("desmutar") || verbs == listOf("tirar", "mudo") || verbs == listOf("tira", "mudo") || (verbs.size == 1 && first in setOf("ativar", "ligar", "com") && "som" in core) -> VoiceCommand.UNMUTE
      verbs.size == 1 && (first in setOf("mudo", "mutar", "silenciar", "silencio") || (first in setOf("sem", "desligar", "tirar") && "som" in core)) -> VoiceCommand.MUTE
      verbs.size != 1 -> if (verbs == listOf("voltar", "tocar") || verbs == listOf("volta", "tocar")) VoiceCommand.PLAY else null
      first in pause -> VoiceCommand.PAUSE
      first in play -> VoiceCommand.PLAY
      first in next || (noun && first in forward) -> VoiceCommand.NEXT
      first in previous -> VoiceCommand.PREVIOUS
      first in back || first in forward -> if (first in back) VoiceCommand.BACK10 else VoiceCommand.FORWARD10
      else -> null
    }
  }

  /** Drops a leading "por"/"sobre" and a trailing "no YouTube"; nothing left means nothing to search. */
  private fun search(words: List<String>, keys: List<String>): VoiceIntent {
    var start = 0
    var end = words.size
    if (start < end && keys[start] in setOf("por", "sobre")) start++
    for (tail in trailing) if (end - start >= tail.size && keys.subList(end - tail.size, end) == tail) { end -= tail.size; break }
    val query = words.subList(start, end).joinToString(" ").trim()
    // 200 code points, never splitting an emoji's surrogate pair.
    val limited = if (query.codePointCount(0, query.length) > 200) query.substring(0, query.offsetByCodePoints(0, 200)) else query
    return if (limited.isEmpty()) VoiceIntent.Unknown else VoiceIntent.Search(limited)
  }
}
