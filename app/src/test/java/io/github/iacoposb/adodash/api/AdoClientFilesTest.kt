package io.github.iacoposb.adodash.api

import io.github.iacoposb.adodash.Fakes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The Files and Commits tabs' calls: pushes, changed files, contents, commits, threads on a line. */
class AdoClientFilesTest {
    private val server = MockWebServer().apply { start() }
    private val client = AdoClient(server.url("/org").toString(), AdoClient.pat("secret"))
    private val pr = Fakes.pr(311, Fakes.anna).copy(repoId = "repo-1")
    private val base = "/org/Contoso/_apis/git/repositories/repo-1/pullRequests/311"

    @After fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun `the pushes with their commits, oldest first`() = runTest {
        server.enqueue(
            json(
                """{"value":[
                {"id":2,"sourceRefCommit":{"commitId":"s2"},"targetRefCommit":{"commitId":"t2"},"commonRefCommit":{"commitId":"c2"}},
                {"id":1,"sourceRefCommit":{"commitId":"s1"},"targetRefCommit":{"commitId":"t1"}}
                ]}""",
            ),
        )
        val list = client.pullRequestIterations(pr)
        assertEquals("$base/iterations?api-version=7.1", server.takeRequest().path)
        assertEquals(listOf(PrIteration(1, "s1", "t1"), PrIteration(2, "s2", "c2")), list)
    }

    @Test fun `the files of a push, folders left out`() = runTest {
        server.enqueue(
            json(
                """{"changeEntries":[
                {"changeTrackingId":1,"changeId":1,"item":{"objectId":"a","path":"/src/payments/Gateway.kt"},"changeType":"edit"},
                {"changeTrackingId":2,"changeId":2,"item":{"objectId":"b","path":"/src/payments/RetryPolicy.kt"},"changeType":"add"},
                {"changeTrackingId":3,"changeId":3,"item":{"path":"/old/Legacy.kt"},"changeType":"delete"},
                {"changeTrackingId":4,"changeId":4,"item":{"path":"/src/New.kt"},"changeType":"rename, edit","sourceServerItem":"/src/Old.kt"},
                {"changeTrackingId":5,"changeId":5,"item":{"path":"/src/payments","isFolder":true,"gitObjectType":"tree"},"changeType":"add"}
                ]}""",
            ),
        )
        val changes = client.iterationChanges(pr, 2)
        assertEquals("$base/iterations/2/changes?%24top=2000&api-version=7.1", server.takeRequest().path)
        assertEquals(
            listOf(
                PrFileChange("/src/payments/Gateway.kt", ChangeType.EDIT, changeTrackingId = 1),
                PrFileChange("/src/payments/RetryPolicy.kt", ChangeType.ADD, changeTrackingId = 2),
                PrFileChange("/old/Legacy.kt", ChangeType.DELETE, changeTrackingId = 3),
                PrFileChange("/src/New.kt", ChangeType.RENAME, originalPath = "/src/Old.kt", changeTrackingId = 4),
            ),
            changes,
        )
        assertNull(changes[1].basePath)
        assertNull(changes[2].targetPath)
        assertEquals("/src/Old.kt", changes[3].basePath)
        assertEquals("/src/payments", changes[0].folder)
        assertEquals("Gateway.kt", changes[0].name)
    }

    @Test fun `a file's content at a commit`() = runTest {
        server.enqueue(json("""{"path":"/src/A.kt","content":"fun a() {}\n","contentMetadata":{"encoding":65001,"isBinary":false}}"""))
        val content = client.fileContent(pr, "/src/A.kt", "abc123")
        assertEquals(
            "/org/Contoso/_apis/git/repositories/repo-1/items?path=%2Fsrc%2FA.kt&versionDescriptor.version=abc123" +
                "&versionDescriptor.versionType=commit&includeContent=true&api-version=7.1",
            server.takeRequest().path,
        )
        assertEquals(FileContent.Text("fun a() {}\n"), content)
    }

    @Test fun `binary, missing and too large files`() = runTest {
        server.enqueue(json("""{"content":"PNG","contentMetadata":{"isBinary":true}}"""))
        assertEquals(FileContent.Binary, client.fileContent(pr, "/a.png", "c"))
        server.enqueue(json("""{"content":"a\u0000b"}"""))
        assertEquals(FileContent.Binary, client.fileContent(pr, "/a.bin", "c"))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"TF401174: not found"}"""))
        assertEquals(FileContent.Missing, client.fileContent(pr, "/gone.kt", "c"))
        val big = "x".repeat(1_100_000)
        server.enqueue(json("""{"content":"$big"}"""))
        assertEquals(FileContent.TooLarge, client.fileContent(pr, "/big.txt", "c"))
    }

    @Test fun `the commits with the first line of their message`() = runTest {
        server.enqueue(
            json(
                """{"value":[
                {"commitId":"0123456789abcdef","comment":"Add retry\n\nWith backoff","author":{"name":"Anna Verdi","email":"a@x","date":"2026-10-16T08:00:00Z"}},
                {"commitId":"fedcba9876543210","comment":"Initial","author":{"name":"Anna Verdi","date":"2026-10-15T08:00:00Z"}}
                ]}""",
            ).setHeader("x-ms-continuationtoken", "next"),
        )
        server.enqueue(json("""{"value":[{"commitId":"aaaa","comment":"Older","author":{"name":"Marco"}}]}"""))
        val commits = client.pullRequestCommits(pr)
        assertEquals("$base/commits?api-version=7.1", server.takeRequest().path)
        assertEquals("$base/commits?continuationToken=next&api-version=7.1", server.takeRequest().path)
        assertEquals(3, commits.size)
        assertEquals("Add retry", commits[0].message)
        assertEquals("Anna Verdi", commits[0].author)
        assertEquals(Instant.parse("2026-10-16T08:00:00Z"), commits[0].date)
        assertEquals("01234567", commits[0].shortId)
        assertEquals("${server.url("/org")}/Contoso/_git/backend/commit/0123456789abcdef", commits[0].url)
    }

    @Test fun `a new thread on a line of a file`() = runTest {
        server.enqueue(
            json(
                """{"id":12,"status":"active","threadContext":{"filePath":"/src/A.kt","rightFileStart":{"line":88,"offset":1},"rightFileEnd":{"line":88,"offset":1}},
                "comments":[{"id":1,"parentCommentId":0,"author":{"id":"me","displayName":"Iacopo"},"content":"Why?","commentType":"text","publishedDate":"2026-10-16T10:00:00Z"}]}""",
            ),
        )
        val thread = client.createPullRequestFileThread(pr, "Why?", FileAnchor("/src/A.kt", 88), iteration = 3, changeTrackingId = 7)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("$base/threads?api-version=7.1", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("active", body["status"]!!.jsonPrimitive.content)
        assertEquals("Why?", body["comments"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
        val context = body["threadContext"]!!.jsonObject
        assertEquals("/src/A.kt", context["filePath"]!!.jsonPrimitive.content)
        assertEquals(88, context["rightFileStart"]!!.jsonObject["line"]!!.jsonPrimitive.int)
        assertEquals(1, context["rightFileStart"]!!.jsonObject["offset"]!!.jsonPrimitive.int)
        assertEquals(88, context["rightFileEnd"]!!.jsonObject["line"]!!.jsonPrimitive.int)
        assertNull(context["leftFileStart"])
        val prContext = body["pullRequestThreadContext"]!!.jsonObject
        assertEquals(7, prContext["changeTrackingId"]!!.jsonPrimitive.int)
        assertEquals(3, prContext["iterationContext"]!!.jsonObject["secondComparingIteration"]!!.jsonPrimitive.int)
        assertEquals("/src/A.kt", thread.filePath)
        assertEquals(88, thread.line)
        assertEquals(false, thread.leftSide)
    }

    @Test fun `a thread on a deleted line is on the left side`() = runTest {
        server.enqueue(
            json(
                """{"id":13,"status":"active","threadContext":{"filePath":"/src/A.kt","leftFileStart":{"line":86,"offset":1}},
                "comments":[{"id":1,"author":{"id":"me"},"content":"Gone?","publishedDate":"2026-10-16T10:00:00Z"}]}""",
            ),
        )
        val thread = client.createPullRequestFileThread(pr, "Gone?", FileAnchor("/src/A.kt", 86, left = true))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val context = body["threadContext"]!!.jsonObject
        assertEquals(86, context["leftFileStart"]!!.jsonObject["line"]!!.jsonPrimitive.int)
        assertEquals(86, context["leftFileEnd"]!!.jsonObject["line"]!!.jsonPrimitive.int)
        assertNull(context["rightFileStart"])
        // Without the push, no push context
        assertNull(body["pullRequestThreadContext"])
        assertTrue(thread.leftSide)
        assertEquals(86, thread.line)
    }
}
