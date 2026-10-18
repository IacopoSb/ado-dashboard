package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.anna
import io.github.iacoposb.adodash.Fakes.marco
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.CommentPage
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.Person
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrFilter
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.api.WorkItemDetail
import io.github.iacoposb.adodash.api.WorkItemTypeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The comments of the work item details: reading, the composer's draft, posting, replying. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkItemCommentsControllerTest {
    private val item = Fakes.workItem(4812, "Bug", "SSO sign-in fails", "Active", 1)
    private val annaPerson = Person(anna.id, anna.name, "anna@contoso.com")

    private fun comment(id: Int, author: Identity = anna, text: String = "Comment $id") =
        Comment(id, item.id, author, text, Fakes.now.minusSeconds(1000L - id))

    private inner class Api : AdoApi {
        /** By continuation token: null is the latest page. */
        val pages = mutableMapOf<String?, CommentPage>(null to CommentPage(listOf(comment(1), comment(2, marco)), null))
        var postFail: AdoException? = null
        var gate: CompletableDeferred<Unit>? = null
        val posted = mutableListOf<String>()
        var people = listOf(annaPerson, Person("eeeeeeee-1111-2222-3333-444444444444", "Annabel Neri", "annabel@contoso.com"))

        override suspend fun workItemDetail(id: Int) = WorkItemDetail(item, rev = 7, commentCount = 2)
        override suspend fun workItemCommentPage(project: String, id: Int, continuation: String?) =
            pages[continuation] ?: CommentPage(emptyList(), null)
        override suspend fun addWorkItemComment(project: String, id: Int, html: String): Comment {
            posted += html
            gate?.await()
            postFail?.let { throw it }
            return Comment(100 + posted.size, id, Fakes.me, html, Fakes.now)
        }
        override suspend fun searchPeople(query: String) = people.filter { it.name.contains(query, ignoreCase = true) }

        override suspend fun me() = Identity("", "")
        override suspend fun projects() = emptyList<String>()
        override suspend fun queryWorkItemIds(wiql: String, top: Int) = emptyList<Int>()
        override suspend fun workItems(ids: List<Int>) = emptyList<WorkItem>()
        override suspend fun stateCategories(project: String, type: String) = emptyMap<String, String>()
        override suspend fun areas(project: String) = AreaNode(project, project)
        override suspend fun workItemTypes(project: String) = emptyList<WorkItemTypeInfo>()
        override suspend fun workItemComments(project: String, id: Int) = emptyList<Comment>()
        override suspend fun activePullRequests(project: String, filter: PrFilter) = emptyList<PullRequest>()
        override suspend fun pullRequestComments(pr: PullRequest) = emptyList<PrComment>()
    }

    private val commented = mutableListOf<Pair<WorkItem, Comment>>()

    private fun TestScope.controller(api: Api) = WorkItemController(
        this, { api }, { null }, { Person(Fakes.me.id, Fakes.me.name, "") },
        onSaved = { _, _ -> }, errorText = { it.message ?: "" },
        onCommented = { wi, c -> commented += wi to c }, clock = { Fakes.now },
    )

    private fun WorkItemController.page() = state.value!!.page

    @Test fun `comments are read with the work item, the earlier ones on request`() = runTest {
        val api = Api()
        api.pages[null] = CommentPage(listOf(comment(51), comment(52)), "tok", total = 52)
        api.pages["tok"] = CommentPage(listOf(comment(1), comment(2)), null, total = 52)
        val c = controller(api)
        c.open(item.id, item)
        advanceUntilIdle()
        assertEquals(listOf(51, 52), c.page().comments.items.map { it.id })
        assertEquals("tok", c.page().comments.earlier)
        assertEquals(52, c.page().comments.total)
        c.loadEarlierComments()
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 51, 52), c.page().comments.items.map { it.id })
        assertNull(c.page().comments.earlier)
    }

    @Test fun `the draft survives closing and opening the work item again`() = runTest {
        val api = Api()
        val c = controller(api)
        c.open(item.id, item)
        advanceUntilIdle()
        val revision = c.page().composer.revision
        val draft = Draft("@Anna Verdi half written", listOf(annaPerson))
        c.editDraft(draft)
        // Typed: the field keeps its own text, nothing is pushed back to it
        assertEquals(revision, c.page().composer.revision)
        c.close()
        c.open(4790)
        assertEquals(Draft(), c.page().composer.draft)
        c.close()
        c.open(item.id, item)
        assertEquals(draft, c.page().composer.draft)
    }

    @Test fun `a comment shows at once as being sent, then as posted, and is my own`() = runTest {
        val api = Api().apply { gate = CompletableDeferred() }
        val c = controller(api)
        c.open(item.id, item)
        advanceUntilIdle()
        c.editDraft(Draft("@Anna Verdi ok\n<done>", listOf(annaPerson)))
        c.sendComment()
        advanceUntilIdle()
        val sending = c.page().comments.sending.single()
        assertEquals(
            "<a href=\"#\" data-vss-mention=\"version:2.0,${anna.id}\">@Anna Verdi</a> ok<br>&lt;done&gt;",
            sending.html,
        )
        assertEquals("", c.page().composer.draft.text)
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        val comments = c.page().comments
        assertTrue(comments.sending.isEmpty())
        assertEquals(listOf(1, 2, 101), comments.items.map { it.id })
        assertEquals(3, comments.total)
        assertEquals(item.id to 101, commented.single().let { it.first.id to it.second.id })
        // Sent: nothing left for the next opening
        c.close()
        c.open(item.id, item)
        assertEquals(Draft(), c.page().composer.draft)
    }

    @Test fun `a refused comment goes back to the composer with the reason`() = runTest {
        val api = Api().apply { postFail = AdoException(403, "TF237111") }
        val c = controller(api)
        c.open(item.id, item)
        advanceUntilIdle()
        val draft = Draft("@Anna Verdi please look", listOf(annaPerson))
        c.editDraft(draft)
        val before = c.page().composer.revision
        c.sendComment()
        advanceUntilIdle()
        val page = c.page()
        assertTrue(page.comments.sending.isEmpty())
        assertEquals(listOf(1, 2), page.comments.items.map { it.id })
        assertEquals(draft, page.composer.draft)
        assertTrue(page.composer.revision > before)
        assertEquals(CommentError(SaveProblem.DENIED, "TF237111"), page.comments.sendError)
        assertTrue(commented.isEmpty())
        // Typing again clears the reason
        c.editDraft(draft.copy(text = draft.text + "!"))
        assertNull(c.page().comments.sendError)
    }

    @Test fun `from a mention, its comment is the target and the reply to its author is ready`() = runTest {
        val api = Api()
        api.pages[null] = CommentPage(listOf(comment(60), comment(61, marco)), "tok")
        api.pages["tok"] = CommentPage(listOf(comment(7, anna, "<a href=\"#\" data-vss-mention=\"version:2.0,${Fakes.me.id}\">@Iacopo</a> look")), null)
        val c = controller(api)
        c.open(item.id, commentId = 7)
        advanceUntilIdle()
        val page = c.page()
        assertEquals(7, page.target)
        // Read until found
        assertEquals(listOf(7, 60, 61), page.comments.items.map { it.id })
        assertEquals(MentionTyping.reply(Person(anna.id, anna.name, "")), page.composer.draft)
        c.targetShown()
        assertNull(c.page().target)
    }

    @Test fun `a reply does not overwrite a draft`() = runTest {
        val api = Api()
        val c = controller(api)
        c.open(item.id, item)
        advanceUntilIdle()
        c.editDraft(Draft("my own text"))
        c.close()
        c.open(item.id, commentId = 1)
        advanceUntilIdle()
        assertEquals("my own text", c.page().composer.draft.text)
    }

    @Test fun `mention suggestions show the comments' authors first, then the search`() = runTest {
        val api = Api()
        // Anna Bianchi has only an entity id: no mention can be made of her
        api.people = listOf(
            annaPerson, Person("eeeeeeee-1111-2222-3333-444444444444", "Annabel Neri", "annabel@contoso.com"), Person(Fakes.me.id, Fakes.me.name, ""),
            Person("aad.ZmFrZS1lbnRpdHk", "Anna Bianchi", "anna.bianchi@contoso.com"),
        )
        api.pages[null] = CommentPage(listOf(comment(1, anna), comment(2, Fakes.me)), null)
        val c = controller(api)
        c.open(item.id, item)
        advanceUntilIdle()
        c.searchMentions("ann")
        // At once, before the search: the authors of the comments
        assertEquals(listOf(anna.id), c.state.value!!.mentions.results.map { it.id })
        assertTrue(c.state.value!!.mentions.loading)
        advanceUntilIdle()
        assertEquals(listOf(anna.id, "eeeeeeee-1111-2222-3333-444444444444"), c.state.value!!.mentions.results.map { it.id })
        c.searchMentions("")
        assertTrue(c.state.value!!.mentions.results.isEmpty())
    }

    @Test fun `a comment being sent when the details close is still stored as mine`() = runTest {
        val api = Api().apply { gate = CompletableDeferred() }
        val c = controller(api)
        c.open(item.id, item)
        advanceUntilIdle()
        c.editDraft(Draft("Sent just before closing"))
        c.sendComment()
        advanceUntilIdle()
        c.close()
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, api.posted.size)
        assertEquals(item.id, commented.single().first.id)
    }
}
