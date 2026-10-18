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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The pull request page's calls: details, vote, threads, replies, new threads, statuses. */
class AdoClientPullRequestTest {
    private val server = MockWebServer().apply { start() }
    private val client = AdoClient(server.url("/org").toString(), AdoClient.pat("secret"))
    private val pr = Fakes.pr(311, Fakes.anna).copy(repoId = "repo-1")
    private val base = "/org/Contoso/_apis/git/repositories/repo-1/pullRequests/311"

    @After fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private val prJson = """
        {"pullRequestId":311,"title":"Retry &amp; backoff","status":"active","isDraft":false,
         "description":"Adds **retry**.","creationDate":"2026-10-16T08:00:00Z",
         "createdBy":{"id":"anna","displayName":"Anna Verdi"},
         "sourceRefName":"refs/heads/feature/x-311","targetRefName":"refs/heads/main",
         "mergeStatus":"succeeded",
         "repository":{"id":"repo-1","name":"backend","project":{"id":"p-1","name":"Contoso"}},
         "reviewers":[{"id":"me","displayName":"Iacopo","vote":0,"isRequired":true,"votedFor":[{"id":"team"}]},
                      {"id":"team","displayName":"[Contoso]\\Backend","vote":0,"isContainer":true}],
         "labels":[{"name":"payments","active":true},{"name":"old","active":false}],
         "workItemRefs":[{"id":"4812","url":"x"}]}
    """.trimIndent()

    @Test fun `the details with description, labels and linked work items`() = runTest {
        server.enqueue(json(prJson))
        val detail = client.pullRequestDetail(pr)
        assertEquals("$base?includeWorkItemRefs=true&api-version=7.1", server.takeRequest().path)
        assertEquals("Adds **retry**.", detail.description)
        assertEquals(listOf("payments"), detail.labels)
        assertEquals(listOf(4812), detail.workItemIds)
        assertEquals("active", detail.status)
        assertEquals("Retry & backoff", detail.pr.title)
        assertEquals("p-1", detail.pr.projectId)
        assertTrue(detail.pr.reviewers.first { it.id == "team" }.group)
        assertEquals(listOf("team"), detail.pr.reviewers.first { it.id == "me" }.votedFor)
    }

    @Test fun `a pull request by id alone`() = runTest {
        server.enqueue(json(prJson))
        val read = client.pullRequest(311)
        assertEquals("/org/_apis/git/pullrequests/311?api-version=7.1", server.takeRequest().path)
        assertEquals("repo-1", read.repoId)
        assertEquals("Contoso", read.project)
    }

    @Test fun `the vote is a PUT of my reviewer`() = runTest {
        server.enqueue(json("""{"id":"me","displayName":"Iacopo","vote":10,"isRequired":true}"""))
        val reviewer = client.votePullRequest(pr, "me", Vote.APPROVED)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("$base/reviewers/me?api-version=7.1", request.path)
        assertEquals(10, Json.parseToJsonElement(request.body.readUtf8()).jsonObject["vote"]!!.jsonPrimitive.int)
        assertEquals(Reviewer("me", "Iacopo", 10, true), reviewer)
    }

    @Test fun `a vote refused is a 403`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"TF401027: no permission"}"""))
        val e = assertThrows(AdoException::class.java) { kotlinx.coroutines.runBlocking { client.votePullRequest(pr, "me", Vote.REJECTED) } }
        assertEquals(403, e.code)
        assertTrue(e.isDenied)
    }

    @Test fun `threads with their file and line, system comments left out`() = runTest {
        server.enqueue(
            json(
                """{"value":[
                {"id":5,"status":"active","threadContext":{"filePath":"/src/Gateway.kt","rightFileStart":{"line":88,"offset":1}},
                 "comments":[{"id":1,"parentCommentId":0,"author":{"id":"anna","displayName":"Anna"},"content":"@<aaaaaaaa-1111-2222-3333-444444444444> ok?","commentType":"text","publishedDate":"2026-10-16T09:56:00Z"},
                             {"id":2,"parentCommentId":1,"author":{"id":"me","displayName":"Me"},"content":"Yes","commentType":"text","publishedDate":"2026-10-16T09:57:00Z"}]},
                {"id":6,"status":"fixed","comments":[{"id":1,"author":{"id":"x"},"content":"PR-level","publishedDate":"2026-10-16T09:00:00Z"}]},
                {"id":7,"comments":[{"id":1,"author":{"id":"x"},"content":"voted 10","commentType":"system","publishedDate":"2026-10-16T09:00:00Z"}]}
                ]}""",
            ),
        )
        val threads = client.pullRequestThreads(pr)
        assertEquals("$base/threads?api-version=7.1", server.takeRequest().path)
        val file = threads.first { it.id == 5 }
        assertEquals("/src/Gateway.kt", file.filePath)
        assertEquals(88, file.line)
        assertEquals(listOf(0, 1), file.comments.map { it.parentId })
        assertEquals(Instant.parse("2026-10-16T09:57:00Z"), file.comments[1].publishedAt)
        val prLevel = threads.first { it.id == 6 }
        assertNull(prLevel.filePath)
        assertNull(prLevel.line)
        assertEquals("fixed", prLevel.status)
        assertTrue(threads.first { it.id == 7 }.comments.isEmpty())
    }

    @Test fun `a reply goes to the thread's comments`() = runTest {
        server.enqueue(
            json("""{"id":3,"parentCommentId":1,"author":{"id":"me","displayName":"Me"},"content":"Done @<u-1>","commentType":"text","publishedDate":"2026-10-16T10:00:00Z"}"""),
        )
        val comment = client.addPullRequestComment(pr, 5, 1, "Done @<u-1>")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("$base/threads/5/comments?api-version=7.1", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("Done @<u-1>", body["content"]!!.jsonPrimitive.content)
        assertEquals(1, body["parentCommentId"]!!.jsonPrimitive.int)
        assertEquals(1, body["commentType"]!!.jsonPrimitive.int)
        assertEquals(PrComment(5, 3, Identity("me", "Me"), "Done @<u-1>", Instant.parse("2026-10-16T10:00:00Z"), 1), comment)
    }

    @Test fun `a new thread on the pull request is active with its comment`() = runTest {
        server.enqueue(
            json("""{"id":9,"status":"active","comments":[{"id":1,"author":{"id":"me","displayName":"Me"},"content":"Looks good","commentType":"text","publishedDate":"2026-10-16T10:00:00Z"}]}"""),
        )
        val thread = client.createPullRequestThread(pr, "Looks good")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("$base/threads?api-version=7.1", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("active", body["status"]!!.jsonPrimitive.content)
        val first = body["comments"]!!.jsonArray.single().jsonObject
        assertEquals("Looks good", first["content"]!!.jsonPrimitive.content)
        assertEquals(0, first["parentCommentId"]!!.jsonPrimitive.int)
        assertEquals(9, thread.id)
        assertEquals("Looks good", thread.comments.single().text)
    }

    @Test fun `the status is a PATCH of the thread`() = runTest {
        server.enqueue(json("""{"id":5,"status":"fixed","comments":[]}"""))
        val thread = client.setPullRequestThreadStatus(pr, 5, ThreadStatus.FIXED)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("$base/threads/5?api-version=7.1", request.path)
        assertEquals("""{"status":"fixed"}""", request.body.readUtf8())
        assertEquals("fixed", thread.status)
    }

    @Test fun `policies with their names, approvals wanted and when`() = runTest {
        server.enqueue(
            json(
                """{"value":[
                {"status":"approved","completedDate":"2026-10-16T09:56:00Z","context":{"buildId":981,"isExpired":false},
                 "configuration":{"isEnabled":true,"isBlocking":true,"type":{"id":"0609b952-1397-4640-95ec-e00a01b2c241","displayName":"Build"},"settings":{"displayName":"backend-ci"}}},
                {"status":"queued","configuration":{"isEnabled":true,"isBlocking":true,"type":{"id":"fa4e907d-c16b-4a4c-9dfa-4906e5d171dd","displayName":"Minimum number of reviewers"},"settings":{"minimumApproverCount":2}}},
                {"status":"queued","configuration":{"isEnabled":true,"isBlocking":true,"type":{"id":"fd2167ab-b0be-447a-8ec8-39368250530e","displayName":"Required reviewers"},"settings":{"requiredReviewerIds":["team-1","marco"],"creatorVoteCounts":true}}}
                ]}""",
            ),
        )
        val list = client.policyEvaluations(pr.copy(projectId = "p-1"))
        assertEquals("backend-ci", list[0].displayName)
        assertEquals(981, list[0].buildId)
        assertEquals(Instant.parse("2026-10-16T09:56:00Z"), list[0].completedAt)
        assertEquals(2, list[1].minApprovers)
        assertNull(list[1].displayName)
        assertEquals(false, list[1].creatorVoteCounts)
        assertEquals(listOf("team-1", "marco"), list[2].requiredReviewerIds)
        assertNull(list[2].minApprovers)
        assertTrue(list[2].creatorVoteCounts)
    }

    @Test fun `names of the identities mentioned`() = runTest {
        server.enqueue(json("""{"value":[{"id":"u-1","providerDisplayName":"Luca Rossi"},{"id":"u-2","customDisplayName":"Giò","providerDisplayName":"Giovanni"}]}"""))
        val names = client.identityNames(listOf("u-1", "u-2"))
        assertEquals("/org/_apis/identities?identityIds=u-1%2Cu-2&api-version=7.1", server.takeRequest().path)
        assertEquals(mapOf("u-1" to "Luca Rossi", "u-2" to "Giò"), names)
    }
}
