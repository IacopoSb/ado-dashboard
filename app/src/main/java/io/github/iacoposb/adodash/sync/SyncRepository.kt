package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.Identity
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
) {
    private val mutex = Mutex()
    private val stateCategories = mutableMapOf<String, Map<String, String>>()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    suspend fun sync() = mutex.withLock {
        val s = settings.current()
        if (!s.configured) return@withLock
        _state.update { it.copy(syncing = true) }
        try {
            val now = Instant.now()
            val fetched = SyncEngine(apiFor(s), stateCategories, texts = texts).fetch(
                me = Identity(s.meId, s.meName),
                since = s.lastSyncAt.takeIf { it > 0 }?.let(Instant::ofEpochMilli),
            )
            val firstSync = s.baselineAt == 0L
            val found = Events.from(fetched, if (firstSync) now else Instant.ofEpochMilli(s.baselineAt), texts)
            if (firstSync) events.insertNew(found.map { it.copy(read = true, hidden = true) }) else notify(store(found))
            events.prune(before = now.minus(RETENTION).toEpochMilli())
            settings.markSynced(now.toEpochMilli())
            _state.update { SyncState(snapshot = fetched.snapshot) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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

    suspend fun markRead(key: String) = events.markRead(key)
    suspend fun markAllRead() = events.markAllRead()

    /** Forgets the previous account. */
    suspend fun reset() = mutex.withLock {
        events.clear()
        stateCategories.clear()
        _state.value = SyncState()
    }

    companion object {
        private val RETENTION = Duration.ofDays(30)
    }
}
