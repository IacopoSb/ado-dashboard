package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** A run of text with its style; [link] is the address it opens. */
data class RichSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val link: String? = null)

/** A paragraph, a heading or a list item ([bullet] "•" or "1."), [indent] levels deep. */
data class RichBlock(val spans: List<RichSpan>, val bullet: String? = null, val indent: Int = 0) {
    val text: String get() = spans.joinToString("") { it.text }
}

/**
 * Azure DevOps' HTML (descriptions, repro steps) as readable text: paragraphs, headings, lists,
 * links, bold and italic. Images, scripts, styles and every other tag are left out, their text
 * kept.
 */
object HtmlText {
    private val TOKEN = Regex("<!--[\\s\\S]*?-->|<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)/?>")
    private val HREF = Regex("href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val SPACES = Regex("\\s+")
    private val BLOCKS = setOf(
        "p", "div", "blockquote", "tr", "table", "tbody", "thead", "section", "article", "header", "footer", "hr",
        "h1", "h2", "h3", "h4", "h5", "h6", "pre",
    )
    private val SKIPPED = setOf("script", "style", "head", "title")
    private val NAMED = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "laquo" to "«", "raquo" to "»", "rsquo" to "’", "lsquo" to "‘",
        "rdquo" to "”", "ldquo" to "“", "euro" to "€", "copy" to "©", "reg" to "®", "middot" to "·", "bull" to "•",
    )

    fun decode(text: String): String = ENTITY.replace(text) { m ->
        val name = m.groupValues[1]
        when {
            name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)?.let(::codePoint)
            name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED[name.lowercase()]
        } ?: m.value
    }

    private fun codePoint(cp: Int): String? = runCatching { String(Character.toChars(cp)) }.getOrNull()

    fun parse(html: String): List<RichBlock> = Parser().run(html)

    private class ListLevel(val ordered: Boolean, var count: Int = 0)

    private class Parser {
        val blocks = mutableListOf<RichBlock>()
        val spans = mutableListOf<RichSpan>()
        var bold = 0
        var italic = 0
        var heading = 0
        var skip = 0
        var pre = 0
        val links = ArrayDeque<String?>()
        val lists = ArrayDeque<ListLevel>()
        /** The bullet of a list item whose text has not started yet. */
        var bullet: String? = null
        var indent = 0

        fun run(html: String): List<RichBlock> {
            var at = 0
            TOKEN.findAll(html).forEach { m ->
                if (m.range.first > at) text(html.substring(at, m.range.first))
                at = m.range.last + 1
                if (m.value.startsWith("<!--")) return@forEach
                val closing = m.groupValues[1] == "/"
                val tag = m.groupValues[2].lowercase()
                if (closing) close(tag) else open(tag, m.groupValues[3])
            }
            if (at < html.length) text(html.substring(at))
            flush()
            return blocks
        }

        fun open(tag: String, attributes: String) {
            if (tag in SKIPPED) { skip++; return }
            when (tag) {
                in BLOCKS -> {
                    flush()
                    if (tag.length == 2 && tag[0] == 'h' && tag[1].isDigit()) heading++
                    if (tag == "pre") pre++
                }
                "br" -> if (spans.isNotEmpty()) append("\n")
                "ul", "ol" -> {
                    flush()
                    lists.addLast(ListLevel(ordered = tag == "ol"))
                }
                "li" -> {
                    flush()
                    val list = lists.lastOrNull()
                    bullet = if (list?.ordered == true) "${++list.count}." else "•"
                    indent = (lists.size - 1).coerceAtLeast(0)
                }
                "b", "strong" -> bold++
                "i", "em" -> italic++
                "a" -> links.addLast(HREF.find(attributes)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let(::decode))
                "td", "th" -> if (spans.isNotEmpty()) append("  ")
            }
        }

        fun close(tag: String) {
            if (tag in SKIPPED) { skip = (skip - 1).coerceAtLeast(0); return }
            when (tag) {
                in BLOCKS -> {
                    flush()
                    if (tag.length == 2 && tag[0] == 'h' && tag[1].isDigit()) heading = (heading - 1).coerceAtLeast(0)
                    if (tag == "pre") pre = (pre - 1).coerceAtLeast(0)
                }
                "ul", "ol" -> {
                    flush()
                    lists.removeLastOrNull()
                    indent = (lists.size - 1).coerceAtLeast(0)
                }
                "li" -> {
                    flush()
                    bullet = null
                }
                "b", "strong" -> bold = (bold - 1).coerceAtLeast(0)
                "i", "em" -> italic = (italic - 1).coerceAtLeast(0)
                "a" -> links.removeLastOrNull()
            }
        }

        fun text(raw: String) {
            if (skip > 0) return
            var t = decode(if (pre > 0) raw else SPACES.replace(raw, " "))
            val last = spans.lastOrNull()?.text?.lastOrNull()
            // No space at the start of a line
            if (pre == 0 && (last == null || last == '\n' || last == ' ')) t = t.trimStart(' ')
            if (t.isEmpty()) return
            append(t)
        }

        fun append(t: String) {
            val span = RichSpan(t, bold = bold > 0 || heading > 0, italic = italic > 0, link = links.lastOrNull())
            val last = spans.lastOrNull()
            if (last != null && last.bold == span.bold && last.italic == span.italic && last.link == span.link) {
                spans[spans.lastIndex] = last.copy(text = last.text + t)
            } else {
                spans += span
            }
        }

        fun flush() {
            // Trailing spaces and line breaks
            while (spans.isNotEmpty()) {
                val last = spans.last()
                val trimmed = last.text.trimEnd()
                if (trimmed.isEmpty()) spans.removeAt(spans.lastIndex) else {
                    spans[spans.lastIndex] = last.copy(text = trimmed)
                    break
                }
            }
            if (spans.isNotEmpty()) {
                blocks += RichBlock(spans.toList(), bullet, if (bullet != null || lists.isNotEmpty()) indent else 0)
                // Further paragraphs of the same item: indented, without a bullet
                bullet = null
            }
            spans.clear()
        }
    }
}

/** [blocks] as text: links open with [onLink]. */
@Composable
fun RichText(blocks: List<RichBlock>, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = AdoTheme.colors
    val style = MaterialTheme.typography.bodyMedium
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            val text = remember(block, c.primary) { block.annotated(c.primary, onLink) }
            if (block.bullet != null || block.indent > 0) {
                Row(Modifier.padding(start = (block.indent * 16).dp)) {
                    Text(block.bullet.orEmpty(), Modifier.width(20.dp), style = style, color = c.textMuted)
                    Text(text, style = style, color = c.text)
                }
            } else {
                Text(text, style = style, color = c.text)
            }
        }
    }
}

private fun RichBlock.annotated(linkColor: androidx.compose.ui.graphics.Color, onLink: (String) -> Unit): AnnotatedString =
    buildAnnotatedString {
        spans.forEach { span ->
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
            )
            val link = span.link
            if (link != null && (link.startsWith("http://") || link.startsWith("https://") || link.startsWith("mailto:"))) {
                val annotation = LinkAnnotation.Clickable(
                    link, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                ) { onLink(link) }
                withLink(annotation) { withStyle(style) { append(span.text) } }
            } else {
                withStyle(style) { append(span.text) }
            }
        }
    }
