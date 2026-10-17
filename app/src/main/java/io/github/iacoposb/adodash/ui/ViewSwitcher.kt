package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.ui.components.AdoIcons
import io.github.iacoposb.adodash.ui.components.DismissLayer
import io.github.iacoposb.adodash.ui.components.SearchField
import io.github.iacoposb.adodash.ui.components.highlight
import io.github.iacoposb.adodash.ui.components.scrollbar

/**
 * The menu under the app bar's title: the saved views, the boards, and "Edit view" (the one
 * open) or "New view". Drawn over the dashboard, below the title it belongs to. The views,
 * the boards' search and the actions stay in place; only the boards scroll: the recent ones,
 * then each project, open only for the board shown until tapped.
 */
@Composable
fun ViewSwitcher(
    choices: ViewChoices,
    onOpenView: (String) -> Unit,
    onOpenBoard: (BoardRef) -> Unit,
    onEdit: () -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
    /** For the screenshots: a search already typed. */
    initialQuery: String = "",
) {
    val c = AdoTheme.colors
    val current = choices.views.current
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var expanded by rememberSaveable(stateSaver = projectsSaver) { mutableStateOf(initialExpanded(current.board)) }
    val boards = choices.boards
    val menu = boardMenu(boards.orEmpty(), choices.recentBoards, query, expanded)
    val settled = rememberSettled(boards == null)
    val open = { ref: BoardRef -> if (settled()) onOpenBoard(ref) }
    Box {
        DismissLayer(onDismiss)
        BoxWithConstraints(Modifier.padding(start = 8.dp, top = 64.dp, end = 8.dp, bottom = 8.dp)) {
            val title = stringResource(R.string.action_choose_view)
            Column(
                Modifier.width(400.dp.coerceAtMost(maxWidth)).heightIn(max = maxHeight.coerceAtMost(MENU_MAX_HEIGHT))
                    .shadow(8.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(c.surface)
                    // Taps between the rows stay in the menu
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .semantics { paneTitle = title },
            ) {
                Column(Modifier.padding(top = 8.dp)) {
                    MenuLabel(stringResource(R.string.switcher_views))
                    choices.views.saved.forEach { v ->
                        MenuRow(viewIcon(v), AnnotatedString(viewTitle(v)), v.id == current.id) { onOpenView(v.id) }
                    }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp), color = c.outline)
                MenuLabel(stringResource(R.string.switcher_boards))
                SearchField(
                    query, { query = it }, stringResource(R.string.boards_search),
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
                val scroll = rememberScrollState()
                Column(
                    Modifier.weight(1f, fill = false).scrollbar(scroll, c.outline, gap = (-6).dp).verticalScroll(scroll)
                        .padding(vertical = 4.dp),
                ) {
                    if (menu.recent.isNotEmpty()) {
                        SubLabel(stringResource(R.string.boards_recent))
                        menu.recent.forEach { ref ->
                            MenuRow(
                                AdoIcons.Flag, AnnotatedString(stringResource(R.string.view_board_title, ref.project, ref.team)),
                                current.board == ref,
                            ) { open(ref) }
                        }
                    }
                    when {
                        boards == null -> BoardsLoading(Modifier.fillMaxWidth().padding(horizontal = 16.dp), rowHeight = 36.dp, rows = 3)
                        menu.groups.isEmpty() -> BoardsMessage(choices, menu, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                        else -> menu.groups.forEach { group ->
                            ProjectHeader(group, menu.query, modifier = Modifier.padding(horizontal = 8.dp), onToggle = {
                                expanded = if (group.project in expanded) expanded - group.project else expanded + group.project
                            })
                            if (group.expanded) {
                                group.teams.forEach { team ->
                                    val ref = BoardRef(group.project, team.name)
                                    MenuRow(
                                        AdoIcons.Flag, highlight(team.name, menu.query.takeIf { !group.projectMatch }),
                                        current.board == ref, indent = 20.dp, trailing = team.sprint,
                                    ) { open(ref) }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = c.outline)
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    TextButton(onClick = onEdit) {
                        Icon(AdoIcons.FilterList, null, Modifier.size(18.dp), tint = c.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_edit_view), style = MaterialTheme.typography.titleSmall, color = c.primary)
                    }
                    TextButton(onClick = onNew) {
                        Text(stringResource(R.string.action_new_view), style = MaterialTheme.typography.titleSmall, color = c.primary)
                    }
                }
            }
        }
    }
}

/** Taller than this the menu would cover most of the dashboard: the boards scroll instead. */
private val MENU_MAX_HEIGHT = 640.dp

@Composable
private fun MenuLabel(text: String) = Text(
    text.uppercase(), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
    style = MaterialTheme.typography.labelSmall, color = AdoTheme.colors.textMuted,
)

@Composable
private fun SubLabel(text: String) = Text(
    text, Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp).semantics { heading() },
    style = MaterialTheme.typography.labelMedium, color = AdoTheme.colors.textFaint,
)

@Composable
private fun MenuRow(
    icon: ImageVector,
    text: AnnotatedString,
    selected: Boolean,
    indent: Dp = 0.dp,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    val c = AdoTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp + indent, end = 8.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
            .then(if (selected) Modifier.background(c.primaryContainer) else Modifier)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (selected) c.onPrimaryContainer else c.textMuted)
        Spacer(Modifier.width(16.dp))
        Text(
            text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = if (selected) c.onPrimaryContainer else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (selected) c.onPrimaryContainer else c.textFaint, maxLines = 1)
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = c.onPrimaryContainer)
        }
    }
}
