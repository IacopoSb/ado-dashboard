package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.Person

/**
 * What the composer holds: the text as typed, and the people picked from the suggestions. A
 * mention is the text "@Display Name" of one of [mentions]: edited into something else, it is
 * plain text again.
 */
data class Draft(val text: String = "", val mentions: List<Person> = emptyList()) {
    val isBlank get() = text.isBlank()

    /** The mentions still in the text, by where they are. */
    fun mentionRanges(): List<Pair<IntRange, Person>> = CommentMarkup.ranges(text, mentions)

    /** Only the people still mentioned. */
    fun pruned(): Draft = copy(mentions = mentions.filter { p -> mentionRanges().any { it.second.id == p.id } }.distinctBy { it.id })
}

/** "@an" being typed: [start] is the "@", [query] what follows it up to the cursor. */
data class MentionQuery(val start: Int, val end: Int, val query: String)

/** Azure DevOps' comment HTML, from what the composer holds. Pure. */
object CommentMarkup {
    /**
     * [draft] as the HTML Azure DevOps stores: each mention as
     * `<a href="#" data-vss-mention="version:2.0,<id>">@Name</a>`, like its own editor makes them
     * (that is what notifies the person), everything else escaped and line breaks as `<br>`.
     */
    fun build(draft: Draft): String {
        val text = draft.text.trim()
        val ranges = ranges(text, draft.mentions)
        val out = StringBuilder()
        var at = 0
        ranges.forEach { (range, person) ->
            out.append(escapeText(text.substring(at, range.first)))
            out.append("<a href=\"#\" data-vss-mention=\"version:2.0,")
                .append(escape(person.id))
                .append("\">@")
                .append(escape(person.name))
                .append("</a>")
            at = range.last + 1
        }
        out.append(escapeText(text.substring(at)))
        return out.toString()
    }

    /** Escaped, with line breaks as `<br>`. */
    private fun escapeText(text: String) = escape(text.replace("\r\n", "\n")).replace("\n", "<br>")

    fun escape(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(ch)
            }
        }
    }

    /**
     * Where "@Name" of one of [people] stands in [text], first to last, not overlapping: the
     * longest name wins ("@Anna Verdi" over "@Anna"), and a name must end there ("@Anna" is not
     * in "@Annabel").
     */
    fun ranges(text: String, people: List<Person>): List<Pair<IntRange, Person>> {
        if (people.isEmpty()) return emptyList()
        val byLength = people.filter { it.name.isNotBlank() }.distinctBy { it.id }.sortedByDescending { it.name.length }
        val out = mutableListOf<Pair<IntRange, Person>>()
        var i = 0
        while (i < text.length) {
            if (text[i] == '@' && (i == 0 || !text[i - 1].isLetterOrDigit())) {
                val person = byLength.firstOrNull { p ->
                    val end = i + 1 + p.name.length
                    text.startsWith(p.name, i + 1) && (end == text.length || !text[end].isLetterOrDigit())
                }
                if (person != null) {
                    val range = i until i + 1 + person.name.length
                    out += range to person
                    i = range.last + 1
                    continue
                }
            }
            i++
        }
        return out
    }
}

/** The mention being typed and the text once a person is picked. Pure. */
object MentionTyping {
    /** Longer than any name: past it, the "@" was not a mention. */
    private const val MAX_QUERY = 40
    private const val MAX_WORDS = 3

    /**
     * The mention typed up to [cursor]: an "@" at the start or after a space or punctuation (not
     * in an e-mail address), then at least a letter, up to three words on the same line. Null
     * inside a mention already picked.
     */
    fun query(draft: Draft, cursor: Int): MentionQuery? {
        val text = draft.text
        if (cursor < 0 || cursor > text.length) return null
        val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
        val at = text.lastIndexOf('@', cursor - 1)
        if (at < lineStart) return null
        if (at > 0 && text[at - 1].isLetterOrDigit()) return null
        val query = text.substring(at + 1, cursor)
        if (query.isEmpty() || query.length > MAX_QUERY || !query.first().isLetter()) return null
        if (query.contains("  ") || query.split(' ').size > MAX_WORDS) return null
        // Right after a mention picked ("@Anna Verdi |"): not a new one
        if (draft.mentionRanges().any { (range, _) -> at in range }) return null
        return MentionQuery(at, cursor, query)
    }

    /** [person] in place of [query], with a space after it; returns the draft and the cursor. */
    fun insert(draft: Draft, query: MentionQuery, person: Person): Pair<Draft, Int> {
        val text = draft.text
        val mention = "@${person.name}"
        val after = text.substring(query.end)
        val spaced = if (after.startsWith(' ')) mention else "$mention "
        val newText = text.substring(0, query.start) + spaced + after
        val cursor = query.start + mention.length + 1
        val mentions = (draft.mentions.filterNot { it.id == person.id } + person)
        return Draft(newText, mentions).pruned() to cursor.coerceAtMost(newText.length)
    }

    /** Replying to [author]: their mention, ready for the text. */
    fun reply(author: Person): Draft = Draft("@${author.name} ", listOf(author))
}
