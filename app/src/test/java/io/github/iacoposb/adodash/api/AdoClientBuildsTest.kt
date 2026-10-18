package io.github.iacoposb.adodash.api

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The calls behind the pull request signals, the pipeline tile and the build details. */
class AdoClientBuildsTest {
    private val server = MockWebServer().apply { start() }
    private val client = AdoClient(server.url("/org").toString(), AdoClient.pat("secret"))

    @After fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private val pr = PullRequest(
        305, "My Project", "r1", "backend", "t", Identity("u1", ""), "feature/x", "main", false, emptyList(),
        Instant.EPOCH, "", projectId = "p-guid",
    )

    @Test fun `pull requests carry merge status, last commit and project id`() = runTest {
        server.enqueue(json("""
            {"value":[{"pullRequestId":42,"title":"t","creationDate":"2026-10-15T08:00:00Z","mergeStatus":"conflicts",
              "lastMergeSourceCommit":{"commitId":"abc123","url":"x"},
              "repository":{"id":"r1","name":"web","project":{"id":"p-guid","name":"My Project"}}}]}
        """.trimIndent()))
        val got = client.activePullRequests("My Project", PrFilter.All).single()
        assertEquals("conflicts", got.mergeStatus)
        assertTrue(got.hasConflicts)
        assertEquals("abc123", got.lastCommit)
        assertEquals("p-guid", got.projectId)
    }

    @Test fun `threads keep their status`() = runTest {
        server.enqueue(json("""
            {"value":[
              {"id":1,"status":"active","comments":[{"id":1,"content":"?","commentType":"text","publishedDate":"2026-10-15T08:00:00Z"}]},
              {"id":2,"status":"fixed","comments":[{"id":1,"content":"ok","commentType":"text","publishedDate":"2026-10-15T08:00:00Z"}]},
              {"id":3,"comments":[{"id":1,"content":"voted","commentType":"system","publishedDate":"2026-10-15T08:00:00Z"}]}]}
        """.trimIndent()))
        val threads = client.pullRequestThreads(pr)
        assertEquals(listOf("active", "fixed", null), threads.map { it.status })
        assertEquals(listOf(true, false, false), threads.map { it.active })
        assertEquals("/org/My%20Project/_apis/git/repositories/r1/pullRequests/305/threads?api-version=7.1", server.takeRequest().path)
    }

    @Test fun `policy evaluations of a pull request`() = runTest {
        server.enqueue(json("""
            {"count":2,"value":[
              {"configuration":{"isEnabled":true,"isBlocking":true,
                "type":{"id":"0609b952-1397-4640-95ec-e00a01b2c241","displayName":"Build"},"settings":{"buildDefinitionId":5}},
               "status":"rejected","context":{"buildId":981,"isExpired":false,"buildDefinitionName":"backend-ci"}},
              {"configuration":{"isEnabled":true,"isBlocking":false,
                "type":{"id":"fa4e907d-c16b-4a4c-9dfa-4906e5d171dd","displayName":"Minimum number of reviewers"}},
               "status":"approved"}]}
        """.trimIndent()))
        val evaluations = client.policyEvaluations(pr)
        assertEquals(
            listOf(
                PolicyEvaluation(PolicyEvaluation.BUILD_POLICY, "Build", true, true, "rejected", 981, false, displayName = "backend-ci"),
                PolicyEvaluation("fa4e907d-c16b-4a4c-9dfa-4906e5d171dd", "Minimum number of reviewers", false, true, "approved"),
            ),
            evaluations,
        )
        assertTrue(evaluations[0].isBuild)
        assertEquals(
            "/org/p-guid/_apis/policy/evaluations?artifactId=vstfs%3A%2F%2F%2FCodeReview%2FCodeReviewId%2Fp-guid%2F305" +
                "&api-version=7.1-preview.1",
            server.takeRequest().path,
        )
    }

    private val buildJson = """
        {"id":981,"buildNumber":"20261016.3","status":"completed","result":"failed",
         "queueTime":"2026-10-16T09:40:00.1234567Z","startTime":"2026-10-16T09:41:00Z","finishTime":"2026-10-16T09:47:40Z",
         "sourceBranch":"refs/pull/305/merge","reason":"pullRequest",
         "definition":{"id":5,"name":"backend-ci"},"project":{"id":"p-guid","name":"My Project"},
         "repository":{"id":"r1","name":"backend","type":"TfsGit"},
         "requestedFor":{"id":"u2","displayName":"Marco Bianchi"},
         "triggerInfo":{"pr.number":"305","pr.sourceBranch":"refs/heads/feature/x-305","pr.isFork":"False"},
         "_links":{"web":{"href":"https://dev.azure.com/org/My%20Project/_build/results?buildId=981"}}}
    """.trimIndent()

    @Test fun `builds of a project, latest first`() = runTest {
        server.enqueue(json("""{"count":2,"value":[$buildJson,
            {"id":980,"buildNumber":"20261016.2","status":"inProgress","sourceBranch":"refs/heads/main","reason":"individualCI",
             "queueTime":"2026-10-16T09:30:00Z","startTime":"2026-10-16T09:31:00Z",
             "definition":{"id":6,"name":"nightly"},"repository":{"id":"r1","name":"backend"}}]}"""))
        val builds = client.builds("My Project")
        val failed = builds[0]
        assertEquals(305, failed.prId)
        assertEquals("feature/x-305", failed.branch)
        assertEquals(BuildState.FAILED, failed.state)
        assertEquals("backend-ci", failed.definitionName)
        assertEquals("Marco Bianchi", failed.requestedBy)
        assertEquals(400L, failed.duration(Instant.EPOCH)?.seconds)
        assertEquals("https://dev.azure.com/org/My%20Project/_build/results?buildId=981&view=logs", failed.logUrl)
        assertTrue(failed.definitionUrl.endsWith("/org/My%20Project/_build?definitionId=5"))
        val running = builds[1]
        assertNull(running.prId)
        assertEquals("main", running.branch)
        assertEquals(BuildState.RUNNING, running.state)
        assertTrue("a page of its own without links", running.url.endsWith("/org/My%20Project/_build/results?buildId=980"))
        assertEquals(
            "/org/My%20Project/_apis/build/builds?queryOrder=queueTimeDescending&%24top=50&api-version=7.1",
            server.takeRequest().path,
        )
    }

    @Test fun `builds without Build (read) are refused`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        val e = assertThrows(AdoException::class.java) { kotlinx.coroutines.runBlocking { client.builds("P") } }
        assertEquals(401, e.code)
    }

    @Test fun `pipelines of a project with their latest run, in folders`() = runTest {
        server.enqueue(json("""
            {"count":3,"value":[
              {"id":5,"name":"backend-ci","path":"\\Backend","project":{"id":"p-guid","name":"My Project"},
               "latestBuild":$buildJson},
              {"id":7,"name":"docs","path":"\\",
               "latestCompletedBuild":{"id":40,"buildNumber":"12","status":"completed","result":"succeeded",
                 "sourceBranch":"refs/heads/main","queueTime":"2026-10-15T09:00:00Z"}},
              {"id":9,"name":"never-run"}]}
        """.trimIndent()))
        val pipelines = client.pipelines("My Project")
        assertEquals(listOf("backend-ci", "docs", "never-run"), pipelines.map { it.name })
        assertEquals(listOf("\\Backend", "\\", "\\"), pipelines.map { it.folder })
        val ci = pipelines[0]
        assertEquals(981, ci.latest?.id)
        assertEquals(305, ci.latest?.prId)
        assertEquals(BuildState.FAILED, ci.latest?.state)
        assertTrue(ci.url.endsWith("/org/My%20Project/_build?definitionId=5"))
        // Without its definition, a latest run takes the pipeline's
        val docs = pipelines[1].latest!!
        assertEquals(7, docs.definitionId)
        assertEquals("docs", docs.definitionName)
        assertEquals("main", docs.branch)
        assertNull(pipelines[2].latest)
        assertEquals(
            "/org/My%20Project/_apis/build/definitions?includeLatestBuilds=true&queryOrder=definitionNameAscending" +
                "&%24top=1000&api-version=7.1",
            server.takeRequest().path,
        )
    }

    @Test fun `pipelines follow the continuation token`() = runTest {
        server.enqueue(json("""{"value":[{"id":1,"name":"a","path":"\\"}]}""").setHeader("x-ms-continuationtoken", "next-1"))
        server.enqueue(json("""{"value":[{"id":2,"name":"b","path":"\\"}]}"""))
        assertEquals(listOf(1, 2), client.pipelines("P").map { it.id })
        server.takeRequest()
        assertTrue(server.takeRequest().path!!.contains("continuationToken=next-1"))
        assertEquals(2, server.requestCount)
    }

    @Test fun `pipelines without Build (read) are refused`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json").setBody("""{"message":"no"}"""))
        val e = assertThrows(AdoException::class.java) { kotlinx.coroutines.runBlocking { client.pipelines("P") } }
        assertEquals(403, e.code)
    }

    @Test fun `the latest runs of a pipeline`() = runTest {
        server.enqueue(json("""{"count":1,"value":[$buildJson]}"""))
        val runs = client.pipelineRuns("My Project", 5)
        assertEquals(listOf(981), runs.map { it.id })
        assertEquals("backend-ci", runs.single().definitionName)
        assertEquals(
            "/org/My%20Project/_apis/build/builds?definitions=5&%24top=10&queryOrder=queueTimeDescending&api-version=7.1",
            server.takeRequest().path,
        )
    }

    @Test fun `a single build`() = runTest {
        server.enqueue(json(buildJson))
        assertEquals("20261016.3", client.build("My Project", 981).number)
        assertEquals("/org/My%20Project/_apis/build/builds/981?api-version=7.1", server.takeRequest().path)
    }

    @Test fun `timeline records`() = runTest {
        server.enqueue(json("""
            {"records":[
              {"id":"a","parentId":null,"type":"Stage","name":"Build","order":1,"state":"completed","result":"succeeded",
               "startTime":"2026-10-16T09:41:00Z","finishTime":"2026-10-16T09:43:00Z"},
              {"id":"b","parentId":"a","type":"Task","name":"Publish","order":7,"state":"completed","result":"skipped"},
              {"id":"c","parentId":"a","type":"Task","name":"Tests","order":6,"state":"inProgress","startTime":"2026-10-16T09:42:00Z"}]}
        """.trimIndent()))
        val records = client.buildTimeline("My Project", 981)
        assertEquals(listOf(BuildState.SUCCEEDED, BuildState.CANCELED, BuildState.RUNNING), records.map { it.state })
        assertEquals(listOf(false, true, false), records.map { it.skipped })
        assertEquals("a", records[1].parentId)
        assertEquals("/org/My%20Project/_apis/build/builds/981/timeline?api-version=7.1", server.takeRequest().path)
    }

    @Test fun `test runs and their failed results`() = runTest {
        server.enqueue(json("""{"value":[{"id":77,"name":"unit","totalTests":120,"passedTests":118,"unanalyzedTests":2}]}"""))
        server.enqueue(json("""
            {"value":[
              {"testCaseTitle":"refreshesGuestAccount","automatedTestName":"io.contoso.auth.TokenRefreshTest.refreshesGuestAccount",
               "outcome":"Failed","errorMessage":"expected 200 but was 401\n"},
              {"testCaseTitle":"Only a title","outcome":"Failed"}]}
        """.trimIndent()))
        assertEquals(listOf(TestRun(77, "unit", 2)), client.testRuns("My Project", 981))
        assertEquals(
            listOf(FailedTest("TokenRefreshTest.refreshesGuestAccount", "expected 200 but was 401"), FailedTest("Only a title", "")),
            client.failedTests("My Project", 77),
        )
        assertEquals(
            "/org/My%20Project/_apis/test/runs?buildUri=vstfs%3A%2F%2F%2FBuild%2FBuild%2F981&api-version=7.1",
            server.takeRequest().path,
        )
        assertEquals(
            "/org/My%20Project/_apis/test/Runs/77/results?outcomes=Failed&%24top=10&api-version=7.1",
            server.takeRequest().path,
        )
    }

    @Test fun `rerun is one patch of the build`() = runTest {
        server.enqueue(json(buildJson))
        client.retryBuild("My Project", 981)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/org/My%20Project/_apis/build/builds/981?retry=true&api-version=7.1", request.path)
        assertEquals("{}", request.body.readUtf8())
    }

    @Test fun `a refused rerun is an error with its code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json").setBody("""{"message":"no"}"""))
        val e = assertThrows(AdoException::class.java) { kotlinx.coroutines.runBlocking { client.retryBuild("P", 1) } }
        assertEquals(403, e.code)
    }

    @Test fun `default branches by repository`() = runTest {
        server.enqueue(json("""
            {"value":[{"id":"r1","name":"backend","defaultBranch":"refs/heads/main"},{"id":"r2","name":"empty"},
                      {"id":"r3","name":"legacy","defaultBranch":"refs/heads/master"}]}
        """.trimIndent()))
        assertEquals(mapOf("r1" to "main", "r3" to "master"), client.defaultBranches("My Project"))
        assertEquals("/org/My%20Project/_apis/git/repositories?api-version=7.1", server.takeRequest().path)
    }
}
