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

    @Test fun `areas become a tree with work item paths`() = runTest {
        server.enqueue(json("""
            {"id":1,"name":"My Project","structureType":"area","hasChildren":true,"path":"\\My Project\\Area",
             "children":[
               {"id":3,"name":"Frontend","structureType":"area","hasChildren":false,"path":"\\My Project\\Area\\Frontend"},
               {"id":2,"name":"Backend","structureType":"area","hasChildren":true,"path":"\\My Project\\Area\\Backend",
                "children":[{"id":4,"name":"Gateway","structureType":"area","hasChildren":false,"path":"\\My Project\\Area\\Backend\\Gateway"}]}]}
        """.trimIndent()))
        val root = client.areas("My Project")
        assertEquals("My Project", root.path)
        assertEquals(listOf("Backend", "Frontend"), root.children.map { it.name })
        assertEquals("My Project\\Backend\\Gateway", root.children[0].children.single().path)
        assertEquals(
            "/org/My%20Project/_apis/wit/classificationnodes/areas?%24depth=10&api-version=7.1",
            server.takeRequest().path,
        )
    }

    @Test fun `work item types come with their states, hidden ones marked`() = runTest {
        server.enqueue(json("""
            {"count":3,"value":[
              {"name":"Epic","referenceName":"Microsoft.VSTS.WorkItemTypes.Epic","color":"FF7B00","isDisabled":false,
               "states":[{"name":"New","color":"b2b2b2","category":"Proposed"},{"name":"Resolved","color":"ff9d00","category":"InProgress"}]},
              {"name":"Code Review Request","color":"FF9D00","isDisabled":false,"states":[{"name":"Requested","category":"Proposed"}]},
              {"name":"Old","color":"000000","isDisabled":true,"states":[]}]}
        """.trimIndent()))
        server.enqueue(json("""{"name":"Hidden Types Category","workItemTypes":[{"name":"Code Review Request","url":"x"}]}"""))
        val types = client.workItemTypes("Contoso")
        assertEquals(listOf("Epic", "Code Review Request"), types.map { it.name })
        assertEquals(listOf(StateInfo("New", "Proposed"), StateInfo("Resolved", "InProgress")), types[0].states)
        assertEquals("FF7B00", types[0].color)
        assertEquals(listOf(false, true), types.map { it.hidden })
        assertEquals("/org/Contoso/_apis/wit/workitemtypes?api-version=7.1", server.takeRequest().path)
        assertEquals("/org/Contoso/_apis/wit/workitemtypecategories/Microsoft.HiddenCategory?api-version=7.1", server.takeRequest().path)
    }

    @Test fun `types without states read them separately`() = runTest {
        server.enqueue(json("""{"value":[{"name":"User Story","color":"009CCC"}]}"""))
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("{}"))
        server.enqueue(json("""{"value":[{"name":"Active","category":"InProgress"}]}"""))
        val type = client.workItemTypes("Contoso").single()
        assertEquals(listOf(StateInfo("Active", "InProgress")), type.states)
        assertEquals(false, type.hidden)
        server.takeRequest()
        server.takeRequest()
        assertEquals("/org/Contoso/_apis/wit/workitemtypes/User%20Story/states?api-version=7.1", server.takeRequest().path)
    }

    @Test fun `work items carry their area path`() = runTest {
        server.enqueue(json("""
            {"value":[{"id":7,"fields":{"System.TeamProject":"Contoso","System.WorkItemType":"Bug","System.Title":"t",
              "System.State":"Active","System.AreaPath":"Contoso\\Backend","System.ChangedDate":"2026-10-15T08:00:00Z"}}]}
        """.trimIndent()))
        assertEquals("Contoso\\Backend", client.workItems(listOf(7)).single().areaPath)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body, body.contains("System.AreaPath"))
    }

    @Test fun `expired PAT redirecting to sign in is an auth error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(203).setHeader("Content-Type", "text/html").setBody("<html/>"))
        val e = assertThrows(AdoException::class.java) { kotlinx.coroutines.runBlocking { client.me() } }
        assertEquals(401, e.code)
    }
}
