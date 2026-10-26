package com.pcremote

import com.pcremote.ReleaseNotes.Block
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
  @Test fun parsesHeadingsParagraphsAndLists() {
    val source = "## Novidades\r\nPrimeira linha\ncontinua aqui.\n\n- Item **um**\n  continua\n* Item dois\n1. Passo\n---\nFim"
    assertEquals(
      listOf(
        Block.Heading("Novidades"),
        Block.Paragraph("Primeira linha continua aqui."),
        Block.Item("Item **um** continua", null),
        Block.Item("Item dois", null),
        Block.Item("Passo", 1),
        Block.Paragraph("Fim"),
      ),
      ReleaseNotes.parse(source),
    )
  }

  @Test fun numberTooLongForAnIntBecomesAnUnnumberedItem() {
    assertEquals(listOf(Block.Item("Correções", null)), ReleaseNotes.parse("20261026123. Correções"))
  }

  @Test fun emptyNotesHaveNoBlocks() {
    assertEquals(emptyList<Block>(), ReleaseNotes.parse("\n\n"))
  }
}
