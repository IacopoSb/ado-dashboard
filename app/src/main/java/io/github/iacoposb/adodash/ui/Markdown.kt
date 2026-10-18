package io.github.iacoposb.adodash.ui

/**
 * The markdown of pull request descriptions and comments as readable text: paragraphs,
 * headings, lists, code blocks, inline code, bold, italic, links and mentions (`@<id>`, shown as
 * "@Name"). Tables, images and HTML are shown as their text. Pure.
 */
object Markdown {
    /** How Azure DevOps writes a mention in a pull request: the identity's id in angle brackets. */
    val MENTION = Regex("@<([0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12})>")
    private val FENCE = Regex("^\\s{0,3}(```+|~~~+)(.*)$")
    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$")
    private val LIST_ITEM = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(.*)$")
    private val QUOTE = Regex("^\\s{0,3}>\\s?(.*)$")
    private val RULE = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private const val ESCAPABLE = "\\`*_{}[]()#+-.!<>|~@"

    /** The ids mentioned in [markdown], lowercase. */
    fun mentionIds(markdown: String): Set<String> = MENTION.findAll(markdown).map { it.groupValues[1].lowercase() }.toSet()

    /**
     * [names] by lowercase id; a mention of someone not in it reads "@[unknown]". A line break
     * inside a paragraph stays one, as Azure DevOps shows it.
     */
    fun parse(markdown: String, names: Map<String, String> = emptyMap(), unknown: String = "?"): List<RichBlock> {
        val blocks = mutableListOf<RichBlock>()
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val paragraph = mutableListOf<String>()
        var bullet: String? = null
        var indent = 0
        var inList = false

        fun flush() {
            if (paragraph.isNotEmpty()) {
                val spans = Inline(names, unknown).run(paragraph.joinToString("\n").trim())
                if (spans.isNotEmpty()) blocks += RichBlock(spans, bullet, indent)
            }
            paragraph.clear()
            bullet = null
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.matchEntire(line)
            when {
                fence != null -> {
                    flush()
                    val marker = fence.groupValues[1]
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(marker)) {
                        code += lines[i]
                        i++
                    }
                    val text = code.joinToString("\n").trimEnd()
                    if (text.isNotEmpty()) {
                        blocks += RichBlock(listOf(RichSpan(text, code = true)), indent = if (inList) indent + 1 else 0, codeBlock = true)
                    }
                }
                line.isBlank() -> {
                    flush()
                    // A blank line ends a list only if what follows is not indented
                    val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() }
                    if (next == null || (!LIST_ITEM.matches(next) && !next.startsWith("  "))) {
                        inList = false
                        indent = 0
                    }
                }
                RULE.matches(line) -> flush()
                HEADING.matches(line) -> {
                    flush()
                    inList = false
                    indent = 0
                    val text = HEADING.matchEntire(line)!!.groupValues[1]
                    val spans = Inline(names, unknown).run(text).map { it.copy(bold = true) }
                    if (spans.isNotEmpty()) blocks += RichBlock(spans)
                }
                LIST_ITEM.matches(line) -> {
                    flush()
                    val m = LIST_ITEM.matchEntire(line)!!
                    val spaces = m.groupValues[1].replace("\t", "    ").length
                    indent = (spaces / 2).coerceAtMost(MAX_INDENT)
                    val marker = m.groupValues[2]
                    bullet = if (marker[0].isDigit()) "${marker.dropLast(1)}." else "•"
                    inList = true
                    paragraph += m.groupValues[3]
                }
                QUOTE.matches(line) -> {
                    if (paragraph.isEmpty()) inList = false
                    paragraph += QUOTE.matchEntire(line)!!.groupValues[1]
                }
                else -> {
                    // More of a list item's text, or of a paragraph
                    if (paragraph.isEmpty() && !inList) indent = 0
                    if (paragraph.isEmpty() && inList && !line.startsWith(" ")) {
                        inList = false
                        indent = 0
                    }
                    paragraph += line.trim()
                }
            }
            i++
        }
        flush()
        return blocks
    }

    private const val MAX_INDENT = 3

    /** The inline markup of a paragraph. */
    private class Inline(private val names: Map<String, String>, private val unknown: String) {
        private val out = mutableListOf<RichSpan>()

        fun run(text: String): List<RichSpan> {
            parse(text, RichSpan(""))
            return out.filter { it.text.isNotEmpty() }
        }

        private fun emit(text: String, style: RichSpan) {
            if (text.isEmpty()) return
            val span = style.copy(text = text)
            val last = out.lastOrNull()
            if (last != null && last.sameStyle(span)) out[out.lastIndex] = last.copy(text = last.text + text) else out += span
        }

        private fun parse(text: String, style: RichSpan) {
            val plain = StringBuilder()
            fun flushPlain() {
                emit(plain.toString(), style)
                plain.clear()
            }
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                // Escaped punctuation: the character itself
                if (ch == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE) {
                    plain.append(text[i + 1])
                    i += 2
                    continue
                }
                if (ch == '`') {
                    val run = text.countRun(i, '`')
                    val close = text.indexOf("`".repeat(run), i + run).takeIf { it >= 0 && !(it + run < text.length && text[it + run] == '`') }
                    if (close != null) {
                        flushPlain()
                        var code = text.substring(i + run, close).replace('\n', ' ')
                        if (code.length > 2 && code.startsWith(' ') && code.endsWith(' ')) code = code.substring(1, code.length - 1)
                        emit(code, style.copy(code = true, link = null))
                        i = close + run
                        continue
                    }
                    plain.append("`".repeat(run))
                    i += run
                    continue
                }
                if (ch == '@') {
                    val m = MENTION.matchAt(text, i)
                    if (m != null) {
                        flushPlain()
                        val name = names[m.groupValues[1].lowercase()] ?: unknown
                        emit("@$name", style.copy(mention = true, link = null))
                        i = m.range.last + 1
                        continue
                    }
                }
                if (ch == '!' && i + 1 < text.length && text[i + 1] == '[') {
                    val link = linkAt(text, i + 1)
                    if (link != null) {
                        // An image: its description
                        plain.append(link.label)
                        i = link.end
                        continue
                    }
                }
                if (ch == '[') {
                    val link = linkAt(text, i)
                    if (link != null) {
                        flushPlain()
                        parse(link.label, style.copy(link = link.url))
                        i = link.end
                        continue
                    }
                }
                if (ch == '<') {
                    val close = text.indexOf('>', i)
                    val inside = if (close > i) text.substring(i + 1, close) else ""
                    if (inside.startsWith("http://") || inside.startsWith("https://") || inside.startsWith("mailto:")) {
                        if (' ' !in inside) {
                            flushPlain()
                            emit(inside, style.copy(link = inside))
                            i = close + 1
                            continue
                        }
                    }
                }
                if ((ch == 'h') && style.link == null && (i == 0 || !text[i - 1].isLetterOrDigit()) &&
                    (text.startsWith("https://", i) || text.startsWith("http://", i))
                ) {
                    var end = i
                    while (end < text.length && !text[end].isWhitespace() && text[end] != '<') end++
                    while (end > i && text[end - 1] in ".,;:!?)'\"*_") end--
                    val url = text.substring(i, end)
                    flushPlain()
                    emit(url, style.copy(link = url))
                    i = end
                    continue
                }
                if (ch == '*' || ch == '_') {
                    val run = text.countRun(i, ch)
                    val wordInside = ch == '_' && i > 0 && text[i - 1].isLetterOrDigit()
                    val opens = !wordInside && i + run < text.length && !text[i + run].isWhitespace()
                    if (opens && run >= 2) {
                        val close = closing(text, i + 2, "$ch$ch")
                        if (close != null) {
                            flushPlain()
                            parse(text.substring(i + 2, close), style.copy(bold = true))
                            i = close + 2
                            continue
                        }
                    }
                    if (opens) {
                        val close = closing(text, i + 1, "$ch")
                        if (close != null) {
                            flushPlain()
                            parse(text.substring(i + 1, close), style.copy(italic = true))
                            i = close + 1
                            continue
                        }
                    }
                    plain.append(ch.toString().repeat(run))
                    i += run
                    continue
                }
                plain.append(ch)
                i++
            }
            flushPlain()
        }

        /** Where [delimiter] closes an emphasis opened before [from]: after text, not before a space. */
        private fun closing(text: String, from: Int, delimiter: String): Int? {
            var at = text.indexOf(delimiter, from)
            while (at >= 0) {
                val afterOk = delimiter[0] != '_' || at + delimiter.length >= text.length || !text[at + delimiter.length].isLetterOrDigit()
                val single = delimiter.length == 1 && at + 1 < text.length && text[at + 1] == delimiter[0]
                if (at > from && !text[at - 1].isWhitespace() && afterOk && !single) return at
                // "**" inside an italic run is skipped as a whole
                at = text.indexOf(delimiter, at + if (single) 2 else 1)
            }
            return null
        }

        private class Link(val label: String, val url: String, val end: Int)

        /** "[label](url)" starting at [start]; null if it is not a link. */
        private fun linkAt(text: String, start: Int): Link? {
            var depth = 0
            var i = start
            while (i < text.length) {
                when (text[i]) {
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) break
                    }
                    '\n' -> if (i + 1 < text.length && text[i + 1] == '\n') return null
                }
                i++
            }
            if (i >= text.length || i + 1 >= text.length || text[i + 1] != '(') return null
            val close = text.indexOf(')', i + 2).takeIf { it > 0 } ?: return null
            val target = text.substring(i + 2, close).trim().substringBefore(' ').removeSurrounding("<", ">")
            return Link(text.substring(start + 1, i), target, close + 1)
        }

        private fun String.countRun(from: Int, ch: Char): Int {
            var n = 0
            while (from + n < length && this[from + n] == ch) n++
            return n
        }
    }
}

/** What the composer holds as Azure DevOps' pull request markdown. Pure. */
object PrMarkup {
    /**
     * [draft] as the markdown of a pull request comment: each mention as `@<id>` (what notifies
     * the person), the rest as typed, trimmed.
     */
    fun build(draft: Draft): String {
        val text = draft.text.trim()
        val out = StringBuilder()
        var at = 0
        CommentMarkup.ranges(text, draft.mentions).forEach { (range, person) ->
            out.append(text, at, range.first)
            out.append("@<").append(person.id).append('>')
            at = range.last + 1
        }
        out.append(text.substring(at))
        return out.toString()
    }
}
