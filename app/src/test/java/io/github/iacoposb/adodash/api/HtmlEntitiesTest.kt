package io.github.iacoposb.adodash.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HtmlEntitiesTest {
    @Test fun `named references, Latin-1 included`() {
        assertEquals("Attuatore con più comandi", HtmlEntities.decode("Attuatore con pi&ugrave; comandi"))
        assertEquals("È già così: perché no?", HtmlEntities.decode("&Egrave; gi&agrave; cos&igrave;: perch&eacute; no?"))
        assertEquals("a & b < c > d \"e\" 'f'", HtmlEntities.decode("a &amp; b &lt; c &gt; d &quot;e&quot; &apos;f&apos;"))
        assertEquals("1 – 2 — 3… €", HtmlEntities.decode("1 &ndash; 2 &mdash; 3&hellip; &euro;"))
        assertEquals(" ", HtmlEntities.decode("&nbsp;"))
    }

    @Test fun `names are case-sensitive, except the few HTML allows in capitals`() {
        assertEquals("ÙÀ ù", HtmlEntities.decode("&Ugrave;&Agrave; &ugrave;"))
        assertEquals("& <", HtmlEntities.decode("&AMP; &LT;"))
        assertEquals("&UGRAVE;", HtmlEntities.decode("&UGRAVE;"))
    }

    @Test fun `decimal and hex references`() {
        assertEquals("più ' €", HtmlEntities.decode("pi&#249; &#39; &#x20AC;"))
        assertEquals("ù😀", HtmlEntities.decode("&#XF9;&#x1F600;"))
    }

    @Test fun `one pass only`() {
        assertEquals("&lt;b&gt;", HtmlEntities.decode("&amp;lt;b&amp;gt;"))
        assertEquals("&amp;", HtmlEntities.decode("&amp;amp;"))
    }

    @Test fun `plain text stays as it is`() {
        listOf(
            "AT&T and R&D", "Tom & Jerry", "a&b;c", "&unknown;", "& ;", "&#;", "&#x;", "&ugrave", "100% & more",
            "&#0;", "&#xD800;", "&#x110000;", "&#99999999;",
        ).forEach { assertEquals(it, HtmlEntities.decode(it)) }
        val plain = "Nothing to decode"
        assertSame(plain, HtmlEntities.decode(plain))
    }
}
