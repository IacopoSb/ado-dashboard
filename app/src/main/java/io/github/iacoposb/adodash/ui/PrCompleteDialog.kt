package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.MergeStrategy
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.PullRequestDetail
import io.github.iacoposb.adodash.ui.components.AdoButton
import io.github.iacoposb.adodash.ui.components.AdoCheckbox
import io.github.iacoposb.adodash.ui.components.AdoIcons
import io.github.iacoposb.adodash.ui.components.AdoTextField
import io.github.iacoposb.adodash.ui.components.Banner
import io.github.iacoposb.adodash.ui.components.BannerTone
import io.github.iacoposb.adodash.ui.components.ButtonStyle
import io.github.iacoposb.adodash.ui.components.DropdownField
import io.github.iacoposb.adodash.ui.components.DropdownOption
import io.github.iacoposb.adodash.ui.components.ModalOverlay
import io.github.iacoposb.adodash.ui.components.SwitchRow
import io.github.iacoposb.adodash.ui.components.TextAction
import java.time.Instant

/**
 * "Complete" in the page's bar: disabled for a draft (published first), "Completing…" while
 * Azure DevOps merges it.
 */
@Composable
internal fun CompleteButton(screen: PrScreen, actions: PrActions) {
    val c = AdoTheme.colors
    val pr = screen.pr ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (pr.draft) {
            Text(stringResource(R.string.pr_complete_publish_draft), style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1)
            Spacer(Modifier.width(8.dp))
        }
        AdoButton(
            stringResource(if (screen.close.completing) R.string.pr_completing else R.string.pr_complete),
            actions.onOpenComplete,
            style = ButtonStyle.Outlined,
            icon = AdoIcons.MergeType,
            enabled = !pr.draft && !screen.close.completing,
        )
    }
}

/**
 * What the page says of its state under the bar: abandoned (with "Reactivate"), completed,
 * completing, auto-complete set (with "Cancel auto-complete"), and what was refused while the
 * dialog was closed.
 */
@Composable
internal fun CloseBanners(screen: PrScreen, pr: PullRequest, actions: PrActions) {
    val detail = screen.detail ?: return
    val close = screen.close
    val modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
    val by = detail.closedBy?.name?.takeIf { it.isNotBlank() }
    when {
        screen.status == PullRequestDetail.ABANDONED -> Banner(
            if (by != null) stringResource(R.string.pr_abandoned_by, by) else stringResource(R.string.pr_abandoned_banner),
            BannerTone.Warning, modifier,
        ) {
            if (close.busy == CloseAction.REACTIVATE) BusyIndicator()
            else TextAction(stringResource(R.string.pr_reactivate), actions.onReactivate)
        }
        screen.status == PullRequestDetail.COMPLETED -> Banner(
            if (by != null) stringResource(R.string.pr_completed_by, by, pr.targetBranch)
            else stringResource(R.string.pr_completed_banner, pr.targetBranch),
            BannerTone.Info, modifier, icon = AdoIcons.CheckCircle,
        )
        close.completing && close.dialog == null -> Banner(
            stringResource(R.string.pr_completing_banner, pr.targetBranch), BannerTone.Info, modifier,
        ) { BusyIndicator() }
        detail.autoCompleteSetBy != null -> Banner(
            stringResource(R.string.pr_auto_complete_on, detail.autoCompleteSetBy.name), BannerTone.Info, modifier, icon = AdoIcons.MergeType,
        ) {
            if (close.busy == CloseAction.CANCEL_AUTO_COMPLETE) BusyIndicator()
            else TextAction(stringResource(R.string.pr_auto_complete_cancel), actions.onCancelAutoComplete)
        }
    }
    val error = close.error
    if (error != null && close.dialog == null) {
        Banner(closeErrorText(error, pr), if (error.problem == CloseProblem.SLOW) BannerTone.Info else BannerTone.Error, modifier) {
            TextAction(stringResource(R.string.pr_dismiss), actions.onDismissCloseError)
        }
    }
}

@Composable
private fun BusyIndicator() {
    CircularProgressIndicator(Modifier.padding(horizontal = 16.dp).size(20.dp), color = AdoTheme.colors.primary, strokeWidth = 2.dp)
}

/** Why a close action went wrong, as the user reads it. */
@Composable
internal fun closeErrorText(error: CloseError, pr: PullRequest): String = when (error.problem) {
    CloseProblem.DENIED -> stringResource(
        when {
            error.bypass -> R.string.pr_bypass_denied
            error.action == CloseAction.COMPLETE -> R.string.pr_complete_denied
            error.action == CloseAction.ABANDON -> R.string.pr_abandon_denied
            error.action == CloseAction.REACTIVATE -> R.string.pr_reactivate_denied
            else -> R.string.pr_auto_complete_denied
        },
    )
    CloseProblem.STALE -> stringResource(R.string.pr_close_stale)
    CloseProblem.MERGE_FAILED -> if (pr.hasConflicts) stringResource(R.string.pr_merge_conflicts, pr.targetBranch)
    else stringResource(R.string.pr_merge_failed, error.message)
    CloseProblem.SLOW -> stringResource(R.string.pr_complete_slow)
    CloseProblem.OTHER -> stringResource(
        when (error.action) {
            CloseAction.COMPLETE -> R.string.pr_complete_failed
            CloseAction.ABANDON -> R.string.pr_abandon_failed
            CloseAction.REACTIVATE -> R.string.pr_reactivate_failed
            else -> R.string.pr_auto_complete_failed
        },
        error.message,
    )
}

@Composable
private fun strategyLabel(s: MergeStrategy): String = stringResource(
    when (s) {
        MergeStrategy.NO_FAST_FORWARD -> R.string.pr_merge_no_ff
        MergeStrategy.SQUASH -> R.string.pr_merge_squash
        MergeStrategy.REBASE -> R.string.pr_merge_rebase
        MergeStrategy.REBASE_MERGE -> R.string.pr_merge_rebase_merge
    },
)

@Composable
private fun strategyDescription(s: MergeStrategy, target: String): String = stringResource(
    when (s) {
        MergeStrategy.NO_FAST_FORWARD -> R.string.pr_merge_no_ff_desc
        MergeStrategy.SQUASH -> R.string.pr_merge_squash_desc
        MergeStrategy.REBASE -> R.string.pr_merge_rebase_desc
        MergeStrategy.REBASE_MERGE -> R.string.pr_merge_rebase_merge_desc
    },
    target,
)

/**
 * Azure DevOps' complete dialog: the merge type the branch policy allows, the merge commit's
 * message, deleting the branch and completing the work items. When required checks have not
 * passed, they are listed and auto-complete is offered instead, or the policies bypassed with a
 * reason; "Completing…" while Azure DevOps merges it.
 */
@Composable
internal fun CompleteDialog(screen: PrScreen, pr: PullRequest, draft: CompleteDraft, now: Instant, actions: PrActions) {
    val c = AdoTheme.colors
    val close = screen.close
    val busy = close.busy != null || close.merging
    val blocked = screen.blocked
    val title = stringResource(R.string.pr_complete_title)
    val edit = actions.onEditComplete
    ModalOverlay(title, onDismiss = { if (!busy) actions.onDismissComplete() }, maxWidth = if (blocked) 960.dp else 640.dp) { fullScreen ->
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, color = c.text)
                Text(
                    "!${pr.id}  ${pr.title}", style = MaterialTheme.typography.bodyMedium, color = c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${pr.repoName} · ${pr.sourceBranch} → ${pr.targetBranch}", style = MaterialTheme.typography.bodySmall,
                    color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = actions.onDismissComplete, enabled = !busy) {
                Icon(Icons.Filled.Close, stringResource(R.string.action_close), tint = c.textMuted)
            }
        }
        HorizontalDivider(color = c.outline)
        // Tablets: what blocks it beside the options, so that both fit without scrolling
        val twoColumns = blocked && !fullScreen
        Column(
            Modifier.weight(1f, fill = fullScreen).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            screen.detail?.autoCompleteSetBy?.let { who ->
                Banner(stringResource(R.string.pr_auto_complete_on, who.name), BannerTone.Info, icon = AdoIcons.MergeType) {
                    if (close.busy == CloseAction.CANCEL_AUTO_COMPLETE) BusyIndicator()
                    else TextAction(stringResource(R.string.pr_auto_complete_cancel), actions.onCancelAutoComplete)
                }
            }
            if (twoColumns) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Blockers(screen, pr, draft.bypass, now, actions)
                        Bypass(draft, busy, edit)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        MergeOptions(pr, draft, busy, edit)
                    }
                }
            } else {
                if (blocked) {
                    Blockers(screen, pr, draft.bypass, now, actions)
                    Bypass(draft, busy, edit)
                }
                MergeOptions(pr, draft, busy, edit)
            }
            close.error?.let { error ->
                Banner(closeErrorText(error, pr), if (error.problem == CloseProblem.SLOW) BannerTone.Info else BannerTone.Error)
            }
        }
        HorizontalDivider(color = c.outline)
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            if (close.busy == CloseAction.COMPLETE || close.merging || close.busy == CloseAction.AUTO_COMPLETE) {
                CircularProgressIndicator(Modifier.size(20.dp), color = c.primary, strokeWidth = 2.dp)
                if (close.busy == CloseAction.COMPLETE || close.merging) {
                    Text(stringResource(R.string.pr_completing), style = MaterialTheme.typography.bodyMedium, color = c.textMuted, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            if (!fullScreen) AdoButton(stringResource(R.string.action_cancel), actions.onDismissComplete, style = ButtonStyle.Outlined, enabled = !busy)
            when {
                blocked && !draft.bypass -> AdoButton(
                    stringResource(if (screen.detail?.autoCompleteSetBy != null) R.string.pr_auto_complete_update else R.string.pr_auto_complete_set),
                    actions.onSetAutoComplete, icon = AdoIcons.MergeType,
                    enabled = !busy && PrCompletion.canAutoComplete(pr, screen.status, draft),
                )
                else -> AdoButton(
                    stringResource(if (draft.bypass) R.string.pr_bypass_complete else R.string.pr_complete),
                    actions.onComplete,
                    style = if (draft.bypass) ButtonStyle.Danger else ButtonStyle.Filled,
                    icon = AdoIcons.MergeType,
                    enabled = !busy && PrCompletion.canComplete(pr, screen.status, blocked, draft),
                )
            }
        }
    }
}

/** Merge type with what it does, the merge commit's message, the branch and the work items. */
@Composable
private fun MergeOptions(pr: PullRequest, draft: CompleteDraft, busy: Boolean, edit: (CompleteDraft) -> Unit) {
    val c = AdoTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val label = stringResource(R.string.pr_merge_type)
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        DropdownField(strategyLabel(draft.strategy), label, enabled = !busy && draft.allowed.size > 1) { dismiss ->
            draft.allowed.forEach { s ->
                DropdownOption(strategyLabel(s), selected = s == draft.strategy) {
                    dismiss()
                    edit(draft.copy(strategy = s))
                }
            }
        }
        Text(
            if (draft.allowed.size == 1) stringResource(R.string.pr_merge_only_allowed)
            else strategyDescription(draft.strategy, pr.targetBranch),
            style = MaterialTheme.typography.bodySmall, color = c.textMuted,
        )
    }
    if (draft.strategy.hasMessage) {
        AdoTextField(
            draft.message, { edit(draft.copy(message = it)) }, stringResource(R.string.pr_merge_message),
            enabled = !busy, singleLine = false, minLines = 2,
        )
    }
    Column {
        CheckboxRow(stringResource(R.string.pr_delete_branch, pr.sourceBranch), draft.deleteSourceBranch, !busy) {
            edit(draft.copy(deleteSourceBranch = it))
        }
        CheckboxRow(stringResource(R.string.pr_transition_work_items), draft.transitionWorkItems, !busy) {
            edit(draft.copy(transitionWorkItems = it))
        }
    }
}

/** "Bypass policies", and once on the reason, without which it does not complete. */
@Composable
private fun Bypass(draft: CompleteDraft, busy: Boolean, edit: (CompleteDraft) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AdoTheme.colors.surfaceAlt).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SwitchRow(
            stringResource(R.string.pr_bypass), draft.bypass, { edit(draft.copy(bypass = it)) },
            subtitle = stringResource(R.string.pr_bypass_hint), enabled = !busy,
            titleStyle = MaterialTheme.typography.titleSmall,
        )
        if (draft.bypass) {
            AdoTextField(
                draft.bypassReason, { edit(draft.copy(bypassReason = it)) }, stringResource(R.string.pr_bypass_reason),
                Modifier.padding(bottom = 8.dp), hint = stringResource(R.string.pr_bypass_reason_hint),
                enabled = !busy, singleLine = false, minLines = 2,
            )
        }
    }
}

/**
 * What keeps it from completing: the checks not passed, as in "Checks and policies", with
 * "Re-queue" on the builds expired or failed.
 */
@Composable
private fun Blockers(screen: PrScreen, pr: PullRequest, bypassing: Boolean, now: Instant, actions: PrActions) {
    val active = screen.threads.activeCount.takeIf { screen.threads.loaded }
    val rows = PrCompletion.blockers(PrChecks.of(pr, screen.policies.orEmpty(), active, screen.detail?.workItemIds.orEmpty(), screen.statuses))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Banner(
            stringResource(
                when {
                    bypassing -> R.string.pr_blocked_bypassing
                    rows.isEmpty() -> R.string.pr_blocked_generic
                    else -> R.string.pr_blocked
                },
            ),
            if (bypassing) BannerTone.Error else BannerTone.Warning,
        )
        val expired = if (screen.canRequeue) PrChecks.expiredBuilds(screen.policies) else emptyList()
        if (expired.size > 1) RequeueAll(screen, expired, actions, Modifier.align(Alignment.End))
        if (rows.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 4.dp)) {
                rows.forEach { CheckLine(it, now, onClick = null, requeueOf(screen, it, actions), requeuing(screen, it)) }
            }
        }
        screen.requeue.error?.let { RequeueErrorBanner(it, actions) }
    }
}

/** A choice of the dialog: the whole row toggles. */
@Composable
private fun CheckboxRow(text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AdoCheckbox(ToggleableState(checked), null)
        Spacer(Modifier.width(4.dp))
        Text(
            text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) AdoTheme.colors.text else AdoTheme.colors.textMuted,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Abandon !311?": it leaves the dashboard; it can be reactivated. */
@Composable
internal fun AbandonConfirm(pr: PullRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = AdoTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(stringResource(R.string.pr_abandon_title, pr.id), color = c.text) },
        text = { Text(stringResource(R.string.pr_abandon_text), style = MaterialTheme.typography.bodyMedium, color = c.textMuted) },
        confirmButton = {
            TextAction(stringResource(R.string.pr_abandon), {
                onDismiss()
                onConfirm()
            }, color = c.danger)
        },
        dismissButton = { TextAction(stringResource(R.string.action_cancel), onDismiss) },
    )
}
