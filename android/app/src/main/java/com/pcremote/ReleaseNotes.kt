package com.pcremote

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/** Markdown subset of GitHub release notes, parsed into plain data (no HTML). */
internal object ReleaseNotes {
  sealed interface Block {
    data class Heading(val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Item(val text: String, val number: Int?) : Block
  }

  fun parse(source: String): List<Block> {
    val blocks = mutableListOf<Block>()
    val paragraph = mutableListOf<String>()
    fun flush() {
      if (paragraph.isNotEmpty()) blocks += Block.Paragraph(paragraph.joinToString(" "))
      paragraph.clear()
    }
    for (raw in source.replace("\r\n", "\n").lines()) {
      val line = raw.trim()
      when {
        line.isEmpty() -> flush()
        line.startsWith("#") -> { flush(); blocks += Block.Heading(line.trimStart('#').trim().trimEnd('#').trim()) }
        Regex("^([-*_])(\\s*\\1){2,}$").matches(line) -> flush()
        Regex("^[-*+]\\s+.*").matches(line) -> { flush(); blocks += Block.Item(line.substring(1).trim(), null) }
        Regex("^\\d+[.)]\\s+.*").matches(line) -> {
          flush()
          val number = line.takeWhile(Char::isDigit).toIntOrNull()
          blocks += Block.Item(line.dropWhile(Char::isDigit).drop(1).trim(), number)
        }
        raw.startsWith("  ") && blocks.lastOrNull() is Block.Item && paragraph.isEmpty() -> {
          val last = blocks.removeAt(blocks.lastIndex) as Block.Item
          blocks += last.copy(text = "${last.text} $line")
        }
        else -> paragraph += line
      }
    }
    flush()
    return blocks
  }

  private val inline = Regex("\\*\\*([^*]+)\\*\\*|__([^_]+)__|`([^`]+)`|\\[([^\\]]+)]\\([^)\\s]+\\)")

  /** Bold, code and link text; markup characters are removed, never interpreted as HTML. */
  private fun appendInline(builder: SpannableStringBuilder, text: String) {
    var index = 0
    for (match in inline.findAll(text)) {
      builder.append(text, index, match.range.first)
      val start = builder.length
      val bold = match.groups[1]?.value ?: match.groups[2]?.value
      val code = match.groups[3]?.value
      val link = match.groups[4]?.value
      builder.append(bold ?: code ?: link.orEmpty())
      when {
        bold != null -> builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        code != null -> builder.setSpan(TypefaceSpan("monospace"), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      index = match.range.last + 1
    }
    builder.append(text, index, text.length)
  }

  fun render(source: String, bulletColor: Int, gapPx: Int): CharSequence {
    val builder = SpannableStringBuilder()
    for (block in parse(source)) {
      if (builder.isNotEmpty()) builder.append("\n\n")
      val start = builder.length
      when (block) {
        is Block.Heading -> {
          appendInline(builder, block.text)
          builder.setSpan(StyleSpan(Typeface.BOLD), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
          builder.setSpan(RelativeSizeSpan(1.1f), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        is Block.Paragraph -> appendInline(builder, block.text)
        is Block.Item -> {
          if (block.number != null) builder.append("${block.number}. ")
          appendInline(builder, block.text)
          if (block.number == null) builder.setSpan(BulletSpan(gapPx, bulletColor), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
      }
    }
    return builder
  }
}
