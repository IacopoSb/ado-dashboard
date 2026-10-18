package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.anna
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.ChangeType
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.FileAnchor
import io.github.iacoposb.adodash.api.FileContent
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.Person
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrCommit
import io.github.iacoposb.adodash.api.PrFileChange
import io.github.iacoposb.adodash.api.PrFilter
import io.github.iacoposb.adodash.api.PrIteration
import io.github.iacoposb.adodash.api.PrThread
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.WorkItemTypeInfo
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** The Files and Commits tabs: the files of the latest push, diffs, viewed, threads on a line. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PullRequestFilesTest {
    private val me = Person(Fakes.me.id, Fakes.me.name, "")
    private val pr = Fakes.pr(311, anna).copy(projectId = "p-1")

    private inner class Api : AdoApi {
        val contents = mutableMapOf(
            "/src/B.kt@base" to "a\nb\nc\n",
            "/src/B.kt@src" to "a\nB\nc\nd\n",
            "/src/A.kt@src" to "new\n",
            "/img.png@base" to "",
            "/img.png@src" to "",
        )
        val reads = mutableListOf<String>()
        val fileThreads = mutableListOf<Triple<String, FileAnchor, Pair<Int, Int>>>()
        var threadFail: AdoException? = null

        override suspend fun pullRequestIterations(pr: PullRequest) = listOf(PrIteration(1, "old", "base0"), PrIteration(2, "src", "base"))
        override suspend fun iterationChanges(pr: PullRequest, iteration: Int): List<PrFileChange> {
            assertEquals(2, iteration)
            return listOf(
                PrFileChange("/src/B.kt", ChangeType.EDIT, changeTrackingId = 2),
                PrFileChange("/src/A.kt", ChangeType.ADD, changeTrackingId = 1),
                PrFileChange("/img.png", ChangeType.EDIT, changeTrackingId = 3),
            )
        }
        override suspend fun fileContent(pr: PullRequest, path: String, commit: String): FileContent {
            reads += "$path@$commit"
            if (path == "/img.png") return FileContent.Binary
            return contents["$path@$commit"]?.let { FileContent.Text(it) } ?: FileContent.Missing
        }
        override suspend fun pullRequestCommits(pr: PullRequest) = listOf(PrCommit("abc", "Add retry", "Anna", Fakes.now, "https://x/commit/abc"))
        override suspend fun pullRequestThreads(pr: PullRequest) = emptyList<PrThread>()
        override suspend fun createPullRequestFileThread(pr: PullRequest, content: String, anchor: FileAnchor, iteration: Int, changeTrackingId: Int): PrThread {
            fileThreads += Triple(content, anchor, iteration to changeTrackingId)
            threadFail?.let { throw it }
            // Answered without the context: placed where it was written anyway
            return PrThread(40, "active", listOf(PrComment(40, 1, Fakes.me, content, Fakes.now)))
        }

        override suspend fun me() = Identity("", "")
        override suspend fun projects() = emptyList<String>()
        override suspend fun queryWorkItemIds(wiql: String, top: Int) = emptyList<Int>()
        override suspend fun workItems(ids: List<Int>) = emptyList<io.github.iacoposb.adodash.api.WorkItem>()
        override suspend fun stateCategories(project: String, type: String) = emptyMap<String, String>()
        override suspend fun areas(project: String) = AreaNode(project, project)
        override suspend fun workItemTypes(project: String) = emptyList<WorkItemTypeInfo>()
        override suspend fun workItemComments(project: String, id: Int) = emptyList<Comment>()
        override suspend fun activePullRequests(project: String, filter: PrFilter) = emptyList<PullRequest>()
        override suspend fun pullRequestComments(pr: PullRequest) = emptyList<PrComment>()
    }

    private val commented = mutableListOf<PrThread>()

    private fun TestScope.controller(api: Api) = PullRequestController(
        this, { api }, { me }, { it.message ?: "error" },
        onCommented = { _, t -> commented += t },
        clock = { Fakes.now },
        compute = EmptyCoroutineContext,
    )

    @Test fun `the files of the latest push in folder order, each diffed against the base`() = runTest {
        val api = Api()
        val c = controller(api)
        c.open(311, pr)
        advanceUntilIdle()
        val files = c.state.value!!.files
        assertTrue(files.loaded)
        assertEquals(PrIteration(2, "src", "base"), files.iteration)
        assertEquals(listOf("/img.png", "/src/A.kt", "/src/B.kt"), files.changes.map { it.path })
        assertEquals("/img.png", files.selected)
        assertEquals(FileDiff.Binary, files.diffs["/img.png"])
        // An added file is not read in the base
        assertFalse("/src/A.kt@base" in api.reads)
        val b = files.diffs["/src/B.kt"] as FileDiff.Ready
        assertEquals(2 to 1, b.added to b.deleted)
        assertEquals(1 to 0, (files.diffs["/src/A.kt"] as FileDiff.Ready).let { it.added to it.deleted })
        // A binary file has no counts: no totals
        assertNull(files.totals)
        assertEquals(listOf("Add retry"), c.state.value!!.commits.items.map { it.message })
    }

    @Test fun `selecting, viewing, folders and layout`() = runTest {
        val api = Api()
        val c = controller(api)
        c.open(311, pr)
        advanceUntilIdle()
        c.selectFile("/src/B.kt")
        assertEquals("/src/B.kt", c.state.value!!.files.selectedChange?.path)
        c.toggleViewed("/src/B.kt")
        c.toggleFolder("/src")
        c.setSideBySide(true)
        c.expandGap("/src/B.kt", 0)
        val s = c.state.value!!.files
        assertEquals(setOf("/src/B.kt"), s.viewed)
        assertEquals(setOf("/src"), s.collapsedFolders)
        assertTrue(s.sideBySide)
        assertEquals(setOf(0), s.expandedGaps["/src/B.kt"])
        // Viewed and the layout stay for the session
        c.close()
        c.open(311, pr)
        assertEquals(setOf("/src/B.kt"), c.state.value!!.files.viewed)
        assertTrue(c.state.value!!.files.sideBySide)
        c.toggleViewed("/src/B.kt")
        assertEquals(emptySet<String>(), c.state.value!!.files.viewed)
    }

    @Test fun `a new thread on a line, shown at once and placed on its line`() = runTest {
        val api = Api()
        val c = controller(api)
        c.open(311, pr)
        advanceUntilIdle()
        c.selectFile("/src/B.kt")
        c.startFileThread(LineRef(2, left = false))
        assertEquals(FileAnchor("/src/B.kt", 2, false), c.state.value!!.files.newThread)
        c.editDraft(PrScreen.NEW_FILE_THREAD, Draft("Why uppercase?"))
        c.send(PrScreen.NEW_FILE_THREAD)
        val sending = c.state.value!!
        assertNull(sending.files.newThread)
        assertEquals(FileAnchor("/src/B.kt", 2, false), sending.threads.sending.single().anchor)
        advanceUntilIdle()
        assertEquals(Triple("Why uppercase?", FileAnchor("/src/B.kt", 2, false), 2 to 2), api.fileThreads.single())
        val thread = c.state.value!!.threads.items.single { it.id == 40 }
        assertEquals("/src/B.kt", thread.filePath)
        assertEquals(2, thread.line)
        assertTrue(c.state.value!!.threads.sending.isEmpty())
        // My own comment is stored as seen
        assertEquals(listOf(40), commented.map { it.id })
    }

    @Test fun `a thread on a deleted line, refused, the composer back on its line with the text`() = runTest {
        val api = Api().apply { threadFail = AdoException(403, "denied") }
        val c = controller(api)
        c.open(311, pr)
        advanceUntilIdle()
        c.selectFile("/src/B.kt")
        c.startFileThread(LineRef(2, left = true))
        c.editDraft(PrScreen.NEW_FILE_THREAD, Draft("Removed?"))
        c.send(PrScreen.NEW_FILE_THREAD)
        advanceUntilIdle()
        val s = c.state.value!!
        assertEquals(FileAnchor("/src/B.kt", 2, true), s.files.newThread)
        assertEquals("Removed?", s.composer(PrScreen.NEW_FILE_THREAD).draft.text)
        assertEquals(SaveProblem.DENIED, s.threads.sendErrors[PrScreen.NEW_FILE_THREAD]?.problem)
        c.cancelFileThread()
        assertNull(c.state.value!!.files.newThread)
    }

    @Test fun `a file whose diff failed is read again`() = runTest {
        var fail = true
        val api = object : AdoApi by Api() {
            override suspend fun fileContent(pr: PullRequest, path: String, commit: String): FileContent {
                if (fail) throw AdoException(500, "boom")
                return FileContent.Text("x\n")
            }
        }
        val c = PullRequestController(this, { api }, { me }, { it.message ?: "error" }, clock = { Fakes.now }, compute = EmptyCoroutineContext)
        c.open(311, pr)
        advanceUntilIdle()
        assertEquals(FileDiff.Failed("boom"), c.state.value!!.files.diffs["/img.png"])
        fail = false
        c.retryFile("/img.png")
        advanceUntilIdle()
        assertTrue(c.state.value!!.files.diffs["/img.png"] is FileDiff.Ready)
    }
}
