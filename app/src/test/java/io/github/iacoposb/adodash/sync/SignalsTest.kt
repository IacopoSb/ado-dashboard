package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.anna
import io.github.iacoposb.adodash.Fakes.marco
import io.github.iacoposb.adodash.Fakes.me
import io.github.iacoposb.adodash.Fakes.now
import io.github.iacoposb.adodash.api.BuildState
import io.github.iacoposb.adodash.api.PolicyEvaluation
import io.github.iacoposb.adodash.api.PrStatus
import io.github.iacoposb.adodash.api.PrThread
import io.github.iacoposb.adodash.api.Reviewer
import io.github.iacoposb.adodash.api.TimelineRecord
import io.github.iacoposb.adodash.api.Vote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalsTest {
    private fun buildPolicy(status: String, buildId: Int? = 1, expired: Boolean = false, blocking: Boolean = true) =
        PolicyEvaluation(PolicyEvaluation.BUILD_POLICY, "Build", blocking, true, status, buildId, expired)

    private fun policy(status: String, blocking: Boolean = true) =
        PolicyEvaluation("fa4e907d-c16b-4a4c-9dfa-4906e5d171dd", "Minimum number of reviewers", blocking, true, status)

    private fun thread(id: Int, status: String?) = PrThread(id, status, listOf(Fakes.comment(id, 1, anna, "text")))

    private val approvedPr = Fakes.pr(
        320, me, reviewers = listOf(Reviewer(anna.id, anna.name, Vote.APPROVED, true), Reviewer(marco.id, marco.name, Vote.NONE, false)),
    ).copy(mergeStatus = "succeeded")

    @Test fun `ready when policies pass, required reviewers approved, no threads and no conflicts`() {
        val s = Signals.of(approvedPr, listOf(buildPolicy("approved"), policy("approved")), listOf(thread(1, "fixed")), emptyList())
        assertEquals(PrSignals(BuildSignal.SUCCEEDED, activeThreads = 0, conflicts = false, readyToComplete = true), s)
    }

    @Test fun `not ready for each reason`() {
        val ok = listOf(buildPolicy("approved"), policy("approved"))
        fun ready(pr: io.github.iacoposb.adodash.api.PullRequest = approvedPr, evaluations: List<PolicyEvaluation>? = ok, threads: List<PrThread>? = emptyList()) =
            Signals.of(pr, evaluations, threads, emptyList()).readyToComplete
        assertTrue(ready())
        assertFalse("an active thread", ready(threads = listOf(thread(1, "active"))))
        assertFalse("a pending thread", ready(threads = listOf(thread(1, "pending"))))
        assertFalse("conflicts", ready(pr = approvedPr.copy(mergeStatus = "conflicts")))
        assertFalse("draft", ready(pr = approvedPr.copy(draft = true)))
        assertFalse("a blocking policy rejected", ready(evaluations = listOf(buildPolicy("approved"), policy("rejected"))))
        assertFalse("a build still running", ready(evaluations = listOf(buildPolicy("running"), policy("approved"))))
        assertFalse("an expired build", ready(evaluations = listOf(buildPolicy("approved", expired = true), policy("approved"))))
        assertTrue("an optional policy failing", ready(evaluations = ok + policy("rejected", blocking = false)))
        assertFalse("policies unknown", ready(evaluations = null))
        assertFalse("threads unknown", ready(threads = null))
        val waiting = approvedPr.copy(reviewers = listOf(Reviewer(anna.id, anna.name, Vote.WAITING, true)))
        assertFalse("a required reviewer waiting", ready(pr = waiting))
        val rejected = approvedPr.copy(reviewers = approvedPr.reviewers + Reviewer(me.id, me.name, Vote.REJECTED, false))
        assertFalse("someone rejected", ready(pr = rejected))
        val suggestions = approvedPr.copy(reviewers = listOf(Reviewer(anna.id, anna.name, Vote.APPROVED_WITH_SUGGESTIONS, true)))
        assertTrue("approved with suggestions", ready(pr = suggestions))
    }

    @Test fun `system threads and resolved ones are not active`() {
        val threads = listOf(thread(1, "active"), thread(2, "active"), thread(3, "fixed"), thread(4, "closed"), thread(5, null),
            PrThread(6, "active", emptyList()))
        assertEquals(2, Signals.of(approvedPr, emptyList(), threads, emptyList()).activeThreads)
    }

    @Test fun `build from the policies first`() {
        fun build(vararg e: PolicyEvaluation) = Signals.buildOf(approvedPr, e.toList(), emptyList())
        assertEquals(BuildSignal.FAILED, build(buildPolicy("approved"), buildPolicy("rejected")))
        assertEquals(BuildSignal.RUNNING, build(buildPolicy("approved"), buildPolicy("running")))
        assertEquals(BuildSignal.RUNNING, build(buildPolicy("queued", buildId = 7)))
        assertEquals("a manual build not queued yet", BuildSignal.NONE, build(buildPolicy("queued", buildId = null)))
        assertEquals(BuildSignal.SUCCEEDED, build(buildPolicy("approved")))
        assertEquals(BuildSignal.NONE, build(policy("approved")))
        // A build policy wins over the builds list
        val failedRun = Fakes.build(1, BuildState.FAILED, prId = approvedPr.id)
        assertEquals(BuildSignal.SUCCEEDED, Signals.buildOf(approvedPr, listOf(buildPolicy("approved")), listOf(failedRun)))
    }

    @Test fun `a required build expired is not ok`() {
        fun build(vararg e: PolicyEvaluation) = Signals.buildOf(approvedPr, e.toList(), emptyList())
        assertEquals(BuildSignal.EXPIRED, build(buildPolicy("approved"), buildPolicy("approved", expired = true)))
        assertEquals("expired while still queued", BuildSignal.EXPIRED, build(buildPolicy("queued", buildId = 7, expired = true)))
        assertEquals("a failure wins", BuildSignal.FAILED, build(buildPolicy("approved", expired = true), buildPolicy("rejected")))
        assertEquals("a build running wins", BuildSignal.RUNNING, build(buildPolicy("approved", expired = true), buildPolicy("running")))
        assertEquals("an optional one does not hold it back", BuildSignal.SUCCEEDED,
            build(buildPolicy("approved"), buildPolicy("approved", expired = true, blocking = false)))
        val s = Signals.of(approvedPr, listOf(buildPolicy("approved", expired = true)), emptyList(), emptyList())
        assertEquals(BuildSignal.EXPIRED, s.build)
        assertFalse(s.readyToComplete)
    }

    @Test fun `without build policies the PR's own latest builds tell`() {
        val pr = approvedPr
        val builds = listOf(
            Fakes.build(3, BuildState.SUCCEEDED, prId = pr.id, ago = 100),
            Fakes.build(2, BuildState.FAILED, prId = pr.id, ago = 900),
            Fakes.build(4, BuildState.FAILED, prId = 999, ago = 50),
            Fakes.build(5, BuildState.FAILED, prId = pr.id, repoId = "other-repo", ago = 50),
        )
        assertEquals("a rerun that passed hides the older failure", BuildSignal.SUCCEEDED, Signals.buildOf(pr, emptyList(), builds))
        val twoPipelines = builds + Fakes.build(6, BuildState.RUNNING, prId = pr.id, definition = 2, ago = 30)
        assertEquals(BuildSignal.RUNNING, Signals.buildOf(pr, null, twoPipelines))
        assertEquals(BuildSignal.NONE, Signals.buildOf(pr, null, emptyList()))
    }

    @Test fun `the tile has my PR builds and the latest default branch build of each repo`() {
        val mine = Fakes.pr(305, me)
        val builds = listOf(
            Fakes.build(10, BuildState.FAILED, prId = 305, ago = 100),
            Fakes.build(9, BuildState.SUCCEEDED, prId = 305, ago = 900),
            Fakes.build(8, BuildState.SUCCEEDED, prId = 311, ago = 200),
            Fakes.build(7, BuildState.RUNNING, ago = 300),
            Fakes.build(6, BuildState.SUCCEEDED, ago = 1000),
            Fakes.build(5, BuildState.SUCCEEDED, branch = "feature/other", ago = 50),
            Fakes.build(4, BuildState.SUCCEEDED, repoId = "repo-2", branch = "master", ago = 400),
            Fakes.build(3, BuildState.SUCCEEDED, repoId = "repo-3", branch = "main", ago = 400),
        )
        val tile = Pipelines.tile(builds, listOf(mine), mapOf("repo-1" to "main", "repo-2" to "master"), acceptsRepo = { true })
        assertEquals(listOf(10, 7, 4), tile.map { it.id })
        assertEquals(Pipelines.Counts(failed = 1, running = 1, ok = 1), Pipelines.counts(tile))
        val onlyRepo2 = Pipelines.tile(builds, emptyList(), mapOf("repo-1" to "main", "repo-2" to "master")) { it.repoId == "repo-2" }
        assertEquals(listOf(4), onlyRepo2.map { it.id })
    }

    private fun typed(type: String, status: String, blocking: Boolean = true, name: String? = null) =
        PolicyEvaluation(type, "Type", blocking, true, status, displayName = name)

    private fun sonarPolicy(status: String, blocking: Boolean = true) = PolicyEvaluation(
        PolicyEvaluation.STATUS_POLICY, "Status", blocking, true, status, statusGenre = "SonarQube", statusName = "quality-gate",
    )

    @Test fun `blocking checks other than builds have their own signals`() {
        val s = Signals.of(
            approvedPr,
            listOf(
                buildPolicy("approved"),
                typed(PolicyEvaluation.COMMENTS_POLICY, "rejected"),
                typed(PolicyEvaluation.WORK_ITEMS_POLICY, "rejected"),
                typed(PolicyEvaluation.MIN_REVIEWERS_POLICY, "queued"),
                sonarPolicy("rejected"),
                typed("custom", "queued", name = "Security scan"),
                // Not blocking: no signal
                typed("optional", "rejected", blocking = false, name = "Lint"),
            ),
            listOf(thread(1, "active")), emptyList(),
        )
        assertEquals(BuildSignal.SUCCEEDED, s.build)
        assertTrue(s.commentsBlocking)
        assertTrue(s.workItemsMissing)
        assertTrue(s.approvalsMissing)
        assertEquals(listOf("SonarQube/quality-gate"), s.failedChecks)
        assertEquals(listOf("Security scan"), s.pendingChecks)
        assertFalse(s.readyToComplete)
    }

    @Test fun `nothing to say when the checks pass`() {
        val s = Signals.of(
            approvedPr,
            listOf(
                typed(PolicyEvaluation.COMMENTS_POLICY, "approved"), typed(PolicyEvaluation.WORK_ITEMS_POLICY, "notApplicable"),
                typed(PolicyEvaluation.REQUIRED_REVIEWERS_POLICY, "approved"), sonarPolicy("approved"),
            ),
            emptyList(), emptyList(), statuses = listOf(PrStatus("SonarQube", "quality-gate", "succeeded")),
        )
        assertEquals(PrSignals(BuildSignal.NONE, activeThreads = 0, readyToComplete = true), s)
    }

    @Test fun `a required reviewer who has not approved is missing, not in a draft`() {
        val pr = approvedPr.copy(reviewers = listOf(Reviewer(anna.id, anna.name, Vote.WAITING, true)))
        assertTrue(Signals.of(pr, emptyList(), emptyList(), emptyList()).approvalsMissing)
        assertFalse(Signals.of(pr.copy(draft = true), emptyList(), emptyList(), emptyList()).approvalsMissing)
    }

    @Test fun `a failed status is named once, by its policy if one waits for it`() {
        val statuses = listOf(
            PrStatus("SonarQube", "quality-gate", "failed"),
            PrStatus("Checkmarx", "scan", "error"),
            PrStatus(null, "coverage", "pending"),
            PrStatus(null, "docs", "succeeded"),
        )
        val withPolicy = Signals.of(approvedPr, listOf(sonarPolicy("rejected")), emptyList(), emptyList(), statuses)
        assertEquals(listOf("SonarQube/quality-gate", "Checkmarx/scan"), withPolicy.failedChecks)
        // A pending status no policy waits for does not hold the pull request back
        assertEquals(emptyList<String>(), withPolicy.pendingChecks)
        val alone = Signals.of(approvedPr, emptyList(), emptyList(), emptyList(), statuses)
        assertEquals(listOf("SonarQube/quality-gate", "Checkmarx/scan"), alone.failedChecks)
        // Statuses unread: only the policies speak
        assertEquals(emptyList<String>(), Signals.of(approvedPr, emptyList(), emptyList(), emptyList(), null).failedChecks)
    }

    @Test fun `a status policy waiting for its service is pending`() {
        val s = Signals.of(approvedPr, listOf(sonarPolicy("queued")), emptyList(), emptyList(), emptyList())
        assertEquals(listOf("SonarQube/quality-gate"), s.pendingChecks)
        assertFalse(s.readyToComplete)
    }

    @Test fun `the latest status of each service, those that do not apply left out`() {
        fun status(genre: String?, name: String, state: String, at: Long, id: Int) =
            PrStatus(genre, name, state, at = now.minusSeconds(at), id = id)
        val latest = PrStatus.latest(
            listOf(
                status("SonarQube", "quality-gate", "pending", 600, 1),
                status("sonarqube", "Quality-Gate", "failed", 300, 2),
                status("SonarQube", "quality-gate", "succeeded", 900, 3),
                status(null, "coverage", "succeeded", 600, 4),
                status(null, "coverage", "notApplicable", 60, 5),
                status("ci", "lint", "notSet", 60, 6),
            ),
        )
        assertEquals(listOf(2), latest.map { it.id })
        assertTrue(Signals.pendingStatuses(listOf(status(null, "x", "pending", 1, 1))))
    }

    private fun record(id: String, type: String, name: String, order: Int, parent: String? = null, state: BuildState = BuildState.SUCCEEDED) =
        TimelineRecord(id, parent, type, name, order, state, false, now.minusSeconds(60), now)

    @Test fun `steps are the stages, else the jobs, else the job's tasks`() {
        val stages = listOf(
            record("s2", "Stage", "Deploy", 2), record("s1", "Stage", "Build", 1),
            record("j1", "Job", "Compile", 1, "p1"), record("p1", "Phase", "Compile", 1, "s1"),
        )
        assertEquals(listOf("Build", "Deploy"), BuildSteps.of(stages).map { it.name })
        val jobs = listOf(record("s1", "Stage", "__default", 1), record("j1", "Job", "Linux", 1), record("j2", "Job", "Windows", 2))
        assertEquals(listOf("Linux", "Windows"), BuildSteps.of(jobs).map { it.name })
        val tasks = listOf(
            record("s1", "Stage", "__default", 1), record("j1", "Job", "Job", 1),
            record("t0", "Task", "Initialize job", 1, "j1"), record("t2", "Task", "Unit tests", 3, "j1", BuildState.FAILED),
            record("t1", "Task", "Checkout", 2, "j1"), record("t3", "Task", "Post-job: Checkout", 4, "j1"),
            record("t4", "Task", "Finalize Job", 5, "j1"),
        )
        val steps = BuildSteps.of(tasks)
        assertEquals(listOf("Checkout", "Unit tests"), steps.map { it.name })
        assertEquals(BuildState.FAILED, steps[1].state)
    }
}
