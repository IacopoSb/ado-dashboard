package io.github.iacoposb.adodash.update

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class GitHubReleasesTest {
    private val server = MockWebServer().apply { start() }
    private val releases = GitHubReleases("IacopoSb/ado-dashboard", apiBase = server.url("/api").toString().trimEnd('/'))

    @get:Rule val tmp = TemporaryFolder()

    @After fun tearDown() = server.shutdown()

    private val apk = ByteArray(200_000) { (it % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    private fun listJson(version: String) = """
        [{"tag_name":"release/$version","draft":false,"prerelease":false,"body":"### Novità\n- Something",
          "assets":[{"name":"ado-dashboard-$version.apk","browser_download_url":"${server.url("/dl/app.apk")}","size":${apk.size}},
                    {"name":"SHA256SUMS","browser_download_url":"${server.url("/dl/SHA256SUMS")}","size":100}]}]
    """.trimIndent()

    @Test fun `newer release is available`() = runTest {
        server.enqueue(MockResponse().setBody(listJson("0.2.0")))
        val result = releases.check("0.1.0")
        assertTrue(result is CheckResult.Available)
        assertEquals("0.2.0", (result as CheckResult.Available).release.version)
        val request = server.takeRequest()
        assertEquals("/api/repos/IacopoSb/ado-dashboard/releases?per_page=30", request.path)
        // Unauthenticated: nothing secret in the APK
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun `same version is up to date`() = runTest {
        server.enqueue(MockResponse().setBody(listJson("0.2.0")))
        assertEquals(CheckResult.UpToDate, releases.check("0.2.0"))
    }

    @Test fun `private repo or no releases`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))
        assertEquals(CheckResult.NoReleases, releases.check("0.1.0"))
        server.enqueue(MockResponse().setBody("[]"))
        assertEquals(CheckResult.NoReleases, releases.check("0.1.0"))
    }

    @Test fun `rate limit and network errors have a reason`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val limited = runCatching { releases.check("0.1.0") }.exceptionOrNull()
        assertEquals(UpdateException.Reason.RATE_LIMITED, (limited as UpdateException).reason)
        // Nothing listens on port 1
        val offline = runCatching { GitHubReleases("a/b", apiBase = "http://127.0.0.1:1").check("0.1.0") }.exceptionOrNull()
        assertEquals(UpdateException.Reason.UNREACHABLE, (offline as UpdateException).reason)
    }

    @Test fun `download verifies sha256sums`() = runTest {
        server.enqueue(MockResponse().setBody("$apkSha  ado-dashboard-0.2.0.apk\n"))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(apk)))
        val release = newestRelease(listJson("0.2.0"))!!
        val dest = tmp.newFile("a.apk")
        val progress = mutableListOf<Float>()
        releases.download(release, dest) { progress += it }
        assertArrayEquals(apk, dest.readBytes())
        assertEquals(1f, progress.last())
    }

    @Test fun `download with wrong digest fails and removes the file`() {
        server.enqueue(MockResponse().setBody("${"0".repeat(64)}  ado-dashboard-0.2.0.apk\n"))
        server.enqueue(MockResponse().setBody(okio.Buffer().write(apk)))
        val release = newestRelease(listJson("0.2.0"))!!
        val dest = tmp.newFile("b.apk")
        val e = assertThrows(UpdateException::class.java) { kotlinx.coroutines.runBlocking { releases.download(release, dest) } }
        assertEquals(UpdateException.Reason.CHECKSUM_MISMATCH, e.reason)
        assertFalse(dest.exists())
    }
}
