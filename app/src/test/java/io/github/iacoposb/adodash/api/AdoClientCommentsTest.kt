package io.github.iacoposb.adodash.api

import io.github.iacoposb.adodash.ui.CommentMarkup
import io.github.iacoposb.adodash.ui.Draft
import io.github.iacoposb.adodash.ui.MentionTyping
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** A work item's comments in its details: the latest page, the earlier ones, a new one. */
class AdoClientCommentsTest {
    private val server = MockWebServer().apply { start() }
    private val client = AdoClient(server.url("/org").toString(), AdoClient.pat("secret"))

    @After fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun comment(id: Int, at: String, text: String = "Comment $id", modified: String = at, version: Int = 1, deleted: Boolean = false) = """
        {"id":$id,"workItemId":4812,"version":$version,"text":"$text","isDeleted":$deleted,
         "createdBy":{"id":"u$id","displayName":"Person $id"},
         "createdDate":"$at","modifiedDate":"$modified"}
    """.trimIndent()

    @Test fun `the latest page, oldest first, with the token of the earlier ones`() = runTest {
        server.enqueue(
            json(
                """{"totalCount":53,"count":3,"continuationToken":"tok-1","comments":[
                ${comment(53, "2026-10-16T09:56:00Z", modified = "2026-10-16T09:58:00Z", version = 2)},
                ${comment(52, "2026-10-16T09:00:00Z", modified = "2026-10-16T09:00:00.012Z")},
                ${comment(51, "2026-10-15T09:00:00Z", deleted = true)}]}""",
            ),
        )
        val page = client.workItemCommentPage("Contoso", 4812)
        assertEquals(
            "/org/Contoso/_apis/wit/workItems/4812/comments?%24top=50&order=desc&api-version=7.1-preview.4",
            server.takeRequest().path,
        )
        assertEquals(listOf(52, 53), page.comments.map { it.id })
        assertEquals("tok-1", page.continuation)
        assertEquals(53, page.total)
        assertEquals(listOf(false, true), page.comments.map { it.edited })
        assertEquals(Identity("u53", "Person 53"), page.comments.last().author)
        assertEquals(Instant.parse("2026-10-16T09:56:00Z"), page.comments.last().createdAt)
    }

    @Test fun `the earlier comments with the token, the last page without one`() = runTest {
        server.enqueue(json("""{"totalCount":53,"count":1,"comments":[${comment(1, "2026-10-01T09:00:00Z")}]}"""))
        val page = client.workItemCommentPage("Contoso", 4812, continuation = "tok-1")
        assertEquals(
            "/org/Contoso/_apis/wit/workItems/4812/comments?%24top=50&order=desc&continuationToken=tok-1&api-version=7.1-preview.4",
            server.takeRequest().path,
        )
        assertEquals(listOf(1), page.comments.map { it.id })
        assertNull(page.continuation)
    }

    @Test fun `a new comment is posted as html with its mentions`() = runTest {
        server.enqueue(json(comment(54, "2026-10-16T10:00:00Z", text = "ok")))
        val anna = io.github.iacoposb.adodash.api.Person("bbbbbbbb-1111", "Anna Verdi", "anna@contoso.com")
        val (draft, _) = MentionTyping.insert(
            Draft("Thanks @An"), MentionTyping.query(Draft("Thanks @An"), 10)!!, anna,
        )
        val html = CommentMarkup.build(draft.copy(text = draft.text + "is <b>this</b> & that ok?\nBye"))
        val posted = client.addWorkItemComment("Contoso", 4812, html)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/org/Contoso/_apis/wit/workItems/4812/comments?api-version=7.1-preview.4", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("text"), body.keys)
        assertEquals(
            "Thanks <a href=\"#\" data-vss-mention=\"version:2.0,bbbbbbbb-1111\">@Anna Verdi</a> " +
                "is &lt;b&gt;this&lt;/b&gt; &amp; that ok?<br>Bye",
            body["text"]!!.jsonPrimitive.content,
        )
        assertEquals(54, posted.id)
        assertFalse(posted.edited)
    }

    @Test fun `a refused comment is denied`() = runTest {
        server.enqueue(json("""{"message":"TF237111: no permission"}""").setResponseCode(403))
        val e = assertThrows(AdoException::class.java) { kotlinx.coroutines.runBlocking { client.addWorkItemComment("Contoso", 1, "x") } }
        assertTrue(e.isDenied)
    }
}
