package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.data.AreaSelection
import io.github.iacoposb.adodash.data.Check
import io.github.iacoposb.adodash.data.Filters
import io.github.iacoposb.adodash.sync.CatalogState
import io.github.iacoposb.adodash.sync.TypeStates
import io.github.iacoposb.adodash.ui.components.AdoCheckbox
import io.github.iacoposb.adodash.ui.components.AdoFilterChip
import io.github.iacoposb.adodash.ui.components.Banner
import io.github.iacoposb.adodash.ui.components.BannerTone
import io.github.iacoposb.adodash.ui.components.SectionCard
import io.github.iacoposb.adodash.ui.components.SwitchRow
import io.github.iacoposb.adodash.ui.components.TextAction

private val pathSetSaver = listSaver<Set<String>, String>({ it.toList() }, { it.toSet() })

/**
 * "Global filters": the projects-and-areas tree on the left, states to hide and pull request
 * options on the right. Every change goes out through [onChange] at once.
 * [wide]: the tree fills the height and scrolls inside its card.
 */
@Composable
fun FiltersSection(
    filters: Filters,
    catalog: CatalogState,
    onChange: (Filters) -> Unit,
    onRetry: () -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
    /** Expanded nodes at first; by default the projects partly selected. */
    initiallyExpanded: Set<String>? = null,
    initialQuery: String = "",
) {
    val spacing = Arrangement.spacedBy(12.dp)
    val error: @Composable () -> Unit = {
        // With a saved catalog a failed refresh changes nothing: it is tried again next time
        catalog.error?.takeIf { catalog.catalog == null }?.let {
            Banner(stringResource(R.string.filters_load_failed, it), BannerTone.Error) {
                TextAction(stringResource(R.string.action_retry), onRetry)
            }
        }
    }
    if (wide) {
        Column(modifier, verticalArrangement = spacing) {
            error()
            Row(Modifier.weight(1f), horizontalArrangement = spacing) {
                AreasCard(filters, catalog, onChange, true, initiallyExpanded, initialQuery, Modifier.weight(1.45f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = spacing) {
                    StatesCard(filters, catalog, onChange)
                    PullRequestsCard(filters, onChange)
                }
            }
        }
    } else {
        Column(modifier, verticalArrangement = spacing) {
            error()
            AreasCard(filters, catalog, onChange, false, initiallyExpanded, initialQuery, Modifier.fillMaxWidth())
            StatesCard(filters, catalog, onChange)
            PullRequestsCard(filters, onChange)
        }
    }
}

@Composable
private fun AreasCard(
    filters: Filters,
    catalog: CatalogState,
    onChange: (Filters) -> Unit,
    fill: Boolean,
    initiallyExpanded: Set<String>?,
    initialQuery: String,
    modifier: Modifier,
) {
    val c = AdoTheme.colors
    val roots = catalog.catalog?.projects
    val selection = AreaSelection(roots.orEmpty(), filters.areas)
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var expanded by rememberSaveable(stateSaver = pathSetSaver) { mutableStateOf(initiallyExpanded.orEmpty()) }
    var expandedOnce by rememberSaveable { mutableStateOf(initiallyExpanded != null) }
    LaunchedEffect(roots != null) {
        if (roots != null && !expandedOnce) {
            expanded = roots.filter { selection.check(it) == Check.PARTIAL }.map { it.path }.toSet()
            expandedOnce = true
        }
    }
    val search = selection.search(query)
    fun change(areas: Set<String>?) = onChange(filters.copy(areas = areas))

    SectionCard(
        stringResource(R.string.filters_areas_title), modifier,
        trailing = if (catalog.refreshing) ({ Updating() }) else null,
    ) {
        Text(stringResource(R.string.filters_areas_hint), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        val actions: @Composable () -> Unit = {
            if (search == null) {
                TextAction(stringResource(R.string.action_select_all), { change(selection.all()) })
                TextAction(stringResource(R.string.action_deselect_all), { change(selection.none()) })
            } else {
                TextAction(stringResource(R.string.action_select_results), { change(selection.set(search.matches, true)) })
                TextAction(stringResource(R.string.action_deselect_results), { change(selection.set(search.matches, false)) })
            }
        }
        if (fill) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, { query = it }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                actions()
            }
        } else {
            // Narrow: the field gets the whole width, the actions go below it
            SearchField(query, { query = it }, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { actions() }
        }
        val tree = Modifier.fillMaxWidth().then(if (fill) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier)
        Column(tree) {
            when {
                roots == null && catalog.loading -> Row(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = c.primary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.filters_loading), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                }
                roots == null -> {}
                search != null && search.matches.isEmpty() -> Text(
                    stringResource(R.string.filters_no_results, search.query),
                    Modifier.padding(vertical = 24.dp).fillMaxWidth(), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
                )
                else -> roots.forEach { root ->
                    if (search == null || root.path in search.visible) {
                        TreeNode(
                            root, root, 0, selection, search, expanded,
                            onExpand = { path -> expanded = if (path in expanded) expanded - path else expanded + path },
                            onCheck = { change(selection.toggle(it)) },
                            onOnly = { change(selection.only(it)) },
                        )
                    }
                }
            }
        }
        if (roots != null) {
            HorizontalDivider(color = c.outline)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val projects = pluralStringResource(
                    R.plurals.filters_summary_projects, selection.selectedProjects, selection.selectedProjects, roots.size,
                )
                val areas = pluralStringResource(R.plurals.filters_summary_areas, selection.selectedAreas, selection.selectedAreas)
                Text("$projects · $areas", style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.filters_long_press_hint), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = c.textFaint, textAlign = TextAlign.End,
                )
            }
        }
    }
}

/** A fresh catalog being read over the saved one: small, the filters stay usable. */
@Composable
private fun Updating() {
    val c = AdoTheme.colors
    Spacer(Modifier.width(12.dp))
    CircularProgressIndicator(Modifier.size(14.dp), color = c.textMuted, strokeWidth = 1.5.dp)
    Spacer(Modifier.width(8.dp))
    Text(stringResource(R.string.filters_updating), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier) {
    val c = AdoTheme.colors
    val placeholder = stringResource(R.string.filters_search)
    Row(
        modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(c.surfaceAlt).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, Modifier.size(20.dp), tint = c.textMuted)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = c.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value, onValueChange, Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
                cursorBrush = SolidColor(c.primary),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }, Modifier.size(36.dp)) {
                Icon(Icons.Filled.Close, stringResource(R.string.action_clear_search), Modifier.size(20.dp), tint = c.textMuted)
            }
        } else {
            Spacer(Modifier.width(10.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeNode(
    node: AreaNode,
    root: AreaNode,
    level: Int,
    selection: AreaSelection,
    search: AreaSelection.SearchResult?,
    expanded: Set<String>,
    onExpand: (String) -> Unit,
    onCheck: (AreaNode) -> Unit,
    onOnly: (AreaNode) -> Unit,
) {
    val c = AdoTheme.colors
    val children = if (search != null) node.children.filter { it.path in search.visible } else node.children
    val open = if (search != null) children.isNotEmpty() else node.path in expanded
    val match = search?.isMatch(node) == true
    val check = selection.check(node)
    val state = when (check) {
        Check.ON -> ToggleableState.On
        Check.PARTIAL -> ToggleableState.Indeterminate
        Check.OFF -> ToggleableState.Off
    }
    val count = when {
        search != null -> if (level == 0) pluralStringResource(R.plurals.filters_results, search.matchesUnder(root), search.matchesUnder(root)) else null
        level == 0 && node.children.isEmpty() -> stringResource(R.string.filters_no_areas)
        level == 0 -> selection.areaCount(node).let { total ->
            pluralStringResource(R.plurals.filters_project_area_count, total, selection.selectedAreaCount(node), total)
        }
        node.children.isNotEmpty() -> selection.leaves(node).let { leaves ->
            stringResource(R.string.filters_area_count, leaves.count { selection.covers(it.path) }, leaves.size)
        }
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
            .then(if (match) Modifier.background(c.primaryContainer) else Modifier)
            .combinedClickable(role = Role.Checkbox, onLongClick = { onOnly(node) }, onClick = { onCheck(node) })
            .semantics { toggleableState = state }
            .padding(start = 8.dp + 28.dp * level, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.children.isNotEmpty() && (search == null || children.isNotEmpty())) {
            val label = stringResource(if (open) R.string.action_collapse else R.string.action_expand, node.name)
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable(enabled = search == null, role = Role.Button) { onExpand(node.path) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (open) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null, Modifier.size(20.dp), tint = c.textMuted,
                )
            }
        } else {
            Spacer(Modifier.width(32.dp))
        }
        Spacer(Modifier.width(4.dp))
        AdoCheckbox(state, null)
        Spacer(Modifier.width(8.dp))
        Text(
            highlight(node.name, search?.query.takeIf { match }),
            Modifier.weight(1f),
            style = if (level == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(count, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
        }
    }
    if (open) {
        children.forEach {
            TreeNode(it, root, level + 1, selection, search, expanded, onExpand, onCheck, onOnly)
        }
    }
}

/** [text] with the first occurrence of [query] in bold. */
private fun highlight(text: String, query: String?): AnnotatedString {
    val start = query?.let { text.indexOf(it, ignoreCase = true) } ?: -1
    if (query == null || start < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, start))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(start, start + query.length)) }
        append(text.substring(start + query.length))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatesCard(filters: Filters, catalog: CatalogState, onChange: (Filters) -> Unit) {
    val c = AdoTheme.colors
    SectionCard(stringResource(R.string.filters_states_title)) {
        Text(stringResource(R.string.filters_states_hint), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        val all = catalog.catalog?.types
        when {
            all == null -> if (catalog.loading) {
                CircularProgressIndicator(Modifier.size(20.dp), color = c.primary, strokeWidth = 2.dp)
            }
            else -> {
                // The types of the projects shown; all of them while none is
                val types = all.filter { t -> t.projects.any(filters::includesProject) }.ifEmpty { all }
                if (types.isEmpty()) {
                    Text(stringResource(R.string.filters_states_empty), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                }
                types.forEach { type -> TypeRow(type, filters, onChange) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeRow(type: TypeStates, filters: Filters, onChange: (Filters) -> Unit) {
    val c = AdoTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(22.dp).clip(RoundedCornerShape(2.dp)).background(typeColor(type)))
        Spacer(Modifier.width(12.dp))
        Text(
            type.name, Modifier.width(84.dp), style = MaterialTheme.typography.titleSmall, color = c.text,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            type.states.forEach { state ->
                val hidden = filters.hides(type.name, state)
                AdoFilterChip(state, hidden, {
                    val key = filters.hiddenStates.keys.firstOrNull { it.equals(type.name, ignoreCase = true) } ?: type.name
                    val current = filters.hiddenStates[key].orEmpty()
                    val next = if (hidden) current.filterNot { it.equals(state, ignoreCase = true) }.toSet() else current + state
                    onChange(filters.copy(hiddenStates = (filters.hiddenStates - key).let { if (next.isEmpty()) it else it + (key to next) }))
                })
            }
        }
    }
}

/** The app's colour for the usual types, Azure Boards' own for the others. */
@Composable
private fun typeColor(type: TypeStates): Color {
    val known = workItemColor(type.name)
    if (known != AdoTheme.colors.wiChore) return known
    return type.color?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: known
}

@Composable
private fun PullRequestsCard(filters: Filters, onChange: (Filters) -> Unit) {
    SectionCard(stringResource(R.string.filters_prs_title)) {
        SwitchRow(
            stringResource(R.string.filters_show_drafts), filters.draftsToReview, { onChange(filters.copy(draftsToReview = it)) },
            subtitle = stringResource(R.string.filters_show_drafts_hint),
        )
        SwitchRow(
            stringResource(R.string.filters_hide_approved), filters.hideApprovedByMe, { onChange(filters.copy(hideApprovedByMe = it)) },
        )
    }
}
