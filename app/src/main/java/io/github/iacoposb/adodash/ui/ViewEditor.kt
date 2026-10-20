package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.data.ActivityKind
import io.github.iacoposb.adodash.data.Condition
import io.github.iacoposb.adodash.data.DashboardView
import io.github.iacoposb.adodash.data.PrScope
import io.github.iacoposb.adodash.data.PullRequestsPanel
import io.github.iacoposb.adodash.data.WiField
import io.github.iacoposb.adodash.data.WiOrder
import io.github.iacoposb.adodash.ui.components.AdoButton
import io.github.iacoposb.adodash.ui.components.AdoFilterChip
import io.github.iacoposb.adodash.ui.components.AdoSwitch
import io.github.iacoposb.adodash.ui.components.AdoTextField
import io.github.iacoposb.adodash.ui.components.ButtonStyle
import io.github.iacoposb.adodash.ui.components.DropdownField
import io.github.iacoposb.adodash.ui.components.DropdownOption
import io.github.iacoposb.adodash.ui.components.ModalOverlay
import io.github.iacoposb.adodash.ui.components.SearchField
import io.github.iacoposb.adodash.ui.components.SwitchRow
import io.github.iacoposb.adodash.ui.components.TextAction

/** A panel of a view, to open the editor on it; [REPOSITORIES] is a board's own section. */
enum class ViewPanel { WORK_ITEMS, PULL_REQUESTS, ACTIVITY, REPOSITORIES }

/**
 * The editor open on a view: [isNew] for one still to be saved, [focus] the panel to scroll to,
 * [pickRepositories] with the repositories' menu open (a board's, from its empty panel).
 */
data class EditorTarget(
    val view: DashboardView,
    val isNew: Boolean = false,
    val focus: ViewPanel? = null,
    val pickRepositories: Boolean = false,
)

/** A view as its JSON, so that what is being edited survives a rotation. */
internal val viewSaver: Saver<DashboardView, String> = Saver(
    save = { DashboardView.json.encodeToString(DashboardView.serializer(), it) },
    restore = { runCatching { DashboardView.json.decodeFromString(DashboardView.serializer(), it) }.getOrNull() },
)

/** The editor open, if any, across a rotation: the view as JSON, whether new, the panel in focus. */
internal val editorTargetSaver: Saver<EditorTarget?, Any> = Saver(
    save = { target ->
        target?.let { arrayListOf(with(viewSaver) { save(it.view) }, it.isNew, it.focus?.name.orEmpty()) }
    },
    restore = { saved ->
        val (view, isNew, focus) = saved as List<*>
        viewSaver.restore(view as String)?.let { v ->
            EditorTarget(v, isNew as Boolean, ViewPanel.entries.firstOrNull { it.name == focus })
        }
    },
)

/**
 * "Edit view": the name, then one section per panel with its switch and its conditions.
 * Nothing changes until "Save". The global view can be edited but not deleted; a board keeps
 * its name ("Project › Team") and the scope of its team.
 */
@Composable
fun ViewEditor(
    target: EditorTarget,
    options: EditorOptions,
    onSave: (DashboardView) -> Unit,
    onDelete: (DashboardView) -> Unit,
    onDismiss: () -> Unit,
    /** For the screenshots: the deletion's confirmation shown. */
    initialConfirmDelete: Boolean = false,
) {
    val view = target.view
    val defaultName = viewTitle(view)
    var draft by rememberSaveable(view.id, stateSaver = viewSaver) { mutableStateOf(view) }
    var confirmDelete by rememberSaveable(view.id) { mutableStateOf(initialConfirmDelete) }
    var name by rememberSaveable(view.id) { mutableStateOf(if (target.isNew) "" else defaultName) }
    val title = stringResource(if (target.isNew) R.string.editor_title_new else R.string.editor_title_edit)
    val canSave = draft.showsSomething && (view.board != null || view.isGlobal || name.isNotBlank())
    fun save() {
        val newName = when {
            view.board != null -> ""
            view.isGlobal && (name.isBlank() || name == defaultName && view.name.isBlank()) -> ""
            else -> name.trim()
        }
        onSave(draft.copy(name = newName))
    }

    ModalOverlay(title, onDismiss) { fullScreen ->
        val c = AdoTheme.colors
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = c.text)
                Text(stringResource(R.string.editor_subtitle), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.action_close), tint = c.textMuted) }
        }

        val scroll = rememberScrollState()
        val positions = remember { mutableStateMapOf<ViewPanel, Int>() }
        var pickOpen by remember { mutableStateOf(target.pickRepositories && target.focus == null) }
        LaunchedEffect(target.focus) {
            val focus = target.focus ?: return@LaunchedEffect
            withFrameNanos {}
            positions[focus]?.let { scroll.scrollTo(it) }
            // Opened once in place: a menu opened before the scroll would stay where the field was
            pickOpen = target.pickRepositories
        }
        fun Modifier.at(panel: ViewPanel) = onGloballyPositioned { positions[panel] = it.positionInParent().y.toInt() }

        Column(
            Modifier.weight(1f, fill = fullScreen).verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AdoTextField(
                name, { name = it }, stringResource(R.string.editor_name),
                Modifier.widthIn(max = 420.dp).padding(bottom = 4.dp),
                enabled = view.board == null,
                hint = if (view.board != null) stringResource(R.string.editor_board_hint) else null,
            )
            // A board's repositories: its pull requests and its pipelines, whatever the panels show
            draft.board?.let { board ->
                Column(
                    Modifier.at(ViewPanel.REPOSITORIES).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AdoTheme.colors.surfaceAlt)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.editor_board_repositories), style = MaterialTheme.typography.titleMedium, color = c.text)
                    Text(
                        stringResource(R.string.editor_board_repositories_hint, board.project),
                        style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                    )
                    RepositoryCondition(draft, options, initiallyOpen = pickOpen) { draft = it }
                }
            }
            EditorSection(
                stringResource(R.string.panel_work_items), draft.workItems.enabled,
                { draft = draft.copy(workItems = draft.workItems.copy(enabled = it)) }, Modifier.at(ViewPanel.WORK_ITEMS),
            ) { WorkItemConditions(draft, options) { draft = it } }
            EditorSection(
                stringResource(R.string.panel_pull_requests), draft.pullRequests.enabled,
                { draft = draft.copy(pullRequests = draft.pullRequests.copy(enabled = it)) }, Modifier.at(ViewPanel.PULL_REQUESTS),
            ) { PullRequestSettings(draft, options) { draft = it } }
            EditorSection(
                stringResource(R.string.panel_activity), draft.activity.enabled,
                { draft = draft.copy(activity = draft.activity.copy(enabled = it)) }, Modifier.at(ViewPanel.ACTIVITY),
            ) { ActivitySettings(draft) { draft = it } }
        }

        HorizontalDivider(color = c.outline)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!view.isGlobal && view.board == null && !target.isNew) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.action_delete_view), style = MaterialTheme.typography.labelLarge, color = c.primary)
                }
            }
            Spacer(Modifier.weight(1f))
            AdoButton(stringResource(R.string.action_cancel), onDismiss, style = ButtonStyle.Outlined)
            AdoButton(stringResource(R.string.action_save), ::save, icon = Icons.Filled.Check, enabled = canSave)
        }
    }
    if (confirmDelete) {
        val c = AdoTheme.colors
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = c.surface,
            title = { Text(stringResource(R.string.dialog_delete_view_title, defaultName), color = c.text) },
            text = {
                Text(stringResource(R.string.dialog_delete_view_text), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            },
            confirmButton = {
                TextAction(stringResource(R.string.action_delete), {
                    confirmDelete = false
                    onDelete(view)
                }, color = c.danger)
            },
            dismissButton = { TextAction(stringResource(R.string.action_cancel), { confirmDelete = false }) },
        )
    }
}

/** A panel's section: title and switch, its settings below while on. */
@Composable
private fun EditorSection(
    title: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = AdoTheme.colors
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceAlt).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SwitchRow(title, enabled, onEnabledChange, titleStyle = MaterialTheme.typography.titleMedium)
        if (enabled) content()
    }
}

/** Field, operator and values in a row, with the button that removes it. */
@Composable
private fun ConditionRow(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
    third: @Composable (Modifier) -> Unit,
    onRemove: (() -> Unit)?,
) {
    val remove: @Composable () -> Unit = {
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, stringResource(R.string.action_remove_condition), tint = AdoTheme.colors.textMuted)
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
    }
    BoxWithConstraints {
        if (maxWidth >= NARROW_ROW) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                first(Modifier.weight(0.27f))
                second(Modifier.weight(0.2f))
                third(Modifier.weight(0.53f))
                remove()
            }
        } else {
            // Narrow: field and operator above, the values below at full width
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    first(Modifier.weight(0.55f))
                    second(Modifier.weight(0.45f))
                    remove()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    third(Modifier.weight(1f))
                    Spacer(Modifier.width(48.dp))
                }
            }
        }
    }
}

/** Below this a condition takes two rows. */
private val NARROW_ROW = 560.dp

private val WiField.label
    get() = when (this) {
        WiField.TYPE -> R.string.field_type
        WiField.ASSIGNED_TO -> R.string.field_assigned_to
        WiField.STATE -> R.string.field_state
    }

@Composable
private fun valueLabel(value: String): String = when (value) {
    Condition.ME -> stringResource(R.string.value_me)
    Condition.ANYONE -> stringResource(R.string.value_anyone)
    Condition.CLOSED -> stringResource(R.string.value_closed)
    else -> value
}

@Composable
private fun WorkItemConditions(draft: DashboardView, options: EditorOptions, onChange: (DashboardView) -> Unit) {
    val panel = draft.workItems
    fun set(conditions: List<Condition>) = onChange(draft.copy(workItems = panel.copy(conditions = conditions)))
    panel.conditions.forEachIndexed { index, condition ->
        fun replace(new: Condition) = set(panel.conditions.toMutableList().also { it[index] = new })
        val fieldLabel = stringResource(R.string.editor_field)
        ConditionRow(
            first = { m ->
                DropdownField(stringResource(condition.field.label), fieldLabel, m) { close ->
                    WiField.entries.forEach { f ->
                        DropdownOption(stringResource(f.label), f == condition.field) {
                            close()
                            if (f != condition.field) {
                                replace(if (f == WiField.ASSIGNED_TO) Condition.assignedTo(Condition.ME) else Condition(f))
                            }
                        }
                    }
                }
            },
            second = { m ->
                val operators = if (condition.field == WiField.ASSIGNED_TO) listOf(false) else listOf(false, true)
                fun opLabel(negate: Boolean) = when {
                    condition.field == WiField.ASSIGNED_TO -> R.string.op_is
                    negate -> R.string.op_is_not_one_of
                    else -> R.string.op_is_one_of
                }
                DropdownField(stringResource(opLabel(condition.negate)), stringResource(R.string.editor_operator), m) { close ->
                    operators.forEach { negate ->
                        DropdownOption(stringResource(opLabel(negate)), negate == condition.negate) {
                            close()
                            replace(condition.copy(negate = negate))
                        }
                    }
                }
            },
            third = { m ->
                val any = stringResource(R.string.value_any)
                val text = condition.values.map { valueLabel(it) }.joinToString(", ").ifEmpty { any }
                DropdownField(text, stringResource(R.string.editor_values), m) { close ->
                    when (condition.field) {
                        WiField.ASSIGNED_TO -> (listOf(Condition.ME, Condition.ANYONE) + options.people).forEach { who ->
                            DropdownOption(valueLabel(who), condition.values.firstOrNull() == who) {
                                close()
                                replace(condition.copy(values = listOf(who)))
                            }
                        }
                        WiField.TYPE, WiField.STATE -> {
                            val choices = if (condition.field == WiField.TYPE) options.types else listOf(Condition.CLOSED) + options.states
                            (choices + condition.values.filter { v -> choices.none { it.equals(v, true) } }).forEach { v ->
                                val on = condition.values.any { it.equals(v, ignoreCase = true) }
                                DropdownOption(valueLabel(v), on) {
                                    replace(condition.copy(values = if (on) condition.values.filterNot { it.equals(v, true) } else condition.values + v))
                                }
                            }
                        }
                    }
                }
            },
            onRemove = { set(panel.conditions.filterIndexed { i, _ -> i != index }) },
        )
    }
    AddAction(stringResource(R.string.editor_add_condition)) {
        val unused = WiField.entries.firstOrNull { f -> panel.conditions.none { it.field == f } } ?: WiField.TYPE
        set(panel.conditions + if (unused == WiField.ASSIGNED_TO) Condition.assignedTo(Condition.ME) else Condition(unused))
    }
    fun orderLabel(order: WiOrder) = when (order) {
        WiOrder.PRIORITY -> R.string.order_priority
        WiOrder.CHANGED -> R.string.order_changed
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StaticField(stringResource(R.string.editor_sort_by), Modifier.weight(0.27f))
        DropdownField(stringResource(orderLabel(panel.order)), stringResource(R.string.editor_sort_by), Modifier.weight(0.73f)) { close ->
            WiOrder.entries.forEach { o ->
                DropdownOption(stringResource(orderLabel(o)), o == panel.order) {
                    close()
                    onChange(draft.copy(workItems = panel.copy(order = o)))
                }
            }
        }
        Spacer(Modifier.width(48.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PullRequestSettings(draft: DashboardView, options: EditorOptions, onChange: (DashboardView) -> Unit) {
    val c = AdoTheme.colors
    val panel = draft.pullRequests
    fun set(p: PullRequestsPanel) = onChange(draft.copy(pullRequests = p))
    Text(stringResource(R.string.editor_show), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            PrScope.TO_REVIEW to R.string.pr_show_to_review,
            PrScope.MINE to R.string.pr_show_mine,
            PrScope.ALL to R.string.pr_show_all,
        ).forEach { (scope, label) ->
            val on = scope in panel.show
            AdoFilterChip(stringResource(label), on, { set(panel.copy(show = if (on) panel.show - scope else panel.show + scope)) })
        }
    }
    // A board chooses its repositories in a section of its own, above
    if (draft.board == null) RepositoryCondition(draft, options, initiallyOpen = false, onChange)
    SwitchRow(stringResource(R.string.editor_include_drafts), panel.drafts, { set(panel.copy(drafts = it)) })
}

/**
 * "Repository is one of": none is all of them, or none at all on a board, whose choices are
 * its project's repositories. A long list has a search.
 */
@Composable
private fun RepositoryCondition(
    draft: DashboardView,
    options: EditorOptions,
    initiallyOpen: Boolean,
    onChange: (DashboardView) -> Unit,
) {
    val c = AdoTheme.colors
    val panel = draft.pullRequests
    fun set(repositories: Set<String>) = onChange(draft.copy(pullRequests = panel.copy(repositories = repositories)))
    val none = stringResource(if (draft.board != null) R.string.value_none_chosen else R.string.value_all)
    ConditionRow(
        first = { m -> StaticField(stringResource(R.string.editor_repository), m) },
        second = { m -> StaticField(stringResource(R.string.op_is_one_of), m) },
        third = { m ->
            val text = panel.repositories.sortedBy { it.lowercase() }.joinToString(", ").ifEmpty { none }
            // Recreated when asked to open, which happens once the editor has scrolled to it
            key(initiallyOpen) { DropdownField(text, stringResource(R.string.editor_repository), m, initiallyOpen = initiallyOpen) {
                val offered = options.repositoriesFor(draft)
                val choices = offered + panel.repositories.filter { r -> offered.none { it.equals(r, true) } }.sortedBy { it.lowercase() }
                var query by remember { mutableStateOf("") }
                Column(Modifier.width(REPOSITORY_MENU_WIDTH)) {
                    if (choices.size > REPOSITORY_SEARCH_FROM) {
                        SearchField(
                            query, { query = it }, stringResource(R.string.editor_search_repositories),
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    val shown = choices.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
                    when {
                        choices.isEmpty() && options.repositoriesLoading -> Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = c.primary, strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.editor_repositories_loading), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                        }
                        choices.isEmpty() && draft.board == null -> DropdownOption(none, true) {}
                        shown.isEmpty() -> Text(
                            stringResource(if (choices.isEmpty()) R.string.editor_no_repositories else R.string.editor_no_repositories_match),
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
                        )
                    }
                    // The list scrolls under the search, and the menu fits below its field
                    Column(Modifier.heightIn(max = REPOSITORY_LIST_MAX).verticalScroll(rememberScrollState())) {
                        shown.forEach { repo ->
                            val on = panel.repositories.any { it.equals(repo, ignoreCase = true) }
                            DropdownOption(repo, on) {
                                set(if (on) panel.repositories.filterNot { it.equals(repo, true) }.toSet() else panel.repositories + repo)
                            }
                        }
                    }
                }
            } }
        },
        onRemove = if (panel.repositories.isEmpty()) null else ({ set(emptySet()) }),
    )
}

/** More repositories than this and their menu has a search. */
private const val REPOSITORY_SEARCH_FROM = 8

private val REPOSITORY_MENU_WIDTH = 320.dp

/** About seven repositories, then the list scrolls. */
private val REPOSITORY_LIST_MAX = 340.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivitySettings(draft: DashboardView, onChange: (DashboardView) -> Unit) {
    val panel = draft.activity
    Text(stringResource(R.string.editor_show), style = MaterialTheme.typography.bodySmall, color = AdoTheme.colors.textMuted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ActivityKind.offered.forEach { kind ->
            val on = kind in panel.kinds
            AdoFilterChip(stringResource(activityLabel(kind)), on, {
                onChange(draft.copy(activity = panel.copy(kinds = if (on) panel.kinds - kind else panel.kinds + kind)))
            })
        }
    }
}

/** A box like a [DropdownField] that cannot change: the fixed part of a condition. */
@Composable
private fun StaticField(text: String, modifier: Modifier) {
    val c = AdoTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier.heightIn(min = 48.dp).clip(shape).background(c.surface).border(1.dp, c.outline, shape).padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1)
    }
}

@Composable
private fun AddAction(text: String, onClick: () -> Unit) {
    val c = AdoTheme.colors
    TextButton(onClick = onClick) {
        Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = c.primary)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = c.primary)
    }
}
