package io.github.iacoposb.adodash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.sync.Backlog
import io.github.iacoposb.adodash.sync.BacklogFilter
import io.github.iacoposb.adodash.sync.BacklogGroup
import io.github.iacoposb.adodash.sync.Sprint
import io.github.iacoposb.adodash.sync.SprintItem
import io.github.iacoposb.adodash.ui.components.AdoFilterChip
import io.github.iacoposb.adodash.ui.components.AdoIcons
import io.github.iacoposb.adodash.ui.components.Avatar
import io.github.iacoposb.adodash.ui.components.FullScreenModalWidth
import io.github.iacoposb.adodash.ui.components.StatusChip
import io.github.iacoposb.adodash.ui.components.TextAction
import io.github.iacoposb.adodash.ui.components.stateTone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Today on the device, for the days left. */
internal fun today(now: Instant): LocalDate = now.atZone(ZoneId.systemDefault()).toLocalDate()

/**
 * The app bar's sprint tile of a board: "Sprint 42 · 6 days left" and my items by state
 * ("Mine: 2 to do · 3 in progress · 4 done"), the latter left out when [compact]. Tapping it
 * opens the sprint backlog.
 */
@Composable
fun SprintTile(sprint: Sprint, me: Identity, now: Instant, onOpen: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = AdoTheme.colors
    val mine = sprint.mine(me)
    Row(
        modifier.height(52.dp).clip(RoundedCornerShape(12.dp)).background(c.surface)
            .clickable(onClickLabel = stringResource(R.string.sprint_open), role = Role.Button, onClick = onOpen)
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AdoIcons.Flag, null, Modifier.size(20.dp), tint = c.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                listOfNotNull(sprint.iteration.name, daysLeftShort(sprint, now)).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // Narrow screens: the name and the days only
            if (!compact) {
                Text(
                    stringResource(R.string.sprint_mine_counts, mine.toDo, mine.inProgress, mine.done),
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "6 days left", or that it is over; null without dates. */
@Composable
private fun daysLeftShort(sprint: Sprint, now: Instant): String? {
    val days = sprint.daysLeft(today(now)) ?: return null
    return if (days == 0) stringResource(R.string.sprint_over) else pluralStringResource(R.plurals.sprint_days_left, days, days)
}

/** "6 working days left", or that it is over; null without dates. */
@Composable
private fun workingDaysLeft(sprint: Sprint, now: Instant): String? {
    val days = sprint.daysLeft(today(now)) ?: return null
    return if (days == 0) stringResource(R.string.sprint_over) else pluralStringResource(R.plurals.sprint_working_days_left, days, days)
}

/** "6 – 17 October", "28 September – 9 October"; the days in UTC, as Azure DevOps keeps them. */
@Composable
private fun sprintDates(sprint: Sprint): String? {
    val start = sprint.iteration.start?.atOffset(ZoneOffset.UTC)?.toLocalDate() ?: return null
    val finish = sprint.iteration.finish?.atOffset(ZoneOffset.UTC)?.toLocalDate() ?: return null
    val locale = LocalConfiguration.current.locales[0]
    val full = DateTimeFormatter.ofPattern("d MMMM", locale)
    val from = if (start.month == finish.month && start.year == finish.year) "${start.dayOfMonth}" else full.format(start)
    return "$from – ${full.format(finish)}"
}

/** "8 h", "1.5 h". */
@Composable
private fun hours(value: Double): String {
    val locale = LocalConfiguration.current.locales[0]
    val number = if (value % 1.0 == 0.0) "${value.toLong()}" else String.format(locale, "%.1f", value)
    return stringResource(R.string.backlog_hours, number)
}

/** What the backlog screen does. */
class BacklogActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    /** A row tapped: the work item's details over the backlog. */
    val onOpen: (WorkItem) -> Unit = {},
)

/**
 * The current sprint's backlog on the whole screen: its top-level items in backlog order with
 * their children folding under them; "Mine" or the whole team, types, "Hide closed" and
 * "Expand all". On tablets a table with columns, on narrow screens a list. System back closes
 * it. The initial values are for the screenshots.
 */
@Composable
fun SprintBacklogScreen(
    sprint: Sprint,
    me: Identity,
    now: Instant,
    syncing: Boolean,
    actions: BacklogActions,
    /** "Contoso › Backend". */
    boardTitle: String,
    initialFilter: BacklogFilter = BacklogFilter(),
    initialExpanded: Set<Int> = emptySet(),
) {
    BackHandler(onBack = actions.onBack)
    val c = AdoTheme.colors
    var filter by remember(sprint.board) { mutableStateOf(initialFilter) }
    var expanded by remember(sprint.board) { mutableStateOf(initialExpanded) }
    val types = Backlog.types(sprint.groups)
    val rows = Backlog.filter(sprint.groups, filter, me)
    val expandable = rows.filter { it.children.isNotEmpty() }.map { it.parent.id }.toSet()
    val allExpanded = expandable.isNotEmpty() && expanded.containsAll(expandable)
    val state = BacklogState(
        rows, expanded, types, filter, allExpanded,
        onFilter = { filter = it },
        onToggle = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
        onToggleAll = { expanded = if (allExpanded) expanded - expandable else expanded + expandable },
    )
    BoxWithConstraints(
        Modifier.fillMaxSize().background(c.bg)
            // Nothing below it is reachable
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val title = "${sprint.iteration.name} · $boardTitle"
        if (maxWidth >= FullScreenModalWidth) {
            WideBacklog(sprint, title, now, syncing, actions, state)
        } else {
            NarrowBacklog(sprint, title, now, syncing, actions, state)
        }
    }
}

/** The rows shown and what the screen's own controls change. */
private class BacklogState(
    val rows: List<BacklogGroup>,
    val expanded: Set<Int>,
    val types: List<String>,
    val filter: BacklogFilter,
    val allExpanded: Boolean,
    val onFilter: (BacklogFilter) -> Unit,
    val onToggle: (Int) -> Unit,
    val onToggleAll: () -> Unit,
) {
    fun toggleType(type: String) {
        val on = filter.types.any { it.equals(type, ignoreCase = true) }
        onFilter(filter.copy(types = if (on) filter.types.filterNot { it.equals(type, ignoreCase = true) }.toSet() else filter.types + type))
    }
}

@Composable
private fun RefreshButton(syncing: Boolean, onRefresh: () -> Unit) {
    val c = AdoTheme.colors
    if (syncing) {
        CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), color = c.primary, strokeWidth = 2.dp)
    } else {
        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh), tint = c.textMuted) }
    }
}

// --- Tablet ---

/** Column widths of the table, so that header and rows line up. */
private object Col {
    val chevron = 40.dp
    val type = 112.dp
    val id = 48.dp
    val children = 76.dp
    val state = 96.dp
    val who = 52.dp
    val remaining = 52.dp
    /** A child's type and title start this much further in. */
    val indent = 12.dp
}

@Composable
private fun WideBacklog(sprint: Sprint, title: String, now: Instant, syncing: Boolean, actions: BacklogActions, state: BacklogState) {
    val c = AdoTheme.colors
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 4.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = c.text)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(sprintDates(sprint), workingDaysLeft(sprint, now)).joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(12.dp))
            ScopeToggle(state.filter.mine) { state.onFilter(state.filter.copy(mine = it)) }
            Spacer(Modifier.width(12.dp))
            RefreshButton(syncing, actions.onRefresh)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
                .clip(RoundedCornerShape(16.dp)).background(c.surface),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AdoFilterChip(stringResource(R.string.backlog_all_types), state.filter.types.isEmpty(), {
                        state.onFilter(state.filter.copy(types = emptySet()))
                    })
                    state.types.forEach { type ->
                        AdoFilterChip(type, state.filter.types.any { it.equals(type, ignoreCase = true) }, { state.toggleType(type) })
                    }
                }
                Spacer(Modifier.width(16.dp))
                AdoFilterChip(stringResource(R.string.backlog_hide_closed), state.filter.hideClosed, {
                    state.onFilter(state.filter.copy(hideClosed = !state.filter.hideClosed))
                })
                Spacer(Modifier.width(12.dp))
                TextAction(stringResource(if (state.allExpanded) R.string.backlog_collapse_all else R.string.backlog_expand_all), state.onToggleAll)
            }
            HeaderRow()
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (state.rows.isEmpty()) empty()
                state.rows.forEach { row ->
                    val open = row.parent.id in state.expanded && row.children.isNotEmpty()
                    item(key = row.parent.id) {
                        WideRow(row.parent, child = false, row = row, open = open, onToggle = { state.onToggle(row.parent.id) }, onOpen = actions.onOpen)
                    }
                    if (open) {
                        items(row.children, key = { "${row.parent.id}/${it.id}" }) {
                            WideRow(it, child = true, row = null, open = false, onToggle = {}, onOpen = actions.onOpen)
                        }
                    }
                }
            }
            HorizontalDivider(color = c.outline)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(totalsText(state.rows), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
                Text(stringResource(R.string.backlog_hint), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
            }
        }
    }
}

/** "8 items · 12 children · 37 h remaining". */
@Composable
private fun totalsText(rows: List<BacklogGroup>): String {
    val totals = Backlog.totals(rows)
    return listOf(
        pluralStringResource(R.plurals.backlog_items, totals.items, totals.items),
        pluralStringResource(R.plurals.backlog_children, totals.children, totals.children),
        stringResource(R.string.backlog_remaining, hours(totals.remainingWork)),
    ).joinToString(" · ")
}

/** "Mine" and "Whole team" as a segmented control. */
@Composable
private fun ScopeToggle(mine: Boolean, onChange: (Boolean) -> Unit) {
    val c = AdoTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(c.surfaceAlt).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(true to R.string.backlog_mine, false to R.string.backlog_team).forEach { (value, label) ->
            val on = value == mine
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

@Composable
private fun HeaderRow() {
    val c = AdoTheme.colors
    Row(
        Modifier.fillMaxWidth().height(28.dp).background(c.surfaceAlt).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(Col.chevron))
        HeaderCell(stringResource(R.string.backlog_col_type), Col.type, TextAlign.Start, Modifier.padding(start = 10.dp))
        HeaderCell(stringResource(R.string.backlog_col_id), Col.id, TextAlign.End)
        Text(
            stringResource(R.string.backlog_col_title).uppercase(), Modifier.weight(1f).padding(start = 16.dp),
            style = MaterialTheme.typography.labelSmall, color = c.textMuted, maxLines = 1,
        )
        HeaderCell(stringResource(R.string.backlog_col_children), Col.children, TextAlign.End)
        HeaderCell(stringResource(R.string.backlog_col_state), Col.state, TextAlign.Start, Modifier.padding(start = 12.dp))
        HeaderCell(stringResource(R.string.backlog_col_who), Col.who, TextAlign.Center)
        HeaderCell(stringResource(R.string.backlog_col_remaining), Col.remaining, TextAlign.End)
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, width: Dp, align: TextAlign, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), modifier.width(width), style = MaterialTheme.typography.labelSmall, color = AdoTheme.colors.textMuted,
        maxLines = 1, textAlign = align, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * A row of the table: chevron (for a parent with children), type bar and name, id, title on one
 * line, children done/total, state, assignee and hours left. [row] is the parent's group, null
 * for a child.
 */
@Composable
private fun WideRow(
    item: SprintItem,
    child: Boolean,
    row: BacklogGroup?,
    open: Boolean,
    onToggle: () -> Unit,
    onOpen: (WorkItem) -> Unit,
) {
    val c = AdoTheme.colors
    val wi = item.item
    Column {
        Row(
            Modifier.fillMaxWidth().height(48.dp).clickable { onOpen(wi) }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(Col.chevron), contentAlignment = Alignment.CenterStart) {
                if (row != null && row.children.isNotEmpty()) Chevron(open, onToggle)
            }
            Row(Modifier.width(Col.type).padding(start = if (child) Col.indent else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(24.dp).clip(RoundedCornerShape(2.dp)).background(workItemColor(wi.type)))
                Spacer(Modifier.width(10.dp))
                Text(wi.type, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${wi.id}", Modifier.width(Col.id), style = MaterialTheme.typography.bodySmall, color = c.textFaint,
                maxLines = 1, textAlign = TextAlign.End,
            )
            Text(
                wi.title, Modifier.weight(1f).padding(start = if (child) 16.dp + Col.indent else 16.dp, end = 8.dp),
                style = if (child) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = if (item.closed) c.textMuted else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                row?.group?.takeIf { it.childrenTotal > 0 }?.let { "${it.childrenDone}/${it.childrenTotal}" }.orEmpty(),
                Modifier.width(Col.children), style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                maxLines = 1, textAlign = TextAlign.End,
            )
            Box(Modifier.width(Col.state).padding(start = 12.dp), contentAlignment = Alignment.CenterEnd) {
                StatusChip(wi.state, tone = stateTone(wi.state))
            }
            Box(Modifier.width(Col.who), contentAlignment = Alignment.Center) {
                val who = wi.assignedTo
                if (who != null) Avatar(who, description = who) else Text("—", style = MaterialTheme.typography.bodySmall, color = c.textFaint)
            }
            val remaining = if (row != null) row.remainingWork else item.remainingWork.takeUnless { item.closed }
            Text(
                remaining?.let { hours(it) } ?: "—", Modifier.width(Col.remaining), style = MaterialTheme.typography.bodySmall,
                color = c.textMuted, maxLines = 1, textAlign = TextAlign.End,
            )
        }
        HorizontalDivider(color = c.outline)
    }
}

@Composable
private fun Chevron(open: Boolean, onToggle: () -> Unit) {
    val c = AdoTheme.colors
    Box(
        Modifier.size(32.dp).clip(RoundedCornerShape(16.dp))
            .clickable(
                onClickLabel = stringResource(if (open) R.string.backlog_hide_children else R.string.backlog_show_children),
                role = Role.Button, onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            stringResource(if (open) R.string.backlog_hide_children else R.string.backlog_show_children),
            Modifier.size(20.dp), tint = c.textMuted,
        )
    }
}

private fun LazyListScope.empty() = item(key = "empty") {
    Text(
        stringResource(R.string.backlog_empty), Modifier.fillMaxWidth().padding(24.dp),
        style = MaterialTheme.typography.bodyMedium, color = AdoTheme.colors.textMuted, textAlign = TextAlign.Center,
    )
}

// --- Phone ---

@Composable
private fun NarrowBacklog(sprint: Sprint, title: String, now: Instant, syncing: Boolean, actions: BacklogActions, state: BacklogState) {
    val c = AdoTheme.colors
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = c.text)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val totals = Backlog.totals(state.rows)
                Text(
                    listOfNotNull(daysLeftShort(sprint, now), hours(totals.remainingWork)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            RefreshButton(syncing, actions.onRefresh)
            FilterMenu(state)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AdoFilterChip(stringResource(R.string.backlog_team), !state.filter.mine, {
                state.onFilter(state.filter.copy(mine = !state.filter.mine))
            })
            AdoFilterChip(stringResource(R.string.backlog_hide_closed), state.filter.hideClosed, {
                state.onFilter(state.filter.copy(hideClosed = !state.filter.hideClosed))
            })
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(c.surface), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (state.rows.isEmpty()) empty()
            state.rows.forEach { row ->
                val open = row.parent.id in state.expanded && row.children.isNotEmpty()
                item(key = row.parent.id) { NarrowRow(row.parent, row, open, { state.onToggle(row.parent.id) }, actions.onOpen) }
                if (open) {
                    items(row.children, key = { "${row.parent.id}/${it.id}" }) { NarrowRow(it, null, false, {}, actions.onOpen) }
                }
            }
        }
    }
}

/** The types, and "Expand all", in a menu: on phones the chips row has no room for them. */
@Composable
private fun FilterMenu(state: BacklogState) {
    val c = AdoTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(AdoIcons.FilterList, stringResource(R.string.backlog_filters), tint = c.textMuted) }
        DropdownMenu(open, { open = false }, containerColor = c.surface) {
            @Composable
            fun option(text: String, on: Boolean, onClick: () -> Unit) = DropdownMenuItem(
                text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text) },
                onClick = onClick,
                leadingIcon = { Box(Modifier.size(20.dp)) { if (on) Icon(Icons.Filled.Check, null, tint = c.primary) } },
            )
            option(stringResource(R.string.backlog_all_types), state.filter.types.isEmpty()) { state.onFilter(state.filter.copy(types = emptySet())) }
            state.types.forEach { type -> option(type, state.filter.types.any { it.equals(type, ignoreCase = true) }) { state.toggleType(type) } }
            HorizontalDivider(color = c.outline)
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(if (state.allExpanded) R.string.backlog_collapse_all else R.string.backlog_expand_all),
                        style = MaterialTheme.typography.bodyMedium, color = c.primary,
                    )
                },
                onClick = { open = false; state.onToggleAll() },
            )
        }
    }
}

/** A row of the phone list: chevron, type bar, title and "Type · id · 1/3 children", state. */
@Composable
private fun NarrowRow(item: SprintItem, row: BacklogGroup?, open: Boolean, onToggle: () -> Unit, onOpen: (WorkItem) -> Unit) {
    val c = AdoTheme.colors
    val wi = item.item
    val child = row == null
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onOpen(wi) }
                .padding(start = if (child) 72.dp else 0.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!child) {
                Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                    if (row.children.isNotEmpty()) Chevron(open, onToggle)
                }
            }
            Box(Modifier.width(4.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(workItemColor(wi.type)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    wi.title, style = MaterialTheme.typography.bodyLarge, color = if (item.closed) c.textMuted else c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val counts = row?.group?.takeIf { it.childrenTotal > 0 }?.let {
                    stringResource(R.string.backlog_children_count, it.childrenDone, it.childrenTotal)
                }
                Text(
                    listOfNotNull(wi.type, "${wi.id}", counts).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(wi.state, tone = stateTone(wi.state))
        }
        HorizontalDivider(color = c.outline)
    }
}
