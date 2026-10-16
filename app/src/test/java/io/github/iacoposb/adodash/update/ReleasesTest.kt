package io.github.iacoposb.adodash.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasesTest {
    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false, apk: Boolean = true, sums: Boolean = true) = """
        {"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"body":"notes $tag","assets":[
          ${if (apk) """{"name":"ado-dashboard-${tag.removePrefix("release/")}.apk","browser_download_url":"https://dl/$tag.apk","size":1234},""" else ""}
          ${if (sums) """{"name":"SHA256SUMS","browser_download_url":"https://dl/$tag/SHA256SUMS","size":90}""" else """{"name":"other.txt","browser_download_url":"x","size":1}"""}
        ]}
    """.trimIndent()

    private fun list(vararg releases: String) = releases.joinToString(",", "[", "]")

    @Test fun `tags are release slash semver only`() {
        assertEquals(Version(0, 2, 0), Version.ofTag("release/0.2.0"))
        assertEquals(Version(1, 10, 3), Version.ofTag("release/1.10.3"))
        assertNull(Version.ofTag("v0.2.0"))
        assertNull(Version.ofTag("release/0.2"))
        assertNull(Version.ofTag("release/0.2.0-rc1"))
        assertNull(Version.ofTag("app-release/0.2.0"))
    }

    @Test fun `versions compare numerically`() {
        assertTrue(isNewer("0.10.0", "0.9.3"))
        assertTrue(isNewer("1.0.0", "0.99.99"))
        assertTrue(isNewer("0.2.1", "0.2.0"))
        assertFalse(isNewer("0.2.0", "0.2.0"))
        assertFalse(isNewer("0.1.9", "0.2.0"))
        assertFalse(isNewer("garbage", "0.2.0"))
        // Suffixed local builds compare by their numbers
        assertFalse(isNewer("0.2.0", "0.2.0-dev"))
        assertTrue(isNewer("0.2.1", "0.2.0-dev"))
    }

    @Test fun `newest skips drafts, prereleases, other tags and releases without apk`() {
        val json = list(
            release("release/0.2.0"),
            release("release/0.9.0", draft = true),
            release("release/0.8.0", prerelease = true),
            release("release/0.7.0", apk = false),
            release("v5.0.0"),
            release("release/0.10.0", sums = false),
            release("release/0.3.0"),
        )
        val r = newestRelease(json)!!
        assertEquals("0.10.0", r.version)
        assertEquals("release/0.10.0", r.tag)
        assertEquals("ado-dashboard-0.10.0.apk", r.apkName)
        assertEquals("https://dl/release/0.10.0.apk", r.apkUrl)
        assertEquals(1234L, r.apkSize)
        assertNull(r.sumsUrl)
        assertEquals("https://dl/release/0.3.0/SHA256SUMS", newestRelease(list(release("release/0.3.0")))!!.sumsUrl)
    }

    @Test fun `no usable release is null`() {
        assertNull(newestRelease("[]"))
        assertNull(newestRelease(list(release("release/1.0.0", draft = true), release("nightly"))))
    }

    @Test fun `sha256sums map names to digests`() {
        val a = "a".repeat(64)
        val b = "B".repeat(64)
        val sums = parseSha256Sums("$a  ado-dashboard-0.2.0.apk\n$b *SHA256SUMS\n\nnot a line\n")
        assertEquals(mapOf("ado-dashboard-0.2.0.apk" to a, "SHA256SUMS" to b.lowercase()), sums)
    }

    @Test fun `notes keep headings and bullets`() {
        val notes = parseNotes("### Novità\n- Add update check\n* Other\n\n### Correzioni\n- Fix crash\nPlain text")
        assertEquals(
            listOf(
                NoteLine.Heading("Novità"), NoteLine.Bullet("Add update check"), NoteLine.Bullet("Other"),
                NoteLine.Heading("Correzioni"), NoteLine.Bullet("Fix crash"), NoteLine.Text("Plain text"),
            ),
            notes,
        )
    }
}
