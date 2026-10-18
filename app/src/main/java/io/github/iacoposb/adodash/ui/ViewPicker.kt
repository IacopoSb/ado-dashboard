package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.data.DashboardView
import io.github.iacoposb.adodash.ui.components.AdoButton
import io.github.iacoposb.adodash.ui.components.AdoSwitch
import io.github.iacoposb.adodash.ui.components.FullScreenModalWidth
import io.github.iacoposb.adodash.ui.components.SearchField
import io.github.iacoposb.adodash.ui.components.highlight
import io.github.iacoposb.adodash.ui.components.scrollbar

/**
 * "What do you want to see?", at launch: a saved view on the left, a project's board on the
 * right, and whether to skip this screen from now on. Opening a board stores it as a view.
 */
@Composable
fun ViewPickerScreen(
    choices: ViewChoices,
    onOpen: (viewId: String?, board: BoardRef?, always: Boolean) -> Unit,
    onNewView: () -> Unit,
    onLoadBoards: () -> Unit = {},
    /** The editor over the picker, when "New view with filters" opened it. */
    editor: @Composable () -> Unit = {},
    editorOpen: Boolean = false,
) {
    val views = choices.views
    var selected by rememberSaveable(views.currentId) { mutableStateOf(views.currentId) }
    var always by rememberSaveable { mutableStateOf(views.alwaysOpen || !views.chosenAtLaunch) }
    LaunchedEffect(Unit) { onLoadBoards() }
    val boardSelected = (choices.boards.orEmpty().flatMap { p -> p.teams.map { BoardRef(p.project, it.name) } } + choices.recentBoards)
        .firstOrNull { it.viewId == selected } ?: views.find(selected)?.board
    val chosen: DashboardView = boardSelected?.let(views::board) ?: views.find(selected) ?: views.global

    val c = AdoTheme.colors
    Box(Modifier.fillMaxSize().background(c.bg)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .then(if (editorOpen) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            val wide = maxWidth >= FullScreenModalWidth
            // A fixed height on wide screens: the boards arriving never move the card or its button
            val card = if (wide) {
                Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 860.dp)
                    .height((maxHeight - 48.dp).coerceAtMost(720.dp))
                    .clip(RoundedCornerShape(24.dp)).background(c.surface)
            } else {
                Modifier.fillMaxSize().background(c.surface)
            }
            val header: @Composable () -> Unit = {
                Text(
                    stringResource(R.string.picker_title), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall, color = c.text,
                )
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.picker_subtitle), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                Spacer(Modifier.size(20.dp))
            }
            val myViews: @Composable (Modifier) -> Unit = { m ->
                Column(m.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickerLabel(stringResource(R.string.picker_my_views))
                    views.saved.forEach { v ->
                        ViewCard(v, v.id == selected && boardSelected == null) { selected = v.id }
                    }
                    TextButton(onClick = onNewView) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = c.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.picker_new_view), style = MaterialTheme.typography.titleSmall, color = c.primary)
                    }
                }
            }
            Column(card) {
                if (wide) {
                    Column(Modifier.padding(start = 28.dp, end = 28.dp, top = 28.dp)) { header() }
                    // Each column scrolls on its own
                    Row(Modifier.weight(1f).padding(horizontal = 28.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        val scroll = rememberScrollState()
                        myViews(Modifier.weight(1f).scrollbar(scroll, c.outline).verticalScroll(scroll))
                        PickerBoards(choices, boardSelected, { selected = it.viewId }, Modifier.weight(1f), fill = true)
                    }
                } else {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState())
                            .padding(start = 28.dp, end = 28.dp, top = 28.dp),
                    ) {
                        header()
                        myViews(Modifier.fillMaxWidth())
                        Spacer(Modifier.size(16.dp))
                        PickerBoards(choices, boardSelected, { selected = it.viewId }, Modifier.fillMaxWidth(), fill = false)
                    }
                }
                val alwaysRow: @Composable (Modifier) -> Unit = { m ->
                    Row(m.toggleable(always, role = Role.Switch) { always = it }, verticalAlignment = Alignment.CenterVertically) {
                        AdoSwitch(always, null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.picker_always_open), style = MaterialTheme.typography.bodyMedium, color = c.text)
                    }
                }
                val openButton: @Composable (Modifier) -> Unit = { m ->
                    AdoButton(
                        stringResource(R.string.picker_open, viewTitle(chosen)),
                        { onOpen(if (boardSelected == null) chosen.id else null, boardSelected, always) },
                        m,
                    )
                }
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp)) {
                    if (maxWidth < STACKED_BELOW) {
                        // A phone: a long view or board name would leave the switch no room beside it
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            alwaysRow(Modifier.fillMaxWidth())
                            openButton(Modifier.align(Alignment.End))
                        }
                    } else {
                        val half = maxWidth / 2
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            alwaysRow(Modifier.weight(1f))
                            Spacer(Modifier.width(16.dp))
                            openButton(Modifier.widthIn(max = half))
                        }
                    }
                }
            }
        }
        editor()
    }
}

/** Narrower than this, "Always open" and "Open …" go one above the other. */
private val STACKED_BELOW = 560.dp

@Composable
private fun PickerLabel(text: String) = Text(
    text.uppercase(), Modifier.padding(bottom = 4.dp).semantics { heading() },
    style = MaterialTheme.typography.labelSmall, color = AdoTheme.colors.textMuted,
)

/** A saved view: icon, name and what it shows; tinted with a tick when chosen. */
@Composable
private fun ViewCard(view: DashboardView, selected: Boolean, onClick: () -> Unit) {
    val c = AdoTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(shape)
            .background(if (selected) c.primaryContainer else c.surface)
            .border(1.dp, if (selected) c.primary else c.outline, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(viewIcon(view), null, Modifier.size(20.dp), tint = if (selected) c.onPrimaryContainer else c.textMuted)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                viewTitle(view), style = MaterialTheme.typography.titleSmall,
                color = if (selected) c.onPrimaryContainer else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                viewDescription(view), style = MaterialTheme.typography.bodySmall,
                color = if (selected) c.onPrimaryContainer else c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = c.onPrimaryContainer)
        }
    }
}

/**
 * "Project and board": a search, the recent boards, then the projects with their teams as radio
 * rows (the current sprint on the right), only the chosen board's project open at first.
 * [fill]: the list takes the rest of the height and scrolls inside it.
 */
@Composable
private fun PickerBoards(
    choices: ViewChoices,
    selected: BoardRef?,
    onSelect: (BoardRef) -> Unit,
    modifier: Modifier,
    fill: Boolean,
) {
    val c = AdoTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable(stateSaver = projectsSaver) { mutableStateOf(initialExpanded(selected)) }
    val boards = choices.boards
    val menu = boardMenu(boards.orEmpty(), choices.recentBoards, query, expanded)
    val settled = rememberSettled(boards == null)
    val pick = { ref: BoardRef -> if (settled()) onSelect(ref) }
    val sprints = boards.orEmpty().associate { p -> p.project to p.teams.associate { it.name to it.sprint } }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PickerLabel(stringResource(R.string.picker_project_board))
        SearchField(query, { query = it }, stringResource(R.string.boards_search), Modifier.fillMaxWidth())
        val scroll = rememberScrollState()
        Column(
            (if (fill) Modifier.weight(1f).scrollbar(scroll, c.outline).verticalScroll(scroll) else Modifier)
                .fillMaxWidth().selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (menu.recent.isNotEmpty()) {
                BoardCard {
                    SubLabel(stringResource(R.string.boards_recent))
                    menu.recent.forEach { ref ->
                        BoardRadio(
                            AnnotatedString(stringResource(R.string.view_board_title, ref.project, ref.team)),
                            sprints[ref.project]?.get(ref.team), ref == selected,
                        ) { pick(ref) }
                    }
                }
            }
            when {
                boards == null -> BoardsLoading(Modifier.fillMaxWidth())
                menu.groups.isEmpty() -> BoardsMessage(choices, menu, Modifier.padding(vertical = 12.dp))
                else -> menu.groups.forEach { group ->
                    BoardCard {
                        ProjectHeader(group, menu.query, onToggle = {
                            expanded = if (group.project in expanded) expanded - group.project else expanded + group.project
                        })
                        if (group.expanded) {
                            group.teams.forEach { team ->
                                val ref = BoardRef(group.project, team.name)
                                BoardRadio(highlight(team.name, menu.query.takeIf { !group.projectMatch }), team.sprint, ref == selected) {
                                    pick(ref)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal val projectsSaver = listSaver<Set<String>, String>({ it.toList() }, { it.toSet() })

@Composable
private fun BoardCard(content: @Composable () -> Unit) = Column(
    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AdoTheme.colors.surfaceAlt).padding(vertical = 4.dp),
) { content() }

@Composable
private fun SubLabel(text: String) = Text(
    text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { heading() },
    style = MaterialTheme.typography.titleSmall, color = AdoTheme.colors.textMuted,
)

@Composable
private fun BoardRadio(name: AnnotatedString, sprint: String?, on: Boolean, onClick: () -> Unit) {
    val c = AdoTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).selectable(on, role = Role.RadioButton, onClick = onClick)
            .padding(start = 2.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(on, null, Modifier.padding(horizontal = 10.dp), colors = RadioButtonDefaults.colors(
            selectedColor = c.primary, unselectedColor = c.textMuted,
        ))
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        sprint?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = c.textFaint, maxLines = 1)
        }
    }
}
