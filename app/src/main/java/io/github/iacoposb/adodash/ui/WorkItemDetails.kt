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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.FieldChange
import io.github.iacoposb.adodash.api.LinkKind
import io.github.iacoposb.adodash.api.Person
import io.github.iacoposb.adodash.api.StateInfo
import io.github.iacoposb.adodash.api.WiFields
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.api.WorkItemDetail
import io.github.iacoposb.adodash.ui.components.AdoIcons
import io.github.iacoposb.adodash.ui.components.Avatar
import io.github.iacoposb.adodash.ui.components.DropdownField
import io.github.iacoposb.adodash.ui.components.DropdownOption
import io.github.iacoposb.adodash.ui.components.ModalOverlay
import io.github.iacoposb.adodash.ui.components.SearchField
import io.github.iacoposb.adodash.ui.components.StatusChip
import io.github.iacoposb.adodash.ui.components.TextAction
import io.github.iacoposb.adodash.ui.components.Tone
import io.github.iacoposb.adodash.ui.components.stateTone
import java.text.DecimalFormat
import java.time.Instant

/** What the work item details do; all of it goes to [WorkItemController]. */
class WorkItemActions(
    val onOpenUrl: (String) -> Unit = {},
    val onOpenWorkItem: (Int) -> Unit = {},
    val onChange: (FieldChange) -> Unit = {},
    val onSearchPeople: (String) -> Unit = {},
    val onReload: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onClose: () -> Unit = {},
)

/**
 * The details of a work item: a modal over the dashboard on tablets (description and links on
 * the left, the fields to change on the right), a page of its own on narrow screens. Changes
 * save at once; [initialMenu] opens a field's menu from the start, for the screenshots.
 */
@Composable
fun WorkItemDetailsModal(
    modal: WorkItemModal,
    now: Instant,
    actions: WorkItemActions,
    initialMenu: String? = null,
    /** What the open menu's search starts with, for the screenshots. */
    initialQuery: String = "",
) {
    val page = modal.page
    val item = page.item
    val title = item?.title ?: stringResource(R.string.wi_loading)
    ModalOverlay(title, onDismiss = actions.onClose, onBack = actions.onBack, maxWidth = 1040.dp, maxHeight = 700.dp) { fullScreen ->
        if (fullScreen) PhonePage(modal, now, actions, initialMenu, initialQuery) else TabletModal(modal, now, actions, initialMenu, initialQuery)
    }
}

@Composable
private fun ColumnScope.TabletModal(modal: WorkItemModal, now: Instant, actions: WorkItemActions, initialMenu: String?, initialQuery: String) {
    val c = AdoTheme.colors
    val page = modal.page
    val item = page.item
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
        if (modal.canGoBack) {
            IconButton(onClick = actions.onBack, Modifier.padding(end = 4.dp).size(40.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = c.textMuted)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (item != null) Chips(item, withState = true)
            Text(
                item?.title ?: stringResource(R.string.wi_loading), Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (item != null) {
                Text(
                    meta(item, page.saved, now), style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item != null) {
            IconButton(onClick = { actions.onOpenUrl(item.url) }) {
                Icon(AdoIcons.OpenInNew, stringResource(R.string.action_open_build), tint = c.textMuted)
            }
        }
        IconButton(onClick = actions.onClose) { Icon(Icons.Filled.Close, stringResource(R.string.action_close), tint = c.textMuted) }
    }
    HorizontalDivider(color = c.outline)
    Row(Modifier.weight(1f).fillMaxWidth()) {
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Body(page, actions)
        }
        Column(
            Modifier.width(320.dp).fillMaxHeight().background(c.surfaceAlt).verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Fields(modal, now, actions, initialMenu, initialQuery, twoPerRow = false)
            Text(stringResource(R.string.wi_saved_at_once), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
    }
}

@Composable
private fun ColumnScope.PhonePage(modal: WorkItemModal, now: Instant, actions: WorkItemActions, initialMenu: String?, initialQuery: String) {
    val c = AdoTheme.colors
    val page = modal.page
    val item = page.item
    Row(Modifier.fillMaxWidth().background(c.bg).padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = actions.onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = c.text)
        }
        Column(Modifier.weight(1f)) {
            Text(
                item?.let { "${it.type} ${it.id}" } ?: stringResource(R.string.wi_loading), Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (item != null) {
                Text(
                    "${areaLabel(item.areaPath)} · ${ago(item.changedAt, now)}", style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (item != null) {
            IconButton(onClick = { actions.onOpenUrl(item.url) }) {
                Icon(AdoIcons.OpenInNew, stringResource(R.string.action_open_build), tint = c.textMuted)
            }
        }
    }
    Column(
        Modifier.weight(1f).fillMaxWidth().background(c.bg).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (item != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chips(item, withState = false)
                Text(item.title, style = MaterialTheme.typography.headlineSmall, color = c.text)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Fields(modal, now, actions, initialMenu, initialQuery, twoPerRow = true)
        }
        Body(page, actions)
    }
}

/** Type, state and priority, as the header shows them. */
@Composable
private fun Chips(item: WorkItem, withState: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TypeChip(item.type)
        if (withState) StatusChip(item.state, tone = stateTone(item.state))
        item.priority?.let { StatusChip("P$it") }
    }
}

/** The type in its Azure Boards colour. */
@Composable
private fun TypeChip(type: String) {
    val color = workItemColor(type)
    Box(
        Modifier.height(22.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(type, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/** "Bug 4812 · Contoso › Backend · changed 30 min ago by Anna Verdi". */
@Composable
private fun meta(item: WorkItem, saved: WorkItemDetail?, now: Instant): String {
    val changed = saved?.changedBy?.let { stringResource(R.string.wi_changed_by, ago(item.changedAt, now), it) }
        ?: stringResource(R.string.wi_changed, ago(item.changedAt, now))
    return listOf("${item.type} ${item.id}", areaLabel(item.areaPath), changed).joinToString(" · ")
}

private fun areaLabel(path: String) = path.split('\\').joinToString(" › ")

@Composable
private fun SectionLabel(text: String) = Text(
    text.uppercase(), Modifier.padding(bottom = 8.dp).semantics { heading() },
    style = MaterialTheme.typography.labelSmall, color = AdoTheme.colors.textMuted,
)

/** Description, links and the comments' count. */
@Composable
private fun Body(page: WorkItemPage, actions: WorkItemActions) {
    val c = AdoTheme.colors
    val detail = page.detail
    when {
        page.error != null -> Text(stringResource(R.string.wi_load_failed, page.error), style = MaterialTheme.typography.bodyMedium, color = c.danger)
        detail == null -> Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(32.dp), color = c.primary, strokeWidth = 3.dp)
        }
        else -> {
            Column {
                SectionLabel(stringResource(R.string.wi_description))
                val blocks = remember(detail.descriptionHtml) { HtmlText.parse(detail.descriptionHtml) }
                if (blocks.isEmpty()) {
                    Text(stringResource(R.string.wi_no_description), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                } else {
                    RichText(blocks, actions.onOpenUrl)
                }
            }
            if (detail.links.isNotEmpty()) {
                Column {
                    SectionLabel(stringResource(R.string.wi_links))
                    val links = page.links
                    if (links == null) {
                        CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), color = c.primary, strokeWidth = 2.dp)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            links.forEach { LinkRowView(it, actions) }
                        }
                    }
                }
            }
            Column {
                SectionLabel(
                    if (detail.commentCount > 0) "${stringResource(R.string.wi_comments)} · ${detail.commentCount}"
                    else stringResource(R.string.wi_comments),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (detail.commentCount == 0) {
                        Text(stringResource(R.string.wi_no_comments), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                        Spacer(Modifier.width(4.dp))
                    }
                    TextAction(stringResource(R.string.wi_comments_open), { actions.onOpenUrl(detail.item.url) })
                }
            }
        }
    }
}

@Composable
private fun LinkRowView(link: LinkRow, actions: WorkItemActions) {
    val c = AdoTheme.colors
    val isPr = link.kind == LinkKind.PULL_REQUEST
    val onClick = if (isPr) { { if (link.url.isNotEmpty()) actions.onOpenUrl(link.url) } } else { { actions.onOpenWorkItem(link.id) } }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceAlt)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isPr) {
            Icon(AdoIcons.MergeType, null, Modifier.size(18.dp), tint = c.primary)
        } else {
            Icon(AdoIcons.Flag, null, Modifier.size(18.dp), tint = link.type?.let { workItemColor(it) } ?: c.textMuted)
        }
        Spacer(Modifier.width(10.dp))
        val label = if (isPr) {
            listOfNotNull(stringResource(R.string.build_pr, link.id), link.title).joinToString(" ")
        } else {
            listOfNotNull(link.type, "${link.id}", link.title).joinToString(" ")
        }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(12.dp))
        val kind = stringResource(
            when (link.kind) {
                LinkKind.PARENT -> R.string.wi_link_parent
                LinkKind.CHILD -> R.string.wi_link_child
                LinkKind.RELATED -> R.string.wi_link_related
                LinkKind.PULL_REQUEST -> R.string.wi_link_pr
            },
        )
        val status = if (isPr) {
            link.prStatus?.let { prStatusText(it, link.draft) }
        } else {
            link.state
        }
        Text(
            listOfNotNull(status, kind.takeIf { !isPr }).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = c.textMuted, maxLines = 1,
        )
    }
}

@Composable
private fun prStatusText(status: String, draft: Boolean): String = when {
    status == "active" && draft -> stringResource(R.string.pr_draft)
    status == "active" -> stringResource(R.string.wi_pr_active)
    status == "completed" -> stringResource(R.string.wi_pr_completed)
    status == "abandoned" -> stringResource(R.string.wi_pr_abandoned)
    else -> status
}

/**
 * State, assigned to, priority, iteration, area and remaining work, one below the other or two
 * per row ([twoPerRow], phones).
 */
@Composable
private fun Fields(
    modal: WorkItemModal,
    now: Instant,
    actions: WorkItemActions,
    initialMenu: String?,
    initialQuery: String,
    twoPerRow: Boolean,
) {
    val c = AdoTheme.colors
    val page = modal.page
    val detail = page.detail
    val item = page.item
    val enabled = detail != null && !modal.readOnly
    if (modal.readOnly) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(AdoIcons.Error, null, Modifier.padding(top = 1.dp).size(16.dp), tint = c.warning)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.wi_read_only), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
    }
    val unassigned = stringResource(R.string.wi_unassigned)
    val fields = buildList<Pair<String, @Composable (Modifier) -> Unit>> {
        add(WiFields.STATE to { m ->
            Field(stringResource(R.string.wi_state), WiFields.STATE, page, m, actions.onReload) {
                DropdownField(item?.state.orEmpty(), stringResource(R.string.wi_state), enabled = enabled, initiallyOpen = initialMenu == WiFields.STATE) { close ->
                    val states = page.states ?: listOfNotNull(item?.state?.let { StateInfo(it, "") })
                    states.forEach { s ->
                        StateOption(s.name, s.name == item?.state) { close(); actions.onChange(FieldChange.State(s.name)) }
                    }
                }
            }
        })
        add(WiFields.ASSIGNED_TO to { m ->
            Field(stringResource(R.string.wi_assigned_to), WiFields.ASSIGNED_TO, page, m, actions.onReload) {
                DropdownField(
                    detail?.assignee?.name ?: item?.assignedTo ?: unassigned, stringResource(R.string.wi_assigned_to),
                    enabled = enabled, initiallyOpen = initialMenu == WiFields.ASSIGNED_TO,
                ) { close ->
                    PeopleMenu(modal, detail?.assignee, actions, close)
                }
            }
        })
        add(WiFields.PRIORITY to { m ->
            Field(stringResource(R.string.wi_priority), WiFields.PRIORITY, page, m, actions.onReload) {
                DropdownField(item?.priority?.toString() ?: "–", stringResource(R.string.wi_priority), enabled = enabled) { close ->
                    (1..4).forEach { p ->
                        DropdownOption("$p", p == item?.priority) { close(); actions.onChange(FieldChange.Priority(p)) }
                    }
                }
            }
        })
        add(WiFields.ITERATION to { m ->
            Field(stringResource(R.string.wi_iteration), WiFields.ITERATION, page, m, actions.onReload) {
                val project = item?.project.orEmpty()
                val tree = remember(project, page.iterations) { page.iterations?.let { PathTree.fromIterations(project, it) } }
                PathPickerField(
                    detail?.iterationPath ?: project, stringResource(R.string.wi_iteration), tree, now,
                    stringResource(R.string.wi_search_iterations), enabled,
                    onPick = { actions.onChange(FieldChange.IterationPath(it)) },
                    initiallyOpen = initialMenu == WiFields.ITERATION, initialQuery = initialQuery,
                )
            }
        })
        add(WiFields.AREA to { m ->
            Field(stringResource(R.string.wi_area), WiFields.AREA, page, m, actions.onReload) {
                val tree = remember(page.areas) { page.areas?.let(PathTree::fromArea) }
                PathPickerField(
                    item?.areaPath.orEmpty(), stringResource(R.string.wi_area), tree, null,
                    stringResource(R.string.wi_search_areas), enabled,
                    onPick = { actions.onChange(FieldChange.AreaPath(it)) },
                    initiallyOpen = initialMenu == WiFields.AREA, initialQuery = initialQuery,
                )
            }
        })
        if (page.hasRemainingWork) {
            add(WiFields.REMAINING_WORK to { m ->
                Field(stringResource(R.string.wi_remaining_work), WiFields.REMAINING_WORK, page, m, actions.onReload) {
                    HoursField(detail?.remainingWork, enabled, stringResource(R.string.wi_remaining_work)) {
                        actions.onChange(FieldChange.RemainingWork(it))
                    }
                }
            })
        }
    }
    // The phone design: state and priority, assigned to and iteration, then area and remaining work
    val ordered = if (twoPerRow) {
        val byKey = fields.toMap()
        listOf(WiFields.STATE, WiFields.PRIORITY, WiFields.ASSIGNED_TO, WiFields.ITERATION, WiFields.AREA, WiFields.REMAINING_WORK)
            .mapNotNull { byKey[it] }
    } else {
        fields.map { it.second }
    }
    if (twoPerRow) {
        ordered.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { it(Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    } else {
        ordered.forEach { it(Modifier.fillMaxWidth()) }
    }
    page.fieldError?.takeIf { it.field !in fields.map { f -> f.first } }?.let { FieldErrorText(it, actions.onReload) }
}

/** A field's label with the saving indicator, the field, and why its change was not saved. */
@Composable
private fun Field(
    label: String,
    field: String,
    page: WorkItemPage,
    modifier: Modifier,
    onReload: () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = AdoTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.height(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (page.saving(field)) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(10.dp), color = c.primary, strokeWidth = 1.5.dp)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.wi_saving), style = MaterialTheme.typography.bodySmall, color = c.primary, maxLines = 1)
            }
        }
        content()
        page.fieldError?.takeIf { it.field == field }?.let { FieldErrorText(it, onReload) }
    }
}

/** Why a change was not saved; after a conflict "reload" reads the work item again. */
@Composable
private fun FieldErrorText(error: FieldError, onReload: () -> Unit) {
    val c = AdoTheme.colors
    val style = MaterialTheme.typography.bodySmall
    when (error.problem) {
        SaveProblem.CONFLICT -> {
            val reload = stringResource(R.string.wi_conflict_reload)
            val text = buildAnnotatedString {
                append(stringResource(R.string.wi_conflict))
                append(" ")
                withLink(
                    LinkAnnotation.Clickable(
                        "reload", TextLinkStyles(SpanStyle(color = c.primary, fontWeight = FontWeight.Medium)),
                    ) { onReload() },
                ) { append(reload) }
            }
            Text(text, style = style, color = c.danger)
        }
        SaveProblem.DENIED -> Text(stringResource(R.string.wi_denied), style = style, color = c.danger)
        SaveProblem.OTHER -> Text(stringResource(R.string.wi_not_saved, error.message), style = style, color = c.danger)
    }
}

@Composable
private fun StateOption(state: String, selected: Boolean, onClick: () -> Unit) {
    val c = AdoTheme.colors
    DropdownMenuItem(
        text = { StatusChip(state, tone = stateTone(state)) },
        onClick = onClick,
        trailingIcon = {
            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = c.primary)
        },
        modifier = Modifier.width(240.dp).semantics { this.contentDescription = state },
    )
}

/** Search, "Unassigned", me, the current assignee and the search's results. */
@Composable
private fun PeopleMenu(modal: WorkItemModal, current: Person?, actions: WorkItemActions, close: () -> Unit) {
    val c = AdoTheme.colors
    var query by remember { mutableStateOf(modal.people.query) }
    Column(Modifier.width(300.dp)) {
        SearchField(
            query, { query = it; actions.onSearchPeople(it) }, stringResource(R.string.wi_search_people),
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        )
        val people = if (query.isBlank()) {
            listOfNotNull(modal.me, current).distinctBy { it.name }
        } else {
            modal.people.results
        }
        if (query.isBlank()) {
            PersonOption(null, current == null) { close(); actions.onChange(FieldChange.AssignedTo(null)) }
        }
        when {
            query.isNotBlank() && modal.people.loading && people.isEmpty() ->
                CircularProgressIndicator(Modifier.padding(16.dp).size(20.dp), color = c.primary, strokeWidth = 2.dp)
            query.isNotBlank() && people.isEmpty() && !modal.people.loading -> Text(
                stringResource(R.string.wi_no_people), Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
            )
        }
        people.forEach { p ->
            PersonOption(p, current?.name == p.name) { close(); actions.onChange(FieldChange.AssignedTo(p)) }
        }
    }
}

@Composable
private fun PersonOption(person: Person?, selected: Boolean, onClick: () -> Unit) {
    val c = AdoTheme.colors
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    person?.name ?: stringResource(R.string.wi_unassigned), style = MaterialTheme.typography.bodyMedium,
                    color = if (person == null) c.textMuted else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                person?.uniqueName?.takeIf { it.isNotBlank() && it != person.name }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        onClick = onClick,
        leadingIcon = {
            if (person != null) {
                Avatar(person.name, size = 28.dp)
            } else {
                Box(Modifier.size(28.dp).clip(CircleShape).border(1.dp, c.outline, CircleShape))
            }
        },
        trailingIcon = {
            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = c.primary)
        },
    )
}

/** Hours as a number, saved when done or when the field loses focus. */
@Composable
private fun HoursField(hours: Double?, enabled: Boolean, label: String, onSave: (Double?) -> Unit) {
    val c = AdoTheme.colors
    val shown = hours?.let { DecimalFormat("0.##").format(it) }.orEmpty()
    var text by remember(hours) { mutableStateOf(shown) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        val value = text.replace(',', '.').trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        if (text.isNotBlank() && value == null) {
            text = shown
            return
        }
        if (value != hours) onSave(value?.coerceAtLeast(0.0))
    }
    LaunchedEffect(shown) { if (!focused) text = shown }
    val shape = RoundedCornerShape(8.dp)
    BasicTextField(
        text, { text = it },
        Modifier.fillMaxWidth()
            .onFocusChanged { f ->
                if (focused && !f.isFocused) commit()
                focused = f.isFocused
            }
            .semantics { contentDescription = label },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = if (enabled) c.text else c.textMuted),
        cursorBrush = SolidColor(c.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(shape).background(c.surface)
                    .border(if (focused) 2.dp else 1.dp, if (focused) c.primary else c.outline, shape)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { inner() }
                Text(stringResource(R.string.wi_hours_unit), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }
        },
    )
}
