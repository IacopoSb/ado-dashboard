package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.anna
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.CompletionOptions
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.MergeStrategy
import io.github.iacoposb.adodash.api.Person
import io.github.iacoposb.adodash.api.PolicyEvaluation
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrFilter
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.PullRequestDetail
import io.github.iacoposb.adodash.api.Reviewer
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.api.WorkItemTypeInfo
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Completing, auto-completing, abandoning and reactivating a pull request from its page. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PullRequestCompleteTest {
    private val me = Person(Fakes.me.id, Fakes.me.name, "")
    private val pr = Fakes.pr(311, anna, "Retry handling", reviewers = listOf(Reviewer(me.id, me.name, Vote.APPROVED, true)))
        .copy(projectId = "p-1", lastCommit = "abc123", mergeStatus = "succeeded")

    private fun policy(type: String, status: String, blocking: Boolean = true, strategies: Set<MergeStrategy>? = null) =
        PolicyEvaluation(type, "Policy", blocking, enabled = true, status = status, mergeStrategies = strategies)

    private val build = policy(PolicyEvaluation.BUILD_POLICY, "approved")

    private inner class Api : AdoApi {
        var detail = PullRequestDetail(pr, "Closes #4812", workItemIds = listOf(4812))
        var policies = listOf(build)
        /** What the reads after completing answer, in turn; the last one again after them. */
        var afterComplete = mutableListOf<PullRequestDetail>()
        var fail: AdoException? = null
        val completed = mutableListOf<Pair<String, CompletionOptions>>()
        val autoCompletes = mutableListOf<Pair<String?, CompletionOptions?>>()
        val statuses = mutableListOf<String>()
        var detailReads = 0

        override suspend fun pullRequest(id: Int) = pr
        override suspend fun pullRequestDetail(pr: PullRequest): PullRequestDetail {
            detailReads++
            return if (afterComplete.isEmpty()) detail else afterComplete.removeAt(0).also { if (afterComplete.isEmpty()) detail = it }
        }
        override suspend fun policyEvaluations(pr: PullRequest) = policies
        override suspend fun completePullRequest(pr: PullRequest, lastCommit: String, options: CompletionOptions): PullRequestDetail {
            completed += lastCommit to options
            fail?.let { throw it }
            // The merge runs after the call
            return detail.copy(pr = pr.copy(mergeStatus = "queued"))
        }
        override suspend fun setAutoComplete(pr: PullRequest, setById: String?, options: CompletionOptions?): PullRequestDetail {
            autoCompletes += setById to options
            fail?.let { throw it }
            return detail.copy(autoCompleteSetBy = setById?.let { Identity(it, me.name) }, completionOptions = options)
        }
        override suspend fun setPullRequestStatus(pr: PullRequest, status: String): PullRequestDetail {
            statuses += status
            fail?.let { throw it }
            return detail.copy(status = status, closedBy = Fakes.me, workItemIds = emptyList())
        }
        override suspend fun workItems(ids: List<Int>) = ids.map { Fakes.workItem(it) }

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

    private val closed = mutableListOf<Int>()
    private val reactivated = mutableListOf<Int>()

    private fun TestScope.controller(api: Api) = PullRequestController(
        this, { api }, { me }, { it.message ?: "error" },
        onClosed = { closed += it.id },
        onReactivated = { reactivated += it.id },
        clock = { Fakes.now },
    )

    private fun TestScope.opened(api: Api): PullRequestController {
        val c = controller(api)
        c.open(311, pr)
        advanceUntilIdle()
        return c
    }

    @Test fun `the merge types are those the branch policy allows, all of them without one`() {
        assertEquals(MergeStrategy.entries, PrCompletion.allowed(listOf(build)))
        assertEquals(MergeStrategy.entries, PrCompletion.allowed(null))
        val squashOrRebase = policy(
            PolicyEvaluation.MERGE_STRATEGY_POLICY, "approved", strategies = setOf(MergeStrategy.REBASE_MERGE, MergeStrategy.SQUASH),
        )
        assertEquals(listOf(MergeStrategy.SQUASH, MergeStrategy.REBASE_MERGE), PrCompletion.allowed(listOf(build, squashOrRebase)))
        // Two policies on the branch: what both allow
        val squash = policy(PolicyEvaluation.MERGE_STRATEGY_POLICY, "approved", strategies = setOf(MergeStrategy.SQUASH))
        assertEquals(listOf(MergeStrategy.SQUASH), PrCompletion.allowed(listOf(squashOrRebase, squash)))
        // Not applying to this pull request
        assertEquals(MergeStrategy.entries, PrCompletion.allowed(listOf(squash.copy(status = "notApplicable"))))
    }

    @Test fun `the dialog opens with the first type allowed and Azure DevOps' message`() = runTest {
        val api = Api().apply {
            policies = listOf(build, policy(PolicyEvaluation.MERGE_STRATEGY_POLICY, "approved", strategies = setOf(MergeStrategy.SQUASH, MergeStrategy.REBASE)))
        }
        val c = opened(api)
        c.openComplete()
        val draft = c.state.value!!.close.dialog!!
        assertEquals(MergeStrategy.SQUASH, draft.strategy)
        assertEquals(listOf(MergeStrategy.SQUASH, MergeStrategy.REBASE), draft.allowed)
        assertEquals("Merged PR 311: Retry handling", draft.message)
        assertTrue(draft.deleteSourceBranch && draft.transitionWorkItems)
        // Rebase and fast-forward makes no merge commit: no message sent
        assertNull(draft.copy(strategy = MergeStrategy.REBASE).options().mergeCommitMessage)
    }

    @Test fun `completing sends the options at the commit shown, then waits for the merge`() = runTest {
        val api = Api()
        val c = opened(api)
        c.openComplete()
        c.editComplete(c.state.value!!.close.dialog!!.copy(strategy = MergeStrategy.SQUASH, deleteSourceBranch = false))
        api.afterComplete = mutableListOf(api.detail.copy(pr = pr.copy(mergeStatus = "queued")), api.detail.copy(status = PullRequestDetail.COMPLETED))
        c.complete()
        advanceUntilIdle()
        val (commit, options) = api.completed.single()
        assertEquals("abc123", commit)
        assertEquals(MergeStrategy.SQUASH, options.mergeStrategy)
        assertEquals("Merged PR 311: Retry handling", options.mergeCommitMessage)
        assertFalse(options.deleteSourceBranch)
        assertTrue(options.transitionWorkItems)
        assertFalse(options.bypassPolicy)
        val s = c.state.value!!
        assertEquals(PullRequestDetail.COMPLETED, s.status)
        assertNull(s.close.dialog)
        assertNull(s.close.error)
        assertFalse(s.close.completing)
        // Off the dashboard; the work items the answer leaves out are kept
        assertEquals(listOf(311), closed)
        assertEquals(listOf(4812), s.detail?.workItemIds)
    }

    @Test fun `a merge that fails keeps the dialog open with the reason`() = runTest {
        val api = Api()
        val c = opened(api)
        c.openComplete()
        api.afterComplete = mutableListOf(api.detail.copy(pr = pr.copy(mergeStatus = "failure"), mergeFailure = "TF401019: hook declined"))
        c.complete()
        advanceUntilIdle()
        val s = c.state.value!!
        assertEquals(CloseError(CloseAction.COMPLETE, CloseProblem.MERGE_FAILED, "TF401019: hook declined"), s.close.error)
        assertTrue(s.close.dialog != null)
        assertEquals(PullRequestDetail.ACTIVE, s.status)
        assertTrue(closed.isEmpty())
    }

    @Test fun `with failing required checks only bypassing with a reason completes`() = runTest {
        val api = Api().apply { policies = listOf(policy(PolicyEvaluation.BUILD_POLICY, "rejected")) }
        val c = opened(api)
        assertTrue(c.state.value!!.blocked)
        c.openComplete()
        val draft = c.state.value!!.close.dialog!!
        c.complete()
        advanceUntilIdle()
        assertTrue(api.completed.isEmpty())
        // On, but no reason yet
        c.editComplete(draft.copy(bypass = true, bypassReason = "  "))
        c.complete()
        advanceUntilIdle()
        assertTrue(api.completed.isEmpty())
        c.editComplete(draft.copy(bypass = true, bypassReason = "Hotfix for the outage"))
        api.afterComplete = mutableListOf(api.detail.copy(status = PullRequestDetail.COMPLETED))
        c.complete()
        advanceUntilIdle()
        val options = api.completed.single().second
        assertTrue(options.bypassPolicy)
        assertEquals("Hotfix for the outage", options.bypassReason)
    }

    @Test fun `bypassing without the permission says so`() = runTest {
        val api = Api().apply {
            policies = listOf(policy(PolicyEvaluation.BUILD_POLICY, "rejected"))
            fail = AdoException(403, "HTTP 403: TF401027: You need the Git 'PullRequestBypassPolicy' permission")
        }
        val c = opened(api)
        c.openComplete()
        c.editComplete(c.state.value!!.close.dialog!!.copy(bypass = true, bypassReason = "Hotfix"))
        c.complete()
        advanceUntilIdle()
        val s = c.state.value!!
        assertEquals(CloseProblem.DENIED, s.close.error?.problem)
        assertTrue(s.close.error!!.bypass)
        assertNull(s.close.busy)
        assertTrue(s.close.dialog != null)
    }

    @Test fun `a push in between is a 409, and the pull request is read again`() = runTest {
        val api = Api().apply { fail = AdoException(409, "HTTP 409: TF401181: the source branch was updated") }
        val c = opened(api)
        val reads = api.detailReads
        api.detail = api.detail.copy(pr = pr.copy(lastCommit = "def456"))
        c.openComplete()
        c.complete()
        advanceUntilIdle()
        val s = c.state.value!!
        assertEquals(CloseProblem.STALE, s.close.error?.problem)
        assertEquals(reads + 1, api.detailReads)
        assertEquals("def456", s.pr?.lastCommit)
    }

    @Test fun `auto-complete is set by me with the dialog's options, never bypassing, and can be cancelled`() = runTest {
        val api = Api().apply { policies = listOf(policy(PolicyEvaluation.BUILD_POLICY, "running")) }
        val c = opened(api)
        c.openComplete()
        c.editComplete(c.state.value!!.close.dialog!!.copy(strategy = MergeStrategy.REBASE_MERGE))
        c.setAutoComplete()
        advanceUntilIdle()
        val (by, options) = api.autoCompletes.single()
        assertEquals(me.id, by)
        assertEquals(MergeStrategy.REBASE_MERGE, options?.mergeStrategy)
        assertFalse(options!!.bypassPolicy)
        var s = c.state.value!!
        assertEquals(me.id, s.detail?.autoCompleteSetBy?.id)
        assertNull(s.close.dialog)
        // Opened again: as it was set
        c.openComplete()
        assertEquals(MergeStrategy.REBASE_MERGE, c.state.value!!.close.dialog?.strategy)
        c.dismissComplete()
        c.cancelAutoComplete()
        advanceUntilIdle()
        assertEquals(null to null, api.autoCompletes.last())
        s = c.state.value!!
        assertNull(s.detail?.autoCompleteSetBy)
        assertTrue(closed.isEmpty())
    }

    @Test fun `a draft cannot be completed nor set to auto-complete`() = runTest {
        val api = Api()
        val c = controller(api)
        val draftPr = pr.copy(draft = true)
        api.detail = api.detail.copy(pr = draftPr)
        c.open(311, draftPr)
        advanceUntilIdle()
        c.openComplete()
        val draft = c.state.value!!.close.dialog!!
        assertFalse(PrCompletion.canComplete(draftPr, PullRequestDetail.ACTIVE, blocked = false, draft = draft))
        assertFalse(PrCompletion.canAutoComplete(draftPr, PullRequestDetail.ACTIVE, draft))
        c.complete()
        c.setAutoComplete()
        advanceUntilIdle()
        assertTrue(api.completed.isEmpty() && api.autoCompletes.isEmpty())
    }

    @Test fun `abandoned it leaves the dashboard, reactivated it comes back`() = runTest {
        val api = Api()
        val c = opened(api)
        c.abandon()
        advanceUntilIdle()
        var s = c.state.value!!
        assertEquals(PullRequestDetail.ABANDONED, s.status)
        assertFalse(s.canClose)
        assertFalse(s.canVote)
        assertEquals(listOf(311), closed)
        c.reactivate()
        advanceUntilIdle()
        s = c.state.value!!
        assertEquals(listOf(PullRequestDetail.ABANDONED, PullRequestDetail.ACTIVE), api.statuses)
        assertEquals(PullRequestDetail.ACTIVE, s.status)
        assertEquals(listOf(311), reactivated)
        assertTrue(s.canClose)
    }

    @Test fun `abandoning refused for permission keeps it active with the reason`() = runTest {
        val api = Api().apply { fail = AdoException(403, "HTTP 403") }
        val c = opened(api)
        c.abandon()
        advanceUntilIdle()
        val s = c.state.value!!
        assertEquals(CloseError(CloseAction.ABANDON, CloseProblem.DENIED, "HTTP 403"), s.close.error)
        assertEquals(PullRequestDetail.ACTIVE, s.status)
        assertTrue(closed.isEmpty())
        c.dismissCloseError()
        assertNull(c.state.value!!.close.error)
    }

    @Test fun `blocked by checks, conflicts, a required reviewer or a rejection`() {
        assertFalse(PrCompletion.blocked(pr, listOf(build)))
        // Not read yet: Azure DevOps says if it is
        assertFalse(PrCompletion.blocked(pr, null))
        assertTrue(PrCompletion.blocked(pr, listOf(policy(PolicyEvaluation.BUILD_POLICY, "queued"))))
        assertTrue(PrCompletion.blocked(pr, listOf(build.copy(expired = true))))
        assertFalse(PrCompletion.blocked(pr, listOf(policy(PolicyEvaluation.WORK_ITEMS_POLICY, "rejected", blocking = false))))
        assertTrue(PrCompletion.blocked(pr.copy(mergeStatus = "conflicts"), listOf(build)))
        assertTrue(PrCompletion.blocked(pr.copy(reviewers = listOf(Reviewer(me.id, me.name, Vote.WAITING, true))), listOf(build)))
        assertTrue(PrCompletion.blocked(pr.copy(reviewers = listOf(Reviewer("x", "X", Vote.REJECTED, false))), listOf(build)))
    }
}
