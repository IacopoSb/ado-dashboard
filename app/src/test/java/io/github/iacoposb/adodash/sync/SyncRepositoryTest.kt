package io.github.iacoposb.adodash.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrFilter
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.api.WorkItemTypeInfo
import io.github.iacoposb.adodash.data.AppDatabase
import io.github.iacoposb.adodash.data.AuthMethod
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant

/** Syncs one after the other, with the real database and settings: what is notified, and when. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncRepositoryTest {
    @get:Rule val folder = TemporaryFolder()

    private class FakeApi : AdoApi {
        var items: List<WorkItem> = emptyList()
        var toReview: List<PullRequest> = emptyList()
        override suspend fun me() = Fakes.me
        override suspend fun projects() = listOf("Contoso")
        override suspend fun queryWorkItemIds(wiql: String, top: Int) = if ("@Me" in wiql) items.map { it.id } else emptyList()
        override suspend fun workItems(ids: List<Int>) = items.filter { it.id in ids }
        override suspend fun stateCategories(project: String, type: String) = mapOf("Active" to "InProgress", "New" to "Proposed")
        override suspend fun areas(project: String) = AreaNode(project, project)
        override suspend fun workItemTypes(project: String) = emptyList<WorkItemTypeInfo>()
        override suspend fun workItemComments(project: String, id: Int) = emptyList<Comment>()
        override suspend fun activePullRequests(project: String, filter: PrFilter) =
            if (filter is PrFilter.Reviewer) toReview else emptyList()
        override suspend fun pullRequestComments(pr: PullRequest) = emptyList<PrComment>()
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private val api = FakeApi()
    private val notified = mutableListOf<List<Event>>()
    private var now = Instant.now()

    private lateinit var repo: SyncRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") })
        repo = SyncRepository(
            settings, db.events(), { events, _ -> notified += events }, Fakes.Texts, apiFor = { api },
            log = {}, clock = { now },
        )
        runBlocking { settings.saveAccount("org", AuthMethod.ENTRA, "", Fakes.me.id, Fakes.me.name) }
    }

    @After fun tearDown() {
        db.close()
        scope.cancel()
    }

    private fun sync() = runBlocking { repo.sync(restore = false) }

    private fun later(minutes: Long = 5) {
        now = now.plus(Duration.ofMinutes(minutes))
    }

    private fun notifiedKeys() = notified.flatten().map { it.key }

    @Test fun `an item assigned long ago is notified once, not at every sync`() {
        api.items = listOf(Fakes.workItem(1).copy(changedAt = now.minus(Duration.ofDays(60))))
        sync()
        later()
        sync()
        assertTrue("history is never notified: ${notifiedKeys()}", notifiedKeys().isEmpty())

        // Assigned to me now, last changed two months ago
        api.items += Fakes.workItem(2).copy(changedAt = now.minus(Duration.ofDays(45)))
        later()
        sync()
        assertEquals(listOf("wi:2:assigned"), notifiedKeys())
        later()
        sync()
        later()
        sync()
        assertEquals(listOf("wi:2:assigned"), notifiedKeys())
        assertEquals(listOf(false), runBlocking { db.events().recentOnce() }.filter { it.key == "wi:2:assigned" }.map { it.read })
    }

    @Test fun `what a sync still finds is kept past the retention, the rest goes`() {
        api.items = listOf(Fakes.workItem(1))
        api.toReview = listOf(Fakes.pr(7, Fakes.anna, ago = 90L * 24 * 3600))
        sync()
        later()
        api.items += Fakes.workItem(2)
        sync()
        assertEquals(listOf("wi:2:assigned"), notifiedKeys())

        // Item 2 leaves my list; a month and more later, still reviewing the old PR
        api.items = listOf(Fakes.workItem(1))
        later(minutes = 40L * 24 * 60)
        sync()
        val keys = runBlocking { db.events().keys() }
        assertTrue("pr:7:review" in keys)
        assertTrue("wi:1:assigned" in keys)
        assertTrue("wi:2:assigned" !in keys)
        assertEquals(listOf("wi:2:assigned"), notifiedKeys())
    }
}
