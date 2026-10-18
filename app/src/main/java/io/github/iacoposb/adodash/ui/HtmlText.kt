package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.api.HtmlEntities

/**
 * A run of text with its style; [link] is the address it opens, [mention] a person mentioned
 * ("@Name"), [code] inline code or a code block.
 */
data class RichSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val link: String? = null,
    val mention: Boolean = false,
    val code: Boolean = false,
) {
    fun sameStyle(other: RichSpan) = copy(text = "") == other.copy(text = "")
}

/**
 * A paragraph, a heading or a list item ([bullet] "•" or "1."), [indent] levels deep; a
 * [codeBlock] is shown as is, in a box.
 */
data class RichBlock(val spans: List<RichSpan>, val bullet: String? = null, val indent: Int = 0, val codeBlock: Boolean = false) {
    val text: String get() = spans.joinToString("") { it.text }
}

/**
 * Azure DevOps' HTML (descriptions, repro steps, comments) as readable text: paragraphs,
 * headings, lists, links, mentions, code, bold and italic. Images, scripts, styles and every
 * other tag are left out, their text kept.
 */
object HtmlText {
    private val TOKEN = Regex("<!--[\\s\\S]*?-->|<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)/?>")
    private val HREF = Regex("href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE)
    private val MENTION = Regex("data-vss-mention\\s*=", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("\\s+")
    private val BLOCKS = setOf(
        "p", "div", "blockquote", "tr", "table", "tbody", "thead", "section", "article", "header", "footer", "hr",
        "h1", "h2", "h3", "h4", "h5", "h6", "pre",
    )
    private val SKIPPED = setOf("script", "style", "head", "title")

    /** Entities decoded, no-break spaces as plain ones (the text is wrapped as it fits). */
    fun decode(text: String): String = HtmlEntities.decode(text).replace('\u00A0', ' ')

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
        val mentions = ArrayDeque<Boolean>()
        var code = 0
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
                    if (tag == "pre") { pre++; code++ }
                }
                "code", "kbd", "samp", "tt" -> code++
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
                "a" -> {
                    links.addLast(HREF.find(attributes)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let(::decode))
                    mentions.addLast(MENTION.containsMatchIn(attributes))
                }
                "td", "th" -> if (spans.isNotEmpty()) append("  ")
            }
        }

        fun close(tag: String) {
            if (tag in SKIPPED) { skip = (skip - 1).coerceAtLeast(0); return }
            when (tag) {
                in BLOCKS -> {
                    flush()
                    if (tag.length == 2 && tag[0] == 'h' && tag[1].isDigit()) heading = (heading - 1).coerceAtLeast(0)
                    if (tag == "pre") {
                        pre = (pre - 1).coerceAtLeast(0)
                        code = (code - 1).coerceAtLeast(0)
                    }
                }
                "code", "kbd", "samp", "tt" -> code = (code - 1).coerceAtLeast(0)
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
                "a" -> {
                    links.removeLastOrNull()
                    mentions.removeLastOrNull()
                }
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
            val mention = mentions.lastOrNull() == true
            val span = RichSpan(
                t, bold = bold > 0 || heading > 0, italic = italic > 0, link = links.lastOrNull().takeUnless { mention },
                mention = mention, code = code > 0,
            )
            val last = spans.lastOrNull()
            if (last != null && last.sameStyle(span)) {
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

/** A code block, for the screenshots to scroll it sideways. */
const val CODE_BLOCK_TAG = "code-block"

/** [blocks] as text: links open with [onLink]; [color] for the text (muted while it is sent). */
@Composable
fun RichText(
    blocks: List<RichBlock>,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = AdoTheme.colors.text,
) {
    val c = AdoTheme.colors
    val style = MaterialTheme.typography.bodyMedium
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            val text = remember(block, c) { block.annotated(c, onLink) }
            if (block.codeBlock) {
                // As wide as the card, long lines scrolled sideways rather than wrapped or cut
                Box(
                    Modifier.fillMaxWidth().padding(start = (block.indent * 16).dp).clip(RoundedCornerShape(8.dp))
                        .background(c.surfaceAlt).horizontalScroll(rememberScrollState()).testTag(CODE_BLOCK_TAG),
                ) {
                    Text(
                        block.text, Modifier.wrapContentWidth(Alignment.Start, unbounded = true).padding(horizontal = 12.dp, vertical = 8.dp),
                        style = CodeStyle, color = color, softWrap = false, maxLines = Int.MAX_VALUE,
                    )
                }
            } else if (block.bullet != null || block.indent > 0) {
                Row(Modifier.padding(start = (block.indent * 16).dp)) {
                    Text(block.bullet.orEmpty(), Modifier.width(20.dp), style = style, color = c.textMuted)
                    Text(text, style = style, color = color)
                }
            } else {
                Text(text, style = style, color = color)
            }
        }
    }
}

private fun RichBlock.annotated(c: AdoColors, onLink: (String) -> Unit): AnnotatedString =
    buildAnnotatedString {
        spans.forEach { span ->
            val style = SpanStyle(
                fontWeight = when {
                    span.bold -> FontWeight.Bold
                    span.mention -> FontWeight.Medium
                    else -> null
                },
                fontStyle = if (span.italic) FontStyle.Italic else null,
                color = if (span.mention) c.primary else Color.Unspecified,
                background = when {
                    span.mention -> c.primaryContainer
                    span.code -> c.surfaceAlt
                    else -> Color.Unspecified
                },
                fontFamily = if (span.code) FontFamily.Monospace else null,
            )
            val link = span.link
            if (link != null && (link.startsWith("http://") || link.startsWith("https://") || link.startsWith("mailto:"))) {
                val annotation = LinkAnnotation.Clickable(
                    link, TextLinkStyles(SpanStyle(color = c.primary, textDecoration = TextDecoration.Underline)),
                ) { onLink(link) }
                withLink(annotation) { withStyle(style) { append(span.text) } }
            } else {
                withStyle(style) { append(span.text) }
            }
        }
    }
