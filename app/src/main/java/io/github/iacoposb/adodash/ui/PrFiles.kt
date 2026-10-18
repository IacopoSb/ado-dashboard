package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.ChangeType
import io.github.iacoposb.adodash.api.PrCommit
import io.github.iacoposb.adodash.api.PrFileChange
import io.github.iacoposb.adodash.api.PrThread
import io.github.iacoposb.adodash.ui.components.AdoCheckbox
import io.github.iacoposb.adodash.ui.components.AdoIcons
import io.github.iacoposb.adodash.ui.components.DropdownField
import io.github.iacoposb.adodash.ui.components.DropdownOption
import io.github.iacoposb.adodash.ui.components.TextAction
import java.time.Instant
import kotlin.math.roundToInt

/** The file list's width on tablets, as in the design. */
private val FileListWidth = 320.dp
private val LineNoWidth = 44.dp
private val MarkerWidth = 20.dp
private val UnifiedGutter = LineNoWidth * 2 + MarkerWidth
private val SideGutter = LineNoWidth + MarkerWidth

/** The changed files, their folders and the file opened, for the screenshots to scroll to. */
const val DIFF_TAG = "pr-diff"

/**
 * The Files tab: on tablets the files by folder on the left (change, +/−, threads, viewed) and
 * the selected one's diff on the right, unified or side by side; on phones a file picker above
 * the unified diff.
 */
@Composable
internal fun FilesTab(screen: PrScreen, now: Instant, actions: PrActions, wide: Boolean) {
    val c = AdoTheme.colors
    val files = screen.files
    when {
        !files.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(32.dp), color = c.primary, strokeWidth = 3.dp)
        }
        files.error != null -> Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text(stringResource(R.string.pr_files_failed, files.error), style = MaterialTheme.typography.bodyMedium, color = c.danger)
            TextAction(stringResource(R.string.action_retry), actions.onRetry)
        }
        files.changes.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp)) {
            Text(stringResource(R.string.pr_files_none), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
        wide -> Row(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.width(FileListWidth).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(c.surface)) {
                FileList(screen, actions)
            }
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(c.surface)) {
                files.selectedChange?.let { DiffPane(screen, it, now, actions, wide = true) }
            }
        }
        else -> Column(Modifier.fillMaxSize().background(c.surface)) {
            files.selectedChange?.let { change ->
                PhoneFilePicker(screen, change, actions)
                HorizontalDivider(color = c.outline)
                DiffPane(screen, change, now, actions, wide = false)
            }
        }
    }
}

/** "+24 −6" in the design's colors; nothing until the diff is known. */
@Composable
private fun Counts(diff: FileDiff?, modifier: Modifier = Modifier) {
    val ready = diff as? FileDiff.Ready ?: return
    val c = AdoTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ready.added > 0 || ready.deleted == 0) Text("+${ready.added}", style = MaterialTheme.typography.labelMedium, color = c.success, maxLines = 1)
        if (ready.deleted > 0) Text("−${ready.deleted}", style = MaterialTheme.typography.labelMedium, color = c.danger, maxLines = 1)
    }
}

private fun threadsOn(screen: PrScreen, path: String): List<PrThread> =
    screen.threads.shown.filter { it.filePath.equals(path, ignoreCase = true) }

/** "4 files · +124 −7", then the folders (collapsible) with their files. */
@Composable
private fun FileList(screen: PrScreen, actions: PrActions) {
    val c = AdoTheme.colors
    val files = screen.files
    val totals = files.totals
    val title = pluralStringResource(R.plurals.pr_files_count, files.changes.size, files.changes.size) +
        (totals?.let { (added, deleted) -> " · +$added −$deleted" } ?: "")
    Text(
        title.uppercase(), Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp).semantics { heading() },
        style = MaterialTheme.typography.labelSmall, color = c.textMuted,
    )
    val groups = remember(files.changes) { PrFileList.groups(files.changes) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
        groups.forEach { (folder, changes) ->
            val collapsed = folder in files.collapsedFolders
            item(key = "folder:$folder") { FolderRow(folder, changes.size, collapsed) { actions.onToggleFolder(folder) } }
            if (!collapsed) {
                items(changes, key = { "file:${it.path}" }) { change ->
                    FileRow(
                        change, selected = change.path == files.selected, diff = files.diffs[change.path],
                        threads = threadsOn(screen, change.path).size, viewed = change.path in files.viewed,
                    ) { actions.onSelectFile(change.path) }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(folder: String, count: Int, collapsed: Boolean, onClick: () -> Unit) {
    val c = AdoTheme.colors
    val label = folder.removePrefix("/").ifEmpty { "/" }
    val action = stringResource(if (collapsed) R.string.action_expand else R.string.action_collapse, label)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = action, onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (collapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, null, Modifier.size(20.dp), tint = c.textMuted)
        Spacer(Modifier.width(4.dp))
        Icon(AdoIcons.Folder, null, Modifier.size(16.dp), tint = c.textFaint)
        Spacer(Modifier.width(8.dp))
        Text(
            label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.textMuted,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text("$count", style = MaterialTheme.typography.labelMedium, color = c.textFaint)
        Spacer(Modifier.width(8.dp))
    }
}

/** A changed file as the design system's "Riga file": highlighted when open. */
@Composable
private fun FileRow(change: PrFileChange, selected: Boolean, diff: FileDiff?, threads: Int, viewed: Boolean, onClick: () -> Unit) {
    val c = AdoTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) c.primaryContainer else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick).semantics { this.selected = selected }
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChangeIcon(change.type)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                change.name, style = MaterialTheme.typography.titleSmall,
                color = when {
                    selected -> c.onPrimaryContainer
                    viewed -> c.textMuted
                    else -> c.text
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            change.originalPath?.let {
                Text(
                    stringResource(R.string.pr_file_renamed_from, it.substringAfterLast('/')), style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (threads > 0) {
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.clip(RoundedCornerShape(6.dp)).background(c.warningContainer).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AdoIcons.ChatBubble, null, Modifier.size(12.dp), tint = c.warning)
                Spacer(Modifier.width(4.dp))
                Text("$threads", style = MaterialTheme.typography.labelMedium, color = c.warning)
            }
        }
        Spacer(Modifier.width(8.dp))
        Counts(diff)
        if (viewed) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Check, stringResource(R.string.pr_file_viewed), Modifier.size(16.dp), tint = c.success)
        }
    }
}

/** Added, edited, deleted or renamed: a small icon in the change's color. */
@Composable
private fun ChangeIcon(type: ChangeType) {
    val c = AdoTheme.colors
    val (icon: ImageVector, tint, label) = when (type) {
        ChangeType.ADD -> Triple(Icons.Filled.Add, c.success, R.string.pr_file_added)
        ChangeType.EDIT -> Triple(Icons.Filled.Edit, c.primary, R.string.pr_file_edited)
        ChangeType.DELETE -> Triple(Icons.Filled.Delete, c.danger, R.string.pr_file_deleted)
        ChangeType.RENAME -> Triple(Icons.AutoMirrored.Filled.ArrowForward, c.accent, R.string.pr_file_renamed)
    }
    Icon(icon, stringResource(label), Modifier.size(16.dp), tint = tint)
}

/** Phones: the file as a dropdown ("Gateway.kt · +24 −6") and whether it was viewed. */
@Composable
private fun PhoneFilePicker(screen: PrScreen, change: PrFileChange, actions: PrActions) {
    val c = AdoTheme.colors
    val files = screen.files
    fun label(f: PrFileChange) = f.name + ((files.diffs[f.path] as? FileDiff.Ready)?.let { "  ·  +${it.added} −${it.deleted}" } ?: "")
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        DropdownField(label(change), stringResource(R.string.pr_file_choose), Modifier.weight(1f)) { close ->
            PrFileList.ordered(files.changes).forEach { f ->
                DropdownOption(f.path.removePrefix("/") + ((files.diffs[f.path] as? FileDiff.Ready)?.let { "  +${it.added} −${it.deleted}" } ?: ""), f.path == change.path) {
                    close()
                    actions.onSelectFile(f.path)
                }
            }
        }
        ViewedToggle(change.path in files.viewed, compact = true) { actions.onToggleViewed(change.path) }
    }
}

@Composable
private fun ViewedToggle(viewed: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val c = AdoTheme.colors
    Row(
        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Checkbox, onClick = onClick)
            .padding(start = 4.dp, end = if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AdoCheckbox(if (viewed) ToggleableState.On else ToggleableState.Off, null)
        Text(stringResource(R.string.pr_file_viewed), style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1)
    }
}

/** "Unified | Side by side" as the design's segmented control. */
@Composable
private fun LayoutToggle(sideBySide: Boolean, onChange: (Boolean) -> Unit) {
    val c = AdoTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(c.surfaceAlt).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(false to R.string.pr_diff_unified, true to R.string.pr_diff_side_by_side).forEach { (value, label) ->
            val on = value == sideBySide
            Box(
                Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) c.surface else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onChange(value) }.semantics { selected = on }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = if (on) c.text else c.textMuted, maxLines = 1)
            }
        }
    }
}

/** The selected file: its path, counts and threads, the layout and "Viewed" on tablets; then its diff. */
@Composable
private fun DiffPane(screen: PrScreen, change: PrFileChange, now: Instant, actions: PrActions, wide: Boolean) {
    val c = AdoTheme.colors
    val files = screen.files
    val diff = files.diffs[change.path]
    val threads = threadsOn(screen, change.path)
    if (wide) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    change.path.removePrefix("/"), style = MaterialTheme.typography.titleSmall, color = c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val details = listOfNotNull(
                    (diff as? FileDiff.Ready)?.let { "+${it.added} −${it.deleted}" },
                    threads.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.pr_file_threads, it, it) },
                    change.originalPath?.let { stringResource(R.string.pr_file_renamed_from, it.removePrefix("/")) },
                )
                if (details.isNotEmpty()) {
                    Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (diff is FileDiff.Ready) {
                Spacer(Modifier.width(12.dp))
                LayoutToggle(files.sideBySide, actions.onSetSideBySide)
            }
            Spacer(Modifier.width(12.dp))
            ViewedToggle(change.path in files.viewed) { actions.onToggleViewed(change.path) }
        }
        HorizontalDivider(color = c.outline)
    }
    Box(Modifier.fillMaxSize()) {
        when (diff) {
            null, FileDiff.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = c.primary, strokeWidth = 3.dp)
            is FileDiff.Failed -> Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.pr_diff_failed, diff.message), style = MaterialTheme.typography.bodyMedium, color = c.danger)
                TextAction(stringResource(R.string.action_retry), { actions.onRetryFile(change.path) })
            }
            FileDiff.TooLarge, FileDiff.Binary -> Column(Modifier.padding(20.dp)) {
                Text(
                    stringResource(if (diff == FileDiff.Binary) R.string.pr_diff_binary else R.string.pr_diff_too_large),
                    style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
                )
                screen.pr?.let { pr -> TextAction(stringResource(R.string.action_open_build), { actions.onOpenUrl(fileUrl(pr.url, change.path)) }) }
            }
            is FileDiff.Ready -> DiffList(screen, change, diff, threads, now, actions, sideBySide = wide && files.sideBySide, wide = wide)
        }
    }
}

/** The file in the pull request's own page. */
internal fun fileUrl(prUrl: String, path: String): String =
    "$prUrl?_a=files&path=" + java.net.URLEncoder.encode(path, "UTF-8").replace("+", "%20")

/** Long lines scrolled sideways together, the line numbers staying in place. */
private class CodeScroll {
    var offset by mutableFloatStateOf(0f)
    var max by mutableFloatStateOf(0f)
    val state = ScrollableState { delta ->
        val old = offset
        offset = (offset - delta).coerceIn(0f, max)
        old - offset
    }
}

private fun codeText(text: String) = text.replace("\t", "    ")

/** The diff's rows: hunks with their headers, hidden stretches, threads and the new thread's composer under their lines. */
@Composable
private fun DiffList(
    screen: PrScreen,
    change: PrFileChange,
    diff: FileDiff.Ready,
    threads: List<PrThread>,
    now: Instant,
    actions: PrActions,
    sideBySide: Boolean,
    wide: Boolean,
) {
    val c = AdoTheme.colors
    val files = screen.files
    val anchor = files.newThread?.takeIf { it.path.equals(change.path, ignoreCase = true) }
    val placed = remember(diff, threads) { DiffRows.place(diff.lines, threads) }
    val composerAt = anchor?.let { DiffRows.indexOf(diff.lines, LineRef(it.line, it.left)) }
    val sending = screen.threads.sending.filter { it.anchor?.path.equals(change.path, ignoreCase = true) }
    val sendingAt = sending.mapNotNull { s -> s.anchor?.let { a -> DiffRows.indexOf(diff.lines, LineRef(a.line, a.left))?.let { it to s } } }
    // Threads being sent show under their lines too
    val withSending = remember(placed, sendingAt) {
        val map = placed.byIndex.mapValues { it.value }.toMutableMap()
        sendingAt.forEach { (i, s) ->
            map[i] = map[i].orEmpty() + PrThread(-s.localId, io.github.iacoposb.adodash.api.ThreadStatus.ACTIVE, listOf(sendingComment(screen, s)))
        }
        map.toMap()
    }
    val expanded = files.expandedGaps[change.path].orEmpty()
    val rows = remember(diff, expanded, withSending, composerAt) { DiffRows.rows(diff.lines, expanded, withSending, composerAt) }
    if (rows.none { it is DiffRow.Line }) {
        Text(
            stringResource(R.string.pr_diff_no_changes), Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
        )
        return
    }
    val scroll = remember(change.path, sideBySide) { CodeScroll() }
    val measurer = rememberTextMeasurer()
    val charWidth = remember(measurer) { measurer.measure("0".repeat(20), CodeStyle).size.width / 20f }
    val longest = remember(diff) { diff.lines.maxOfOrNull { codeText(it.text).length } ?: 0 }
    val density = LocalDensity.current
    val view = DiffView(screen, now, actions, scroll, onComment = actions.onStartFileThread, wide = wide)
    BoxWithConstraints(Modifier.fillMaxSize().scrollable(scroll.state, Orientation.Horizontal)) {
        val cell = if (sideBySide) (maxWidth - SideGutter * 2 - 1.dp) / 2 else maxWidth - UnifiedGutter
        val max = with(density) { (longest * charWidth + 24.dp.toPx() - cell.toPx()).coerceAtLeast(0f) }
        SideEffect {
            scroll.max = max
            scroll.offset = scroll.offset.coerceIn(0f, max)
        }
        LazyColumn(Modifier.fillMaxSize().testTag(DIFF_TAG), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            if (placed.elsewhere.isNotEmpty()) {
                item(key = "elsewhere") {
                    Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.pr_diff_elsewhere).uppercase(), style = MaterialTheme.typography.labelSmall, color = c.textMuted,
                        )
                        placed.elsewhere.forEach { t -> ThreadCard(t, screen, now, actions, expanded = true, onToggle = {}) }
                    }
                }
            }
            if (sideBySide) sideRows(DiffRows.sideBySide(rows), change, view) else unifiedRows(rows, change, view)
        }
    }
}

private fun sendingComment(screen: PrScreen, s: SendingPrComment) = io.github.iacoposb.adodash.api.PrComment(
    -s.localId, -s.localId, io.github.iacoposb.adodash.api.Identity(screen.me?.id.orEmpty(), screen.me?.name.orEmpty()), s.markdown, s.at,
)

/** What the rows of a diff need besides themselves. */
private class DiffView(
    val screen: PrScreen,
    val now: Instant,
    val actions: PrActions,
    val scroll: CodeScroll,
    val onComment: (LineRef) -> Unit,
    val wide: Boolean,
)

private fun refOf(line: DiffLine) = if (line.kind == DiffKind.DELETE) LineRef(line.oldNo, left = true) else LineRef(line.newNo, left = false)

private fun LazyListScope.unifiedRows(rows: List<DiffRow>, change: PrFileChange, view: DiffView) {
    items(rows, key = { rowKey(it) }) { row ->
        when (row) {
            is DiffRow.Header -> HunkHeader(row)
            is DiffRow.Gap -> GapRow(row, UnifiedGutter) { view.actions.onExpandGap(change.path, row.start) }
            is DiffRow.Line -> UnifiedLine(row.line, view)
            is DiffRow.Threads -> InlineThreads(row.threads, view, if (view.wide) UnifiedGutter else 0.dp)
            is DiffRow.NewThread -> NewLineThread(view, if (view.wide) UnifiedGutter else 0.dp)
        }
    }
}

private fun LazyListScope.sideRows(rows: List<SideRow>, change: PrFileChange, view: DiffView) {
    items(rows, key = { sideKey(it) }) { row ->
        when (row) {
            is SideRow.Header -> HunkHeader(row.header)
            is SideRow.Gap -> GapRow(row.gap, SideGutter) { view.actions.onExpandGap(change.path, row.gap.start) }
            is SideRow.Pair -> SideLine(row, view)
            is SideRow.Threads -> InlineThreads(row.threads.threads, view, SideGutter)
            is SideRow.NewThread -> NewLineThread(view, SideGutter)
        }
    }
}

private fun rowKey(row: DiffRow): String = when (row) {
    is DiffRow.Header -> "h:${row.oldStart}:${row.newStart}"
    is DiffRow.Gap -> "g:${row.start}"
    is DiffRow.Line -> "l:${row.index}"
    is DiffRow.Threads -> "t:${row.index}"
    is DiffRow.NewThread -> "n:${row.index}"
}

private fun sideKey(row: SideRow): String = when (row) {
    is SideRow.Header -> rowKey(row.header)
    is SideRow.Gap -> rowKey(row.gap)
    is SideRow.Pair -> "p:${row.left?.index}:${row.right?.index}"
    is SideRow.Threads -> rowKey(row.threads)
    is SideRow.NewThread -> rowKey(row.row)
}

@Composable
private fun HunkHeader(header: DiffRow.Header) {
    val c = AdoTheme.colors
    Box(Modifier.fillMaxWidth().background(c.surfaceAlt).padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(header.text, style = CodeStyle, color = c.textMuted, maxLines = 1)
    }
}

@Composable
private fun GapRow(gap: DiffRow.Gap, indent: Dp, onClick: () -> Unit) {
    val c = AdoTheme.colors
    Box(
        Modifier.fillMaxWidth().background(c.bg).clickable(role = Role.Button, onClick = onClick)
            .padding(start = indent, top = 4.dp, bottom = 4.dp),
    ) {
        Text(pluralStringResource(R.plurals.pr_diff_gap, gap.count, gap.count), style = CodeStyle, color = c.primary, maxLines = 1)
    }
}

/** A line's number: tapping it starts a thread on the line. */
@Composable
private fun LineNumber(number: Int, ref: LineRef?, view: DiffView) {
    val c = AdoTheme.colors
    val label = ref?.let { stringResource(R.string.pr_diff_comment_line, it.line) }
    Box(
        Modifier.width(LineNoWidth).fillMaxHeight()
            .then(if (number > 0 && ref != null) Modifier.clickable(onClickLabel = label) { view.onComment(ref) } else Modifier)
            .padding(end = 8.dp),
        contentAlignment = Alignment.CenterEnd,
    ) {
        if (number > 0) Text("$number", style = CodeStyle, color = c.textFaint, maxLines = 1)
    }
}

private fun markerOf(kind: DiffKind?) = when (kind) {
    DiffKind.ADD -> "+"
    DiffKind.DELETE -> "−"
    else -> ""
}

@Composable
private fun rowColor(kind: DiffKind?): Color = when (kind) {
    DiffKind.ADD -> AdoTheme.colors.diffAdd
    DiffKind.DELETE -> AdoTheme.colors.diffDel
    DiffKind.CONTEXT -> Color.Transparent
    null -> AdoTheme.colors.bg
}

@Composable
private fun Marker(kind: DiffKind?) {
    val c = AdoTheme.colors
    Box(Modifier.width(MarkerWidth).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
        Text(markerOf(kind), style = CodeStyle, color = if (kind == DiffKind.ADD) c.success else c.danger, maxLines = 1)
    }
}

/** The code, cut at its cell's edge and moved by the shared sideways scroll. */
@Composable
private fun RowScope.Code(text: String, view: DiffView) {
    val c = AdoTheme.colors
    Box(Modifier.weight(1f).fillMaxHeight().clipToBounds(), contentAlignment = Alignment.CenterStart) {
        Text(
            codeText(text),
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset { IntOffset(-view.scroll.offset.roundToInt(), 0) },
            style = CodeStyle, color = c.text, softWrap = false, maxLines = 1,
        )
    }
}

/** Long-pressing a line starts a thread on it. */
private fun Modifier.longPressComment(ref: LineRef?, view: DiffView): Modifier =
    if (ref == null || ref.line <= 0) this else pointerInput(ref) { detectTapGestures(onLongPress = { view.onComment(ref) }) }

@Composable
private fun UnifiedLine(line: DiffLine, view: DiffView) {
    val ref = refOf(line)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 20.dp).background(rowColor(line.kind)).longPressComment(ref, view),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // An unchanged line is commented on the pull request's side, whichever number is tapped
        LineNumber(line.oldNo, ref.takeIf { line.kind != DiffKind.ADD }, view)
        LineNumber(line.newNo, ref.takeIf { line.kind != DiffKind.DELETE }, view)
        Marker(line.kind)
        Code(line.text, view)
    }
}

@Composable
private fun SideLine(pair: SideRow.Pair, view: DiffView) {
    val c = AdoTheme.colors
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        SideHalf(pair.left?.line, left = true, view)
        VerticalDivider(color = c.outline)
        SideHalf(pair.right?.line, left = false, view)
    }
}

@Composable
private fun RowScope.SideHalf(line: DiffLine?, left: Boolean, view: DiffView) {
    val ref = line?.let { if (left && it.kind == DiffKind.DELETE) LineRef(it.oldNo, true) else if (!left) LineRef(it.newNo, false) else refOf(it) }
    Row(
        Modifier.weight(1f).fillMaxHeight().background(rowColor(line?.kind)).longPressComment(ref, view),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineNumber(if (line == null) 0 else if (left) line.oldNo else line.newNo, ref, view)
        Marker(line?.kind)
        Code(line?.text.orEmpty(), view)
    }
}

/** The threads on a line, under it, as cards with their replies, status and reply composer. */
@Composable
private fun InlineThreads(threads: List<PrThread>, view: DiffView, indent: Dp) {
    Column(
        Modifier.fillMaxWidth().padding(start = indent + 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        threads.forEach { t ->
            ThreadCard(
                t, view.screen, view.now, view.actions, expanded = true, onToggle = {}, showPlace = false,
                sending = t.id < 0,
            )
        }
    }
}

/** The composer of a new thread on the line above, with a way out. */
@Composable
private fun NewLineThread(view: DiffView, indent: Dp) {
    val c = AdoTheme.colors
    val screen = view.screen
    val anchor = screen.files.newThread ?: return
    Column(
        Modifier.fillMaxWidth().padding(start = indent + 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp)).background(c.surface).border(BorderStroke(1.dp, c.primary), RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.pr_diff_comment_line, anchor.line), Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1,
            )
            IconButton(onClick = view.actions.onCancelFileThread) {
                Icon(Icons.Filled.Close, stringResource(R.string.action_cancel), tint = c.textMuted)
            }
        }
        CommentComposer(
            key = "${screen.id}:file:${anchor.path}:${anchor.line}:${anchor.left}",
            composer = screen.composer(PrScreen.NEW_FILE_THREAD),
            search = screen.mentions,
            onSearchMentions = view.actions.onSearchMentions,
            onDraftChange = { view.actions.onDraftChange(PrScreen.NEW_FILE_THREAD, it) },
            onSend = { view.actions.onSend(PrScreen.NEW_FILE_THREAD) },
            error = screen.threads.sendErrors[PrScreen.NEW_FILE_THREAD]?.let { commentErrorText(it) },
            placeholder = stringResource(R.string.pr_diff_new_comment, anchor.line),
            modifier = Modifier.padding(end = 8.dp),
            compact = true,
        )
    }
}

/** The Commits tab: newest first, each opening in Azure DevOps. */
@Composable
internal fun CommitsTab(screen: PrScreen, now: Instant, actions: PrActions, wide: Boolean) {
    val c = AdoTheme.colors
    val commits = screen.commits
    val modifier = if (wide) {
        Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp).clip(RoundedCornerShape(16.dp)).background(c.surface)
    } else {
        Modifier.fillMaxSize().background(c.surface)
    }
    Box(modifier) {
        when {
            !commits.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = c.primary, strokeWidth = 3.dp)
            commits.error != null -> Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.pr_commits_failed, commits.error), style = MaterialTheme.typography.bodyMedium, color = c.danger)
                TextAction(stringResource(R.string.action_retry), actions.onRetry)
            }
            commits.items.isEmpty() -> Text(
                stringResource(R.string.pr_commits_none), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                items(commits.items, key = { it.id }) { commit ->
                    CommitRow(commit, now) { actions.onOpenUrl(commit.url) }
                    if (commit != commits.items.last()) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = c.outline)
                }
            }
        }
    }
}

@Composable
private fun CommitRow(commit: PrCommit, now: Instant, onClick: () -> Unit) {
    val c = AdoTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(commit.message, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(commit.author, ago(commit.date, now)).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            commit.shortId, Modifier.clip(RoundedCornerShape(6.dp)).background(c.surfaceAlt).padding(horizontal = 8.dp, vertical = 2.dp),
            style = CodeStyle, color = c.textMuted, maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Icon(AdoIcons.OpenInNew, null, Modifier.size(16.dp), tint = c.textFaint)
    }
}
