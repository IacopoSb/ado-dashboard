package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.PrThread
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.TeamArea
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.api.WorkItemDetail
import io.github.iacoposb.adodash.data.EVENTS_VERSION
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.EventDao
import io.github.iacoposb.adodash.data.EventKind
import io.github.iacoposb.adodash.data.Filters
import io.github.iacoposb.adodash.data.Settings
import io.github.iacoposb.adodash.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

data class SyncState(
    /** The last sync's, or until it ends the one saved by an earlier run ([SnapshotStore]). */
    val snapshot: Snapshot? = null,
    val syncing: Boolean = false,
    /** The last sync's failure as the user reads it; null after a successful one. */
    val error: String? = null,
    /** Why the last sync failed; null after a successful one. */
    val failure: SyncFailure? = null,
)

/** One sync at a time, shared by the dashboard and the background worker. */
class SyncRepository(
    private val settings: SettingsStore,
    private val events: EventDao,
    /** The news of a sync, with the settings it ran with (what to notify, quiet hours, sounds). */
    private val notify: (List<Event>, Settings) -> Unit,
    private val texts: SyncTexts,
    private val apiFor: (Settings) -> AdoApi,
    /** After each successful sync, with what it read. */
    private val onSynced: suspend (Settings, Snapshot) -> Unit = { _, _ -> },
    /** The last snapshot of each view, shown at once at the next start; null keeps none. */
    private val snapshots: SnapshotStore? = null,
    /** A sync refused for the credentials (expired session, PAT refused), with its settings. */
    private val onSignInRequired: suspend (Settings) -> Unit = {},
    private val log: (String) -> Unit = { android.util.Log.i("AdoSync", it) },
    /** Runs the event writes of one sync together, so the Activity list changes once per sync. */
    private val transaction: suspend (suspend () -> Unit) -> Unit = { it() },
    /** The saved filters' catalog of the account, to tell the areas that no longer exist. */
    private val catalog: suspend (Settings) -> FilterCatalog? = { null },
    private val clock: () -> Instant = Instant::now,
    /** Events read in the app: their notifications go. */
    private val onRead: (List<String>) -> Unit = {},
) {
    private val mutex = Mutex()
    private val stateCategories = mutableMapOf<String, Map<String, String>>()
    private val teamAreas = mutableMapOf<BoardRef, List<TeamArea>>()
    private val signalCache = SignalCache()
    /** Builds refused at the last sync: logged once per change. */
    private var buildsDenied = false
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * Reads everything again. With [restore], what an earlier run saved for the current view is
     * shown first, at once, while this sync runs (the dashboard; the background worker has no
     * use for it).
     */
    suspend fun sync(restore: Boolean = true) {
        if (restore) restoreSaved()
        mutex.withLock { syncLocked() }
    }

    /**
     * The saved snapshot of the current view, when nothing read for it is shown yet: at start,
     * and when another view opens. Never over what a sync read for this view.
     */
    suspend fun restoreSaved() {
        val store = snapshots ?: return
        val s = settings.current()
        if (!s.configured) return
        val view = s.views.current
        if (_state.value.snapshot?.shownFor(view) != null) return
        val saved = try {
            store.load(s.organization, s.meId, view.queryKey)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        _state.update { if (it.snapshot?.shownFor(view) == null) it.copy(snapshot = saved) else it }
    }

    private suspend fun syncLocked() {
        val s = settings.current()
        if (!s.configured) return
        _state.update { it.copy(syncing = true) }
        try {
            val now = clock()
            val fetched = SyncEngine(
                apiFor(s), stateCategories, teamAreas, texts = texts, cache = signalCache, catalog = { catalog(s) },
            ).fetch(
                me = Identity(s.meId, s.meName),
                since = s.lastSyncAt.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                filters = s.filters,
                // What is shown; the events below come from my own items only, whatever the view
                view = s.views.current,
            )
            // An upgrade from a store that may have forgotten events: one sync as a new baseline,
            // or everything it forgot would be notified as new
            val firstSync = s.baselineAt == 0L || s.eventsVersion < EVENTS_VERSION
            val found = Events.from(fetched, if (s.baselineAt == 0L) now else Instant.ofEpochMilli(s.baselineAt), texts)
            val (history, news) = Events.splitHistory(found, firstSync, s.lastSyncAt, s.filtersChangedAt)
            val stamp = now.toEpochMilli()
            var fresh: List<Event>? = null
            transaction {
                if (history.isNotEmpty()) events.insertNew(history.map { it.copy(read = true, hidden = true, storedAt = stamp) })
                if (!firstSync) fresh = store(news, now)
                // What this sync found again stays known, however long ago it happened: forgotten,
                // it would be stored and notified again at the next sync
                found.map { it.key }.distinct().chunked(KEYS_PER_QUERY).forEach { events.touch(it, stamp) }
                if (!firstSync) retireLeft(fetched, found, s.filters, stamp)
                events.prune(before = now.minus(RETENTION).toEpochMilli())
            }
            fresh?.let { notify(it, s) }
            if (fetched.snapshot.buildsDenied != buildsDenied) {
                buildsDenied = fetched.snapshot.buildsDenied
                if (buildsDenied) log("Builds cannot be read with these credentials (Build (read) missing): no pipelines")
            }
            settings.markSynced(now.toEpochMilli())
            _state.update { SyncState(snapshot = fetched.snapshot) }
            save(s, fetched.snapshot)
            onSynced(s, fetched.snapshot)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                // Leaving the dashboard mid-sync: not syncing any more, and not an error
                _state.update { it.copy(syncing = false) }
                throw e
            }
            val failure = SyncFailure.of(e)
            log("Sync failed ($failure): ${e.javaClass.simpleName}")
            _state.update { it.copy(syncing = false, error = texts.error(e), failure = failure) }
            if (failure == SyncFailure.AUTH) onSignInRequired(s)
        }
    }

    /** For the next start; never a reason for the sync to fail. */
    private suspend fun save(s: Settings, snapshot: Snapshot) {
        val store = snapshots ?: return
        try {
            store.save(s.organization, s.meId, s.views.current.queryKey, snapshot)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Snapshot not saved: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Stores [found] and returns what is new and worth notifying. Comments
     * on a PR I was just added to are history, not news: stored as read,
     * mentions excepted. So are comments older than what the app keeps: those were seen
     * (or forgotten) long ago.
     */
    private suspend fun store(found: List<Event>, now: Instant): List<Event> {
        val stamp = now.toEpochMilli()
        val old = now.minus(RETENTION).toEpochMilli()
        val (primary, rest) = found.partition { it.kind == EventKind.ASSIGNED || it.kind == EventKind.PR_REVIEW }
        val newPrimary = inserted(primary, stamp)
        val newReviewPrs = newPrimary.filter { it.kind == EventKind.PR_REVIEW }.map { prId(it.key) }.toSet()
        val newRest = inserted(
            rest.map {
                val history = it.kind == EventKind.PR_COMMENT && prId(it.key) in newReviewPrs
                if (history || it.at < old) it.copy(read = true) else it
            },
            stamp,
        )
        return newPrimary + newRest.filterNot { it.read }
    }

    /**
     * Work items no longer assigned to me and pull requests I no longer review: their "assigned"
     * and "review" events get another key, so that being assigned or added again is notified
     * again. Only what this sync could tell for sure: not after a failed query or in a project
     * whose pull requests could not be read, nor for what the filters hide (they may show it
     * again), nor for a review the filters leave out (drafts, approved by me) but still mine.
     */
    private suspend fun retireLeft(fetched: Fetched, found: List<Event>, filters: Filters, stamp: Long) {
        val current = found.map { it.key }.toSet()
        val left = mutableListOf<String>()
        if (fetched.workItemsComplete) {
            left += events.ofKind(EventKind.ASSIGNED)
                .filter { ASSIGNED_KEY.matches(it.key) && it.key !in current && filters.acceptsEvent(it) }.map { it.key }
        }
        val read = fetched.prProjectsRead.map { it.lowercase() }.toSet()
        left += events.ofKind(EventKind.PR_REVIEW).filter { e ->
            val id = REVIEW_KEY.matchEntire(e.key)?.groupValues?.get(1)?.toIntOrNull() ?: return@filter false
            e.key !in current && id !in fetched.reviewerPrIds && e.project?.lowercase() in read && filters.acceptsEvent(e)
        }.map { it.key }
        left.chunked(KEYS_PER_QUERY).forEach { events.retire(it, stamp) }
    }

    private suspend fun inserted(list: List<Event>, stamp: Long): List<Event> {
        if (list.isEmpty()) return emptyList()
        val stamped = list.map { it.copy(storedAt = stamp) }
        return stamped.zip(events.insertNew(stamped)).filter { it.second != -1L }.map { it.first }
    }

    /** Stored now, for the pruning. */
    private suspend fun insertNow(list: List<Event>) {
        val stamp = clock().toEpochMilli()
        events.insertNew(list.map { it.copy(storedAt = stamp) })
    }

    private fun prId(key: String) = key.removePrefix("pr:").substringBefore(':')

    /**
     * A change made from the app: the dashboard row shows [item] at once, without waiting for the
     * next sync, and so does its row in the sprint backlog. Assigning it to myself is not news:
     * its "assigned" event is stored as already seen, so the next sync does not notify my own
     * change.
     */
    suspend fun applyOwnChange(detail: WorkItemDetail, assignedToMe: Boolean) {
        val item = detail.item
        if (assignedToMe) {
            insertNow(
                listOf(
                    Event(
                        key = "wi:${item.id}:assigned",
                        kind = EventKind.ASSIGNED,
                        title = texts.assigned(item.id, item.title),
                        body = "${item.type} · ${item.project} · ${item.state}",
                        url = item.url,
                        at = item.changedAt.toEpochMilli(),
                        read = true,
                        hidden = true,
                        project = item.project,
                        area = item.areaPath,
                    ),
                ),
            )
        }
        val category = SyncEngine.categoryOf(stateCategories, item)
        val sprintChange = { old: SprintItem ->
            old.copy(
                item = item,
                remainingWork = detail.remainingWork,
                assigneeId = detail.assignee?.id?.takeIf { it.isNotEmpty() },
                category = category ?: old.category.takeIf { old.item.state == item.state },
            )
        }
        _state.update { s -> s.snapshot?.let { s.copy(snapshot = it.replacing(item, sprintChange)) } ?: s }
    }

    /**
     * A change of [board]'s sprint backlog made from the app (its order): shown at once, until
     * the next sync reads it from Azure DevOps.
     */
    fun applyOwnSprint(board: BoardRef, change: (Sprint) -> Sprint) {
        _state.update { s ->
            val snapshot = s.snapshot ?: return@update s
            val shown = snapshot.shown ?: return@update s
            val sprint = shown.sprint?.takeIf { it.board == board } ?: return@update s
            s.copy(snapshot = snapshot.copy(shown = shown.copy(sprint = change(sprint))))
        }
    }

    /** A comment I posted from the app: never notified back to me (see [Events.ownComment]). */
    suspend fun applyOwnComment(item: WorkItem, comment: Comment) {
        val s = settings.current()
        insertNow(listOf(Events.ownComment(item, comment, Identity(s.meId, s.meName), texts)))
    }

    /**
     * My vote from the app: the dashboard row shows it at once. Votes are notified only for
     * other people's votes on my pull requests, so mine never comes back to me.
     */
    fun applyOwnVote(prId: Int, vote: Int) {
        _state.update { s -> s.snapshot?.let { s.copy(snapshot = it.withMyVote(prId, vote)) } ?: s }
    }

    /** A thread I commented in from the app: never notified back to me (see [Events.ownPrThread]). */
    suspend fun applyOwnPrComments(pr: PullRequest, thread: PrThread) {
        val s = settings.current()
        val found = Events.ownPrThread(pr, thread, Identity(s.meId, s.meName), texts)
        if (found.isNotEmpty()) insertNow(found)
    }

    suspend fun markRead(key: String) {
        events.markRead(key)
        onRead(listOf(key))
    }

    suspend fun markAllRead() {
        val unread = events.unreadKeys()
        events.markAllRead()
        onRead(unread)
    }

    suspend fun markRead(keys: List<String>) {
        keys.chunked(KEYS_PER_QUERY).forEach { events.markRead(it) }
        onRead(keys)
    }

    /**
     * Another account, or signed out: [save] stores the new settings while no sync runs, after
     * forgetting the previous account's data when [clear]. A sync queued meanwhile runs after it,
     * with the new settings, and never writes the old account's events after they were cleared.
     */
    suspend fun switchAccount(clear: Boolean, save: suspend () -> Unit) = mutex.withLock {
        if (clear) clearLocked()
        save()
    }

    private suspend fun clearLocked() {
        events.clear()
        stateCategories.clear()
        teamAreas.clear()
        signalCache.clear()
        buildsDenied = false
        snapshots?.clear()
        _state.value = SyncState()
    }

    companion object {
        private val RETENTION = Duration.ofDays(30)
        /** Under SQLite's oldest limit of 999 variables per statement. */
        private const val KEYS_PER_QUERY = 500
        private val ASSIGNED_KEY = Regex("wi:\\d+:assigned")
        private val REVIEW_KEY = Regex("pr:(\\d+):review")
    }
}
