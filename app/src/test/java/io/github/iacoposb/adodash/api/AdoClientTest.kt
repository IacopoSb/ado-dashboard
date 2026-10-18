package io.github.iacoposb.adodash.api

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    /** Answers by path, whatever the order of the calls: some are made together. */
    private fun route(vararg routes: Pair<String, MockResponse>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes.firstOrNull { request.path.orEmpty().substringBefore('?') == it.first }?.second
                    ?: MockResponse().setResponseCode(500)
        }
    }

    private fun paths() = List(server.requestCount) { server.takeRequest().path }.toSet()

    @Test fun `work item types come with their states, hidden ones marked`() = runTest {
        route(
            "/org/Contoso/_apis/wit/workitemtypes" to json("""
                {"count":3,"value":[
                  {"name":"Epic","referenceName":"Microsoft.VSTS.WorkItemTypes.Epic","color":"FF7B00","isDisabled":false,
                   "states":[{"name":"New","color":"b2b2b2","category":"Proposed"},{"name":"Resolved","color":"ff9d00","category":"InProgress"}]},
                  {"name":"Code Review Request","color":"FF9D00","isDisabled":false,"states":[{"name":"Requested","category":"Proposed"}]},
                  {"name":"Old","color":"000000","isDisabled":true,"states":[]}]}
            """.trimIndent()),
            "/org/Contoso/_apis/wit/workitemtypecategories/Microsoft.HiddenCategory" to
                json("""{"name":"Hidden Types Category","workItemTypes":[{"name":"Code Review Request","url":"x"}]}"""),
        )
        val types = client.workItemTypes("Contoso")
        assertEquals(listOf("Epic", "Code Review Request"), types.map { it.name })
        assertEquals(listOf(StateInfo("New", "Proposed"), StateInfo("Resolved", "InProgress")), types[0].states)
        assertEquals("FF7B00", types[0].color)
        assertEquals(listOf(false, true), types.map { it.hidden })
        assertEquals(
            setOf(
                "/org/Contoso/_apis/wit/workitemtypes?api-version=7.1",
                "/org/Contoso/_apis/wit/workitemtypecategories/Microsoft.HiddenCategory?api-version=7.1",
            ),
            paths(),
        )
    }

    @Test fun `types without states read them separately`() = runTest {
        route(
            "/org/Contoso/_apis/wit/workitemtypes" to json("""{"value":[{"name":"User Story","color":"009CCC"}]}"""),
            "/org/Contoso/_apis/wit/workitemtypecategories/Microsoft.HiddenCategory" to
                MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json").setBody("{}"),
            "/org/Contoso/_apis/wit/workitemtypes/User%20Story/states" to
                json("""{"value":[{"name":"Active","category":"InProgress"}]}"""),
        )
        val type = client.workItemTypes("Contoso").single()
        assertEquals(listOf(StateInfo("Active", "InProgress")), type.states)
        assertEquals(false, type.hidden)
        assertTrue(paths().contains("/org/Contoso/_apis/wit/workitemtypes/User%20Story/states?api-version=7.1"))
    }

    @Test fun `a reused authorization is asked once until it expires`() = runTest {
        var asked = 0
        var now = 0L
        val auth = AdoClient.reused({ "token ${++asked}" }, ttlMillis = 1_000, now = { now })
        assertEquals("token 1", auth())
        now = 999
        assertEquals("token 1", auth())
        now = 1_000
        assertEquals("token 2", auth())
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

    @Test fun `encoded titles are shown decoded, once`() = runTest {
        server.enqueue(json("""
            {"value":[{"id":8,"fields":{"System.TeamProject":"Contoso","System.WorkItemType":"Task",
              "System.Title":"Attuatore con pi&ugrave; comandi SET &amp;lt;x&amp;gt;","System.State":"New",
              "System.ChangedDate":"2026-10-15T08:00:00Z"}}]}
        """.trimIndent()))
        assertEquals("Attuatore con più comandi SET &lt;x&gt;", client.workItems(listOf(8)).single().title)
    }

    @Test fun `expired PAT redirecting to sign in is an auth error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(203).setHeader("Content-Type", "text/html").setBody("<html/>"))
        val e = assertThrows(AdoException::class.java) { kotlinx.coroutines.runBlocking { client.me() } }
        assertEquals(401, e.code)
    }

    @Test fun `the sign-in page with a 200 is an auth error too`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html; charset=utf-8").setBody("<html/>"))
        val e = assertThrows(AdoException::class.java) { kotlinx.coroutines.runBlocking { client.me() } }
        assertEquals(401, e.code)
    }

    @Test fun `a proxy's error page keeps its status, not a refusal of the credentials`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setHeader("Content-Type", "text/html").setBody("<html>Bad Gateway</html>"))
        val e = assertThrows(AdoException::class.java) { kotlinx.coroutines.runBlocking { client.me() } }
        assertEquals(502, e.code)
        assertFalse(e.isDenied)
    }

    @Test fun `team field values become the board's area paths`() = runTest {
        server.enqueue(json("""
            {"field":{"referenceName":"System.AreaPath","url":"x"},"defaultValue":"My Project\\Backend",
             "values":[{"value":"My Project\\Backend","includeChildren":true},{"value":"My Project\\Shared","includeChildren":false}]}
        """.trimIndent()))
        assertEquals(
            listOf(TeamArea("My Project\\Backend", true), TeamArea("My Project\\Shared", false)),
            client.teamAreas("My Project", "Backend Team"),
        )
        assertEquals(
            "/org/My%20Project/Backend%20Team/_apis/work/teamsettings/teamfieldvalues?api-version=7.1",
            server.takeRequest().path,
        )
    }

    @Test fun `a team field other than the area path gives no areas`() = runTest {
        server.enqueue(json("""{"field":{"referenceName":"Custom.Team"},"values":[{"value":"Blue","includeChildren":false}]}"""))
        assertEquals(null, client.teamAreas("P", "T"))
    }

    @Test fun `teams, current sprint and repositories`() = runTest {
        route(
            "/org/_apis/projects/My%20Project/teams" to json("""{"value":[{"id":"1","name":"web"},{"id":"2","name":"Backend"}]}"""),
            "/org/My%20Project/web/_apis/work/teamsettings/iterations" to
                json("""{"value":[{"id":"i","name":"Sprint 42","path":"My Project\\Sprint 42"}]}"""),
            "/org/My%20Project/Backend/_apis/work/teamsettings/iterations" to json("""{"value":[]}"""),
            "/org/My%20Project/_apis/git/repositories" to
                json("""{"value":[{"id":"a","name":"payments"},{"id":"b","name":"Backend"},{"id":"c","name":"old","isDisabled":true}]}"""),
        )
        assertEquals(listOf("Backend", "web"), client.teams("My Project"))
        assertEquals("Sprint 42", client.currentSprint("My Project", "web"))
        assertEquals(null, client.currentSprint("My Project", "Backend"))
        assertEquals(listOf("Backend", "payments"), client.repositories("My Project"))
    }

    @Test fun `all active pull requests have no reviewer or creator`() = runTest {
        server.enqueue(json("""{"value":[]}"""))
        client.activePullRequests("P", PrFilter.All)
        val path = server.takeRequest().path!!
        assertEquals("/org/P/_apis/git/pullrequests?searchCriteria.status=active&%24top=100&api-version=7.1", path)
    }
}
