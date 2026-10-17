package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.api.BuildState
import io.github.iacoposb.adodash.api.TimelineRecord
import java.time.Instant

/** A row of the build details' phases: a stage, a job or a step, whichever tells most. */
data class BuildStep(
    val name: String,
    val state: BuildState,
    val skipped: Boolean,
    val startedAt: Instant?,
    val finishedAt: Instant?,
)

object BuildSteps {
    /**
     * The stages of a multi-stage pipeline; with a single stage its jobs; with a single job its
     * steps, without the bookkeeping ones Azure DevOps adds (initialize, finalize, post-job).
     */
    fun of(records: List<TimelineRecord>): List<BuildStep> {
        fun List<TimelineRecord>.ordered() = sortedWith(compareBy({ it.order }, { it.startedAt ?: Instant.MAX }))
        val stages = records.filter { it.type == "Stage" }.ordered()
        val jobs = records.filter { it.type == "Job" }.ordered()
        val chosen = when {
            stages.size > 1 -> stages
            jobs.size > 1 -> jobs
            else -> {
                val job = jobs.singleOrNull()
                val tasks = records.filter { it.type == "Task" && (job == null || it.parentId == job.id) && !isBookkeeping(it.name) }
                tasks.ordered().ifEmpty { jobs.ifEmpty { stages } }
            }
        }
        return chosen.map { BuildStep(it.name, it.state, it.skipped, it.startedAt, it.finishedAt) }
    }

    private fun isBookkeeping(name: String) =
        name.startsWith("Post-job:", ignoreCase = true) ||
            name.equals("Initialize job", ignoreCase = true) ||
            name.equals("Finalize Job", ignoreCase = true) ||
            name.equals("Initialize containers", ignoreCase = true) ||
            name.equals("Stop Containers", ignoreCase = true)
}
