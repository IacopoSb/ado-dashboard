package io.github.iacoposb.adodash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.PullRequestDetail
import io.github.iacoposb.adodash.api.Reviewer
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.sync.BuildSignal
import io.github.iacoposb.adodash.sync.Signals
import io.github.iacoposb.adodash.ui.components.AdoIcons
import io.github.iacoposb.adodash.ui.components.Avatar
import io.github.iacoposb.adodash.ui.components.Banner
import io.github.iacoposb.adodash.ui.components.BannerTone
import io.github.iacoposb.adodash.ui.components.checksText
import io.github.iacoposb.adodash.ui.components.DropdownOption
import io.github.iacoposb.adodash.ui.components.FullScreenModalWidth
import io.github.iacoposb.adodash.ui.components.shortScreen
import io.github.iacoposb.adodash.ui.components.ReviewerAvatar
import io.github.iacoposb.adodash.ui.components.StatusChip
import io.github.iacoposb.adodash.ui.components.TextAction
import io.github.iacoposb.adodash.ui.components.Tone
import io.github.iacoposb.adodash.ui.components.VoteBadge
import io.github.iacoposb.adodash.ui.components.stateTone
import io.github.iacoposb.adodash.ui.components.vote
import kotlinx.coroutines.delay
import java.time.Instant

/** What the pull request page does; all of it goes to [PullRequestController] or the app. */
class PrActions(
    val onBack: () -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onVote: (Int) -> Unit = {},
    val onDismissVoteError: () -> Unit = {},
    val onSelectTab: (PrTab) -> Unit = {},
    val onSelectFilter: (ThreadFilter) -> Unit = {},
    val onDraftChange: (threadId: Int, Draft) -> Unit = { _, _ -> },
    val onSend: (threadId: Int) -> Unit = {},
    val onSetStatus: (threadId: Int, status: String) -> Unit = { _, _ -> },
    val onSearchMentions: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    /** A build policy tapped: the build's details over the page. */
    val onOpenBuild: (project: String, buildId: Int) -> Unit = { _, _ -> },
    val onOpenWorkItem: (WorkItem) -> Unit = {},
    /** The thread opened from a mention was shown. */
    val onTargetShown: () -> Unit = {},
    val onSelectFile: (path: String) -> Unit = {},
    val onToggleViewed: (path: String) -> Unit = {},
    val onToggleFolder: (folder: String) -> Unit = {},
    val onSetSideBySide: (Boolean) -> Unit = {},
    val onExpandGap: (path: String, start: Int) -> Unit = { _, _ -> },
    val onRetryFile: (path: String) -> Unit = {},
    /** A line long-pressed, or its number tapped: a new thread on it. */
    val onStartFileThread: (LineRef) -> Unit = {},
    val onCancelFileThread: () -> Unit = {},
    /** "Complete": the dialog with the merge options. */
    val onOpenComplete: () -> Unit = {},
    val onEditComplete: (CompleteDraft) -> Unit = {},
    val onDismissComplete: () -> Unit = {},
    val onComplete: () -> Unit = {},
    val onSetAutoComplete: () -> Unit = {},
    val onCancelAutoComplete: () -> Unit = {},
    /** Confirmed already. */
    val onAbandon: () -> Unit = {},
    val onReactivate: () -> Unit = {},
    val onDismissCloseError: () -> Unit = {},
)

/** How long the thread opened from a mention stays highlighted. */
private const val HIGHLIGHT_MS = 2_500L

/**
 * A pull request on the whole screen: the app bar with "!311 title", where it goes and who
 * opened it, and my vote; the tabs Overview and Threads with the signals on the right; on
 * tablets the overview in two columns (description, checks and reviewers; the active threads
 * with the new comment's composer), on narrow screens one column with the vote fixed at the
 * bottom. System back closes it.
 */
@Composable
fun PullRequestScreen(
    screen: PrScreen,
    now: Instant,
    actions: PrActions,
    /** For the screenshots: the vote menu open (the overflow, or "Other vote" on phones). */
    initialVoteMenu: Boolean = false,
    /** For the screenshots: threads shown expanded in the overview. */
    initialExpanded: Set<Int> = emptySet(),
) {
    BackHandler(onBack = actions.onBack)
    val c = AdoTheme.colors
    var expanded by remember(screen.id) { mutableStateOf(initialExpanded) }
    var highlighted by remember(screen.id) { mutableStateOf<Int?>(null) }
    val target = screen.target
    LaunchedEffect(target, screen.threads.loaded) {
        if (target != null && screen.threads.loaded && screen.threads.items.any { it.id == target.threadId }) {
            expanded = expanded + target.threadId
            highlighted = target.threadId
            actions.onTargetShown()
        }
    }
    LaunchedEffect(highlighted) {
        if (highlighted == null) return@LaunchedEffect
        delay(HIGHLIGHT_MS)
        highlighted = null
    }
    val view = ThreadsView(
        expanded = expanded, highlighted = highlighted,
        toggle = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
    )
    val dialog = screen.close.dialog
    Box(Modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(c.bg)
                // Nothing below it is reachable
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
                .then(if (dialog != null) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            // Phones on their side too: the single column, the vote fixed at the bottom
            val wide = maxWidth >= FullScreenModalWidth && !shortScreen()
            Column(Modifier.fillMaxSize()) {
                PrAppBar(screen, now, actions, wide, initialVoteMenu && wide)
                screen.vote.error?.let { error ->
                    Banner(
                        if (error.problem == SaveProblem.DENIED) stringResource(R.string.pr_vote_denied)
                        else stringResource(R.string.pr_vote_failed, error.message),
                        BannerTone.Error, Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    ) { TextAction(stringResource(R.string.pr_dismiss), actions.onDismissVoteError) }
                }
                val pr = screen.pr
                if (pr != null) CloseBanners(screen, pr, actions)
                when {
                    screen.error != null -> Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp)) {
                        Text(stringResource(R.string.pr_load_failed, screen.error), style = MaterialTheme.typography.bodyMedium, color = c.danger)
                        TextAction(stringResource(R.string.action_retry), actions.onRetry)
                    }
                    pr == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(32.dp), color = c.primary, strokeWidth = 3.dp)
                    }
                    else -> {
                        PrTabs(screen, pr, actions, wide)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                screen.tab == PrTab.FILES -> FilesTab(screen, now, actions, wide)
                                screen.tab == PrTab.COMMITS -> CommitsTab(screen, now, actions, wide)
                                screen.tab == PrTab.THREADS && wide -> WideThreads(screen, now, actions, view)
                                screen.tab == PrTab.THREADS -> NarrowThreads(screen, now, actions, view)
                                wide -> WideOverview(screen, pr, now, actions, view)
                                else -> NarrowOverview(screen, pr, now, actions)
                            }
                        }
                        // Typing a comment: the keyboard leaves little room, the votes come back after
                        if (!wide && screen.canVote && WindowInsets.ime.getBottom(LocalDensity.current) == 0) PhoneVoteBar(screen, actions, initialVoteMenu)
                    }
                }
            }
        }
        val pr = screen.pr
        if (dialog != null && pr != null) CompleteDialog(screen, pr, dialog, now, actions)
    }
}

/** Which threads are open and which is highlighted: the page's own state. */
internal class ThreadsView(val expanded: Set<Int>, val highlighted: Int?, val toggle: (Int) -> Unit)

/** Back, "!311 title" and "repo · source → target · author · age"; the votes on tablets. */
@Composable
private fun PrAppBar(screen: PrScreen, now: Instant, actions: PrActions, wide: Boolean, menuOpen: Boolean) {
    val c = AdoTheme.colors
    val pr = screen.pr
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 4.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = actions.onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = c.text)
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (pr == null) "!${screen.id}" else "!${pr.id}  ${pr.title}", Modifier.weight(1f, fill = false).semantics { heading() },
                    style = if (wide) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                statusChip(screen)?.let { (text, tone) ->
                    Spacer(Modifier.width(8.dp))
                    StatusChip(text, tone = tone)
                }
            }
            if (pr != null) {
                Text(
                    listOfNotNull(
                        pr.repoName, "${pr.sourceBranch} → ${pr.targetBranch}",
                        pr.author.name.takeIf { wide }, ago(pr.createdAt, now).takeIf { wide },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (wide && screen.canVote) {
            Spacer(Modifier.width(12.dp))
            VoteButtons(screen.vote.shown, busy = screen.vote.busy, onVote = actions.onVote)
        }
        if (wide && screen.canClose) {
            Spacer(Modifier.width(8.dp))
            CompleteButton(screen, actions)
        }
        // Phones: "Complete" in the menu, the vote at the bottom
        OverflowMenu(screen, actions, menuOpen, withComplete = !wide)
    }
}

/** "Draft", "Completed" or "Abandoned" next to the title; nothing for an active one. */
@Composable
private fun statusChip(screen: PrScreen): Pair<String, Tone>? = when {
    screen.status == "completed" -> stringResource(R.string.wi_pr_completed) to Tone.Success
    screen.status == "abandoned" -> stringResource(R.string.wi_pr_abandoned) to Tone.Neutral
    screen.pr?.draft == true -> stringResource(R.string.pr_draft) to Tone.Neutral
    else -> null
}

/** The four votes as the design's buttons; the current one filled in its color. */
@Composable
private fun VoteButtons(current: Int, busy: Boolean, onVote: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        VOTES.forEach { (vote, label, icon) ->
            VoteButton(stringResource(label), icon, vote, current, enabled = !busy) { onVote(vote) }
        }
    }
}

private val VOTES = listOf(
    Triple(Vote.APPROVED, R.string.pr_vote_approve, Icons.Filled.Check),
    Triple(Vote.APPROVED_WITH_SUGGESTIONS, R.string.pr_vote_suggestions, Icons.Filled.Check),
    Triple(Vote.WAITING, R.string.pr_vote_wait, AdoIcons.Hourglass),
    Triple(Vote.REJECTED, R.string.pr_vote_reject, Icons.Filled.Close),
)

/**
 * A vote: filled in the vote's color when it is mine; with no vote yet, the design's look
 * (approve filled, suggestions tonal, wait outlined, reject red); outlined otherwise.
 */
@Composable
private fun VoteButton(
    text: String,
    icon: ImageVector,
    vote: Int,
    current: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = AdoTheme.colors
    val selected = vote == current
    val (container, content, border) = when {
        selected -> Triple(c.vote(vote), c.onPrimary, null)
        current != Vote.NONE -> Triple(Color.Transparent, if (vote == Vote.REJECTED) c.danger else c.primary, c.outline)
        vote == Vote.APPROVED -> Triple(c.primary, c.onPrimary, null)
        vote == Vote.APPROVED_WITH_SUGGESTIONS -> Triple(c.primaryContainer, c.onPrimaryContainer, null)
        vote == Vote.WAITING -> Triple(Color.Transparent, c.primary, c.outline)
        else -> Triple(c.dangerContainer, c.danger, null)
    }
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp).semantics { this.selected = selected },
        enabled = enabled || selected,
        colors = ButtonDefaults.buttonColors(
            containerColor = container, contentColor = content,
            disabledContainerColor = if (selected) container else container.copy(alpha = container.alpha * 0.5f),
            disabledContentColor = if (selected) content else content.copy(alpha = 0.6f),
        ),
        border = border?.let { BorderStroke(1.dp, it) },
        contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * "Reset vote" (tablets), "Complete" (phones), "Abandon" or "Reactivate", and "Open in Azure
 * DevOps"; abandoning asks first.
 */
@Composable
private fun OverflowMenu(screen: PrScreen, actions: PrActions, initiallyOpen: Boolean, withComplete: Boolean) {
    val c = AdoTheme.colors
    var open by remember { mutableStateOf(initiallyOpen) }
    var confirmAbandon by remember { mutableStateOf(false) }
    val idle = screen.close.busy == null && !screen.close.merging
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.pr_more), tint = c.textMuted) }
        DropdownMenu(open, { open = false }, containerColor = c.surface) {
            if (withComplete && screen.canClose) {
                val draft = screen.pr?.draft == true
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(if (draft) R.string.pr_complete_publish_draft else R.string.pr_complete),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = { open = false; actions.onOpenComplete() },
                    leadingIcon = { Icon(AdoIcons.MergeType, null, Modifier.size(18.dp)) },
                    enabled = !draft && idle,
                )
            }
            if (screen.canVote && !withComplete) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pr_vote_reset), style = MaterialTheme.typography.bodyMedium) },
                    onClick = { open = false; actions.onVote(Vote.NONE) },
                    leadingIcon = { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)) },
                    enabled = screen.vote.shown != Vote.NONE && !screen.vote.busy,
                )
            }
            if (screen.canClose) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pr_abandon), style = MaterialTheme.typography.bodyMedium, color = c.danger) },
                    onClick = { open = false; confirmAbandon = true },
                    leadingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(18.dp), tint = c.danger) },
                    enabled = idle,
                )
            }
            if (screen.status == PullRequestDetail.ABANDONED) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pr_reactivate), style = MaterialTheme.typography.bodyMedium) },
                    onClick = { open = false; actions.onReactivate() },
                    leadingIcon = { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)) },
                    enabled = idle,
                )
            }
            screen.pr?.let { pr ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_open_build), style = MaterialTheme.typography.bodyMedium) },
                    onClick = { open = false; actions.onOpenUrl(pr.url) },
                    leadingIcon = { Icon(AdoIcons.OpenInNew, null, Modifier.size(18.dp)) },
                )
            }
        }
    }
    val pr = screen.pr
    if (confirmAbandon && pr != null) AbandonConfirm(pr, actions.onAbandon) { confirmAbandon = false }
}

/** Phones: "Approve" and "Other vote" fixed at the bottom. */
@Composable
private fun PhoneVoteBar(screen: PrScreen, actions: PrActions, initialMenu: Boolean) {
    val c = AdoTheme.colors
    val current = screen.vote.shown
    var open by remember { mutableStateOf(initialMenu) }
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        HorizontalDivider(color = c.outline)
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VoteButton(
                stringResource(if (current == Vote.APPROVED) R.string.vote_approved else R.string.pr_vote_approve),
                Icons.Filled.Check, Vote.APPROVED, if (current == Vote.APPROVED) current else Vote.NONE,
                Modifier.weight(1f).height(48.dp), enabled = !screen.vote.busy,
            ) { actions.onVote(Vote.APPROVED) }
            Box(Modifier.weight(1f)) {
                val other = current != Vote.NONE && current != Vote.APPROVED
                Button(
                    onClick = { open = true },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = !screen.vote.busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (other) c.vote(current) else Color.Transparent,
                        contentColor = if (other) c.onPrimary else c.primary,
                    ),
                    border = if (other) null else BorderStroke(1.dp, c.outline),
                ) {
                    Text(
                        stringResource(if (other) voteLabel(current) else R.string.pr_vote_other),
                        style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(open, { open = false }, containerColor = c.surface) {
                    VOTES.drop(1).forEach { (vote, label, _) ->
                        DropdownOption(stringResource(label), selected = vote == current) {
                            open = false
                            actions.onVote(vote)
                        }
                    }
                    if (current != Vote.NONE) {
                        HorizontalDivider(color = c.outline)
                        DropdownOption(stringResource(R.string.pr_vote_reset), selected = false) {
                            open = false
                            actions.onVote(Vote.NONE)
                        }
                    }
                }
            }
        }
    }
}

/** Overview, Files, Threads and Commits, with the signals on the right on tablets. */
@Composable
private fun PrTabs(screen: PrScreen, pr: PullRequest, actions: PrActions, wide: Boolean) {
    val c = AdoTheme.colors
    val active = screen.threads.activeCount
    val files = screen.files.changes.size.takeIf { screen.files.loaded && screen.files.error == null }
    val commits = screen.commits.items.size.takeIf { screen.commits.loaded && screen.commits.error == null }
    val tabs = listOf(
        PrTab.OVERVIEW to stringResource(R.string.pr_tab_overview),
        PrTab.FILES to if (files != null) stringResource(R.string.pr_tab_files_count, files) else stringResource(R.string.pr_tab_files),
        PrTab.THREADS to when {
            !screen.threads.loaded || active == 0 -> stringResource(R.string.pr_tab_threads)
            wide -> pluralStringResource(R.plurals.pr_tab_threads_active, active, active)
            // Four tabs on a phone: the count alone
            else -> "${stringResource(R.string.pr_tab_threads)} · $active"
        },
        PrTab.COMMITS to if (commits != null) stringResource(R.string.pr_tab_commits_count, commits) else stringResource(R.string.pr_tab_commits),
    )
    val selected = tabs.indexOfFirst { it.first == screen.tab }.coerceAtLeast(0)
    if (wide) {
        // As in the design: tabs as wide as their labels, the signals on the right
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { i, (tab, label) ->
                val isSelected = i == selected
                Column(
                    Modifier.width(IntrinsicSize.Max).clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .clickable(role = Role.Tab) { actions.onSelectTab(tab) }
                        .semantics { this.selected = isSelected }
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        label, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                        color = if (isSelected) c.primary else c.textMuted,
                    )
                    Box(
                        Modifier.padding(top = 10.dp).height(3.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            .background(if (isSelected) c.primary else Color.Transparent),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            SignalChips(screen, pr)
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ScrollableTabRow(
            selectedTabIndex = selected, containerColor = c.bg, contentColor = c.primary,
            modifier = Modifier.fillMaxWidth(), edgePadding = 0.dp,
            indicator = { positions ->
                TabRowDefaults.SecondaryIndicator(Modifier.tabIndicatorOffset(positions[selected]), color = c.primary)
            },
            divider = {},
        ) {
            tabs.forEachIndexed { i, (tab, label) ->
                Tab(i == selected, { actions.onSelectTab(tab) }, unselectedContentColor = c.textMuted, text = {
                    Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                })
            }
        }
        if (wide) {
            Spacer(Modifier.weight(1f))
            SignalChips(screen, pr)
        }
    }
}

/** "2 active threads", "Build ok", "Conflicts", "Ready to complete": as on the dashboard row. */
@Composable
private fun SignalChips(screen: PrScreen, pr: PullRequest) {
    val signals = Signals.of(pr, screen.policies, screen.threads.shown.takeIf { screen.threads.loaded }, emptyList(), screen.statuses)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val active = signals.activeThreads?.takeIf { it > 0 }
        if (active != null) {
            StatusChip(pluralStringResource(R.plurals.pr_active_threads, active, active), tone = Tone.Warning)
        } else if (signals.commentsBlocking) {
            StatusChip(stringResource(R.string.pr_check_comments), tone = Tone.Warning)
        }
        if (signals.conflicts) StatusChip(stringResource(R.string.pr_conflicts), tone = Tone.Danger)
        when (signals.build) {
            BuildSignal.SUCCEEDED -> StatusChip(stringResource(R.string.pr_build_ok), tone = Tone.Success)
            BuildSignal.FAILED -> StatusChip(stringResource(R.string.pr_build_failed), tone = Tone.Danger)
            BuildSignal.RUNNING -> StatusChip(stringResource(R.string.pr_build_running), tone = Tone.Info)
            BuildSignal.EXPIRED -> StatusChip(stringResource(R.string.pr_build_expired), tone = Tone.Warning)
            BuildSignal.NONE -> {}
        }
        checksText(signals.failedChecks, R.string.pr_check_failed, R.plurals.pr_checks_failed)?.let { StatusChip(it, tone = Tone.Danger) }
        checksText(signals.pendingChecks, R.string.pr_check_pending, R.plurals.pr_checks_pending)?.let { StatusChip(it, tone = Tone.Info) }
        if (signals.workItemsMissing) StatusChip(stringResource(R.string.pr_check_no_work_items), tone = Tone.Warning)
        if (signals.approvalsMissing) StatusChip(stringResource(R.string.pr_approval_needed))
        if (signals.readyToComplete && screen.status == "active") StatusChip(stringResource(R.string.pr_ready), tone = Tone.Success)
    }
}

/** A white card of the page. */
@Composable
private fun Card(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(AdoTheme.colors.surface), content = content)
}

@Composable
private fun WideOverview(screen: PrScreen, pr: PullRequest, now: Instant, actions: PrActions, view: ThreadsView) {
    val c = AdoTheme.colors
    Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.weight(1f).fillMaxHeight()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) { OverviewSections(screen, pr, now, actions) }
        }
        Card(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.pr_active_threads_title).uppercase(), Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.labelSmall, color = c.textMuted,
                )
                val all = screen.threads.shown.size
                if (screen.threads.loaded && all > 0) {
                    TextAction(stringResource(R.string.pr_all_threads, all), {
                        actions.onSelectFilter(ThreadFilter.ALL)
                        actions.onSelectTab(PrTab.THREADS)
                    })
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ThreadList(screen, now, actions, view, ThreadFilter.ACTIVE, emptyText = stringResource(R.string.pr_no_active_threads))
                SendingThreads(screen, now, actions)
            }
            HorizontalDivider(color = c.outline)
            NewThreadComposer(screen, actions, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }
}

@Composable
private fun NarrowOverview(screen: PrScreen, pr: PullRequest, now: Instant, actions: PrActions) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).background(AdoTheme.colors.surface)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) { OverviewSections(screen, pr, now, actions) }
}

@Composable
private fun WideThreads(screen: PrScreen, now: Instant, actions: PrActions, view: ThreadsView) {
    val c = AdoTheme.colors
    Card(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
        ThreadFilterChips(screen, actions, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ThreadList(screen, now, actions, view, screen.filter, emptyText = stringResource(R.string.pr_no_threads), allExpanded = true)
            SendingThreads(screen, now, actions)
        }
        HorizontalDivider(color = c.outline)
        NewThreadComposer(screen, actions, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

@Composable
private fun NarrowThreads(screen: PrScreen, now: Instant, actions: PrActions, view: ThreadsView) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ThreadFilterChips(screen, actions)
        ThreadList(screen, now, actions, view, screen.filter, emptyText = stringResource(R.string.pr_no_threads), allExpanded = true)
        SendingThreads(screen, now, actions)
        NewThreadComposer(screen, actions, Modifier.padding(top = 4.dp), background = AdoTheme.colors.surface)
    }
}

/** The threads [filter] lets in, newest activity last as in Azure DevOps; the target scrolled to. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ThreadList(
    screen: PrScreen,
    now: Instant,
    actions: PrActions,
    view: ThreadsView,
    filter: ThreadFilter,
    emptyText: String,
    allExpanded: Boolean = false,
) {
    val c = AdoTheme.colors
    val threads = screen.threads
    when {
        !threads.loaded -> CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), color = c.primary, strokeWidth = 2.dp)
        threads.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.pr_threads_failed, threads.error), Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.bodyMedium, color = c.danger,
            )
            TextAction(stringResource(R.string.action_retry), actions.onRetry)
        }
        else -> {
            val list = threads.shown.filter { filter.accepts(it) }
            if (list.isEmpty() && threads.sending.none { it.threadId == PrScreen.NEW_THREAD }) {
                Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            }
            val requesters = remember(screen.id) { mutableMapOf<Int, BringIntoViewRequester>() }
            list.forEach { thread ->
                val requester = requesters.getOrPut(thread.id) { BringIntoViewRequester() }
                ThreadCard(
                    thread, screen, now, actions,
                    expanded = allExpanded || thread.id in view.expanded,
                    onToggle = { view.toggle(thread.id) },
                    modifier = Modifier.bringIntoViewRequester(requester),
                    highlighted = view.highlighted == thread.id,
                )
            }
            LaunchedEffect(view.highlighted) {
                val id = view.highlighted ?: return@LaunchedEffect
                delay(50)
                requesters[id]?.bringIntoView()
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) = Text(
    text.uppercase(), modifier.padding(bottom = 10.dp).semantics { heading() },
    style = MaterialTheme.typography.labelSmall, color = AdoTheme.colors.textMuted,
)

/** Description, labels, checks and policies, reviewers and linked work items. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverviewSections(screen: PrScreen, pr: PullRequest, now: Instant, actions: PrActions) {
    val c = AdoTheme.colors
    Column {
        SectionLabel(stringResource(R.string.pr_description))
        val detail = screen.detail
        val unknown = stringResource(R.string.pr_someone)
        when {
            detail == null -> CircularProgressIndicator(Modifier.size(24.dp), color = c.primary, strokeWidth = 2.dp)
            detail.description.isBlank() ->
                Text(stringResource(R.string.pr_no_description), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
            else -> {
                val blocks = remember(detail.description, screen.names, unknown) { Markdown.parse(detail.description, screen.names, unknown) }
                RichText(blocks, actions.onOpenUrl)
            }
        }
        val labels = detail?.labels.orEmpty()
        if (labels.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.forEach { StatusChip(it, tone = Tone.Info) }
            }
        }
    }
    Column {
        SectionLabel(stringResource(R.string.pr_checks))
        Checks(screen, pr, now, actions)
    }
    Column {
        SectionLabel(stringResource(R.string.pr_reviewers))
        Reviewers(screen, pr)
    }
    val items = screen.workItems
    if (!items.isNullOrEmpty()) {
        Column {
            SectionLabel(stringResource(R.string.pr_work_items))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEach { LinkedWorkItem(it) { actions.onOpenWorkItem(it) } }
            }
        }
    }
}

@Composable
private fun Checks(screen: PrScreen, pr: PullRequest, now: Instant, actions: PrActions) {
    val c = AdoTheme.colors
    val policies = screen.policies
    if (policies == null && !screen.policiesFailed) {
        CircularProgressIndicator(Modifier.size(24.dp), color = c.primary, strokeWidth = 2.dp)
        return
    }
    val active = screen.threads.activeCount.takeIf { screen.threads.loaded }
    val rows = PrChecks.of(pr, policies.orEmpty(), active, screen.detail?.workItemIds.orEmpty(), screen.statuses)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (screen.policiesFailed) {
            Text(stringResource(R.string.pr_checks_failed), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        } else if (rows.none { it.kind !is CheckKind.Merge }) {
            Text(stringResource(R.string.pr_checks_none), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
        rows.forEach { row ->
            val onClick = when (val kind = row.kind) {
                is CheckKind.Build -> kind.buildId?.let { id -> { actions.onOpenBuild(pr.project, id) } }
                // The service's own page: SonarQube's analysis
                is CheckKind.Status -> kind.url?.let { url -> { actions.onOpenUrl(url) } }
                else -> null
            }
            CheckLine(row, now, onClick)
        }
    }
}

@Composable
internal fun CheckLine(row: CheckRow, now: Instant, onClick: (() -> Unit)?) {
    val c = AdoTheme.colors
    val (icon, tint) = when (row.state) {
        CheckState.OK -> AdoIcons.CheckCircle to c.success
        CheckState.FAILED -> AdoIcons.Cancel to c.danger
        CheckState.RUNNING -> Icons.Filled.PlayArrow to c.primary
        CheckState.WAITING -> AdoIcons.Hourglass to c.warning
        CheckState.WARNING -> Icons.Filled.Warning to c.warning
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (onClick != null) 48.dp else 32.dp).clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                checkText(row.kind, row.state), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium,
                color = if (onClick != null) c.primary else c.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (!row.blocking) {
                Spacer(Modifier.width(8.dp))
                Text("· ${stringResource(R.string.pr_check_optional)}", style = MaterialTheme.typography.bodySmall, color = c.textFaint, maxLines = 1)
            }
        }
        row.at?.takeIf { row.kind is CheckKind.Build || row.kind is CheckKind.Status }?.let {
            Spacer(Modifier.width(8.dp))
            Text(ago(it, now), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
        }
    }
}

@Composable
private fun checkText(kind: CheckKind, state: CheckState): String = when (kind) {
    is CheckKind.Build -> {
        val name = kind.name ?: kind.buildId?.let { "#$it" }.orEmpty()
        val res = when {
            state == CheckState.OK -> R.string.pr_check_build_ok
            state == CheckState.FAILED -> R.string.pr_check_build_failed
            state == CheckState.RUNNING -> R.string.pr_check_build_running
            kind.expired -> R.string.pr_check_build_expired
            else -> R.string.pr_check_build_queued
        }
        stringResource(res, name).replace(Regex("\\s+"), " ")
    }
    is CheckKind.Reviewers -> pluralStringResource(R.plurals.pr_check_reviewers, kind.approvals, kind.approvals, kind.required)
    is CheckKind.RequiredReviewers -> stringResource(R.string.pr_check_required_reviewers, kind.approvals, kind.required)
    is CheckKind.Comments -> when {
        kind.active == null -> stringResource(R.string.pr_check_comments)
        kind.active > 0 -> stringResource(R.string.pr_check_comments_active, kind.active)
        else -> stringResource(R.string.pr_check_comments_ok)
    }
    is CheckKind.WorkItems -> if (kind.ids.isEmpty()) {
        stringResource(R.string.pr_check_no_work_items)
    } else {
        pluralStringResource(R.plurals.pr_check_work_items, kind.ids.size, kind.ids.joinToString(", ") { "#$it" })
    }
    is CheckKind.Status -> when {
        kind.description != null && !kind.description.equals(kind.name, ignoreCase = true) -> "${kind.name} · ${kind.description}"
        state == CheckState.WAITING -> stringResource(R.string.pr_check_status_waiting, kind.name)
        else -> kind.name
    }
    is CheckKind.Other -> kind.name
    is CheckKind.Merge -> when (kind.status) {
        "succeeded" -> stringResource(R.string.pr_check_merge_ok, kind.target)
        "conflicts" -> stringResource(R.string.pr_check_merge_conflicts, kind.target)
        "queued" -> stringResource(R.string.pr_check_merge_queued, kind.target)
        else -> stringResource(R.string.pr_check_merge_failed, kind.target)
    }
}

/** Me first, then the required reviewers: avatar ringed by the vote, "Required", the vote. */
@Composable
private fun Reviewers(screen: PrScreen, pr: PullRequest) {
    val c = AdoTheme.colors
    val myId = screen.me?.id
    val mine = { r: Reviewer -> r.id.equals(myId, ignoreCase = true) }
    val reviewers = pr.reviewers.sortedWith(compareBy<Reviewer>({ !mine(it) }, { !it.required }, { it.group }))
    if (reviewers.isEmpty()) {
        Text(stringResource(R.string.pr_no_reviewers), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        reviewers.forEach { r ->
            // My vote as being saved
            val vote = if (mine(r)) screen.vote.shown else r.vote
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                val label = stringResource(voteLabel(vote))
                if (vote == Vote.NONE) Avatar(r.name, size = 32.dp, description = label)
                else ReviewerAvatar(r.name, vote, label)
                Spacer(Modifier.width(10.dp))
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (mine(r)) stringResource(R.string.pr_reviewer_you, r.name) else r.name,
                        Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, color = c.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (r.required) {
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.pr_reviewer_required), style = MaterialTheme.typography.bodySmall, color = c.textFaint, maxLines = 1)
                    }
                }
                Spacer(Modifier.width(8.dp))
                VoteBadge(vote)
            }
        }
    }
}

/** A linked work item: type, id, title and state; it opens in the work item details. */
@Composable
private fun LinkedWorkItem(item: WorkItem, onClick: () -> Unit) {
    val c = AdoTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceAlt)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(4.dp, 18.dp).clip(RoundedCornerShape(2.dp)).background(workItemColor(item.type)))
        Spacer(Modifier.width(10.dp))
        Text("${item.type} ${item.id}", style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        StatusChip(item.state, tone = stateTone(item.state))
    }
}
