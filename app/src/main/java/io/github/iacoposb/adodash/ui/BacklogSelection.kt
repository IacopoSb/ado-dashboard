package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.FieldChange
import io.github.iacoposb.adodash.api.Person
import io.github.iacoposb.adodash.sync.Sprint
import io.github.iacoposb.adodash.sync.SprintItem
import io.github.iacoposb.adodash.ui.components.AdoCheckbox
import io.github.iacoposb.adodash.ui.components.Banner
import io.github.iacoposb.adodash.ui.components.BannerTone
import io.github.iacoposb.adodash.ui.components.DropdownOption
import io.github.iacoposb.adodash.ui.components.SearchField
import io.github.iacoposb.adodash.ui.components.StatusChip
import io.github.iacoposb.adodash.ui.components.TextAction
import io.github.iacoposb.adodash.ui.components.stateTone
import java.time.Instant

/**
 * The selection's bar in place of the title: close, "3 selected", and "Select all" for the
 * rows shown, ticked when all of them are, partly when some are.
 */
@Composable
internal fun SelectionBar(count: Int, all: ToggleableState, onToggleAll: () -> Unit, onClose: () -> Unit, compact: Boolean) {
    val c = AdoTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.backlog_select_close), tint = c.text) }
        Text(
            pluralStringResource(R.plurals.backlog_selected, count, count), Modifier.padding(start = 4.dp, end = 12.dp),
            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            color = c.text, maxLines = 1,
        )
        Row(
            Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp))
                .clickable(role = Role.Checkbox, onClick = onToggleAll).padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AdoCheckbox(all, null, Modifier.padding(start = 8.dp, end = 8.dp))
            Text(
                stringResource(if (all == ToggleableState.On) R.string.action_deselect_all else R.string.action_select_all),
                style = MaterialTheme.typography.labelLarge, color = c.text, maxLines = 1,
            )
        }
    }
}

/**
 * The changes for the selected [items]: state, assignee, iteration, area and priority, each a
 * button with its menu. [initialMenu] is open from the start, for the screenshots.
 */
@Composable
internal fun BatchButtons(
    sprint: Sprint,
    items: List<SprintItem>,
    edit: BacklogEditState,
    now: Instant,
    onChange: (FieldChange) -> Unit,
    onSearchPeople: (String) -> Unit,
    initialMenu: BatchKind?,
) {
    val enabled = items.isNotEmpty() && edit.progress == null
    BatchButton(stringResource(R.string.wi_state), enabled, initialMenu == BatchKind.STATE) { close ->
        StateMenu(items, edit) { close(); onChange(it) }
    }
    BatchButton(stringResource(R.string.wi_assigned_to), enabled, initialMenu == BatchKind.ASSIGN) { close ->
        PeopleMenu(items, edit, onSearchPeople) { close(); onChange(it) }
    }
    BatchButton(stringResource(R.string.wi_iteration), enabled, initialMenu == BatchKind.ITERATION) { close ->
        IterationMenu(sprint, edit, now) { close(); onChange(it) }
    }
    BatchButton(stringResource(R.string.wi_area), enabled, initialMenu == BatchKind.AREA) { close ->
        val tree = remember(edit.options.areas) { edit.options.areas?.let(PathTree::fromArea) }
        val common = items.map { it.item.areaPath }.distinct().singleOrNull().orEmpty()
        PathPicker(tree, common, null, stringResource(R.string.wi_search_areas), "") { close(); onChange(FieldChange.AreaPath(it)) }
    }
    BatchButton(stringResource(R.string.wi_priority), enabled, initialMenu == BatchKind.PRIORITY) { close ->
        val common = items.map { it.item.priority }.distinct().singleOrNull()
        (1..4).forEach { p -> DropdownOption("$p", p == common) { close(); onChange(FieldChange.Priority(p)) } }
    }
}

@Composable
private fun BatchButton(label: String, enabled: Boolean, initiallyOpen: Boolean, menu: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val c = AdoTheme.colors
    var open by remember { mutableStateOf(initiallyOpen && enabled) }
    val shape = RoundedCornerShape(18.dp)
    Box {
        Row(
            Modifier.height(36.dp).clip(shape).background(c.surface).border(1.dp, c.outline, shape)
                .clickable(enabled = enabled, role = Role.DropdownList) { open = true }
                .padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (enabled) c.text else c.textFaint, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp), tint = if (enabled) c.textMuted else c.textFaint)
        }
        DropdownMenu(open, { open = false }, containerColor = c.surface) { menu { open = false } }
    }
}

/** The states all the selected types have; a hint when they have none in common. */
@Composable
private fun StateMenu(items: List<SprintItem>, edit: BacklogEditState, onChange: (FieldChange) -> Unit) {
    val c = AdoTheme.colors
    val states = BacklogBatch.commonStates(items.map { it.item.type }, edit.options.states)
    val common = items.map { it.item.state }.distinct().singleOrNull()
    when {
        states == null -> Loading()
        states.isEmpty() -> Text(
            stringResource(R.string.backlog_batch_no_common_state), Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
        )
        else -> states.forEach { s ->
            DropdownMenuItem(
                text = { StatusChip(s.name, tone = stateTone(s.name)) },
                onClick = { onChange(FieldChange.State(s.name)) },
                trailingIcon = { if (s.name == common) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = c.primary) },
                modifier = Modifier.width(240.dp).semantics { contentDescription = s.name },
            )
        }
    }
}

/** Search, "Unassigned", me, then the search's results. */
@Composable
private fun PeopleMenu(items: List<SprintItem>, edit: BacklogEditState, onSearch: (String) -> Unit, onChange: (FieldChange) -> Unit) {
    val c = AdoTheme.colors
    var query by remember { mutableStateOf(edit.people.query) }
    val common = items.map { it.item.assignedTo }.distinct().singleOrNull()
    Column(Modifier.width(300.dp)) {
        SearchField(
            query, { query = it; onSearch(it) }, stringResource(R.string.wi_search_people),
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        val people: List<Person> = if (query.isBlank()) listOfNotNull(edit.me) else edit.people.results
        if (query.isBlank()) PersonOption(null, items.all { it.item.assignedTo == null }) { onChange(FieldChange.AssignedTo(null)) }
        when {
            query.isNotBlank() && edit.people.loading && people.isEmpty() -> Loading()
            query.isNotBlank() && people.isEmpty() && !edit.people.loading -> Text(
                stringResource(R.string.wi_no_people), Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
            )
        }
        people.forEach { p -> PersonOption(p, common != null && common == p.name) { onChange(FieldChange.AssignedTo(p)) } }
    }
}

/** "Next sprint" first, then the project's iterations as a tree. */
@Composable
private fun IterationMenu(sprint: Sprint, edit: BacklogEditState, now: Instant, onChange: (FieldChange) -> Unit) {
    val c = AdoTheme.colors
    val project = sprint.board.project
    val iterations = edit.options.iterations
    edit.options.nextSprint?.let { next ->
        DropdownMenuItem(
            text = {
                Column {
                    Text(
                        stringResource(R.string.backlog_batch_next_sprint, next.name), style = MaterialTheme.typography.bodyMedium,
                        color = c.primary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        PathTree.label(next.path), style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            onClick = { onChange(FieldChange.IterationPath(next.path)) },
            modifier = Modifier.width(380.dp),
        )
        HorizontalDivider(color = c.outline)
    }
    val tree = remember(project, iterations) { iterations?.takeIf { it.isNotEmpty() }?.let { PathTree.fromIterations(project, it) } }
    PathPicker(tree, sprint.iteration.path, now, stringResource(R.string.wi_search_iterations), "") { onChange(FieldChange.IterationPath(it)) }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(20.dp), color = AdoTheme.colors.primary, strokeWidth = 2.dp)
    }
}

/**
 * How the selection's change goes: "Updating 3 of 7…", then "7 work items updated", or how many
 * failed with the failures listed (permission refused said in so many words) and "Retry".
 */
@Composable
internal fun BatchStatus(edit: BacklogEditState, onRetry: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = AdoTheme.colors
    val progress = edit.progress
    val result = edit.result
    when {
        progress != null -> Banner(
            stringResource(R.string.backlog_batch_progress, progress.done, progress.total), BannerTone.Info, modifier,
        ) { CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), color = c.onPrimaryContainer, strokeWidth = 2.dp) }
        result == null -> Unit
        result.failures.isEmpty() -> Banner(
            pluralStringResource(R.plurals.backlog_batch_done, result.updated, result.updated), BannerTone.Info, modifier,
            icon = Icons.Filled.Check,
        ) { TextAction(stringResource(R.string.action_ok), onDismiss, color = c.onPrimaryContainer) }
        else -> Column(
            modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.dangerContainer)
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.backlog_batch_partial, result.updated, result.failures.size),
                style = MaterialTheme.typography.titleSmall, color = c.text,
            )
            if (result.denied) {
                Text(stringResource(R.string.backlog_batch_denied), style = MaterialTheme.typography.bodySmall, color = c.danger)
            }
            result.failures.take(MAX_FAILURES).forEach { f ->
                val why = when (f.problem) {
                    SaveProblem.DENIED -> stringResource(R.string.backlog_batch_failure_denied)
                    SaveProblem.CONFLICT -> stringResource(R.string.backlog_batch_failure_conflict)
                    SaveProblem.OTHER -> f.message
                }
                Text(
                    "#${f.item.id} ${f.item.title} — $why", style = MaterialTheme.typography.bodySmall, color = c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (result.failures.size > MAX_FAILURES) {
                Text(
                    stringResource(R.string.backlog_batch_more, result.failures.size - MAX_FAILURES),
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextAction(stringResource(R.string.action_retry), onRetry)
                TextAction(stringResource(R.string.action_ok), onDismiss)
            }
        }
    }
}

/** Failures listed by name; the rest counted. */
private const val MAX_FAILURES = 5
