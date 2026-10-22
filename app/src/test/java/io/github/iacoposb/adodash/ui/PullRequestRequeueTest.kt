package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.anna
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.Identity
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Build checks expired or failed queued again from the pull request's page. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PullRequestRequeueTest {
    private val me = Person(Fakes.me.id, Fakes.me.name, "")
    private val pr = Fakes.pr(311, anna, "Retry handling", reviewers = listOf(Reviewer(me.id, me.name, Vote.APPROVED, true)))
        .copy(projectId = "p-1", lastCommit = "abc123", mergeStatus = "succeeded")

    private fun build(id: String, status: String, expired: Boolean = false, buildId: Int? = 1) = PolicyEvaluation(
        PolicyEvaluation.BUILD_POLICY, "Build", blocking = true, enabled = true, status = status,
        buildId = buildId, expired = expired, displayName = id, evaluationId = id,
    )

    private inner class Api : AdoApi {
        var policies = listOf(build("ev-ci", "approved"), build("ev-tests", "approved", expired = true), build("ev-app", "approved", expired = true))
        /** What each read after a re-queue answers, in turn; the policies stay as the last one after them. */
        var afterRequeue = mutableListOf<List<PolicyEvaluation>>()
        var fail: AdoException? = null
        val requeued = mutableListOf<String>()
        var reads = 0

        override suspend fun pullRequest(id: Int) = pr
        override suspend fun pullRequestDetail(pr: PullRequest) = PullRequestDetail(pr)
        override suspend fun policyEvaluations(pr: PullRequest): List<PolicyEvaluation> {
            reads++
            if (requeued.isNotEmpty() && afterRequeue.isNotEmpty()) policies = afterRequeue.removeAt(0)
            return policies
        }
        override suspend fun requeuePolicyEvaluation(pr: PullRequest, evaluationId: String): PolicyEvaluation {
            requeued += evaluationId
            fail?.let { throw it }
            return build(evaluationId, "queued", buildId = 990 + requeued.size)
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

    private fun TestScope.opened(api: Api): PullRequestController {
        val c = PullRequestController(this, { api }, { me }, { it.message ?: "error" }, clock = { Fakes.now })
        c.open(311, pr)
        advanceUntilIdle()
        return c
    }

    private fun PrScreen.policy(id: String) = policies!!.single { it.evaluationId == id }

    @Test fun `re-queue is offered on build checks expired or failed`() {
        fun offered(e: PolicyEvaluation) = PrChecks.of(pr, listOf(e), 0, emptyList()).first().let(PrChecks::requeueId)
        assertEquals("ev-1", offered(build("ev-1", "approved", expired = true)))
        assertEquals("ev-1", offered(build("ev-1", "rejected")))
        assertNull(offered(build("ev-1", "approved")))
        assertNull(offered(build("ev-1", "running")))
        assertNull("not known", offered(build("", "approved", expired = true)))
        assertEquals(
            listOf("ev-tests", "ev-app"),
            PrChecks.expiredBuilds(Api().policies + build("ev-lint", "rejected") + build("ev-off", "approved", expired = true).copy(enabled = false)),
        )
    }

    @Test fun `one check queued again, then read again until its build finished`() = runTest {
        val api = Api()
        val c = opened(api)
        assertTrue(c.state.value!!.blocked)
        val reads = api.reads
        val running = api.policies.map { if (it.evaluationId == "ev-tests") build("ev-tests", "running", buildId = 991) else it }
        val passed = api.policies.map { if (it.evaluationId == "ev-tests") build("ev-tests", "approved", buildId = 991) else it }
        api.afterRequeue = mutableListOf(running, running, passed)
        c.requeueCheck("ev-tests")
        assertEquals(setOf("ev-tests"), c.state.value!!.requeue.busy)
        runCurrent()
        // Read again at once: the row turns to running
        var s = c.state.value!!
        assertEquals(listOf("ev-tests"), api.requeued)
        assertEquals(reads + 1, api.reads)
        assertEquals("running", s.policy("ev-tests").status)
        assertTrue(s.requeue.busy.isEmpty())
        val row = PrChecks.of(pr, s.policies!!, 0, emptyList()).first { (it.kind as? CheckKind.Build)?.evaluationId == "ev-tests" }
        assertEquals(CheckState.RUNNING, row.state)
        advanceUntilIdle()
        // Read again now and then until it passed, then no more
        s = c.state.value!!
        assertEquals(reads + 3, api.reads)
        assertEquals("approved", s.policy("ev-tests").status)
        assertFalse(s.policy("ev-tests").expired)
        // The other expired one still blocks it
        assertEquals(1, PrCompletion.blockers(PrChecks.of(pr, s.policies!!, 0, emptyList())).size)
    }

    @Test fun `a check queued again keeps its name while its build has none yet`() = runTest {
        val api = Api()
        val c = opened(api)
        val queued = api.policies.map {
            if (it.evaluationId == "ev-tests") build("ev-tests", "queued", buildId = null).copy(displayName = null) else it
        }
        api.afterRequeue = mutableListOf(queued)
        c.requeueCheck("ev-tests")
        runCurrent()
        assertEquals("ev-tests", c.state.value!!.policy("ev-tests").displayName)
    }

    @Test fun `all the expired ones queued again, and once passed nothing blocks completing`() = runTest {
        val api = Api()
        val c = opened(api)
        api.afterRequeue = mutableListOf(api.policies.map { if (it.expired) build(it.evaluationId, "approved") else it })
        c.requeueExpired()
        assertEquals(setOf("ev-tests", "ev-app"), c.state.value!!.requeue.busy)
        advanceUntilIdle()
        assertEquals(listOf("ev-tests", "ev-app"), api.requeued)
        val s = c.state.value!!
        assertTrue(s.requeue.busy.isEmpty())
        assertNull(s.requeue.error)
        assertFalse(s.blocked)
        assertTrue(PrCompletion.blockers(PrChecks.of(pr, s.policies!!, 0, emptyList())).isEmpty())
    }

    @Test fun `without permission it says so, the others not tried, the checks as they were`() = runTest {
        val api = Api().apply { fail = AdoException(403, "HTTP 403: TF401027: You need the Git 'PullRequestContribute' permission") }
        val c = opened(api)
        val reads = api.reads
        c.requeueExpired()
        advanceUntilIdle()
        val s = c.state.value!!
        assertEquals(listOf("ev-tests"), api.requeued)
        assertEquals(SaveProblem.DENIED, s.requeue.error?.problem)
        assertTrue(s.requeue.busy.isEmpty())
        assertEquals(reads, api.reads)
        assertTrue(s.policy("ev-tests").expired)
        c.dismissRequeueError()
        assertNull(c.state.value!!.requeue.error)
    }

    @Test fun `other errors keep their message`() = runTest {
        val api = Api().apply { fail = AdoException(500, "HTTP 500: the policy service is unavailable") }
        val c = opened(api)
        c.requeueCheck("ev-app")
        advanceUntilIdle()
        val error = c.state.value!!.requeue.error!!
        assertEquals(SaveProblem.OTHER, error.problem)
        assertEquals("HTTP 500: the policy service is unavailable", error.message)
    }

    @Test fun `closing the page stops reading the checks`() = runTest {
        val api = Api()
        val c = opened(api)
        api.afterRequeue = mutableListOf(api.policies.map { if (it.evaluationId == "ev-tests") build("ev-tests", "running") else it })
        c.requeueCheck("ev-tests")
        runCurrent()
        val reads = api.reads
        c.close()
        advanceUntilIdle()
        assertEquals(reads, api.reads)
    }

    @Test fun `nothing is queued again on a pull request no longer active`() = runTest {
        val api = Api()
        val abandoned = object : AdoApi by api {
            override suspend fun pullRequestDetail(pr: PullRequest) = PullRequestDetail(pr, status = PullRequestDetail.ABANDONED)
        }
        val d = PullRequestController(this, { abandoned }, { me }, { it.message ?: "error" }, clock = { Fakes.now })
        d.open(311, pr)
        advanceUntilIdle()
        d.requeueExpired()
        advanceUntilIdle()
        assertTrue(api.requeued.isEmpty())
    }
}
