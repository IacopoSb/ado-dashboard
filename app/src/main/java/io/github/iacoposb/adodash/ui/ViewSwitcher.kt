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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.ui.components.AdoIcons
import io.github.iacoposb.adodash.ui.components.DismissLayer

/**
 * The menu under the app bar's title: the saved views, the boards, and "Edit view" (the one
 * open) or "New view". Drawn over the dashboard, below the title it belongs to.
 */
@Composable
fun ViewSwitcher(
    choices: ViewChoices,
    onOpenView: (String) -> Unit,
    onOpenBoard: (BoardRef) -> Unit,
    onEdit: () -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AdoTheme.colors
    val current = choices.views.current
    Box {
        DismissLayer(onDismiss)
        BoxWithConstraints(Modifier.padding(start = 8.dp, top = 64.dp, end = 8.dp, bottom = 8.dp)) {
            val title = stringResource(R.string.action_choose_view)
            Column(
                Modifier.width(380.dp.coerceAtMost(maxWidth)).heightIn(max = maxHeight)
                    .shadow(8.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(c.surface)
                    // Taps between the rows stay in the menu
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .semantics { paneTitle = title },
            ) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    MenuLabel(stringResource(R.string.switcher_views))
                    choices.views.saved.forEach { v ->
                        MenuRow(viewIcon(v), viewTitle(v), v.id == current.id) { onOpenView(v.id) }
                    }
                    MenuLabel(stringResource(R.string.switcher_boards))
                    val boards = choices.boards
                    when {
                        boards == null -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = c.primary, strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.picker_loading_boards), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                        }
                        boards.isEmpty() -> Text(
                            stringResource(R.string.picker_no_boards), Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
                        )
                        else -> boards.forEach { p ->
                            p.teams.forEach { team ->
                                val ref = BoardRef(p.project, team.name)
                                MenuRow(
                                    AdoIcons.Flag, stringResource(R.string.view_board_title, p.project, team.name),
                                    current.board == ref,
                                ) { onOpenBoard(ref) }
                            }
                        }
                    }
                }
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

@Composable
private fun MenuLabel(text: String) = Text(
    text.uppercase(), Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
    style = MaterialTheme.typography.labelSmall, color = AdoTheme.colors.textMuted,
)

@Composable
private fun MenuRow(icon: ImageVector, text: String, selected: Boolean, onClick: () -> Unit) {
    val c = AdoTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
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
        if (selected) Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = c.onPrimaryContainer)
    }
}
