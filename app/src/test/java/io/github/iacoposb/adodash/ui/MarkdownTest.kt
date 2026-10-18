package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pull request markdown as text, and the composer's text as markdown. */
class MarkdownTest {
    private val id = "aaaaaaaa-1111-2222-3333-444444444444"
    private val names = mapOf(id to "Iacopo Sbalchiero")

    private fun spans(md: String) = Markdown.parse(md, names, "someone").single().spans

    @Test fun `paragraphs split on blank lines, line breaks kept inside`() {
        val blocks = Markdown.parse("First line\nsecond line\n\nNew paragraph")
        assertEquals(listOf("First line\nsecond line", "New paragraph"), blocks.map { it.text })
    }

    @Test fun `bold, italic and inline code`() {
        val s = spans("A **bold** and *italic* and _also_ and `code` here")
        assertEquals(RichSpan("bold", bold = true), s[1])
        assertEquals(RichSpan("italic", italic = true), s[3])
        assertEquals(RichSpan("also", italic = true), s[5])
        assertEquals(RichSpan("code", code = true), s[7])
        assertEquals("A bold and italic and also and code here", s.joinToString("") { it.text })
    }

    @Test fun `underscores inside words and lone stars stay text`() {
        assertEquals(listOf(RichSpan("max_retry_count is 2 * 3")), spans("max_retry_count is 2 * 3"))
    }

    @Test fun `mentions read as names, unknown ones as someone`() {
        val s = spans("@<$id> and @<bbbbbbbb-1111-2222-3333-444444444444>, see")
        assertEquals(RichSpan("@Iacopo Sbalchiero", mention = true), s[0])
        assertEquals(RichSpan("@someone", mention = true), s[2])
    }

    @Test fun `links, autolinks and bare addresses`() {
        val s = spans("See [the log](https://contoso.example/log) or <https://a.example> or https://b.example/x.")
        assertEquals(RichSpan("the log", link = "https://contoso.example/log"), s[1])
        assertEquals(RichSpan("https://a.example", link = "https://a.example"), s[3])
        assertEquals(RichSpan("https://b.example/x", link = "https://b.example/x"), s[5])
        assertEquals(".", s.last().text)
    }

    @Test fun `bold inside a link`() {
        val s = spans("[**PR**](https://x.example)")
        assertEquals(RichSpan("PR", bold = true, link = "https://x.example"), s.single())
    }

    @Test fun `escaped characters are themselves`() {
        assertEquals(listOf(RichSpan("*not italic*")), spans("\\*not italic\\*"))
    }

    @Test fun `lists with bullets, numbers and nesting`() {
        val blocks = Markdown.parse("Steps:\n- one\n- two\n  - nested\n1. first\n2. second")
        assertEquals(listOf(null, "•", "•", "•", "1.", "2."), blocks.map { it.bullet })
        assertEquals(listOf(0, 0, 0, 1, 0, 0), blocks.map { it.indent })
        assertEquals("nested", blocks[3].text)
    }

    @Test fun `fenced code blocks are kept as they are`() {
        val blocks = Markdown.parse("Try:\n```kotlin\nval a = 1\n  **not bold**\n```\nDone")
        assertEquals(3, blocks.size)
        assertTrue(blocks[1].codeBlock)
        assertEquals("val a = 1\n  **not bold**", blocks[1].text)
        assertTrue(blocks[1].spans.single().code)
        assertEquals("Done", blocks[2].text)
    }

    @Test fun `headings are bold, quotes their text, images their description`() {
        val blocks = Markdown.parse("## Summary\n\n> quoted\n\n![screenshot](https://x.example/a.png)")
        assertEquals(RichSpan("Summary", bold = true), blocks[0].spans.single())
        assertEquals("quoted", blocks[1].text)
        assertEquals("screenshot", blocks[2].text)
    }

    @Test fun `a heading keeps a trailing # that is part of it, not a closing run`() {
        assertEquals("Migrate to C#", Markdown.parse("## Migrate to C#").single().text)
        assertEquals("Migrate to C#", Markdown.parse("## Migrate to C# ##").single().text)
        assertEquals("Summary", Markdown.parse("### Summary ###  ").single().text)
    }

    @Test fun `the ids mentioned`() {
        assertEquals(setOf(id), Markdown.mentionIds("@<${id.uppercase()}> hi @<$id>"))
    }

    @Test fun `the composer's mentions become ids`() {
        val luca = Person("u-1", "Luca Rossi", "")
        val draft = Draft("  @Luca Rossi can you check **this**? @Luca  ", listOf(luca))
        assertEquals("@<u-1> can you check **this**? @Luca", PrMarkup.build(draft))
    }

    @Test fun `a reply round trips through markdown`() {
        val me = Person(id, "Iacopo Sbalchiero", "")
        val md = PrMarkup.build(MentionTyping.reply(me).copy(text = "@Iacopo Sbalchiero thanks"))
        assertEquals("@<$id> thanks", md)
        assertEquals("@Iacopo Sbalchiero thanks", Markdown.parse(md, names).single().text)
    }
}
