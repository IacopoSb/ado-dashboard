package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.TeamArea
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.EventDao
import io.github.iacoposb.adodash.data.EventKind
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
    val snapshot: Snapshot? = null,
    val syncing: Boolean = false,
    val error: String? = null,
)

/** One sync at a time, shared by the dashboard and the background worker. */
class SyncRepository(
    private val settings: SettingsStore,
    private val events: EventDao,
    private val notify: (List<Event>) -> Unit,
    private val texts: SyncTexts,
    private val apiFor: (Settings) -> AdoApi,
    /** After each successful sync, with what it read. */
    private val onSynced: suspend (Settings, Snapshot) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = { android.util.Log.i("AdoSync", it) },
) {
    private val mutex = Mutex()
    private val stateCategories = mutableMapOf<String, Map<String, String>>()
    private val teamAreas = mutableMapOf<BoardRef, List<TeamArea>>()
    private val signalCache = SignalCache()
    /** Builds refused at the last sync: logged once per change. */
    private var buildsDenied = false
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    suspend fun sync() = mutex.withLock {
        val s = settings.current()
        if (!s.configured) return@withLock
        _state.update { it.copy(syncing = true) }
        try {
            val now = Instant.now()
            val fetched = SyncEngine(apiFor(s), stateCategories, teamAreas, texts = texts, cache = signalCache).fetch(
                me = Identity(s.meId, s.meName),
                since = s.lastSyncAt.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
                filters = s.filters,
                // What is shown; the events below come from my own items only, whatever the view
                view = s.views.current,
            )
            val firstSync = s.baselineAt == 0L
            val found = Events.from(fetched, if (firstSync) now else Instant.ofEpochMilli(s.baselineAt), texts)
            val (history, news) = Events.splitHistory(found, firstSync, s.lastSyncAt, s.filtersChangedAt)
            if (history.isNotEmpty()) events.insertNew(history.map { it.copy(read = true, hidden = true) })
            if (!firstSync) notify(store(news))
            events.prune(before = now.minus(RETENTION).toEpochMilli())
            if (fetched.snapshot.buildsDenied != buildsDenied) {
                buildsDenied = fetched.snapshot.buildsDenied
                if (buildsDenied) log("Builds cannot be read with these credentials (Build (read) missing): no pipelines")
            }
            settings.markSynced(now.toEpochMilli())
            _state.update { SyncState(snapshot = fetched.snapshot) }
            onSynced(s, fetched.snapshot)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                // Leaving the dashboard mid-sync: not syncing any more, and not an error
                _state.update { it.copy(syncing = false) }
                throw e
            }
            _state.update { it.copy(syncing = false, error = texts.error(e)) }
        }
    }

    /**
     * Stores [found] and returns what is new and worth notifying. Comments
     * on a PR I was just added to are history, not news: stored as read,
     * mentions excepted.
     */
    private suspend fun store(found: List<Event>): List<Event> {
        val (primary, rest) = found.partition { it.kind == EventKind.ASSIGNED || it.kind == EventKind.PR_REVIEW }
        val newPrimary = inserted(primary)
        val newReviewPrs = newPrimary.filter { it.kind == EventKind.PR_REVIEW }.map { prId(it.key) }.toSet()
        val newRest = inserted(
            rest.map { if (it.kind == EventKind.PR_COMMENT && prId(it.key) in newReviewPrs) it.copy(read = true) else it },
        )
        return newPrimary + newRest.filterNot { it.read }
    }

    private suspend fun inserted(list: List<Event>): List<Event> =
        if (list.isEmpty()) emptyList() else list.zip(events.insertNew(list)).filter { it.second != -1L }.map { it.first }

    private fun prId(key: String) = key.removePrefix("pr:").substringBefore(':')

    /**
     * A change made from the app: the dashboard row shows [item] at once, without waiting for the
     * next sync. Assigning it to myself is not news: its "assigned" event is stored as already
     * seen, so the next sync does not notify my own change.
     */
    suspend fun applyOwnChange(item: WorkItem, assignedToMe: Boolean) {
        if (assignedToMe) {
            events.insertNew(
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
        _state.update { s -> s.snapshot?.let { s.copy(snapshot = it.replacing(item)) } ?: s }
    }

    suspend fun markRead(key: String) = events.markRead(key)
    suspend fun markAllRead() = events.markAllRead()
    suspend fun markRead(keys: List<String>) = keys.chunked(500).forEach { events.markRead(it) }

    /** Forgets the previous account. */
    suspend fun reset() = mutex.withLock {
        events.clear()
        stateCategories.clear()
        teamAreas.clear()
        signalCache.clear()
        buildsDenied = false
        _state.value = SyncState()
    }

    companion object {
        private val RETENTION = Duration.ofDays(30)
    }
}
