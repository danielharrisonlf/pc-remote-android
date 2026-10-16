package com.pcremote

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandsTest {
  private fun command(text: String) = VoiceCommands.parse(text)
  private fun cmd(c: VoiceCommand) = VoiceIntent.Command(c)
  private fun search(q: String) = VoiceIntent.Search(q)

  @Test fun mediaCommandsIgnoreCaseAccentsAndFillerWords() {
    for (text in listOf("Pausar o vídeo", "pausa", "Pause", "parar a música", "pausar, por favor")) assertEquals(text, cmd(VoiceCommand.PAUSE), command(text))
    for (text in listOf("Continuar reprodução", "continuar", "retomar o vídeo", "Play", "tocar", "tocar música", "voltar a tocar", "despausar")) assertEquals(text, cmd(VoiceCommand.PLAY), command(text))
    for (text in listOf("Próxima música", "PROXIMA", "próximo vídeo", "pular", "pular música", "avançar faixa")) assertEquals(text, cmd(VoiceCommand.NEXT), command(text))
    for (text in listOf("música anterior", "anterior", "voltar", "voltar música", "faixa anterior")) assertEquals(text, cmd(VoiceCommand.PREVIOUS), command(text))
    for (text in listOf("voltar 10 segundos", "Voltar dez segundos", "retroceder")) assertEquals(text, cmd(VoiceCommand.BACK10), command(text))
    for (text in listOf("avançar 10 segundos", "adiantar dez segundos", "avançar", "pular 10 segundos")) assertEquals(text, cmd(VoiceCommand.FORWARD10), command(text))
  }

  @Test fun volumeCommands() {
    for (text in listOf("aumentar o volume", "Aumenta o volume", "mais alto", "subir volume")) assertEquals(text, cmd(VoiceCommand.VOLUME_UP), command(text))
    for (text in listOf("diminuir o volume", "abaixar volume", "mais baixo")) assertEquals(text, cmd(VoiceCommand.VOLUME_DOWN), command(text))
    for (text in listOf("mudo", "sem som", "silenciar", "tirar o som")) assertEquals(text, cmd(VoiceCommand.MUTE), command(text))
    for (text in listOf("tirar do mudo", "ativar o som", "desmutar")) assertEquals(text, cmd(VoiceCommand.UNMUTE), command(text))
  }

  @Test fun searchesKeepTheUsersWordsWithoutTheVerbOrTheService() {
    assertEquals(search("vídeos de Node.js"), command("Pesquisar vídeos de Node.js no YouTube"))
    assertEquals(search("músicas do artista X"), command("Pesquisar músicas do artista X"))
    assertEquals(search("gatos engraçados"), command("buscar por gatos engraçados"))
    assertEquals(search("receita de bolo"), command("procure receita de bolo pelo YouTube"))
    assertEquals(search("Coldplay"), command("tocar Coldplay"))
    assertEquals(search("vídeos de gatos"), command("vídeos de gatos"))
    // Not exactly a command: the whole utterance is the search.
    assertEquals(search("próxima música do Coldplay"), command("próxima música do Coldplay"))
    assertEquals(search("x".repeat(200)), command("pesquisar " + "x".repeat(250)))
  }

  @Test fun longSearchesStopAt200CharactersWithoutSplittingAnEmoji() {
    val query = (VoiceCommands.parse("pesquisar " + "🦊".repeat(250)) as VoiceIntent.Search).query
    assertEquals(200, query.codePointCount(0, query.length))
    assertEquals(true, Protocol.queryValid(query))
  }

  @Test fun nothingToDoIsNotUnderstood() {
    for (text in listOf("", "   ", "pesquisar", "pesquisar no YouTube", "buscar por")) assertEquals(text, VoiceIntent.Unknown, command(text))
  }
}
