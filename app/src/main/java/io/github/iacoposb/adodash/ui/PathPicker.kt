package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.ui.components.DropdownField
import io.github.iacoposb.adodash.ui.components.SearchField
import io.github.iacoposb.adodash.ui.components.StatusChip
import io.github.iacoposb.adodash.ui.components.Tone
import io.github.iacoposb.adodash.ui.components.highlight
import io.github.iacoposb.adodash.ui.components.scrollbar
import java.time.Instant

/**
 * An iteration or area field: the path below the project, its last node in front, and a tree
 * to pick another from, with a search. [now] gives iterations their dates and marks the current
 * ones; null for areas. [root] is null while the tree is read.
 */
@Composable
fun PathPickerField(
    path: String,
    label: String,
    root: PathNode?,
    now: Instant?,
    searchHint: String,
    enabled: Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
    initialQuery: String = "",
) {
    val segments = PathTree.segments(path)
    DropdownField(
        PathTree.label(path), label, modifier, enabled = enabled, initiallyOpen = initiallyOpen,
        display = { PathText(segments, enabled) },
    ) { close ->
        PathPicker(root, path, now, searchHint, initialQuery) { close(); onPick(it) }
    }
}

/**
 * "Release 3 › Sprint 42" in one line: the last node in front, the ones before muted and, when
 * it does not fit, left out from the start ("… › Sprint 42").
 */
@Composable
private fun RowScope.PathText(segments: List<String>, enabled: Boolean) {
    val c = AdoTheme.colors
    val style = MaterialTheme.typography.bodyMedium
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.weight(1f)) {
        val width = constraints.maxWidth
        val muted = if (enabled) c.textMuted else c.textFaint
        val last = if (enabled) c.text else c.textMuted
        val text = remember(segments, width, style, muted, last) {
            fun build(dropped: Int) = buildAnnotatedString {
                withStyle(SpanStyle(color = muted)) {
                    if (dropped > 0) append("… › ")
                    segments.dropLast(1).drop(dropped).forEach { append(it); append(" › ") }
                }
                withStyle(SpanStyle(color = last, fontWeight = FontWeight.Medium)) { append(segments.lastOrNull().orEmpty()) }
            }
            var dropped = 0
            while (dropped < segments.size - 1 &&
                measurer.measure(build(dropped), style, maxLines = 1, softWrap = false).size.width > width
            ) dropped++
            build(dropped)
        }
        Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The tree in the field's menu: search on top, then the nodes, scrolled to the current one. */
@Composable
internal fun PathPicker(root: PathNode?, selected: String, now: Instant?, searchHint: String, initialQuery: String, onPick: (String) -> Unit) {
    val c = AdoTheme.colors
    Column(Modifier.width(380.dp)) {
        var query by remember { mutableStateOf(initialQuery) }
        SearchField(query, { query = it }, searchHint, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        if (root == null) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), color = c.primary, strokeWidth = 2.dp)
            }
        } else {
            PathList(root, selected, now, query, onPick)
        }
    }
}

@Composable
private fun PathList(root: PathNode, selected: String, now: Instant?, query: String, onPick: (String) -> Unit) {
    val c = AdoTheme.colors
    var expanded by remember(root) { mutableStateOf(PathTree.initiallyExpanded(root, selected, now)) }
    val search = remember(root, query) { PathTree.search(root, query) }
    val rows = remember(root, expanded, search, now) { PathTree.rows(root, expanded, search, now) }
    // The current iteration in view, or else the one selected, with a line or two above it
    val target = remember(root) {
        rows.indexOfFirst { now != null && it is PathRow && PathTree.isCurrent(it.node, now) }.takeIf { it >= 0 }
            ?: rows.indexOfFirst { it is PathRow && it.node.path.equals(selected, ignoreCase = true) }
    }
    val toggle = { key: String -> expanded = if (key in expanded) expanded - key else expanded + key }
    // Rows are ROW_HEIGHT tall but for large fonts: close enough to start there
    val density = LocalDensity.current
    val scroll = rememberScrollState(with(density) { (ROW_HEIGHT * (target - 2).coerceAtLeast(0)).roundToPx() })
    LaunchedEffect(search?.query) { if (search != null) scroll.scrollTo(0) }
    val locale = LocalConfiguration.current.locales[0]
    if (search != null && rows.isEmpty()) {
        Text(
            stringResource(R.string.wi_tree_no_results, search.query), Modifier.fillMaxWidth().padding(16.dp),
            style = MaterialTheme.typography.bodyMedium, color = c.textMuted, textAlign = TextAlign.Center,
        )
    } else {
        // Not a lazy list: the menu measures its content's intrinsic width, which lazy lists cannot give
        Column(
            Modifier.fillMaxWidth().heightIn(max = 360.dp).padding(top = 4.dp)
                .scrollbar(scroll, c.outline, gap = (-6).dp).verticalScroll(scroll),
        ) {
            rows.forEach { row ->
                key(row.key) {
                    when (row) {
                        is PastRow -> PastRowView(row) { toggle(row.key) }
                        is PathRow -> PathRowView(
                            row, row.node.path.equals(selected, ignoreCase = true), now, search?.query,
                            dates = now?.let { PathTree.dates(row.node, it, locale) },
                            onExpand = toggle,
                            onPick = onPick,
                        )
                    }
                }
            }
        }
    }
}

private val ROW_HEIGHT = 44.dp

/** "Past sprints (3)": the folder's sprints that are over, shown below it when open. */
@Composable
private fun PastRowView(row: PastRow, onToggle: () -> Unit) {
    val c = AdoTheme.colors
    val text = stringResource(R.string.wi_iteration_past, row.count)
    val label = stringResource(if (row.open) R.string.action_collapse else R.string.action_expand, text)
    Row(
        Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onToggle)
            .padding(start = 4.dp + 20.dp * row.level, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Icon(
                if (row.open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null, Modifier.size(20.dp), tint = c.textMuted,
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text, Modifier.weight(1f).padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium,
            color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PathRowView(
    row: PathRow,
    selected: Boolean,
    now: Instant?,
    query: String?,
    dates: String?,
    onExpand: (String) -> Unit,
    onPick: (String) -> Unit,
) {
    val c = AdoTheme.colors
    val node = row.node
    val current = now != null && PathTree.isCurrent(node, now)
    val past = now != null && !current && PathTree.isPast(node, now)
    Row(
        Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp))
            .then(
                when {
                    row.match -> Modifier.background(c.primaryContainer)
                    selected -> Modifier.background(c.surfaceAlt)
                    else -> Modifier
                },
            )
            .clickable(role = Role.Button) { onPick(node.path) }
            .semantics { this.selected = selected }
            .padding(start = 4.dp + 20.dp * row.level, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.expandable) {
            val label = stringResource(if (row.open) R.string.action_collapse else R.string.action_expand, node.name)
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable(enabled = query == null, role = Role.Button) { onExpand(node.path) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (row.open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null, Modifier.size(20.dp), tint = c.textMuted,
                )
            }
        } else {
            Spacer(Modifier.width(32.dp))
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                highlight(node.name, query.takeIf { row.match }),
                style = if (row.level == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                color = when {
                    selected -> c.primary
                    past -> c.textMuted
                    else -> c.text
                },
                fontWeight = if (selected) FontWeight.Medium else null,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (dates != null) {
                Text(dates, style = MaterialTheme.typography.bodySmall, color = if (past) c.textFaint else c.textMuted, maxLines = 1)
            }
        }
        if (current) {
            Spacer(Modifier.width(8.dp))
            StatusChip(stringResource(R.string.wi_iteration_current), tone = Tone.Info)
        }
        Box(Modifier.padding(start = 8.dp).size(18.dp)) {
            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = c.primary)
        }
    }
}
