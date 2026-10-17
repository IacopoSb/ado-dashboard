package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MentionsTest {
    private val anna = Person("bbbbbbbb-1111", "Anna Verdi", "anna@contoso.com")
    private val ann = Person("cccccccc-2222", "Anna", "anna2@contoso.com")
    private val luca = Person("dddddddd-3333", "Luca Rossi", "luca@contoso.com")

    private fun mention(p: Person) = "<a href=\"#\" data-vss-mention=\"version:2.0,${p.id}\">@${p.name}</a>"

    @Test fun `mentions as Azure DevOps markup, the rest escaped, line breaks as br`() {
        val draft = Draft("  @Anna Verdi <script>alert('x')</script> & \"quotes\"\nsecond line\r\nthird  ", listOf(anna))
        assertEquals(
            "${mention(anna)} &lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; &amp; &quot;quotes&quot;<br>second line<br>third",
            CommentMarkup.build(draft),
        )
    }

    @Test fun `a mention edited away is plain text again`() {
        assertEquals("@Anna Verd ok", CommentMarkup.build(Draft("@Anna Verd ok", listOf(anna))))
        assertEquals("@Anna Verdiana", CommentMarkup.build(Draft("@Anna Verdiana", listOf(anna))))
        assertEquals("mail@Anna Verdi", CommentMarkup.build(Draft("mail@Anna Verdi", listOf(anna))))
    }

    @Test fun `the longest name wins, every mention is marked`() {
        val draft = Draft("@Anna Verdi and @Anna, cc @Luca Rossi", listOf(ann, anna, luca))
        assertEquals("${mention(anna)} and ${mention(ann)}, cc ${mention(luca)}", CommentMarkup.build(draft))
    }

    @Test fun `a name with markup characters is escaped inside the mention`() {
        val odd = Person("e-1", "O'Brien <Ops>", "")
        assertEquals(
            "<a href=\"#\" data-vss-mention=\"version:2.0,e-1\">@O&#39;Brien &lt;Ops&gt;</a>",
            CommentMarkup.build(Draft("@O'Brien <Ops>", listOf(odd))),
        )
    }

    @Test fun `a mention is typed after an at sign at a word's start`() {
        assertEquals(MentionQuery(7, 10, "An"), MentionTyping.query(Draft("Thanks @An"), 10))
        assertEquals(MentionQuery(0, 8, "Anna Ve"), MentionTyping.query(Draft("@Anna Ve"), 8))
        assertEquals(MentionQuery(1, 4, "lu"), MentionTyping.query(Draft("(@lu) more"), 4))
        // Only "@": nothing typed yet
        assertNull(MentionTyping.query(Draft("Hi @"), 4))
        // An e-mail address
        assertNull(MentionTyping.query(Draft("write to me@contoso"), 19))
        // Another line, or too many words since the "@"
        assertNull(MentionTyping.query(Draft("@an\nhello"), 9))
        assertNull(MentionTyping.query(Draft("@anna can you look at it"), 24))
        assertNull(MentionTyping.query(Draft("@ anna"), 6))
        assertNull(MentionTyping.query(Draft("@1234"), 5))
    }

    @Test fun `no new mention right after one picked`() {
        val draft = Draft("@Anna Verdi ", listOf(anna))
        assertNull(MentionTyping.query(draft, 12))
        assertNull(MentionTyping.query(draft, 6))
        // A second one after it
        assertEquals("Lu", MentionTyping.query(draft.copy(text = "@Anna Verdi @Lu"), 15)!!.query)
    }

    @Test fun `picking replaces what was typed, with a space after`() {
        val typed = Draft("Thanks @an, see you")
        val query = MentionTyping.query(typed, 10)!!
        val (draft, cursor) = MentionTyping.insert(typed, query, anna)
        assertEquals("Thanks @Anna Verdi , see you", draft.text)
        assertEquals(listOf(anna), draft.mentions)
        assertEquals(19, cursor)

        val (end, endCursor) = MentionTyping.insert(Draft("cc @lu"), MentionTyping.query(Draft("cc @lu"), 6)!!, luca)
        assertEquals("cc @Luca Rossi ", end.text)
        assertEquals(end.text.length, endCursor)
        // Before a space already there: not a second one
        val (spaced, _) = MentionTyping.insert(Draft("@lu ok"), MentionTyping.query(Draft("@lu ok"), 3)!!, luca)
        assertEquals("@Luca Rossi ok", spaced.text)
    }

    @Test fun `a reply starts with its author's mention`() {
        val draft = MentionTyping.reply(luca)
        assertEquals("@Luca Rossi ", draft.text)
        assertEquals(mention(luca), CommentMarkup.build(draft))
    }

    @Test fun `pruned keeps only the people still mentioned`() {
        val draft = Draft("@Anna Verdi hello @Luc", listOf(anna, luca, anna))
        assertEquals(listOf(anna), draft.pruned().mentions)
    }
}
