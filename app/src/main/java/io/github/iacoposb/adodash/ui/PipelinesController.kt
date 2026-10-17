package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.Build
import io.github.iacoposb.adodash.api.Pipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** The latest runs of a pipeline, read when it is expanded. */
sealed interface RunsState {
    data object Loading : RunsState
    data class Loaded(val runs: List<Build>) : RunsState
    data class Failed(val message: String) : RunsState
}

/**
 * The pipelines of [project] open over a board: [pipelines] null until read (the ones of the
 * session's last opening stand in meanwhile, with [refreshing]); [denied] when the credentials
 * cannot read builds.
 */
data class PipelinesState(
    val project: String,
    val pipelines: List<Pipeline>? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    val denied: Boolean = false,
    /** By pipeline id, for those expanded. */
    val runs: Map<Int, RunsState> = emptyMap(),
)

/** Opens a project's pipelines and reads the runs of those expanded, a few at a time. */
class PipelinesController(
    private val scope: CoroutineScope,
    private val api: suspend () -> AdoApi,
    private val errorText: (Throwable) -> String,
) {
    private val _state = MutableStateFlow<PipelinesState?>(null)
    val state: StateFlow<PipelinesState?> = _state.asStateFlow()

    /** Each project's pipelines as last read, for the session. */
    private val cache = mutableMapOf<String, List<Pipeline>>()
    private val jobs = mutableListOf<Job>()
    private val runsLimit = Semaphore(PARALLEL_RUNS)

    /** Shows the pipelines read before at once, if any, and reads them again in the background. */
    fun open(project: String) {
        cancelAll()
        val key = project.lowercase()
        val cached = cache[key]
        _state.value = PipelinesState(project, cached, refreshing = true)
        jobs += scope.launch {
            try {
                val list = api().pipelines(project)
                cache[key] = list
                update(project) { it.copy(pipelines = list, refreshing = false, error = null, denied = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdoException) {
                val denied = e.code == 401 || e.code == 403
                update(project) { it.copy(refreshing = false, denied = denied, error = if (denied) null else errorText(e)) }
            } catch (e: Exception) {
                update(project) { it.copy(refreshing = false, error = errorText(e)) }
            }
        }
    }

    /** Reads the latest runs of a pipeline, unless read already or being read. */
    fun loadRuns(definitionId: Int) {
        val current = _state.value ?: return
        val runs = current.runs[definitionId]
        if (runs == RunsState.Loading || runs is RunsState.Loaded) return
        val project = current.project
        jobs.removeAll { it.isCompleted }
        update(project) { it.copy(runs = it.runs + (definitionId to RunsState.Loading)) }
        jobs += scope.launch {
            val result = try {
                RunsState.Loaded(runsLimit.withPermit { api().pipelineRuns(project, definitionId, RUNS) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RunsState.Failed(errorText(e))
            }
            update(project) { it.copy(runs = it.runs + (definitionId to result)) }
        }
    }

    /** The list again after an error. */
    fun retry() {
        _state.value?.let { open(it.project) }
    }

    fun close() {
        cancelAll()
        _state.value = null
    }

    /** Another organization or account: nothing read before applies. */
    fun forget() {
        close()
        cache.clear()
    }

    private fun cancelAll() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    /** Only while the same project is open: a late answer for another one is dropped. */
    private fun update(project: String, change: (PipelinesState) -> PipelinesState) {
        _state.update { s -> if (s != null && s.project == project) change(s) else s }
    }

    companion object {
        /** Runs shown per pipeline. */
        const val RUNS = 10
        private const val PARALLEL_RUNS = 4
    }
}
