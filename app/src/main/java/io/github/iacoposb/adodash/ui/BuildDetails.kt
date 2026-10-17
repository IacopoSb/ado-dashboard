package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.Build
import io.github.iacoposb.adodash.api.BuildState
import io.github.iacoposb.adodash.api.FailedTest
import io.github.iacoposb.adodash.sync.BuildStep
import io.github.iacoposb.adodash.sync.Pipelines
import io.github.iacoposb.adodash.ui.components.AdoButton
import io.github.iacoposb.adodash.ui.components.AdoIcons
import io.github.iacoposb.adodash.ui.components.ButtonStyle
import io.github.iacoposb.adodash.ui.components.ModalOverlay
import io.github.iacoposb.adodash.ui.components.StatusChip
import io.github.iacoposb.adodash.ui.components.TextAction
import io.github.iacoposb.adodash.ui.components.Tone
import java.time.Duration
import java.time.Instant

/** The failed tests of the build details, read after the build. */
sealed interface TestsState {
    data object Loading : TestsState
    /** Not readable (a PAT without Test (read), or no test runs API). */
    data object Unavailable : TestsState
    /** The first failed tests, and how many failed in all. */
    data class Loaded(val tests: List<FailedTest>, val total: Int) : TestsState
}

enum class RerunState { IDLE, BUSY, STARTED, DENIED, FAILED }

/**
 * The build details open over the dashboard: [build] null while it is read (opened from an
 * event), [steps] null while the timeline is.
 */
data class BuildDetail(
    val project: String,
    val buildId: Int,
    val build: Build? = null,
    val steps: List<BuildStep>? = null,
    val tests: TestsState = TestsState.Loading,
    val error: String? = null,
    /** "Rerun failed jobs" offered: not after the credentials were refused it. */
    val canRerun: Boolean = true,
    val rerun: RerunState = RerunState.IDLE,
    val rerunError: String? = null,
)

/** The colors and icon of a build or step state. */
@Composable
private fun stateLook(state: BuildState, skipped: Boolean = false): Pair<ImageVector?, Color> = AdoTheme.colors.run {
    when {
        skipped -> null to textFaint
        state == BuildState.SUCCEEDED -> AdoIcons.CheckCircle to success
        state == BuildState.PARTIAL -> AdoIcons.Error to warning
        state == BuildState.FAILED -> AdoIcons.Cancel to danger
        state == BuildState.RUNNING -> Icons.Filled.PlayArrow to primary
        state == BuildState.QUEUED -> AdoIcons.Schedule to textFaint
        else -> null to textFaint
    }
}

private fun stateLabel(state: BuildState) = when (state) {
    BuildState.SUCCEEDED -> R.string.build_state_succeeded
    BuildState.PARTIAL -> R.string.build_state_partial
    BuildState.FAILED -> R.string.build_state_failed
    BuildState.CANCELED -> R.string.build_state_canceled
    BuildState.RUNNING -> R.string.build_state_running
    BuildState.QUEUED -> R.string.build_state_queued
}

private fun stateTone(state: BuildState) = when (state) {
    BuildState.SUCCEEDED -> Tone.Success
    BuildState.PARTIAL -> Tone.Warning
    BuildState.FAILED -> Tone.Danger
    BuildState.RUNNING -> Tone.Info
    BuildState.CANCELED, BuildState.QUEUED -> Tone.Neutral
}

/**
 * The app bar's pipeline tile: "Pipeline", "1 failed · 1 running · 6 ok" and a dot for each of
 * the latest results. Tapping it opens the details of [Pipelines.focus].
 */
@Composable
fun PipelineTile(builds: List<Build>, onOpen: (Build) -> Unit, modifier: Modifier = Modifier, dots: Boolean = true) {
    val c = AdoTheme.colors
    val counts = Pipelines.counts(builds)
    val parts = buildList {
        if (counts.failed > 0) add(pluralStringResource(R.plurals.pipeline_failed, counts.failed, counts.failed))
        if (counts.running > 0) add(pluralStringResource(R.plurals.pipeline_running, counts.running, counts.running))
        if (counts.ok > 0 || isEmpty()) add(pluralStringResource(R.plurals.pipeline_ok, counts.ok, counts.ok))
    }
    val focus = Pipelines.focus(builds)
    Row(
        modifier.height(52.dp).clip(RoundedCornerShape(12.dp)).background(c.surface)
            .clickable(enabled = focus != null, onClickLabel = stringResource(R.string.pipeline_open), role = Role.Button) {
                focus?.let(onOpen)
            }
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp), tint = c.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(R.string.pipeline_title), style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1)
            Text(
                parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (dots) {
            Spacer(Modifier.width(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // The latest on the left, next to the counts
                builds.take(Pipelines.DOTS).forEach { b ->
                    val color = when (b.state) {
                        BuildState.SUCCEEDED -> c.success
                        BuildState.FAILED, BuildState.PARTIAL -> c.danger
                        BuildState.RUNNING, BuildState.QUEUED -> c.primary
                        BuildState.CANCELED -> c.textFaint
                    }
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                }
            }
        }
    }
}

/**
 * The details of a build in a modal: state and duration, pipeline and number, branch · PR ·
 * who · when; its phases with their durations and its failed tests; "All builds", "Open log"
 * and, for a failed build, "Rerun failed jobs".
 */
@Composable
fun BuildDetailsModal(
    detail: BuildDetail,
    now: Instant,
    onOpen: (url: String) -> Unit,
    onRerun: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AdoTheme.colors
    val build = detail.build
    val title = build?.let { "${it.definitionName}  #${it.number}" } ?: stringResource(R.string.build_loading)
    ModalOverlay(title, onDismiss, maxWidth = 760.dp) { fullScreen ->
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (build != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(stringResource(stateLabel(build.state)), tone = stateTone(build.state))
                        build.duration(now)?.let { StatusChip(duration(it)) }
                    }
                }
                Text(
                    title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = c.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (build != null) {
                    Text(
                        listOfNotNull(
                            build.branch,
                            build.prId?.let { stringResource(R.string.build_pr, it) },
                            build.requestedBy.takeIf { it.isNotBlank() }?.let { stringResource(R.string.build_requested_by, it) },
                            ago(build.queuedAt ?: build.at, now),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (build != null) {
                IconButton(onClick = { onOpen(build.url) }) {
                    Icon(AdoIcons.OpenInNew, stringResource(R.string.action_open_build), tint = c.textMuted)
                }
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.action_close), tint = c.textMuted) }
        }
        HorizontalDivider(color = c.outline)

        val body = Modifier.weight(1f, fill = fullScreen).heightIn(min = 340.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp)
        when {
            detail.error != null -> Column(body) {
                Text(stringResource(R.string.build_load_failed, detail.error), style = MaterialTheme.typography.bodyMedium, color = c.danger)
            }
            build == null -> Column(body, horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.padding(top = 48.dp).size(32.dp), color = c.primary, strokeWidth = 3.dp)
            }
            fullScreen -> Column(body, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Column { Steps(detail.steps, now) }
                if (build.state != BuildState.SUCCEEDED) Column { Tests(detail.tests) }
            }
            else -> Row(body, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f)) { Steps(detail.steps, now) }
                Column(Modifier.weight(1f)) { if (build.state != BuildState.SUCCEEDED) Tests(detail.tests) }
            }
        }

        val note = when (detail.rerun) {
            RerunState.STARTED -> stringResource(R.string.build_rerun_started) to c.success
            RerunState.DENIED -> stringResource(R.string.build_rerun_denied) to c.danger
            RerunState.FAILED -> stringResource(R.string.build_rerun_failed, detail.rerunError.orEmpty()) to c.danger
            else -> null
        }
        note?.let { (text, color) ->
            Text(
                text, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = color, textAlign = TextAlign.End,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        val allBuilds: @Composable () -> Unit = {
            if (build != null) TextAction(stringResource(R.string.action_all_builds), { onOpen(build.definitionUrl) })
        }
        // Narrow screens: "All builds" above, so the two buttons keep their labels
        if (fullScreen) Row(Modifier.padding(start = 12.dp, top = 4.dp)) { allBuilds() }
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            if (!fullScreen) {
                allBuilds()
                Spacer(Modifier.weight(1f))
            }
            if (build != null) {
                AdoButton(stringResource(R.string.action_open_log), { onOpen(build.logUrl) }, style = ButtonStyle.Outlined, icon = AdoIcons.OpenInNew)
                val failed = build.state == BuildState.FAILED || build.state == BuildState.PARTIAL
                if (failed && detail.canRerun && detail.rerun != RerunState.STARTED) {
                    AdoButton(
                        stringResource(R.string.action_rerun_failed), onRerun, icon = Icons.Filled.Refresh,
                        enabled = detail.rerun != RerunState.BUSY,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) = Text(
    text.uppercase(), Modifier.padding(bottom = 8.dp).semantics { heading() },
    style = MaterialTheme.typography.labelSmall, color = AdoTheme.colors.textMuted,
)

@Composable
private fun Steps(steps: List<BuildStep>?, now: Instant) {
    val c = AdoTheme.colors
    SectionLabel(stringResource(R.string.build_stages))
    when {
        steps == null -> CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), color = c.primary, strokeWidth = 2.dp)
        steps.isEmpty() -> Text(stringResource(R.string.build_no_stages), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        else -> steps.forEach { step ->
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint) = stateLook(step.state, step.skipped)
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = tint)
                    else Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    step.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                val time = when {
                    step.skipped -> stringResource(R.string.build_step_skipped)
                    step.startedAt != null -> duration(Duration.between(step.startedAt, step.finishedAt ?: now))
                    !step.state.finished -> stringResource(R.string.build_step_waiting)
                    else -> ""
                }
                Text(time, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Tests(tests: TestsState) {
    val c = AdoTheme.colors
    val label = stringResource(R.string.build_failed_tests)
    when (tests) {
        TestsState.Loading -> {
            SectionLabel(label)
            CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), color = c.primary, strokeWidth = 2.dp)
        }
        TestsState.Unavailable -> {
            SectionLabel(label)
            Text(stringResource(R.string.build_tests_unavailable), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
        is TestsState.Loaded -> {
            SectionLabel(if (tests.total > 0) "$label · ${tests.total}" else label)
            if (tests.tests.isEmpty()) {
                Text(stringResource(R.string.build_no_failed_tests), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tests.tests.forEach { t ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.dangerContainer)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            t.name, style = MaterialTheme.typography.titleSmall, color = c.text,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (t.message.isNotBlank()) {
                            Text(t.message, style = CodeStyle, color = c.danger, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                val more = tests.total - tests.tests.size
                if (more > 0) {
                    Text(
                        pluralStringResource(R.plurals.build_more_tests, more, more), Modifier.widthIn(min = 0.dp),
                        style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                    )
                }
            }
        }
    }
}
