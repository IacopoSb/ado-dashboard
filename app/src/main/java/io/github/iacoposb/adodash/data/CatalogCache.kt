package io.github.iacoposb.adodash.data

import io.github.iacoposb.adodash.sync.FilterCatalog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** What the filters remember of one organization between openings. */
@Serializable
data class CachedCatalog(
    val organization: String,
    /** Null until the catalog is first read; the used types can come before it. */
    val catalog: FilterCatalog? = null,
    /** When [catalog] was read, epoch millis. */
    val savedAt: Long = 0,
    /** Work item types among my work items at the last sync; null before the first one. */
    val usedTypes: Set<String>? = null,
)

/**
 * The global filters' catalog as JSON in one file, for one organization at a time: reading it
 * for another organization finds nothing, and saving for another one replaces it.
 */
class CatalogCache(private val file: File, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val lock = Mutex()

    /** Null when nothing is saved for [organization] or the file cannot be read. */
    suspend fun load(organization: String): CachedCatalog? = lock.withLock { read()?.takeIf { it.isFor(organization) } }

    suspend fun saveCatalog(organization: String, catalog: FilterCatalog, at: Long) = edit(organization) {
        it.copy(catalog = catalog, savedAt = at)
    }

    suspend fun saveUsedTypes(organization: String, types: Set<String>) = edit(organization) { it.copy(usedTypes = types) }

    suspend fun clear() {
        lock.withLock { withContext(io) { file.delete() } }
    }

    private suspend fun edit(organization: String, change: (CachedCatalog) -> CachedCatalog): CachedCatalog = lock.withLock {
        val old = read()?.takeIf { it.isFor(organization) } ?: CachedCatalog(organization)
        change(old).also { new ->
            withContext(io) {
                // Written aside and moved over, so a crash never leaves half a file
                val tmp = File(file.path + ".tmp")
                tmp.writeText(json.encodeToString(CachedCatalog.serializer(), new))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }
    }

    private suspend fun read(): CachedCatalog? = withContext(io) {
        if (!file.exists()) return@withContext null
        runCatching { json.decodeFromString(CachedCatalog.serializer(), file.readText()) }.getOrNull()
    }

    private fun CachedCatalog.isFor(org: String) = organization.trim().equals(org.trim(), ignoreCase = true)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
