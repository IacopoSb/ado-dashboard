package io.github.iacoposb.adodash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.FieldChange
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.sync.Backlog
import io.github.iacoposb.adodash.sync.BacklogFilter
import io.github.iacoposb.adodash.sync.BacklogGroup
import io.github.iacoposb.adodash.sync.BacklogMove
import io.github.iacoposb.adodash.sync.BacklogOrder
import io.github.iacoposb.adodash.sync.Shift
import io.github.iacoposb.adodash.sync.Sprint
import io.github.iacoposb.adodash.sync.SprintGroup
import io.github.iacoposb.adodash.sync.SprintItem
import io.github.iacoposb.adodash.ui.components.AdoCheckbox
import io.github.iacoposb.adodash.ui.components.AdoFilterChip
import io.github.iacoposb.adodash.ui.components.AdoIcons
import io.github.iacoposb.adodash.ui.components.Avatar
import io.github.iacoposb.adodash.ui.components.Banner
import io.github.iacoposb.adodash.ui.components.BannerTone
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
 * ("Mine: 2 to do · 3 in progress · 4 done"); when [compact] the name over the days. Tapping it
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
            val days = daysLeftShort(sprint, now)
            Text(
                if (compact) sprint.iteration.name else listOfNotNull(sprint.iteration.name, days).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // Narrow screens: the name over the days, as the pipeline tile's two lines
            val detail = if (compact) days else stringResource(R.string.sprint_mine_counts, mine.toDo, mine.inProgress, mine.done)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    /** A row dragged or moved from its menu: shown at once, then saved. */
    val onReorder: (BacklogMove) -> Unit = {},
    val onDismissReorderError: () -> Unit = {},
    /** The selection started: its menus' options read. */
    val onLoadOptions: () -> Unit = {},
    val onSearchPeople: (String) -> Unit = {},
    /** A change made to all the selected work items. */
    val onBatch: (List<WorkItem>, FieldChange) -> Unit = { _, _ -> },
    val onRetryBatch: () -> Unit = {},
    val onDismissBatch: () -> Unit = {},
)

/** The backlog's changes, from the dashboard: they need the sprint they are made in. */
class BacklogEditActions(
    val onReorder: (Sprint, BacklogMove) -> Unit = { _, _ -> },
    val onDismissReorderError: () -> Unit = {},
    val onLoadOptions: (Sprint) -> Unit = {},
    val onSearchPeople: (Sprint, String) -> Unit = { _, _ -> },
    val onBatch: (List<WorkItem>, FieldChange) -> Unit = { _, _ -> },
    val onRetryBatch: () -> Unit = {},
    val onDismissBatch: () -> Unit = {},
)

/**
 * The current sprint's backlog on the whole screen: its top-level items in backlog order with
 * their children folding under them; "Mine" or the whole team, types, "Hide closed" and
 * "Expand all". Rows are reordered among their siblings by their handle, dragged or from its
 * menu. A long press (or "Select") selects rows, to change their state, assignee, iteration,
 * area or priority all at once. On tablets a table with columns, on narrow screens a list.
 * System back closes the selection, then the backlog. The initial values are for the
 * screenshots.
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
    edit: BacklogEditState = BacklogEditState(),
    initialFilter: BacklogFilter = BacklogFilter(),
    initialExpanded: Set<Int> = emptySet(),
    /** The row whose handle's menu is open. */
    initialRowMenu: Int? = null,
    /** Selecting, with these rows selected; null when not selecting. */
    initialSelection: Set<Int>? = null,
    /** The selection's change whose menu is open. */
    initialBatchMenu: BatchKind? = null,
) {
    BackHandler(onBack = actions.onBack)
    val c = AdoTheme.colors
    var filter by remember(sprint.board) { mutableStateOf(initialFilter) }
    var expanded by remember(sprint.board) { mutableStateOf(initialExpanded) }
    var selection by remember(sprint.board) { mutableStateOf(initialSelection) }
    // Registered after the backlog's own: back closes the selection first
    BackHandler(enabled = selection != null) { selection = null }
    val selecting = selection != null
    val currentActions by rememberUpdatedState(actions)
    LaunchedEffect(selecting) { if (selecting) currentActions.onLoadOptions() }
    val list = rememberLazyListState()
    val drag = remember(list) { BacklogDrag(list) }
    val types = Backlog.types(sprint.groups)
    val rows = dragged(Backlog.filter(sprint.groups, filter, me), drag)
    val expandable = rows.filter { it.children.isNotEmpty() }.map { it.parent.id }.toSet()
    val allExpanded = expandable.isNotEmpty() && expanded.containsAll(expandable)
    val selected = selection.orEmpty()
    val state = BacklogState(
        rows, expanded, types, filter, allExpanded,
        reorder = BacklogOrder.canReorder(filter) && !selecting, list = list, drag = drag, rowMenu = initialRowMenu,
        selection = selection,
        selectedItems = sprint.items.filter { it.id in selected },
        batchMenu = initialBatchMenu,
        onFilter = { filter = it },
        onToggle = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
        onToggleAll = { expanded = if (allExpanded) expanded - expandable else expanded + expandable },
        onSelect = { selection = it },
        onBatch = { change ->
            actions.onBatch(sprint.items.filter { it.id in selected }.map { it.item }, change)
            selection = null
        },
    )
    BoxWithConstraints(
        Modifier.fillMaxSize().background(c.bg)
            // Nothing below it is reachable
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val title = "${sprint.iteration.name} · $boardTitle"
        val wide = maxWidth >= FullScreenModalWidth
        if (wide) {
            WideBacklog(sprint, title, now, syncing, actions, state, edit)
        } else {
            NarrowBacklog(sprint, title, now, syncing, actions, state, edit)
        }
        edit.reorderError?.let { error ->
            val text = if (error.problem == SaveProblem.DENIED) {
                stringResource(R.string.backlog_reorder_denied)
            } else {
                stringResource(R.string.backlog_reorder_failed, error.id, error.message)
            }
            Banner(
                text, BannerTone.Error,
                // Over the table's footer on tablets
                Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = if (wide) 56.dp else 16.dp)
                    .widthIn(max = 720.dp),
            ) { TextAction(stringResource(R.string.action_ok), actions.onDismissReorderError) }
        }
        BatchStatus(
            edit, actions.onRetryBatch, actions.onDismissBatch,
            Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = if (wide) 56.dp else 16.dp)
                .widthIn(max = 720.dp),
        )
    }
}

/** The rows in the order of the drag going on, if any: the dragged parent without its children. */
private fun dragged(rows: List<BacklogGroup>, drag: BacklogDrag): List<BacklogGroup> {
    val id = drag.id ?: return rows
    val order = drag.order
    val group = drag.group
    return if (group == null) {
        val byId = rows.associateBy { it.parent.id }
        order.mapNotNull { byId[it] } + rows.filter { it.parent.id !in order }
    } else {
        rows.map { row ->
            if (row.parent.id != group) return@map row
            val byId = row.children.associateBy { it.id }
            row.copy(children = order.mapNotNull { byId[it] } + row.children.filter { it.id !in order })
        }
    }.let { shown -> if (group == null) shown.map { if (it.parent.id == id) it.copy(children = emptyList()) else it } else shown }
}

/** The rows shown and what the screen's own controls change. */
private class BacklogState(
    val rows: List<BacklogGroup>,
    val expanded: Set<Int>,
    val types: List<String>,
    val filter: BacklogFilter,
    val allExpanded: Boolean,
    /** Rows can be reordered (see [BacklogOrder.canReorder]). */
    val reorder: Boolean,
    val list: LazyListState,
    val drag: BacklogDrag,
    val rowMenu: Int?,
    /** The rows selected; null when not selecting. */
    val selection: Set<Int>?,
    val selectedItems: List<SprintItem>,
    val batchMenu: BatchKind?,
    val onFilter: (BacklogFilter) -> Unit,
    val onToggle: (Int) -> Unit,
    val onToggleAll: () -> Unit,
    /** Selecting with these rows selected, or no longer (null). */
    val onSelect: (Set<Int>?) -> Unit,
    val onBatch: (FieldChange) -> Unit,
) {
    val selecting get() = selection != null

    /** The ids of the rows on screen: the top-level ones and the children shown under them. */
    val visibleIds: List<Int>
        get() = rows.flatMap { r ->
            listOf(r.parent.id) + if (r.parent.id in expanded) r.children.map { it.id } else emptyList()
        }

    /** "Select all" ticked, partly or not: for the rows on screen. */
    val allSelected: ToggleableState
        get() {
            val visible = visibleIds
            val selected = selection.orEmpty()
            return when {
                visible.isNotEmpty() && selected.containsAll(visible) -> ToggleableState.On
                visible.any { it in selected } -> ToggleableState.Indeterminate
                else -> ToggleableState.Off
            }
        }

    fun toggleAllSelected() {
        val visible = visibleIds
        val selected = selection.orEmpty()
        onSelect(if (selected.containsAll(visible)) selected - visible.toSet() else selected + visible)
    }

    /** A row tapped while selecting. */
    fun toggleSelected(id: Int) {
        val selected = selection.orEmpty()
        onSelect(if (id in selected) selected - id else selected + id)
    }

    /** A row long-pressed: selecting, with it. */
    fun startSelecting(id: Int?) = onSelect(selection.orEmpty() + listOfNotNull(id))

    fun toggleType(type: String) {
        val on = filter.types.any { it.equals(type, ignoreCase = true) }
        onFilter(filter.copy(types = if (on) filter.types.filterNot { it.equals(type, ignoreCase = true) }.toSet() else filter.types + type))
    }

    /** The ids a row moves among: the top-level rows shown, or its parent's children shown. */
    fun siblings(row: BacklogGroup?): List<Int> = row?.children?.map { it.id } ?: rows.map { it.parent.id }
}

/**
 * A row's handle at its start: dragged it moves the row among its siblings, tapped it opens a
 * menu with the same moves (also as accessibility actions). Faint, with a hint in its menu,
 * when the filters do not allow reordering. [row] is the parent's group for a child, null for a
 * top-level row.
 */
@Composable
private fun Handle(item: SprintItem, row: BacklogGroup?, state: BacklogState, actions: BacklogActions, width: Dp) {
    val c = AdoTheme.colors
    val id = item.id
    val group = row?.parent?.id
    val parentId = BacklogOrder.parentOf(item, row?.group)
    val siblings = state.siblings(row)
    var menu by remember(id) { mutableStateOf(state.rowMenu == id) }
    val currentSiblings by rememberUpdatedState(siblings)
    val currentActions by rememberUpdatedState(actions)
    val drag = state.drag
    val moves = Shift.entries.associateWith { if (state.reorder) BacklogOrder.shift(siblings, id, it, parentId) else null }
    val labels = mapOf(
        Shift.TOP to stringResource(R.string.backlog_move_top),
        Shift.UP to stringResource(R.string.backlog_move_up),
        Shift.DOWN to stringResource(R.string.backlog_move_down),
        Shift.BOTTOM to stringResource(R.string.backlog_move_bottom),
    )
    val description = stringResource(R.string.backlog_handle, item.item.title)
    Box(Modifier.width(width), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(width, 40.dp).clip(RoundedCornerShape(8.dp))
                .then(
                    if (state.reorder) {
                        Modifier.pointerInput(id, group) {
                            detectVerticalDragGestures(
                                onDragStart = { drag.start(id, group, currentSiblings) },
                                onDragEnd = {
                                    drag.end()?.let { order -> currentActions.onReorder(BacklogOrder.at(order, id, parentId)) }
                                },
                                onDragCancel = { drag.end() },
                                onVerticalDrag = { change, dy ->
                                    change.consume()
                                    drag.drag(dy)
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .clickable(role = Role.Button) { menu = true }
                .semantics {
                    contentDescription = description
                    customActions = moves.mapNotNull { (shift, move) ->
                        move?.let { m -> CustomAccessibilityAction(labels.getValue(shift)) { actions.onReorder(m); true } }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(AdoIcons.DragIndicator, null, Modifier.size(20.dp), tint = if (state.reorder) c.textMuted else c.textFaint)
        }
        DropdownMenu(menu, { menu = false }, containerColor = c.surface) {
            if (state.reorder) {
                Shift.entries.forEach { shift ->
                    val move = moves[shift]
                    DropdownMenuItem(
                        text = {
                            Text(
                                labels.getValue(shift), style = MaterialTheme.typography.bodyMedium,
                                color = if (move != null) c.text else c.textFaint,
                            )
                        },
                        onClick = { menu = false; move?.let(actions.onReorder) },
                        enabled = move != null,
                    )
                }
            } else {
                Text(
                    stringResource(R.string.backlog_reorder_off), Modifier.widthIn(max = 280.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
                )
            }
            HorizontalDivider(color = c.outline)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.backlog_select), style = MaterialTheme.typography.bodyMedium, color = c.text) },
                onClick = { menu = false; state.startSelecting(id) },
            )
        }
    }
}

/** A row's start: its checkbox while selecting, its handle otherwise. */
@Composable
private fun Lead(item: SprintItem, row: BacklogGroup?, state: BacklogState, actions: BacklogActions, width: Dp) {
    if (state.selecting) {
        Box(Modifier.width(width), contentAlignment = Alignment.Center) {
            AdoCheckbox(ToggleableState(item.id in state.selection.orEmpty()), { state.toggleSelected(item.id) })
        }
    } else {
        Handle(item, row, state, actions, width)
    }
}

/** What tapping and long-pressing a row do: open it, or select it. */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.rowClicks(item: SprintItem, state: BacklogState, onOpen: (WorkItem) -> Unit): Modifier = this.combinedClickable(
    onLongClick = { if (state.selecting) state.toggleSelected(item.id) else state.startSelecting(item.id) },
    onClick = { if (state.selecting) state.toggleSelected(item.id) else onOpen(item.item) },
).then(if (state.selecting) Modifier.semantics { selected = item.id in state.selection.orEmpty() } else Modifier)

/** A selected row stands out. */
@Composable
private fun Modifier.selectedBackground(item: SprintItem, state: BacklogState): Modifier =
    if (item.id in state.selection.orEmpty()) background(AdoTheme.colors.primaryContainer.copy(alpha = 0.5f)) else this

/**
 * A row in the list: while one is dragged it floats over the others, which slide to make room.
 */
@Composable
private fun LazyItemScope.DragRow(id: Int, group: Int?, drag: BacklogDrag, content: @Composable () -> Unit) {
    val c = AdoTheme.colors
    val dragging = drag.isDragging(id, group)
    val modifier = when {
        dragging -> Modifier.zIndex(1f).graphicsLayer {
            translationY = drag.translation()
            shadowElevation = 8.dp.toPx()
        }.background(c.surface)
        drag.id != null -> Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
        else -> Modifier
    }
    Box(modifier) { content() }
}

/** While a row is held near the top or the bottom, the list scrolls that way. */
@Composable
private fun AutoScroll(state: BacklogState) {
    val drag = state.drag
    val density = LocalDensity.current
    val edge = with(density) { 56.dp.toPx() }
    val speed = with(density) { 12.dp.toPx() }
    LaunchedEffect(drag.id) {
        while (drag.id != null) {
            val step = drag.edgeSpeed(edge, speed)
            if (step != 0f) {
                state.list.scrollBy(step)
                drag.settle()
            }
            withFrameNanos {}
        }
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
    /** The handle. */
    val lead = 36.dp
    val chevron = 40.dp
    val type = 112.dp
    val id = 48.dp
    val children = 76.dp
    val who = 52.dp
    val remaining = 52.dp
    /** A child's type and title start this much further in. */
    val indent = 12.dp
}

/**
 * The columns that depend on the sprint: the state's width, fitted to the longest state, and
 * whether the hours are shown at all (see [Backlog.tracksHours]).
 */
internal data class BacklogColumns(val state: Dp, val remaining: Boolean) {
    companion object {
        /** "STATE" in the header still fits. */
        val STATE_MIN = 72.dp
        /** Beyond this a state wraps on two lines in its chip, so that the title keeps its room. */
        val STATE_MAX = 170.dp
        /** The cell's start padding and the chip's own (8 dp a side), plus 1 dp against rounding. */
        private val STATE_EXTRA = 12.dp + 16.dp + 1.dp

        /** The state column for a sprint whose longest state is [longest] wide as chip text. */
        fun stateWidth(longest: Dp): Dp = (longest + STATE_EXTRA).coerceIn(STATE_MIN, STATE_MAX)
    }
}

/** The columns for [sprint]: its states measured as the chips show them. */
@Composable
private fun backlogColumns(sprint: Sprint): BacklogColumns {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    val states = sprint.items.map { it.item.state }.distinct()
    val longest = remember(states, style, density) {
        val px = states.maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1, density = density).size.width } ?: 0
        with(density) { px.toDp() }
    }
    return BacklogColumns(BacklogColumns.stateWidth(longest), Backlog.tracksHours(sprint.groups))
}

@Composable
private fun WideBacklog(
    sprint: Sprint,
    title: String,
    now: Instant,
    syncing: Boolean,
    actions: BacklogActions,
    state: BacklogState,
    edit: BacklogEditState,
) {
    val c = AdoTheme.colors
    val cols = backlogColumns(sprint)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 4.dp, end = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.selecting) {
                SelectionBar(state.selection.orEmpty().size, state.allSelected, state::toggleAllSelected, { state.onSelect(null) }, compact = false)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BatchButtons(sprint, state.selectedItems, edit, now, state.onBatch, actions.onSearchPeople, state.batchMenu)
                }
                return@Row
            }
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
            TextAction(stringResource(R.string.backlog_select), { state.startSelecting(null) })
            Spacer(Modifier.width(8.dp))
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
            HeaderRow(cols)
            AutoScroll(state)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state.list) {
                if (state.rows.isEmpty()) empty()
                state.rows.forEach { row ->
                    val open = row.parent.id in state.expanded && row.children.isNotEmpty()
                    item(key = row.parent.id) {
                        DragRow(row.parent.id, null, state.drag) {
                            WideRow(
                                row.parent, child = false, row = row, open = open, cols = cols,
                                onToggle = { state.onToggle(row.parent.id) }, state = state, onOpen = actions.onOpen,
                            ) { Lead(row.parent, null, state, actions, Col.lead) }
                        }
                    }
                    if (open) {
                        items(row.children, key = { "${row.parent.id}/${it.id}" }) {
                            DragRow(it.id, row.parent.id, state.drag) {
                                WideRow(it, child = true, row = null, open = false, cols = cols, onToggle = {}, state = state, onOpen = actions.onOpen) {
                                    Lead(it, row, state, actions, Col.lead)
                                }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = c.outline)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(totalsText(state.rows, cols.remaining), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
                Text(
                    stringResource(
                        when {
                            state.selecting -> R.string.backlog_select_hint
                            state.reorder -> R.string.backlog_hint
                            else -> R.string.backlog_reorder_off
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1,
                )
            }
        }
    }
}

/** "8 items · 12 children · 37 h remaining", without the hours for a team that does not track them. */
@Composable
private fun totalsText(rows: List<BacklogGroup>, withHours: Boolean): String {
    val totals = Backlog.totals(rows)
    return listOfNotNull(
        pluralStringResource(R.plurals.backlog_items, totals.items, totals.items),
        pluralStringResource(R.plurals.backlog_children, totals.children, totals.children),
        if (withHours) stringResource(R.string.backlog_remaining, hours(totals.remainingWork)) else null,
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
private fun HeaderRow(cols: BacklogColumns) {
    val c = AdoTheme.colors
    Row(
        Modifier.fillMaxWidth().height(28.dp).background(c.surfaceAlt).padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(Col.lead + Col.chevron))
        HeaderCell(stringResource(R.string.backlog_col_type), Col.type, TextAlign.Start, Modifier.padding(start = 10.dp))
        HeaderCell(stringResource(R.string.backlog_col_id), Col.id, TextAlign.End)
        Text(
            stringResource(R.string.backlog_col_title).uppercase(), Modifier.weight(1f).padding(start = 16.dp),
            style = MaterialTheme.typography.labelSmall, color = c.textMuted, maxLines = 1,
        )
        HeaderCell(stringResource(R.string.backlog_col_children), Col.children, TextAlign.End)
        HeaderCell(stringResource(R.string.backlog_col_state), cols.state, TextAlign.Start, Modifier.padding(start = 12.dp))
        HeaderCell(stringResource(R.string.backlog_col_who), Col.who, TextAlign.Center)
        if (cols.remaining) HeaderCell(stringResource(R.string.backlog_col_remaining), Col.remaining, TextAlign.End)
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
 * line, children done/total, state (on two lines if it is that long), assignee and hours left
 * (only for a team that tracks them). [row] is the parent's group, null for a child.
 */
@Composable
private fun WideRow(
    item: SprintItem,
    child: Boolean,
    row: BacklogGroup?,
    open: Boolean,
    cols: BacklogColumns,
    onToggle: () -> Unit,
    state: BacklogState,
    onOpen: (WorkItem) -> Unit,
    /** The handle, or the checkbox. */
    lead: @Composable () -> Unit,
) {
    val c = AdoTheme.colors
    val wi = item.item
    Column {
        Row(
            Modifier.fillMaxWidth().height(48.dp).selectedBackground(item, state).rowClicks(item, state, onOpen)
                .padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            lead()
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
            Box(Modifier.width(cols.state).padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
                StatusChip(wi.state, tone = stateTone(wi.state), maxLines = 2)
            }
            Box(Modifier.width(Col.who), contentAlignment = Alignment.Center) {
                val who = wi.assignedTo
                if (who != null) Avatar(who, description = who) else Text("—", style = MaterialTheme.typography.bodySmall, color = c.textFaint)
            }
            if (cols.remaining) {
                val remaining = if (row != null) row.remainingWork else item.remainingWork.takeUnless { item.closed }
                Text(
                    remaining?.let { hours(it) } ?: "—", Modifier.width(Col.remaining), style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted, maxLines = 1, textAlign = TextAlign.End,
                )
            }
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
private fun NarrowBacklog(
    sprint: Sprint,
    title: String,
    now: Instant,
    syncing: Boolean,
    actions: BacklogActions,
    state: BacklogState,
    edit: BacklogEditState,
) {
    val c = AdoTheme.colors
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.selecting) {
                SelectionBar(state.selection.orEmpty().size, state.allSelected, state::toggleAllSelected, { state.onSelect(null) }, compact = true)
                return@Row
            }
            IconButton(onClick = actions.onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = c.text)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val totals = Backlog.totals(state.rows)
                val left = if (Backlog.tracksHours(sprint.groups)) hours(totals.remainingWork) else null
                Text(
                    listOfNotNull(daysLeftShort(sprint, now), left).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { state.startSelecting(null) }) {
                Icon(AdoIcons.Checklist, stringResource(R.string.backlog_select), tint = c.textMuted)
            }
            RefreshButton(syncing, actions.onRefresh)
            FilterMenu(state)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.selecting) {
                // The selection's changes in place of the filters
                BatchButtons(sprint, state.selectedItems, edit, now, state.onBatch, actions.onSearchPeople, state.batchMenu)
            } else {
                AdoFilterChip(stringResource(R.string.backlog_team), !state.filter.mine, {
                    state.onFilter(state.filter.copy(mine = !state.filter.mine))
                })
                AdoFilterChip(stringResource(R.string.backlog_hide_closed), state.filter.hideClosed, {
                    state.onFilter(state.filter.copy(hideClosed = !state.filter.hideClosed))
                })
            }
        }
        AutoScroll(state)
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(c.surface), state = state.list, contentPadding = PaddingValues(bottom = 12.dp)) {
            if (state.rows.isEmpty()) empty()
            state.rows.forEach { row ->
                val open = row.parent.id in state.expanded && row.children.isNotEmpty()
                item(key = row.parent.id) {
                    DragRow(row.parent.id, null, state.drag) {
                        NarrowRow(row.parent, row, open, { state.onToggle(row.parent.id) }, state, actions.onOpen) {
                            Lead(row.parent, null, state, actions, NARROW_LEAD)
                        }
                    }
                }
                if (open) {
                    items(row.children, key = { "${row.parent.id}/${it.id}" }) {
                        DragRow(it.id, row.parent.id, state.drag) {
                            NarrowRow(it, null, false, {}, state, actions.onOpen) { Lead(it, row, state, actions, NARROW_LEAD) }
                        }
                    }
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

/** The handle's width on phones. */
private val NARROW_LEAD = 32.dp

/** A row of the phone list: handle, chevron, type bar, title and "Type · id · 1/3 children", state. */
@Composable
private fun NarrowRow(
    item: SprintItem,
    row: BacklogGroup?,
    open: Boolean,
    onToggle: () -> Unit,
    state: BacklogState,
    onOpen: (WorkItem) -> Unit,
    lead: @Composable () -> Unit,
) {
    val c = AdoTheme.colors
    val wi = item.item
    val child = row == null
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).selectedBackground(item, state).rowClicks(item, state, onOpen)
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            lead()
            if (child) {
                // Further in than the parent's type bar
                Spacer(Modifier.width(64.dp))
            } else {
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
            StatusChip(wi.state, Modifier.widthIn(max = BacklogColumns.STATE_MAX), tone = stateTone(wi.state), maxLines = 2)
        }
        HorizontalDivider(color = c.outline)
    }
}
