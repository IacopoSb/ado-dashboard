package io.github.iacoposb.adodash.api

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The calls behind the work item details: reading one with its links and changing its fields. */
class AdoClientWorkItemsTest {
    private val server = MockWebServer().apply { start() }
    private val client = AdoClient(server.url("/org").toString(), AdoClient.pat("secret"))

    @After fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private val workItemJson = """
        {"id":4812,"rev":7,"fields":{
          "System.TeamProject":"Contoso","System.WorkItemType":"Bug","System.Title":"SSO sign-in fails",
          "System.State":"Active","Microsoft.VSTS.Common.Priority":1,"System.AreaPath":"Contoso\\Backend",
          "System.IterationPath":"Contoso\\Sprint 42","System.ChangedDate":"2026-10-16T09:30:00Z",
          "System.AssignedTo":{"displayName":"Iacopo Sbalchiero","uniqueName":"iacopo@contoso.com","id":"u1"},
          "System.ChangedBy":{"displayName":"Anna Verdi","uniqueName":"anna@contoso.com","id":"u2"},
          "Microsoft.VSTS.Scheduling.RemainingWork":3.5,"System.CommentCount":3,
          "Microsoft.VSTS.TCM.ReproSteps":"<div>Steps</div>"},
         "relations":[
          {"rel":"System.LinkTypes.Hierarchy-Reverse","url":"https://dev.azure.com/org/_apis/wit/workItems/4790","attributes":{"isLocked":false,"name":"Parent"}},
          {"rel":"System.LinkTypes.Hierarchy-Forward","url":"https://dev.azure.com/org/_apis/wit/workItems/4813","attributes":{"name":"Child"}},
          {"rel":"System.LinkTypes.Related","url":"https://dev.azure.com/org/_apis/wit/workItems/4701","attributes":{"name":"Related"}},
          {"rel":"ArtifactLink","url":"vstfs:///Git/PullRequestId/p-guid%2Fr-guid%2F311","attributes":{"name":"Pull Request"}},
          {"rel":"ArtifactLink","url":"vstfs:///Git/Commit/p-guid%2Fr-guid%2Fabc","attributes":{"name":"Fixed in Commit"}},
          {"rel":"AttachedFile","url":"https://dev.azure.com/org/_apis/wit/attachments/x","attributes":{"name":"log.txt"}}]}
    """.trimIndent()

    @Test fun `a work item with its fields and links`() = runTest {
        server.enqueue(json(workItemJson))
        val detail = client.workItemDetail(4812)
        assertEquals("/org/_apis/wit/workitems/4812?%24expand=relations&api-version=7.1", server.takeRequest().path)
        assertEquals(7, detail.rev)
        assertEquals("Bug", detail.item.type)
        assertEquals("Contoso\\Backend", detail.item.areaPath)
        assertEquals("Contoso\\Sprint 42", detail.iterationPath)
        assertEquals(Person("u1", "Iacopo Sbalchiero", "iacopo@contoso.com"), detail.assignee)
        assertEquals("Anna Verdi", detail.changedBy)
        assertEquals(3.5, detail.remainingWork!!, 0.0)
        assertEquals(3, detail.commentCount)
        // No description: a bug's repro steps
        assertEquals("<div>Steps</div>", detail.descriptionHtml)
        assertEquals(
            listOf(
                WorkItemLink(LinkKind.PARENT, 4790, "https://dev.azure.com/org/_apis/wit/workItems/4790"),
                WorkItemLink(LinkKind.CHILD, 4813, "https://dev.azure.com/org/_apis/wit/workItems/4813"),
                WorkItemLink(LinkKind.RELATED, 4701, "https://dev.azure.com/org/_apis/wit/workItems/4701"),
                WorkItemLink(LinkKind.PULL_REQUEST, 311, server.url("/org/p-guid/_git/r-guid/pullrequest/311").toString()),
            ),
            detail.links,
        )
    }

    @Test fun `a change is a json patch tested against the revision`() = runTest {
        server.enqueue(json(workItemJson.replace("\"rev\":7", "\"rev\":8").replace("\"System.State\":\"Active\"", "\"System.State\":\"Resolved\"")))
        val saved = client.updateWorkItem(4812, 7, FieldChange.State("Resolved"))
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/org/_apis/wit/workitems/4812?%24expand=relations&api-version=7.1", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json-patch+json"))
        val ops = Json.parseToJsonElement(request.body.readUtf8()).jsonArray.map { it.jsonObject }
        assertEquals("test", ops[0]["op"]!!.jsonPrimitive.content)
        assertEquals("/rev", ops[0]["path"]!!.jsonPrimitive.content)
        assertEquals(7, ops[0]["value"]!!.jsonPrimitive.content.toInt())
        assertEquals("add", ops[1]["op"]!!.jsonPrimitive.content)
        assertEquals("/fields/System.State", ops[1]["path"]!!.jsonPrimitive.content)
        assertEquals("Resolved", ops[1]["value"]!!.jsonPrimitive.content)
        assertEquals(8, saved.rev)
        assertEquals("Resolved", saved.item.state)
    }

    @Test fun `someone else's change in between is a conflict`() = runTest {
        server.enqueue(
            json("""{"message":"TF26071: This work item has been changed by someone else since you opened it."}""").setResponseCode(412),
        )
        val e = assertThrows(AdoException::class.java) { kotlinx.coroutines.runBlocking { client.updateWorkItem(4812, 7, FieldChange.Priority(2)) } }
        assertTrue(e.isConflict)
        assertFalse(e.isDenied)
    }

    @Test fun `a refused change is denied`() = runTest {
        server.enqueue(json("""{"message":"TF237111: no permission"}""").setResponseCode(403))
        val e = assertThrows(AdoException::class.java) { kotlinx.coroutines.runBlocking { client.updateWorkItem(1, 1, FieldChange.Priority(2)) } }
        assertTrue(e.isDenied)
        assertFalse(e.isConflict)
    }

    @Test fun `people from the identity picker`() = runTest {
        server.enqueue(json("""
            {"results":[{"queryToken":"anna","identities":[
              {"entityId":"vss.ds.v1.aad.user.1","entityType":"User","localId":"u2","displayName":"Anna Verdi",
               "mail":"anna@contoso.com","signInAddress":"anna@contoso.com"},
              {"entityId":"vss.ds.v1.aad.user.2","entityType":"User","displayName":"Anna Neri","mail":"neri@contoso.com"},
              {"entityId":"vss.ds.v1.aad.user.3","entityType":"User","localId":"u2","displayName":"Anna Verdi","signInAddress":"ANNA@contoso.com"}]}]}
        """.trimIndent()))
        val people = client.searchPeople("anna")
        assertEquals(
            listOf(
                Person("u2", "Anna Verdi", "anna@contoso.com"),
                Person("vss.ds.v1.aad.user.2", "Anna Neri", "neri@contoso.com"),
            ),
            people,
        )
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/org/_apis/IdentityPicker/Identities?api-version=7.1-preview.1", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("anna", body["query"]!!.jsonPrimitive.content)
        assertEquals("user", body["identityTypes"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test fun `team members without groups`() = runTest {
        server.enqueue(json("""
            {"count":2,"value":[
              {"identity":{"id":"u2","displayName":"Anna Verdi","uniqueName":"anna@contoso.com"}},
              {"identity":{"id":"g1","displayName":"[Contoso]\\Backend Team","uniqueName":"vstfs:///g1","isContainer":true}}]}
        """.trimIndent()))
        assertEquals(listOf(Person("u2", "Anna Verdi", "anna@contoso.com")), client.teamMembers("Contoso", "Backend"))
        assertEquals("/org/_apis/projects/Contoso/teams/Backend/members?api-version=7.1", server.takeRequest().path)
    }

    @Test fun `iterations with their dates, paths as work items read them`() = runTest {
        server.enqueue(json("""
            {"id":1,"name":"Contoso","structureType":"iteration","hasChildren":true,"children":[
              {"id":2,"name":"Sprint 41","attributes":{"startDate":"2026-09-28T00:00:00Z","finishDate":"2026-10-09T00:00:00Z"}},
              {"id":3,"name":"Sprint 42","attributes":{"startDate":"2026-10-12T00:00:00Z","finishDate":"2026-10-23T00:00:00Z"}},
              {"id":4,"name":"Backlog","children":[{"id":5,"name":"Later"}]}]}
        """.trimIndent()))
        val iterations = client.iterations("Contoso")
        assertEquals(
            listOf("Contoso", "Contoso\\Sprint 41", "Contoso\\Sprint 42", "Contoso\\Backlog", "Contoso\\Backlog\\Later"),
            iterations.map { it.path },
        )
        assertEquals(Instant.parse("2026-10-12T00:00:00Z"), iterations[2].start)
        assertEquals(
            "/org/Contoso/_apis/wit/classificationnodes/iterations?%24depth=10&api-version=7.1",
            server.takeRequest().path,
        )
    }

    @Test fun `fields of a type`() = runTest {
        server.enqueue(json("""{"count":2,"value":[{"referenceName":"System.Title","name":"Title"},
            {"referenceName":"Microsoft.VSTS.Scheduling.RemainingWork","name":"Remaining Work"}]}"""))
        assertEquals(setOf("System.Title", WiFields.REMAINING_WORK), client.typeFields("Contoso", "Task"))
        assertEquals("/org/Contoso/_apis/wit/workitemtypes/Task/fields?api-version=7.1", server.takeRequest().path)
    }

    @Test fun `a linked pull request by id`() = runTest {
        server.enqueue(json("""
            {"pullRequestId":311,"title":"Retry handling","status":"completed","isDraft":false,"creationDate":"2026-10-15T08:00:00Z",
             "repository":{"id":"r1","name":"backend","project":{"id":"p-guid","name":"Contoso"}}}
        """.trimIndent()))
        val pr = client.pullRequestById(311)
        assertEquals("completed", pr.status)
        assertEquals("Retry handling", pr.title)
        assertEquals(server.url("/org/Contoso/_git/backend/pullrequest/311").toString(), pr.url)
        assertEquals("/org/_apis/git/pullrequests/311?api-version=7.1", server.takeRequest().path)
    }
}
