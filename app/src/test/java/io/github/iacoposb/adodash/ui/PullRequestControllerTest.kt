package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.anna
import io.github.iacoposb.adodash.Fakes.marco
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.Person
import io.github.iacoposb.adodash.api.PolicyEvaluation
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrFilter
import io.github.iacoposb.adodash.api.PrThread
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.PullRequestDetail
import io.github.iacoposb.adodash.api.Reviewer
import io.github.iacoposb.adodash.api.ThreadStatus
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.api.WorkItemTypeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pull request page: reading, voting, replying, new threads, statuses, own actions. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PullRequestControllerTest {
    private val me = Person(Fakes.me.id, Fakes.me.name, "")
    private val pr = Fakes.pr(311, anna, reviewers = listOf(Reviewer(me.id, me.name, Vote.NONE, true))).copy(projectId = "p-1")
    private val luca = Identity("dddddddd-1111-2222-3333-444444444444", "Luca Rossi")

    private fun comment(thread: Int, id: Int, who: Identity, text: String = "Comment $id") =
        PrComment(thread, id, who, text, Fakes.now.minusSeconds(100L - id), parentId = if (id == 1) 0 else 1)

    private inner class Api : AdoApi {
        var threads = listOf(
            PrThread(5, "active", listOf(comment(5, 1, marco, "@<${luca.id}> is this right?")), "/src/A.kt", 3),
            PrThread(6, "fixed", listOf(comment(6, 1, anna))),
        )
        var voteFail: AdoException? = null
        var postFail: AdoException? = null
        var statusFail: AdoException? = null
        var gate: CompletableDeferred<Unit>? = null
        val votes = mutableListOf<Int>()
        val posted = mutableListOf<Triple<Int, Int, String>>()
        val statuses = mutableListOf<Pair<Int, String>>()
        var policyReads = 0

        override suspend fun pullRequest(id: Int) = pr
        override suspend fun pullRequestDetail(pr: PullRequest) =
            PullRequestDetail(pr, "Closes #4812", listOf("payments"), listOf(4812))
        override suspend fun pullRequestThreads(pr: PullRequest) = threads
        override suspend fun policyEvaluations(pr: PullRequest): List<PolicyEvaluation> {
            policyReads++
            return listOf(PolicyEvaluation(PolicyEvaluation.BUILD_POLICY, "Build", true, true, "approved", buildId = 981))
        }
        override suspend fun workItems(ids: List<Int>) = ids.map { Fakes.workItem(it) }
        override suspend fun identityNames(ids: Collection<String>) = if (luca.id in ids) mapOf(luca.id to luca.name) else emptyMap()
        override suspend fun votePullRequest(pr: PullRequest, reviewerId: String, vote: Int): Reviewer {
            votes += vote
            gate?.await()
            voteFail?.let { throw it }
            return Reviewer(reviewerId, me.name, vote, true)
        }
        override suspend fun addPullRequestComment(pr: PullRequest, threadId: Int, parentId: Int, content: String): PrComment {
            posted += Triple(threadId, parentId, content)
            gate?.await()
            postFail?.let { throw it }
            return PrComment(threadId, 10 + posted.size, Fakes.me, content, Fakes.now, parentId)
        }
        override suspend fun createPullRequestThread(pr: PullRequest, content: String): PrThread {
            posted += Triple(0, 0, content)
            postFail?.let { throw it }
            return PrThread(9, "active", listOf(PrComment(9, 1, Fakes.me, content, Fakes.now)))
        }
        override suspend fun setPullRequestThreadStatus(pr: PullRequest, threadId: Int, status: String): PrThread {
            statuses += threadId to status
            gate?.await()
            statusFail?.let { throw it }
            return PrThread(threadId, status, emptyList())
        }
        override suspend fun searchPeople(query: String) = listOf(Person("x-1", "Annabel Neri", "annabel@contoso.com"))

        override suspend fun me() = Identity("", "")
        override suspend fun projects() = emptyList<String>()
        override suspend fun queryWorkItemIds(wiql: String, top: Int) = emptyList<Int>()
        override suspend fun stateCategories(project: String, type: String) = emptyMap<String, String>()
        override suspend fun areas(project: String) = AreaNode(project, project)
        override suspend fun workItemTypes(project: String) = emptyList<WorkItemTypeInfo>()
        override suspend fun workItemComments(project: String, id: Int) = emptyList<Comment>()
        override suspend fun activePullRequests(project: String, filter: PrFilter) = emptyList<PullRequest>()
        override suspend fun pullRequestComments(pr: PullRequest) = emptyList<PrComment>()
    }

    private val voted = mutableListOf<Pair<Int, Int>>()
    private val commented = mutableListOf<PrThread>()

    private fun TestScope.controller(api: Api) = PullRequestController(
        this, { api }, { me }, { it.message ?: "error" },
        onVoted = { p, v -> voted += p.id to v },
        onCommented = { _, t -> commented += t },
        clock = { Fakes.now },
    )

    @Test fun `opening shows the row at once, then the details, threads, policies and names`() = runTest {
        val api = Api()
        val c = controller(api)
        c.open(311, pr)
        val first = c.state.value!!
        assertEquals(pr, first.pr)
        assertNull(first.detail)
        assertEquals(PrTab.OVERVIEW, first.tab)
        advanceUntilIdle()
        val s = c.state.value!!
        assertEquals("Closes #4812", s.detail?.description)
        assertEquals(listOf(4812), s.workItems?.map { it.id })
        assertEquals(2, s.threads.shown.size)
        assertEquals(1, s.threads.activeCount)
        assertEquals(981, s.policies?.single()?.buildId)
        assertEquals("Luca Rossi", s.names[luca.id])
        assertEquals("Marco Bianchi", s.names[marco.id.lowercase()])
    }

    @Test fun `from a notification the pull request is read, the thread opened with the reply ready`() = runTest {
        val api = Api()
        val c = controller(api)
        c.open(311, known = null, target = PrCommentTarget(5, 1))
        advanceUntilIdle()
        val s = c.state.value!!
        assertEquals(pr.id, s.pr?.id)
        assertEquals(PrTab.THREADS, s.tab)
        assertEquals(ThreadFilter.ALL, s.filter)
        assertEquals("@Marco Bianchi ", s.composer(5).draft.text)
    }

    @Test fun `a vote shows at once, then is saved and told to the dashboard`() = runTest {
        val api = Api().apply { gate = CompletableDeferred() }
        val c = controller(api)
        c.open(311, pr)
        advanceUntilIdle()
        val reads = api.policyReads
        c.vote(Vote.APPROVED)
        runCurrent()
        assertEquals(Vote.APPROVED, c.state.value!!.vote.shown)
        assertTrue(c.state.value!!.vote.busy)
        // A second tap while it is saved does nothing
        c.vote(Vote.REJECTED)
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(Vote.APPROVED), api.votes)
        val s = c.state.value!!
        assertEquals(VoteState(Vote.APPROVED), s.vote)
        assertEquals(Vote.APPROVED, s.pr!!.reviewers.single { it.id == me.id }.vote)
        assertEquals(listOf(311 to Vote.APPROVED), voted)
        assertEquals(reads + 1, api.policyReads)
    }

    @Test fun `a vote refused goes back, with the permission problem`() = runTest {
        val api = Api().apply { voteFail = AdoException(403, "HTTP 403") }
        val c = controller(api)
        c.open(311, pr)
        advanceUntilIdle()
        c.vote(Vote.WAITING)
        advanceUntilIdle()
        val vote = c.state.value!!.vote
        assertEquals(Vote.NONE, vote.shown)
        assertEquals(SaveProblem.DENIED, vote.error?.problem)
        assertTrue(voted.isEmpty())
        c.dismissVoteError()
        assertNull(c.state.value!!.vote.error)
    }

    @Test fun `no vote on a draft`() = runTest {
        val api = Api()
        val c = controller(api)
        c.open(311, pr.copy(draft = true))
        runCurrent()
        assertFalse(c.state.value!!.canVote)
        c.vote(Vote.APPROVED)
        advanceUntilIdle()
        assertTrue(api.votes.isEmpty())
    }

    @Test fun `a reply goes to the thread's first comment as markdown, mentions as ids`() = runTest {
        val api = Api().apply { gate = CompletableDeferred() }
        val c = controller(api)
        c.open(311, pr)
        advanceUntilIdle()
        c.editDraft(5, Draft("@Luca Rossi yes", listOf(Person(luca.id, luca.name, ""))))
        c.send(5)
        runCurrent()
        val sending = c.state.value!!.threads.sending.single()
        assertEquals("@<${luca.id}> yes", sending.markdown)
        assertTrue(c.state.value!!.composer(5).draft.isBlank)
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(Triple(5, 1, "@<${luca.id}> yes")), api.posted)
        val thread = c.state.value!!.threads.items.single { it.id == 5 }
        assertEquals(2, thread.comments.size)
        assertTrue(c.state.value!!.threads.sending.isEmpty())
        // The whole thread, mine included, is stored as seen
        assertEquals(listOf(5), commented.map { it.id })
        assertEquals(2, commented.single().comments.size)
    }

    @Test fun `a reply refused goes back to its composer`() = runTest {
        val api = Api().apply { postFail = AdoException(403, "HTTP 403") }
        val c = controller(api)
        c.open(311, pr)
        advanceUntilIdle()
        c.editDraft(5, Draft("Thanks"))
        c.send(5)
        advanceUntilIdle()
        val s = c.state.value!!
        assertEquals("Thanks", s.composer(5).draft.text)
        assertEquals(SaveProblem.DENIED, s.threads.sendErrors[5]?.problem)
        assertTrue(commented.isEmpty())
        // Typing again clears the error
        c.editDraft(5, Draft("Thanks!"))
        assertNull(c.state.value!!.threads.sendErrors[5])
    }

    @Test fun `a new comment opens a thread`() = runTest {
        val api = Api()
        val c = controller(api)
        c.open(311, pr)
        advanceUntilIdle()
        c.editDraft(PrScreen.NEW_THREAD, Draft("Looks good"))
        c.send(PrScreen.NEW_THREAD)
        advanceUntilIdle()
        assertEquals(Triple(0, 0, "Looks good"), api.posted.single())
        assertEquals(9, c.state.value!!.threads.items.last().id)
        assertEquals(2, c.state.value!!.threads.activeCount)
    }

    @Test fun `drafts stay for the session, per thread`() = runTest {
        val api = Api()
        val c = controller(api)
        c.open(311, pr)
        advanceUntilIdle()
        c.editDraft(5, Draft("half a reply"))
        c.editDraft(PrScreen.NEW_THREAD, Draft("half a comment"))
        c.close()
        c.open(311, pr)
        val s = c.state.value!!
        assertEquals("half a reply", s.composer(5).draft.text)
        assertEquals("half a comment", s.composer(PrScreen.NEW_THREAD).draft.text)
    }

    @Test fun `resolving shows at once, then is saved and policies read again`() = runTest {
        val api = Api().apply { gate = CompletableDeferred() }
        val c = controller(api)
        c.open(311, pr)
        advanceUntilIdle()
        val reads = api.policyReads
        c.setStatus(5, ThreadStatus.FIXED)
        runCurrent()
        assertEquals(ThreadStatus.FIXED, c.state.value!!.threads.shown.single { it.id == 5 }.status)
        assertEquals(0, c.state.value!!.threads.activeCount)
        api.gate!!.complete(Unit)
        advanceUntilIdle()
        val thread = c.state.value!!.threads.items.single { it.id == 5 }
        assertEquals(ThreadStatus.FIXED, thread.status)
        // The comments stay, although the answer has none
        assertEquals(1, thread.comments.size)
        assertEquals(reads + 1, api.policyReads)
    }

    @Test fun `a status refused goes back`() = runTest {
        val api = Api().apply { statusFail = AdoException(500, "HTTP 500") }
        val c = controller(api)
        c.open(311, pr)
        advanceUntilIdle()
        c.setStatus(6, ThreadStatus.ACTIVE)
        advanceUntilIdle()
        val s = c.state.value!!
        assertEquals(ThreadStatus.FIXED, s.threads.shown.single { it.id == 6 }.status)
        assertEquals(SaveProblem.OTHER, s.threads.statusErrors[6]?.problem)
    }

    @Test fun `mentions offer the thread's people first, then the directory`() = runTest {
        val api = Api()
        val c = controller(api)
        c.open(311, pr)
        advanceUntilIdle()
        c.searchMentions("a")
        assertEquals(listOf(anna.name, marco.name), c.state.value!!.mentions.results.map { it.name })
        advanceUntilIdle()
        assertEquals(listOf(anna.name, marco.name, "Annabel Neri"), c.state.value!!.mentions.results.map { it.name })
    }

    @Test fun `the vote machine`() {
        val none = VoteState()
        assertNull(VoteMachine.start(none, Vote.NONE))
        val pending = VoteMachine.start(none, Vote.APPROVED)!!
        assertEquals(Vote.APPROVED, pending.shown)
        assertNull(VoteMachine.start(pending, Vote.REJECTED))
        assertEquals(VoteState(Vote.APPROVED), VoteMachine.saved(pending, Vote.APPROVED))
        val failed = VoteMachine.failed(pending, CommentError(SaveProblem.OTHER, "x"))
        assertEquals(Vote.NONE, failed.shown)
        assertFalse(failed.busy)
        // A sync does not override a vote being saved
        assertEquals(pending, VoteMachine.synced(pending, Vote.NONE))
        assertEquals(Vote.WAITING, VoteMachine.synced(none, Vote.WAITING).saved)
    }
}
