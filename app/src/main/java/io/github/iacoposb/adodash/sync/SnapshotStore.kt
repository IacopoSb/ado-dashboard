package io.github.iacoposb.adodash.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * The last snapshot of each view, one JSON file per organization, account and view in [dir], so
 * that the dashboard opens with it while the next sync runs instead of waiting for it. Written
 * after each successful sync; at most [keep] views are kept, the least recently synced go.
 */
class SnapshotStore(
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val keep: Int = 8,
) {
    private val lock = Mutex()

    @Serializable
    private data class Saved(
        val version: Int,
        val organization: String,
        val meId: String,
        val viewKey: String,
        val snapshot: Snapshot,
    )

    /** [snapshot] as the last one of its view ([Shown.viewKey], or [viewKey] for one without). */
    suspend fun save(organization: String, meId: String, viewKey: String, snapshot: Snapshot) = lock.withLock {
        val key = snapshot.shown?.viewKey ?: viewKey
        withContext(io) {
            dir.mkdirs()
            val file = fileFor(organization, meId, key)
            val text = json.encodeToString(Saved.serializer(), Saved(VERSION, organization, meId, key, snapshot))
            // Written aside and moved over, so that a crash never leaves half a file
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            file.setLastModified(System.currentTimeMillis())
            prune()
        }
    }

    /**
     * The last snapshot of the view [viewKey] for this organization and account; null when there
     * is none, or it cannot be read (an older format: the next sync writes it again).
     */
    suspend fun load(organization: String, meId: String, viewKey: String): Snapshot? = lock.withLock {
        withContext(io) {
            val file = fileFor(organization, meId, viewKey)
            if (!file.exists()) return@withContext null
            val saved = runCatching { json.decodeFromString(Saved.serializer(), file.readText()) }.getOrNull()
            val valid = saved != null && saved.version == VERSION && saved.viewKey == viewKey &&
                saved.organization.equals(organization, ignoreCase = true) && saved.meId == meId
            if (!valid) {
                file.delete()
                null
            } else {
                saved?.snapshot
            }
        }
    }

    /** Forgets every snapshot: signed out, or another organization. */
    suspend fun clear() = lock.withLock {
        withContext(io) { dir.listFiles()?.forEach { it.delete() } }
        Unit
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(".json") }.orEmpty()
        files.sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
    }

    private fun fileFor(organization: String, meId: String, viewKey: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${organization.lowercase()}\n$meId\n$viewKey".toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
        return File(dir, "$PREFIX$digest.json")
    }

    companion object {
        /** Bumped when [Snapshot] changes in a way older files cannot be read as. */
        const val VERSION = 1
        private const val PREFIX = "snapshot-"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
