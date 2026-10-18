package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.Build
import io.github.iacoposb.adodash.api.BuildState
import io.github.iacoposb.adodash.ui.components.AdoIcons
import io.github.iacoposb.adodash.ui.components.ModalOverlay
import io.github.iacoposb.adodash.ui.components.SearchField
import io.github.iacoposb.adodash.ui.components.TextAction
import io.github.iacoposb.adodash.ui.components.highlight
import java.time.Instant

/** What the pipelines modal does. */
data class PipelinesActions(
    val onLoadRuns: (Int) -> Unit = {},
    /** A run tapped: its details, inside the modal. */
    val onOpenRun: (Build) -> Unit = {},
    /** From a run's details, back to the list. */
    val onBackToList: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onRerun: () -> Unit = {},
    val onClose: () -> Unit = {},
)

/**
 * The pipelines of a board's project, in their folders, with a search; a pipeline expands to
 * its latest runs. A run opens its details in place of the list ([build]), with a way back.
 * The initial values are for the screenshots.
 */
@Composable
fun PipelinesModal(
    state: PipelinesState,
    build: BuildDetail?,
    now: Instant,
    actions: PipelinesActions,
    initialFolders: Set<String> = emptySet(),
    initialOpen: Set<Int> = emptySet(),
    initialQuery: String = "",
) {
    // Here, not in the list: they stay while a run's details are shown
    var query by remember { mutableStateOf(initialQuery) }
    var folders by remember { mutableStateOf(initialFolders.map(PipelineTree::normalize).toSet()) }
    var open by remember { mutableStateOf(initialOpen) }
    val listState = rememberLazyListState()
    // The team's folder, once its pipelines are known: open, and scrolled to
    var teamDone by remember { mutableStateOf(false) }
    var scrollTo by remember { mutableStateOf<String?>(null) }
    val known = state.pipelines
    LaunchedEffect(known != null) {
        if (teamDone || known == null) return@LaunchedEffect
        teamDone = true
        PipelineTree.teamFolder(PipelineTree.of(known), state.team)?.let { f ->
            folders = folders + PipelineTree.opening(f)
            scrollTo = FolderLine(f, 0, open = true, count = 0).key
        }
    }
    val title = if (build != null) buildTitle(build) else stringResource(R.string.pipelines_title)
    ModalOverlay(
        title, onDismiss = actions.onClose, onBack = if (build != null) actions.onBackToList else actions.onClose,
        maxWidth = 1040.dp, maxHeight = 700.dp,
    ) { fullScreen ->
        if (build != null) {
            BuildDetailsContent(
                build, now, actions.onOpenUrl, actions.onRerun, actions.onClose, fullScreen,
                onBack = actions.onBackToList, fillHeight = true,
            )
        } else {
            PipelineList(
                state, now, actions, query, { query = it }, folders,
                onFolder = { p -> folders = if (p in folders) folders - p else folders + p },
                open = open,
                onToggle = { id ->
                    open = if (id in open) open - id else open + id
                    if (id in open) actions.onLoadRuns(id)
                },
                listState = listState,
                scrollTo = scrollTo,
                onScrolled = { scrollTo = null },
            )
        }
    }
}

@Composable
private fun ColumnScope.PipelineList(
    state: PipelinesState,
    now: Instant,
    actions: PipelinesActions,
    query: String,
    onQuery: (String) -> Unit,
    folders: Set<String>,
    onFolder: (String) -> Unit,
    open: Set<Int>,
    onToggle: (Int) -> Unit,
    listState: LazyListState,
    /** The key of a line to scroll to once shown. */
    scrollTo: String? = null,
    onScrolled: () -> Unit = {},
) {
    val c = AdoTheme.colors
    val pipelines = state.pipelines
    // Expanded before (the screenshots, or back from a run): their runs too
    LaunchedEffect(pipelines != null) { if (pipelines != null) open.forEach(actions.onLoadRuns) }

    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.pipelines_title), Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall, color = c.text, maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val count = pipelines?.size
                Text(
                    if (count == null) state.project else "${state.project} · ${pluralStringResource(R.plurals.pipelines_count, count, count)}",
                    Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (state.refreshing && pipelines != null) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(12.dp), color = c.textMuted, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.pipelines_updating), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
                }
            }
        }
        pipelines?.firstOrNull()?.url?.substringBefore('?')?.let { url ->
            IconButton(onClick = { actions.onOpenUrl(url) }) {
                Icon(AdoIcons.OpenInNew, stringResource(R.string.action_open_build), tint = c.textMuted)
            }
        }
        IconButton(onClick = actions.onClose) { Icon(Icons.Filled.Close, stringResource(R.string.action_close), tint = c.textMuted) }
    }
    if (pipelines != null && pipelines.isNotEmpty()) {
        SearchField(query, onQuery, stringResource(R.string.pipelines_search), Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp))
    }
    HorizontalDivider(color = c.outline)

    val body = Modifier.weight(1f).fillMaxWidth()
    val message: @Composable (String) -> Unit = { text ->
        Text(
            text, Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted, textAlign = TextAlign.Center,
        )
    }
    when {
        pipelines == null && state.denied -> Column(body) { message(stringResource(R.string.pipelines_denied)) }
        pipelines == null && state.error != null -> Column(body, horizontalAlignment = Alignment.CenterHorizontally) {
            message(stringResource(R.string.pipelines_load_failed, state.error))
            TextAction(stringResource(R.string.action_retry), actions.onRetry)
        }
        pipelines == null -> Row(body.padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Top) {
            CircularProgressIndicator(Modifier.size(20.dp), color = c.primary, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.pipelines_loading), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
        pipelines.isEmpty() -> Column(body) { message(stringResource(R.string.pipelines_empty)) }
        else -> {
            val tree = remember(pipelines) { PipelineTree.of(pipelines) }
            val matches = remember(tree, query) { PipelineTree.matches(tree, query) }
            val lines = remember(tree, folders, matches) { PipelineTree.rows(tree, folders, matches) }
            LaunchedEffect(matches) { if (matches != null) listState.scrollToItem(0) }
            LaunchedEffect(scrollTo, lines) {
                val at = lines.indexOfFirst { it.key == scrollTo }
                if (scrollTo != null && at >= 0) {
                    listState.scrollToItem(at)
                    onScrolled()
                }
            }
            if (lines.isEmpty()) {
                Column(body) { message(stringResource(R.string.pipelines_no_results, query.trim())) }
            } else {
                LazyColumn(body, state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(lines, key = { it.key }) { line ->
                        when (line) {
                            is FolderLine -> FolderRow(line, searching = matches != null) { onFolder(PipelineTree.normalize(line.folder.path)) }
                            is PipelineItem -> PipelineRow(
                                line, now, query.trim().takeIf { matches != null }, line.pipeline.id in open, state.runs[line.pipeline.id],
                                onToggle = { onToggle(line.pipeline.id) }, onOpenRun = actions.onOpenRun,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Left padding of a line [level] deep. */
private fun indent(level: Int) = 16.dp + 28.dp * level

@Composable
private fun FolderRow(line: FolderLine, searching: Boolean, onToggle: () -> Unit) {
    val c = AdoTheme.colors
    val label = stringResource(if (line.open) R.string.action_collapse else R.string.action_expand, line.folder.name)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !searching, role = Role.Button, onClickLabel = label, onClick = onToggle)
            .padding(start = indent(line.level) - 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (line.open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null, Modifier.size(20.dp), tint = if (searching) c.textFaint else c.textMuted,
        )
        Spacer(Modifier.width(8.dp))
        Icon(AdoIcons.Folder, null, Modifier.size(20.dp), tint = c.textMuted)
        Spacer(Modifier.width(12.dp))
        Text(
            line.folder.name, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall,
            color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(pluralStringResource(R.plurals.pipelines_count, line.count, line.count), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
    }
}

/**
 * A pipeline: its latest run's state, its name, the run's "#number · branch · PR · who" and
 * on the right how long and when, then the chevron that shows its latest [runs]. Tapping the
 * row opens the latest run (the runs, for one never run).
 */
@Composable
private fun PipelineRow(
    line: PipelineItem,
    now: Instant,
    query: String?,
    expanded: Boolean,
    runs: RunsState?,
    onToggle: () -> Unit,
    onOpenRun: (Build) -> Unit,
) {
    val c = AdoTheme.colors
    val pipeline = line.pipeline
    val latest = pipeline.latest
    Column {
        BuildLine(
            latest, now, indent(line.level) - 8.dp,
            title = highlight(pipeline.name, query),
            subtitle = latest?.let { runSubtitle(it, withNumber = true) } ?: stringResource(R.string.pipelines_never_run),
            onClickLabel = if (latest != null) stringResource(R.string.pipelines_open_latest) else stringResource(R.string.action_expand, pipeline.name),
            onClick = { if (latest != null) onOpenRun(latest) else onToggle() },
        ) {
            val label = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand, pipeline.name)
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onToggle).semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null, Modifier.size(22.dp), tint = c.textMuted,
                )
            }
        }
        if (expanded) Runs(runs, now, indent(line.level) + 36.dp, onOpenRun)
    }
}

/** The latest runs below their pipeline. */
@Composable
private fun Runs(runs: RunsState?, now: Instant, start: Dp, onOpenRun: (Build) -> Unit) {
    val c = AdoTheme.colors
    val note = Modifier.padding(start = start + 8.dp, end = 24.dp, top = 8.dp, bottom = 12.dp)
    when (runs) {
        null, RunsState.Loading -> Row(note, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), color = c.primary, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.pipelines_runs_loading), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        is RunsState.Failed -> Text(
            stringResource(R.string.pipelines_runs_failed, runs.message), note, style = MaterialTheme.typography.bodySmall, color = c.danger,
        )
        is RunsState.Loaded -> if (runs.runs.isEmpty()) {
            Text(stringResource(R.string.pipelines_no_runs), note, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        } else {
            // Grouped under their pipeline, from its name's start to the end of its times
            Column(
                Modifier.padding(start = start, end = 16.dp, bottom = 8.dp).clip(RoundedCornerShape(12.dp))
                    .background(c.surfaceAlt).padding(vertical = 4.dp),
            ) {
                runs.runs.forEach { run ->
                    BuildLine(
                        run, now, 0.dp, title = AnnotatedString("#${run.number}"),
                        subtitle = runSubtitle(run, withNumber = false),
                        onClickLabel = stringResource(R.string.pipelines_open_run),
                        onClick = { onOpenRun(run) }, compact = true,
                    ) { Spacer(Modifier.width(32.dp)) }
                }
            }
        }
    }
}

/** "#20261016.4 · feature/x-311 · PR !311 · Anna Verdi". */
@Composable
private fun runSubtitle(run: Build, withNumber: Boolean): String = listOfNotNull(
    if (withNumber) "#${run.number}" else null,
    run.branch.takeIf { it.isNotBlank() },
    run.prId?.let { stringResource(R.string.build_pr, it) },
    run.requestedBy.takeIf { it.isNotBlank() },
).joinToString(" · ")

/**
 * A build line as in the design system's "build row": state icon, [title] over [subtitle], and
 * on the right the duration (in red for a failure, "running · 3 min", "queued") over how long ago;
 * [trailing] after it.
 */
@Composable
private fun BuildLine(
    run: Build?,
    now: Instant,
    indentStart: Dp,
    title: AnnotatedString,
    subtitle: String,
    onClickLabel: String,
    onClick: () -> Unit,
    compact: Boolean = false,
    trailing: @Composable () -> Unit,
) {
    val c = AdoTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 60.dp).padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .padding(start = 8.dp + indentStart, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, tint) = run?.let { stateLook(it.state) } ?: (null to c.textFaint)
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, run?.let { stringResource(stateLabel(it.state)) }, Modifier.size(if (compact) 18.dp else 20.dp), tint = tint)
            else Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title, style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (run != null) {
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val time = run.duration(now)
                // Phones: the time alone, the icon already says it is running; the number keeps its room
                val phone = LocalConfiguration.current.screenWidthDp < 600
                val (text, color) = when (run.state) {
                    BuildState.RUNNING -> when {
                        time == null -> stringResource(R.string.build_state_running)
                        phone -> duration(time)
                        else -> stringResource(R.string.pipelines_running_for, duration(time))
                    } to c.textMuted
                    BuildState.QUEUED -> stringResource(R.string.pipelines_queued) to c.textMuted
                    BuildState.FAILED -> time?.let { duration(it) }.orEmpty() to c.danger
                    else -> time?.let { duration(it) }.orEmpty() to c.textMuted
                }
                if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1)
                Text(ago(run.queuedAt ?: run.at, now), style = MaterialTheme.typography.bodySmall, color = c.textFaint, maxLines = 1)
            }
        }
        Spacer(Modifier.width(4.dp))
        trailing()
    }
}
