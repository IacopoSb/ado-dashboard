package io.github.iacoposb.adodash.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
        /** Pull requests cannot be read (a project refused, the network…). */
        var prsFail = false
        /** Completed by the test to let a sync go on. */
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun me() = Fakes.me
        override suspend fun projects(): List<String> {
            gate?.await()
            return listOf("Contoso")
        }
        override suspend fun queryWorkItemIds(wiql: String, top: Int) = if ("@Me" in wiql) items.map { it.id } else emptyList()
        override suspend fun workItems(ids: List<Int>) = items.filter { it.id in ids }
        override suspend fun stateCategories(project: String, type: String) = mapOf("Active" to "InProgress", "New" to "Proposed")
        override suspend fun areas(project: String) = AreaNode(project, project)
        override suspend fun workItemTypes(project: String) = emptyList<WorkItemTypeInfo>()
        override suspend fun workItemComments(project: String, id: Int) = emptyList<Comment>()
        override suspend fun activePullRequests(project: String, filter: PrFilter): List<PullRequest> {
            if (prsFail) error("no PRs")
            return if (filter is PrFilter.Reviewer) toReview else emptyList()
        }
        override suspend fun pullRequestComments(pr: PullRequest) = emptyList<PrComment>()
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private val api = FakeApi()
    private val notified = mutableListOf<List<Event>>()
    /** The organization of each sync, as it read the settings. */
    private val synced = mutableListOf<String>()
    /** Notifications cancelled, by event key. */
    private val cancelled = mutableListOf<String>()
    private var now = Instant.now()

    private lateinit var repo: SyncRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") }
        settings = SettingsStore(dataStore)
        repo = SyncRepository(
            settings, db.events(), { events, _ -> notified += events }, Fakes.Texts, apiFor = { synced += it.organization; api },
            log = {}, clock = { now }, onRead = { cancelled += it },
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

    @Test fun `after upgrading from a store that forgot old events, they are not notified as new`() {
        api.items = listOf(Fakes.workItem(1).copy(changedAt = now.minus(Duration.ofDays(60))))
        sync()
        // Before 0.1.0 the event above was pruned by its date right after being stored
        runBlocking {
            db.clearAllTables()
            dataStore.edit { it[androidx.datastore.preferences.core.intPreferencesKey("events_version")] = 1 }
        }
        later()
        sync()
        assertTrue("forgotten history is not news: ${notifiedKeys()}", notifiedKeys().isEmpty())
        assertTrue(runBlocking { db.events().recentOnce() }.filter { it.key == "wi:1:assigned" }.all { it.read })

        // After the upgrade sync, real news are notified again
        api.items += Fakes.workItem(2)
        later()
        sync()
        assertEquals(listOf("wi:2:assigned"), notifiedKeys())
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

    @Test fun `a sync waiting while the account changes runs with the new one, after the old data is cleared`() = runBlocking {
        api.items = listOf(Fakes.workItem(1))
        sync()
        api.gate = CompletableDeferred()
        val running = launch(Dispatchers.IO) { repo.sync(restore = false) }
        while (synced.size < 2) yield()
        val switching = launch(Dispatchers.IO) {
            repo.switchAccount(clear = true) { settings.saveAccount("other", AuthMethod.ENTRA, "", Fakes.anna.id, Fakes.anna.name) }
        }
        // Waiting for the running sync before the next one is queued
        delay(200)
        val queued = launch(Dispatchers.IO) { repo.sync(restore = false) }
        delay(200)
        api.gate!!.complete(Unit)
        joinAll(running, switching, queued)
        assertEquals(listOf("org", "org", "other"), synced)
        // What the old account's syncs stored is gone; the new one's first sync is history
        assertEquals(listOf("wi:1:assigned"), db.events().keys())
        assertEquals(Fakes.anna.id, settings.current().meId)
    }

    @Test fun `what is read in the app leaves the notification shade`() {
        api.items = listOf(Fakes.workItem(1))
        sync()
        later()
        api.items += listOf(Fakes.workItem(2), Fakes.workItem(3))
        sync()
        runBlocking { repo.markRead("wi:2:assigned") }
        assertEquals(listOf("wi:2:assigned"), cancelled)
        runBlocking { repo.markAllRead() }
        assertEquals(listOf("wi:2:assigned", "wi:3:assigned"), cancelled)
    }

    @Test fun `assigned again or added again as a reviewer is notified again`() {
        sync()
        api.items = listOf(Fakes.workItem(2))
        api.toReview = listOf(Fakes.pr(7, Fakes.anna))
        later()
        sync()
        assertEquals(listOf("wi:2:assigned", "pr:7:review"), notifiedKeys())

        // Unassigned, removed as a reviewer; then back
        api.items = emptyList()
        api.toReview = emptyList()
        later()
        sync()
        api.items = listOf(Fakes.workItem(2))
        api.toReview = listOf(Fakes.pr(7, Fakes.anna))
        later()
        sync()
        assertEquals(listOf("wi:2:assigned", "pr:7:review", "wi:2:assigned", "pr:7:review"), notifiedKeys())
        // Both times stay in the activity
        assertEquals(2, runBlocking { db.events().recentOnce() }.count { it.title.startsWith("assigned #2") })
    }

    @Test fun `a review missing because nothing could be read, or hidden by the filters, is not notified again`() {
        api.toReview = listOf(Fakes.pr(7, Fakes.anna, draft = true))
        sync()
        later()
        api.prsFail = true
        api.items = listOf(Fakes.workItem(1))
        sync()
        api.prsFail = false
        later()
        sync()
        // Drafts hidden from the reviews: still mine, only not shown
        runBlocking { settings.editFilters { it.copy(draftsToReview = false) } }
        later()
        sync()
        runBlocking { settings.editFilters { it.copy(draftsToReview = true) } }
        later()
        sync()
        assertEquals(listOf("wi:1:assigned"), notifiedKeys())
    }
}
