package io.github.iacoposb.adodash.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

class AdoClientSprintTest {
    private val server = MockWebServer().apply { start() }
    private val client = AdoClient(server.url("/org").toString(), AdoClient.pat("secret"))

    @After fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private val requests = mutableListOf<RecordedRequest>()

    private fun route(handle: (RecordedRequest) -> MockResponse?) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += request }
                return handle(request) ?: MockResponse().setResponseCode(500)
            }
        }
    }

    @Test fun `the current iteration comes with its id, path and dates`() = runTest {
        route {
            json("""
                {"count":1,"value":[{"id":"it-42","name":"Sprint 42","path":"Contoso\\Release 3\\Sprint 42",
                  "attributes":{"startDate":"2026-10-12T00:00:00Z","finishDate":"2026-10-23T00:00:00Z","timeFrame":"current"}}]}
            """.trimIndent())
        }
        val iteration = client.currentIteration("Contoso", "Backend Team")!!
        assertEquals(
            TeamIteration("it-42", "Sprint 42", "Contoso\\Release 3\\Sprint 42", Instant.parse("2026-10-12T00:00:00Z"), Instant.parse("2026-10-23T00:00:00Z")),
            iteration,
        )
        assertEquals(
            "/org/Contoso/Backend%20Team/_apis/work/teamsettings/iterations?%24timeframe=current&api-version=7.1",
            requests.single().path,
        )
        assertEquals("Sprint 42", client.currentSprint("Contoso", "Backend Team"))
    }

    @Test fun `the team's sprints come with their dates`() = runTest {
        route {
            json("""
                {"count":2,"value":[
                  {"id":"it-42","name":"Sprint 42","path":"Contoso\\Sprint 42","attributes":{"startDate":"2026-10-12T00:00:00Z","finishDate":"2026-10-23T00:00:00Z"}},
                  {"id":"it-43","name":"Sprint 43","path":"Contoso\\Sprint 43","attributes":{"startDate":"2026-10-26T00:00:00Z"}}]}
            """.trimIndent())
        }
        val list = client.teamIterations("Contoso", "Backend")
        assertEquals(listOf("Contoso\\Sprint 42", "Contoso\\Sprint 43"), list.map { it.path })
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"), list[1].start)
        assertEquals("/org/Contoso/Backend/_apis/work/teamsettings/iterations?api-version=7.1", requests.single().path)
    }

    @Test fun `a team without a current sprint has none`() = runTest {
        route { json("""{"count":0,"value":[]}""") }
        assertNull(client.currentIteration("Contoso", "Backend"))
    }

    @Test fun `the iteration's work items keep their parents`() = runTest {
        route {
            json("""
                {"workItemRelations":[
                  {"rel":null,"source":null,"target":{"id":4790,"url":"x"}},
                  {"rel":"System.LinkTypes.Hierarchy-Forward","source":{"id":4790,"url":"x"},"target":{"id":4791,"url":"x"}},
                  {"rel":"System.LinkTypes.Hierarchy-Forward","source":{"id":4790,"url":"x"},"target":{"id":4792,"url":"x"}},
                  {"rel":null,"source":null,"target":{"id":4812,"url":"x"}},
                  {"rel":null,"source":null,"target":{"id":4792,"url":"x"}}
                ],"url":"x"}
            """.trimIndent())
        }
        val links = client.iterationWorkItems("Contoso", "Backend", "it-42")
        assertEquals(
            listOf(IterationLink(4790), IterationLink(4791, 4790), IterationLink(4792, 4790), IterationLink(4812)),
            links,
        )
        assertEquals(
            "/org/Contoso/Backend/_apis/work/teamsettings/iterations/it-42/workitems?api-version=7.1",
            requests.single().path,
        )
    }

    private val batch = """
        {"count":2,"value":[
          {"id":4790,"fields":{"System.Id":4790,"System.TeamProject":"Contoso","System.WorkItemType":"User Story",
            "System.Title":"CSV export","System.State":"Active","System.ChangedDate":"2026-10-15T08:00:00Z",
            "System.AssignedTo":{"id":"me-1","displayName":"Iacopo Sbalchiero"},
            "Microsoft.VSTS.Common.StackRank":1999.5}},
          {"id":4791,"fields":{"System.Id":4791,"System.TeamProject":"Contoso","System.WorkItemType":"Task",
            "System.Title":"Endpoint","System.State":"Closed","System.ChangedDate":"2026-10-15T08:00:00Z",
            "Microsoft.VSTS.Scheduling.RemainingWork":2.5,"Microsoft.VSTS.Common.BacklogPriority":10,"System.Parent":4790}}
        ]}
    """.trimIndent()

    @Test fun `backlog items come with assignee id, remaining work, rank and parent`() = runTest {
        route { json(batch) }
        val items = client.backlogItems(listOf(4790, 4791))
        assertEquals(listOf(4790, 4791), items.map { it.item.id })
        assertEquals("me-1", items[0].assigneeId)
        assertEquals(1999.5, items[0].rank)
        assertNull(items[0].parentId)
        assertEquals(2.5, items[1].remainingWork)
        assertEquals("Backlog Priority when there is no Stack Rank", 10.0, items[1].rank)
        assertEquals(4790, items[1].parentId)
        val request = requests.single()
        assertEquals("/org/_apis/wit/workitemsbatch?api-version=7.1", request.path)
        val fields = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["fields"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(fields.containsAll(listOf("Microsoft.VSTS.Common.StackRank", "Microsoft.VSTS.Common.BacklogPriority", "System.Parent")))
    }

    @Test fun `a reorder patches the iteration's order and returns the new ranks`() = runTest {
        route { json("""{"count":1,"value":[{"id":4812,"order":1999.75}]}""") }
        val ranks = client.reorderSprintItems(
            "Contoso", "Backend Team", "it-42", BacklogReorder(listOf(4812), "Contoso\\Sprint 42", parentId = 0, previousId = 4790, nextId = 4777),
        )
        assertEquals(mapOf(4812 to 1999.75), ranks)
        val request = requests.single()
        assertEquals("PATCH", request.method)
        assertEquals(
            "/org/Contoso/Backend%20Team/_apis/work/iterations/it-42/workitemsorder?api-version=7.1-preview.1",
            request.path,
        )
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(listOf(4812), body["ids"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() })
        assertEquals("Contoso\\Sprint 42", body["iterationPath"]!!.jsonPrimitive.content)
        assertEquals("0", body["parentId"]!!.jsonPrimitive.content)
        assertEquals("4790", body["previousId"]!!.jsonPrimitive.content)
        assertEquals("4777", body["nextId"]!!.jsonPrimitive.content)
    }

    @Test fun `a server without the rank fields is asked again without them`() = runTest {
        route { r ->
            val body = r.body.readUtf8()
            if ("StackRank" in body) MockResponse().setResponseCode(400).setBody("""{"message":"TF51535: Cannot find field"}""")
            else json(batch)
        }
        val items = client.backlogItems(listOf(4790, 4791))
        assertEquals(2, items.size)
        assertEquals(2, requests.size)
    }

    @Test fun `working days and days off of the team`() = runTest {
        route { r ->
            when (r.path.orEmpty().substringBefore('?')) {
                "/org/Contoso/Backend/_apis/work/teamsettings" ->
                    json("""{"workingDays":["monday","tuesday","wednesday","thursday"],"bugsBehavior":"asRequirements"}""")
                "/org/Contoso/Backend/_apis/work/teamsettings/iterations/it-42/teamdaysoff" ->
                    json("""{"daysOff":[{"start":"2026-10-19T00:00:00Z","end":"2026-10-20T00:00:00Z"}]}""")
                else -> null
            }
        }
        assertEquals(
            setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
            client.teamWorkingDays("Contoso", "Backend"),
        )
        assertEquals(
            listOf(DayRange(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20))),
            client.teamDaysOff("Contoso", "Backend", "it-42"),
        )
    }
}
