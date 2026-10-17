package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.data.CatalogCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The global filters' catalog: the saved one at once, a fresh one read in the background when
 * the filters open, and after a sync when the saved one is older than [maxAgeMillis].
 */
class CatalogRepository(
    private val cache: CatalogCache,
    private val scope: CoroutineScope,
    private val errorText: (Throwable) -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAgeMillis: Long = DAY_MILLIS,
) {
    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    private val lock = Mutex()
    /** The organization [state] is for; null before reading the cache. */
    private var shown: String? = null
    private var refresh: Job? = null

    /** The filters opened: what is saved now, a fresh catalog in a moment. */
    fun open(organization: String, api: () -> AdoApi) {
        scope.launch {
            show(organization)
            startRefresh(organization, api)
        }
    }

    /**
     * After a sync: remembers the types of my work items and, if the saved catalog is missing
     * or old, reads a fresh one so that the filters open with it.
     */
    suspend fun synced(organization: String, usedTypes: Set<String>, api: () -> AdoApi) {
        val saved = cache.saveUsedTypes(organization, usedTypes)
        lock.withLock {
            if (shown.equals(organization, ignoreCase = true)) _state.update { it.copy(usedTypes = usedTypes) }
        }
        if (saved.catalog == null || saved.catalog.version < FilterCatalog.VERSION || now() - saved.savedAt > maxAgeMillis) {
            scope.launch {
                show(organization)
                startRefresh(organization, api)
            }
        }
    }

    /**
     * The views' pickers opened: what is saved now, and a fresh catalog only when nothing is
     * saved or it comes from an older release (no teams yet).
     */
    fun openForViews(organization: String, api: () -> AdoApi) {
        scope.launch {
            show(organization)
            val saved = _state.value.catalog
            if (saved == null || saved.version < FilterCatalog.VERSION) startRefresh(organization, api)
        }
    }

    /** Signed out or another organization: nothing of the old one is kept. */
    suspend fun forget() {
        lock.withLock {
            refresh?.cancelAndJoin()
            refresh = null
            shown = null
            cache.clear()
            _state.value = CatalogState()
        }
    }

    private suspend fun show(organization: String) = lock.withLock {
        if (shown.equals(organization, ignoreCase = true)) return@withLock
        refresh?.cancelAndJoin()
        val saved = cache.load(organization)
        _state.value = CatalogState(saved?.catalog, usedTypes = saved?.usedTypes)
        shown = organization
    }

    private suspend fun startRefresh(organization: String, api: () -> AdoApi) = lock.withLock {
        if (refresh?.isActive == true || !shown.equals(organization, ignoreCase = true)) return@withLock
        _state.update { it.copy(loading = true, error = null) }
        refresh = scope.launch {
            try {
                val fresh = FilterCatalog.load(api())
                cache.saveCatalog(organization, fresh, now())
                _state.update { it.copy(catalog = fresh, loading = false) }
            } catch (e: CancellationException) {
                _state.update { it.copy(loading = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = errorText(e)) }
            }
        }
    }

    companion object {
        const val DAY_MILLIS = 24 * 60 * 60_000L
    }
}
