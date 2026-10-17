package io.github.iacoposb.adodash.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlTextTest {
    private fun texts(html: String) = HtmlText.parse(html).map { it.text }

    @Test fun `paragraphs and line breaks`() {
        assertEquals(
            listOf("After about an hour the refresh token is refused.", "Only with guest\naccounts."),
            texts("<div>After about an hour   the refresh\n token is refused.</div><div><br></div><p>Only with guest<br/>accounts.</p>"),
        )
    }

    @Test fun `plain text without tags is one paragraph`() {
        assertEquals(listOf("Just text & more"), texts("Just text &amp; more"))
    }

    @Test fun `entities decoded`() {
        assertEquals(listOf("a < b > c \"d\" 'e' é €"), texts("a &lt; b &gt; c &quot;d&quot; &#39;e&#39; &#233; &#x20AC;"))
        assertEquals(listOf("&unknown;"), texts("&unknown;"))
    }

    @Test fun `bold, italic and links as spans`() {
        val block = HtmlText.parse("""See <b>this</b> and <em>that</em> on <a href="https://example.com/x?a=1&amp;b=2">the site</a>.""").single()
        assertEquals(
            listOf(
                RichSpan("See "),
                RichSpan("this", bold = true),
                RichSpan(" and "),
                RichSpan("that", italic = true),
                RichSpan(" on "),
                RichSpan("the site", link = "https://example.com/x?a=1&b=2"),
                RichSpan("."),
            ),
            block.spans,
        )
    }

    @Test fun `lists with bullets, numbers and nesting`() {
        val blocks = HtmlText.parse("<ul><li>One</li><li>Two<ul><li>Two a</li></ul></li></ul><ol><li>First</li><li><p>Second</p><p>more</p></li></ol>")
        assertEquals(
            listOf(
                RichBlock(listOf(RichSpan("One")), "•", 0),
                RichBlock(listOf(RichSpan("Two")), "•", 0),
                RichBlock(listOf(RichSpan("Two a")), "•", 1),
                RichBlock(listOf(RichSpan("First")), "1.", 0),
                RichBlock(listOf(RichSpan("Second")), "2.", 0),
                RichBlock(listOf(RichSpan("more")), null, 0),
            ),
            blocks,
        )
    }

    @Test fun `headings are bold paragraphs`() {
        assertEquals(listOf(RichBlock(listOf(RichSpan("Steps", bold = true))), RichBlock(listOf(RichSpan("Open it")))),
            HtmlText.parse("<h2>Steps</h2>Open it"))
    }

    @Test fun `images, scripts, styles and comments left out`() {
        assertEquals(
            listOf("Before after"),
            texts("""<style>p{color:red}</style><p>Before <img src="https://x/y.png" alt="shot"/><!-- note -->after</p><script>alert(1)</script>"""),
        )
    }

    @Test fun `tables keep their cells apart`() {
        assertEquals(listOf("Name  Value", "a  1"), texts("<table><tr><th>Name</th><th>Value</th></tr><tr><td>a</td><td>1</td></tr></table>"))
    }

    @Test fun `attributes with angle brackets do not break the tag`() {
        assertEquals(listOf("ok"), texts("""<span title="a > b">ok</span>"""))
    }

    @Test fun `a comment's mentions and code`() {
        val blocks = HtmlText.parse(
            """<div><a href="#" data-vss-mention="version:2.0,aaaa-1111">@Iacopo Sbalchiero</a> try <code>guest01</code>:</div>""" +
                "<pre>val x = 1\n  y</pre>",
        )
        assertEquals(
            listOf(
                RichSpan("@Iacopo Sbalchiero", mention = true),
                RichSpan(" try "),
                RichSpan("guest01", code = true),
                RichSpan(":"),
            ),
            blocks[0].spans,
        )
        assertEquals(listOf(RichSpan("val x = 1\n  y", code = true)), blocks[1].spans)
    }

    @Test fun `empty or blank html has no blocks`() {
        assertEquals(emptyList<String>(), texts(""))
        assertEquals(emptyList<String>(), texts("<div>&nbsp;</div><div><br></div>"))
    }
}
