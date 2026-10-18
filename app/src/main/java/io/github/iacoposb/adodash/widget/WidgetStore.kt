package io.github.iacoposb.adodash.widget

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** The [WidgetSummary] as JSON in one file, and in memory for the widgets on screen. */
class WidgetStore(private val file: File, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val lock = Mutex()
    private var loaded = false
    private val _summary = MutableStateFlow(WidgetSummary())

    /** What is saved; empty until [load]. */
    val summary: StateFlow<WidgetSummary> = _summary.asStateFlow()

    /** What is saved, read from the file once. */
    suspend fun load(): WidgetSummary = lock.withLock { current() }

    suspend fun update(change: (WidgetSummary) -> WidgetSummary): WidgetSummary = lock.withLock {
        val new = change(current())
        if (new != _summary.value || !file.exists()) {
            withContext(io) {
                // Written aside and moved over, so a crash never leaves half a file
                val tmp = File(file.path + ".tmp")
                tmp.writeText(json.encodeToString(WidgetSummary.serializer(), new))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }
        }
        _summary.value = new
        new
    }

    /** Forgets the previous account. */
    suspend fun clear() = lock.withLock {
        withContext(io) { file.delete() }
        _summary.value = WidgetSummary()
        loaded = true
    }

    private suspend fun current(): WidgetSummary {
        if (!loaded) {
            _summary.value = withContext(io) {
                // Unreadable (an older format, half written): as if nothing was saved
                runCatching { json.decodeFromString(WidgetSummary.serializer(), file.readText()) }.getOrNull()
            } ?: WidgetSummary()
            loaded = true
        }
        return _summary.value
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
