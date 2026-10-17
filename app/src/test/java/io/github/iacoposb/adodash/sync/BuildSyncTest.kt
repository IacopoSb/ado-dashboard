package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.anna
import io.github.iacoposb.adodash.Fakes.me
import io.github.iacoposb.adodash.Fakes.now
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.Build
import io.github.iacoposb.adodash.api.BuildState
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.PolicyEvaluation
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrFilter
import io.github.iacoposb.adodash.api.PrThread
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.TestRun
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.api.WorkItemTypeInfo
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.data.DashboardView
import io.github.iacoposb.adodash.data.EventKind
import io.github.iacoposb.adodash.data.Filters
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The pull request signals, the pipeline tile and the failed build events of a sync. */
class BuildSyncTest {
    private class FakeApi(
        val toReview: List<PullRequest> = emptyList(),
        val mine: List<PullRequest> = emptyList(),
        val builds: Map<String, List<Build>> = emptyMap(),
        val threads: Map<Int, List<PrThread>> = emptyMap(),
        var evaluations: Map<Int, List<PolicyEvaluation>> = emptyMap(),
        val buildsError: Int? = null,
    ) : AdoApi {
        var buildCalls = 0
        var policyCalls = 0
        var branchCalls = 0
        var testRunCalls = 0
        override suspend fun me() = me
        override suspend fun projects() = listOf("Contoso", "Clienti")
        override suspend fun queryWorkItemIds(wiql: String, top: Int) = emptyList<Int>()
        override suspend fun workItems(ids: List<Int>) = emptyList<WorkItem>()
        override suspend fun stateCategories(project: String, type: String) = emptyMap<String, String>()
        override suspend fun areas(project: String) = AreaNode(project, project)
        override suspend fun workItemTypes(project: String) = emptyList<WorkItemTypeInfo>()
        override suspend fun workItemComments(project: String, id: Int) = emptyList<Comment>()
        override suspend fun activePullRequests(project: String, filter: PrFilter) =
            (if (filter is PrFilter.Reviewer) toReview else if (filter is PrFilter.Creator) mine else emptyList())
                .filter { it.project == project }
        override suspend fun pullRequestComments(pr: PullRequest) = emptyList<PrComment>()
        override suspend fun pullRequestThreads(pr: PullRequest) = threads[pr.id].orEmpty()
        override suspend fun policyEvaluations(pr: PullRequest): List<PolicyEvaluation> {
            policyCalls++
            return evaluations[pr.id].orEmpty()
        }
        override suspend fun builds(project: String, top: Int): List<Build> {
            buildCalls++
            buildsError?.let { throw AdoException(it, "denied") }
            return builds[project].orEmpty()
        }
        override suspend fun defaultBranches(project: String): Map<String, String> {
            branchCalls++
            return mapOf("repo-1" to "main")
        }
        override suspend fun testRuns(project: String, buildId: Int): List<TestRun> {
            testRunCalls++
            return listOf(TestRun(1, "unit", 2))
        }
    }

    private val since: Instant = now.minusSeconds(300)

    @Test fun `signals come with the snapshot`() = runTest {
        val review = Fakes.pr(311, anna).copy(mergeStatus = "conflicts")
        val mine = Fakes.pr(318, me).copy(mergeStatus = "succeeded")
        val api = FakeApi(
            toReview = listOf(review), mine = listOf(mine),
            builds = mapOf("Contoso" to listOf(Fakes.build(1, BuildState.FAILED, prId = 311))),
            threads = mapOf(311 to listOf(PrThread(1, "active", listOf(Fakes.comment(1, 1, anna, "?"))))),
        )
        val signals = SyncEngine(api, texts = Fakes.Texts, now = { now }).fetch(me, since = null).snapshot.signals
        assertEquals(PrSignals(BuildSignal.FAILED, activeThreads = 1, conflicts = true, readyToComplete = false), signals[311])
        assertEquals(PrSignals(BuildSignal.NONE, activeThreads = 0, conflicts = false, readyToComplete = true), signals[318])
    }

    @Test fun `policies are read again only when something changed`() = runTest {
        val pr = Fakes.pr(318, me).copy(lastCommit = "a1")
        var clock = now
        val cache = SignalCache()
        suspend fun sync(prs: List<PullRequest>) {
            SyncEngine(FakeApiHolder.api(prs), texts = Fakes.Texts, now = { clock }, cache = cache).fetch(me, since = since)
        }
        FakeApiHolder.calls = 0
        FakeApiHolder.running = false
        sync(listOf(pr))
        sync(listOf(pr))
        assertEquals("unchanged: from the cache", 1, FakeApiHolder.calls)
        sync(listOf(pr.copy(lastCommit = "b2")))
        assertEquals("a new commit", 2, FakeApiHolder.calls)
        clock = clock.plus(SyncEngine.SIGNALS_TTL)
        sync(listOf(pr.copy(lastCommit = "b2")))
        assertEquals("too old", 3, FakeApiHolder.calls)
        FakeApiHolder.running = true
        sync(listOf(pr.copy(lastCommit = "c3")))
        sync(listOf(pr.copy(lastCommit = "c3")))
        assertEquals("still running: read at every sync", 5, FakeApiHolder.calls)
    }

    /** Counts policy calls across engines sharing a cache. */
    private object FakeApiHolder {
        var calls = 0
        var running = false
        fun api(prs: List<PullRequest>) = object : AdoApi by FakeApi(mine = prs) {
            override suspend fun policyEvaluations(pr: PullRequest): List<PolicyEvaluation> {
                calls++
                return listOf(
                    PolicyEvaluation(PolicyEvaluation.BUILD_POLICY, "Build", true, true, if (running) "running" else "approved", 1),
                )
            }
        }
    }

    @Test fun `a board's tile shows my PR builds and the default branches, read once`() = runTest {
        val api = FakeApi(
            mine = listOf(Fakes.pr(305, me)),
            builds = mapOf(
                "Contoso" to listOf(Fakes.build(2, BuildState.FAILED, prId = 305), Fakes.build(1, BuildState.SUCCEEDED, ago = 900)),
                "Clienti" to listOf(Fakes.build(3, BuildState.RUNNING, project = "Clienti", repoId = "repo-9")),
            ),
        )
        val cache = SignalCache()
        val engine = SyncEngine(api, texts = Fakes.Texts, now = { now }, cache = cache)
        val board = DashboardView.forBoard(BoardRef("Contoso", "Backend"))
        val snapshot = engine.fetch(me, since = null, view = board).snapshot
        assertEquals(listOf(2, 1), snapshot.shown!!.pipeline.map { it.id })
        assertEquals("Contoso", snapshot.shown!!.pipelineProject)
        assertFalse(snapshot.buildsDenied)
        assertEquals("only the board's project", 1, api.buildCalls)
        engine.fetch(me, since = null, view = board)
        assertEquals("default branches are cached", 1, api.branchCalls)
    }

    @Test fun `the global view has no tile, only the builds of the PRs projects and no default branches`() = runTest {
        val api = FakeApi(
            mine = listOf(Fakes.pr(305, me)),
            builds = mapOf(
                "Contoso" to listOf(Fakes.build(2, BuildState.FAILED, prId = 305), Fakes.build(1, BuildState.SUCCEEDED, ago = 900)),
                "Clienti" to listOf(Fakes.build(3, BuildState.RUNNING, project = "Clienti", repoId = "repo-9")),
            ),
        )
        val fetched = SyncEngine(api, texts = Fakes.Texts, now = { now }).fetch(me, since = since)
        val shown = fetched.snapshot.shown!!
        assertTrue(shown.pipeline.isEmpty())
        assertEquals(null, shown.pipelineProject)
        assertEquals("Contoso only: where my PR is", 1, api.buildCalls)
        assertEquals(0, api.branchCalls)
        // The PR row's build and the notification stay
        assertEquals(BuildSignal.FAILED, fetched.snapshot.signals[305]?.build)
        assertEquals(listOf(2), fetched.failedPrBuilds.map { it.build.id })
    }

    @Test fun `a board's tile has only its project, the failed builds of my PRs stay`() = runTest {
        val api = FakeApi(
            mine = listOf(Fakes.pr(305, me)),
            builds = mapOf(
                "Contoso" to listOf(Fakes.build(2, BuildState.FAILED, prId = 305, ago = 420)),
                "Clienti" to listOf(Fakes.build(3, BuildState.SUCCEEDED, project = "Clienti", ago = 100)),
            ),
        )
        val fetched = SyncEngine(api, texts = Fakes.Texts, now = { now })
            .fetch(me, since = since, view = DashboardView.forBoard(BoardRef("Clienti", "Team")))
        assertEquals(listOf(3), fetched.snapshot.shown!!.pipeline.map { it.id })
        assertEquals("Clienti", fetched.snapshot.shown!!.pipelineProject)
        assertEquals("views do not change notifications", listOf(2), fetched.failedPrBuilds.map { it.build.id })
    }

    @Test fun `builds refused hide the pipeline and are not asked again for a while`() = runTest {
        var clock = now
        val api = FakeApi(mine = listOf(Fakes.pr(305, me)), buildsError = 401)
        val cache = SignalCache()
        val engine = SyncEngine(api, texts = Fakes.Texts, now = { clock }, cache = cache)
        val snapshot = engine.fetch(me, since = null).snapshot
        assertTrue(snapshot.buildsDenied)
        assertTrue(snapshot.shown!!.pipeline.isEmpty())
        assertTrue("the sync itself works", snapshot.warnings.isEmpty())
        val calls = api.buildCalls
        assertTrue(engine.fetch(me, since = null).snapshot.buildsDenied)
        assertEquals(calls, api.buildCalls)
        clock = clock.plus(SyncEngine.BUILDS_RETRY)
        engine.fetch(me, since = null)
        assertTrue(api.buildCalls > calls)
    }

    @Test fun `failed builds of my PRs become events once, with their failed tests`() = runTest {
        val mine = Fakes.pr(305, me)
        val builds = listOf(
            Fakes.build(12, BuildState.FAILED, prId = 305, ago = 420), // finished 10 s ago
            Fakes.build(11, BuildState.FAILED, prId = 305, ago = 7200), // long before the last sync
            Fakes.build(10, BuildState.SUCCEEDED, prId = 305, ago = 420),
            Fakes.build(9, BuildState.FAILED, prId = 999, ago = 420), // someone else's PR
            Fakes.build(8, BuildState.FAILED, ago = 420), // the default branch
        )
        val api = FakeApi(mine = listOf(mine), builds = mapOf("Contoso" to builds))
        val cache = SignalCache()

        val first = SyncEngine(api, texts = Fakes.Texts, now = { now }, cache = cache).fetch(me, since = null)
        assertEquals("first sync: all of them, as history", listOf(12, 11), first.failedPrBuilds.map { it.build.id })
        assertEquals("history needs no test counts", 0, api.testRunCalls)

        val fetched = SyncEngine(api, texts = Fakes.Texts, now = { now }, cache = cache).fetch(me, since = since)
        assertEquals(listOf(12), fetched.failedPrBuilds.map { it.build.id })
        val event = Events.from(fetched, now.minusSeconds(86_400), Fakes.Texts).single { it.kind == EventKind.BUILD_FAILED }
        assertEquals("build:12:failed", event.key)
        assertEquals(12, event.buildId)
        assertEquals("build failed !305", event.title)
        assertEquals("backend-ci #20261016.12 · 2 failed", event.body)
        assertEquals("Contoso", event.project)
        SyncEngine(api, texts = Fakes.Texts, now = { now }, cache = cache).fetch(me, since = since)
        assertEquals("tests counted once per build", 1, api.testRunCalls)
    }

    @Test fun `filtered out projects bring no build events`() = runTest {
        val api = FakeApi(
            mine = listOf(Fakes.pr(305, me, project = "Clienti")),
            builds = mapOf("Clienti" to listOf(Fakes.build(12, BuildState.FAILED, prId = 305, ago = 420, project = "Clienti"))),
        )
        val fetched = SyncEngine(api, texts = Fakes.Texts, now = { now })
            .fetch(me, since = since, filters = Filters(areas = setOf("Contoso")))
        assertTrue(fetched.failedPrBuilds.isEmpty())
        assertTrue(Events.from(fetched, now.minusSeconds(86_400), Fakes.Texts).none { it.kind == EventKind.BUILD_FAILED })
    }

    @Test fun `the same build is one event`() {
        val pr = Fakes.pr(305, me)
        val b = Fakes.build(12, BuildState.FAILED, prId = 305)
        val fetched = Fetched(Snapshot(me, emptyList(), emptyList(), listOf(pr), now), failedPrBuilds = listOf(FailedPrBuild(pr, b), FailedPrBuild(pr, b)))
        assertEquals(listOf("build:12:failed"), Events.from(fetched, now.minusSeconds(86_400), Fakes.Texts).map { it.key })
    }
}
