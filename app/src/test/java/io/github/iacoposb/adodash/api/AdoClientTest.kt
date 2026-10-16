package io.github.iacoposb.adodash.api

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdoClientTest {
    private val server = MockWebServer().apply { start() }
    private val client = AdoClient(server.url("/org").toString(), AdoClient.pat("secret"))

    @After fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun `organization name maps to dev azure com`() {
        assertEquals("https://dev.azure.com/contoso", AdoClient.orgBaseUrl("contoso").toString())
        assertEquals("https://tfs.local/coll", AdoClient.orgBaseUrl("https://tfs.local/coll/").toString())
    }

    @Test fun `me uses basic auth with the PAT`() = runTest {
        server.enqueue(json("""{"authenticatedUser":{"id":"u1","providerDisplayName":"Iacopo"}}"""))
        assertEquals(Identity("u1", "Iacopo"), client.me())
        val request = server.takeRequest()
        assertEquals("/org/_apis/connectionData?api-version=7.1-preview", request.path)
        assertEquals("Basic OnNlY3JldA==", request.getHeader("Authorization"))
    }

    @Test fun `pull requests are parsed with web url`() = runTest {
        server.enqueue(json("""
            {"value":[{"pullRequestId":42,"title":"Fix login","creationDate":"2026-10-15T08:00:00.1234567Z",
              "createdBy":{"id":"u2","displayName":"Anna"},"sourceRefName":"refs/heads/fix/login",
              "targetRefName":"refs/heads/main","isDraft":false,"status":"active",
              "reviewers":[{"id":"u1","displayName":"Iacopo","vote":-5,"isRequired":true}],
              "repository":{"id":"r1","name":"web app","project":{"name":"My Project"}}}],"count":1}
        """.trimIndent()))
        val pr = client.activePullRequests("My Project", PrFilter.Reviewer("u1")).single()
        assertEquals("fix/login", pr.sourceBranch)
        assertEquals(-5, pr.reviewers.single().vote)
        assertTrue(pr.url.endsWith("/org/My%20Project/_git/web%20app/pullrequest/42"))
        val path = server.takeRequest().path!!
        assertTrue(path, path.startsWith("/org/My%20Project/_apis/git/pullrequests?searchCriteria.status=active&searchCriteria.reviewerId=u1"))
    }

    @Test fun `thread comments skip system ones`() = runTest {
        server.enqueue(json("""
            {"value":[{"id":5,"comments":[
              {"id":1,"author":{"id":"u2","displayName":"Anna"},"content":"ok","commentType":"text","publishedDate":"2026-10-15T08:00:00Z"},
              {"id":2,"author":{"id":"u2","displayName":"Anna"},"content":"voted","commentType":"system","publishedDate":"2026-10-15T08:00:00Z"}]}]}
        """.trimIndent()))
        val pr = PullRequest(1, "P", "r1", "repo", "t", Identity("u1", ""), "a", "b", false, emptyList(), java.time.Instant.EPOCH, "")
        assertEquals(listOf(1), client.pullRequestComments(pr).map { it.id })
    }

    @Test fun `expired PAT redirecting to sign in is an auth error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(203).setHeader("Content-Type", "text/html").setBody("<html/>"))
        val e = assertThrows(AdoException::class.java) { kotlinx.coroutines.runBlocking { client.me() } }
        assertEquals(401, e.code)
    }
}
