package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PrThread
import io.github.iacoposb.adodash.api.ThreadStatus
import io.github.iacoposb.adodash.ui.components.Avatar
import io.github.iacoposb.adodash.ui.components.DropdownOption
import io.github.iacoposb.adodash.ui.components.Tone
import io.github.iacoposb.adodash.ui.components.colors
import java.time.Instant

@androidx.annotation.StringRes
internal fun threadStatusLabel(status: String?): Int = when (status) {
    ThreadStatus.ACTIVE -> R.string.pr_thread_active
    ThreadStatus.FIXED -> R.string.pr_thread_fixed
    ThreadStatus.WONT_FIX -> R.string.pr_thread_wont_fix
    ThreadStatus.CLOSED -> R.string.pr_thread_closed
    ThreadStatus.BY_DESIGN -> R.string.pr_thread_by_design
    ThreadStatus.PENDING -> R.string.pr_thread_pending
    else -> R.string.pr_thread_active
}

private fun threadTone(status: String?) = when (status) {
    ThreadStatus.ACTIVE -> Tone.Warning
    ThreadStatus.PENDING -> Tone.Info
    ThreadStatus.FIXED -> Tone.Success
    else -> Tone.Neutral
}

/** "src/payments/Gateway.kt · line 88", for a thread on a file. */
@Composable
internal fun threadPlace(thread: PrThread): String? {
    val path = thread.filePath?.removePrefix("/") ?: return null
    return thread.line?.let { stringResource(R.string.pr_thread_line, path, it) } ?: path
}

/**
 * A thread as the design system draws it: the first comment's author, age and the status (a
 * menu to change it), the file and line, the text, then "2 replies". [expanded] shows the replies
 * and the reply composer; tapping a collapsed thread expands it.
 */
@Composable
internal fun ThreadCard(
    thread: PrThread,
    screen: PrScreen,
    now: Instant,
    actions: PrActions,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    initialStatusMenu: Boolean = false,
    /** A new thread being posted: "Sending…" in place of its age and status. */
    sending: Boolean = false,
) {
    val c = AdoTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val first = thread.comments.first()
    val replies = thread.comments.drop(1)
    val open = ThreadStatus.isOpen(thread.status)
    val posting = screen.threads.sending.filter { it.threadId == thread.id }
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(if (highlighted) c.primaryContainer else c.surface)
            .border(if (highlighted) 2.dp else 1.dp, if (highlighted) c.primary else c.outline, shape)
            .then(if (expanded) Modifier else Modifier.clickable(role = Role.Button, onClick = onToggle))
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(first.author.name.ifBlank { "?" }, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    first.author.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, color = c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                if (sending) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = c.primary, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.wi_comment_sending), style = MaterialTheme.typography.bodySmall, color = c.primary, maxLines = 1)
                } else {
                    Text(ago(first.publishedAt, now), style = MaterialTheme.typography.bodySmall, color = c.textFaint, maxLines = 1)
                }
            }
            if (!sending) StatusMenu(thread, screen, actions, initialStatusMenu)
        }
        threadPlace(thread)?.let {
            Text(
                it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        CommentText(
            first, screen, actions, Modifier.padding(top = 8.dp), muted = !open || sending,
            background = if (highlighted) c.primaryContainer else c.surface,
        )
        if (sending) {
            // Nothing more until it is posted
        } else if (!expanded) {
            Text(
                if (replies.isEmpty()) stringResource(R.string.pr_thread_no_replies)
                else pluralStringResource(R.plurals.pr_thread_replies, replies.size, replies.size),
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.textMuted,
            )
        } else {
            replies.forEach { Reply(it, screen, now, actions, muted = !open) }
            posting.forEach { s ->
                Reply(
                    PrComment(thread.id, -s.localId, io.github.iacoposb.adodash.api.Identity(screen.me?.id.orEmpty(), screen.me?.name.orEmpty()), s.markdown, s.at),
                    screen, now, actions, muted = true, sending = true,
                )
            }
            CommentComposer(
                key = "${screen.id}:${thread.id}",
                composer = screen.composer(thread.id),
                search = screen.mentions,
                onSearchMentions = actions.onSearchMentions,
                onDraftChange = { actions.onDraftChange(thread.id, it) },
                onSend = { actions.onSend(thread.id) },
                error = screen.threads.sendErrors[thread.id]?.let { commentErrorText(it) },
                placeholder = stringResource(R.string.pr_reply),
                modifier = Modifier.padding(top = 12.dp),
                compact = true,
            )
        }
        screen.threads.statusErrors[thread.id]?.let { error ->
            Text(
                if (error.problem == SaveProblem.DENIED) stringResource(R.string.pr_status_denied)
                else stringResource(R.string.pr_status_failed, error.message),
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = c.danger,
            )
        }
    }
}

@Composable
internal fun commentErrorText(error: CommentError): String =
    if (error.problem == SaveProblem.DENIED) stringResource(R.string.pr_comment_denied)
    else stringResource(R.string.wi_comment_not_sent, error.message)

/** The status chip, opening the menu of statuses; a spinner while a change is saved. */
@Composable
private fun StatusMenu(thread: PrThread, screen: PrScreen, actions: PrActions, initiallyOpen: Boolean) {
    val c = AdoTheme.colors
    var open by remember { mutableStateOf(initiallyOpen) }
    val saving = thread.id in screen.threads.statusPending
    val (bg, fg) = threadTone(thread.status).colors()
    val label = stringResource(threadStatusLabel(thread.status))
    val description = stringResource(R.string.pr_thread_status)
    Box {
        Row(
            Modifier.height(24.dp).clip(RoundedCornerShape(6.dp)).background(bg)
                .clickable(enabled = !saving && screen.pr != null, role = Role.DropdownList) { open = true }
                .semantics {
                    contentDescription = description
                    stateDescription = label
                }
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
            if (saving) {
                Spacer(Modifier.width(4.dp))
                CircularProgressIndicator(Modifier.size(12.dp), color = fg, strokeWidth = 1.5.dp)
                Spacer(Modifier.width(2.dp))
            } else {
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(16.dp), tint = fg)
            }
        }
        DropdownMenu(open, { open = false }, containerColor = c.surface) {
            ThreadStatus.all.forEach { status ->
                DropdownOption(stringResource(threadStatusLabel(status)), selected = status == thread.status) {
                    open = false
                    actions.onSetStatus(thread.id, status)
                }
            }
        }
    }
}

/** A reply: smaller avatar, author and age, the text. */
@Composable
private fun Reply(comment: PrComment, screen: PrScreen, now: Instant, actions: PrActions, muted: Boolean, sending: Boolean = false) {
    val c = AdoTheme.colors
    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Avatar(comment.author.name.ifBlank { "?" }, size = 24.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.author.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall,
                    color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                if (sending) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = c.primary, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.wi_comment_sending), style = MaterialTheme.typography.bodySmall, color = c.primary)
                } else {
                    Text(ago(comment.publishedAt, now), style = MaterialTheme.typography.bodySmall, color = c.textFaint, maxLines = 1)
                }
            }
            CommentText(comment, screen, actions, Modifier.padding(top = 4.dp), muted = muted)
        }
    }
}

/** A comment's markdown, mentions as names. */
@Composable
internal fun CommentText(
    comment: PrComment,
    screen: PrScreen,
    actions: PrActions,
    modifier: Modifier,
    muted: Boolean,
    /** Behind the text: what a long comment fades into. */
    background: Color = AdoTheme.colors.surface,
) {
    val c = AdoTheme.colors
    val unknown = stringResource(R.string.pr_someone)
    val blocks = remember(comment.text, screen.names, unknown) { Markdown.parse(comment.text, screen.names, unknown) }
    CollapsibleText("pr:${screen.id}:${comment.threadId}:${comment.id}", modifier, background) {
        RichText(blocks, actions.onOpenUrl, color = if (muted) c.textMuted else c.text)
    }
}

/** The new thread's composer at the bottom of the threads. */
@Composable
internal fun NewThreadComposer(screen: PrScreen, actions: PrActions, modifier: Modifier = Modifier, background: Color = AdoTheme.colors.surfaceAlt) {
    CommentComposer(
        key = "${screen.id}:new",
        composer = screen.composer(PrScreen.NEW_THREAD),
        search = screen.mentions,
        onSearchMentions = actions.onSearchMentions,
        onDraftChange = { actions.onDraftChange(PrScreen.NEW_THREAD, it) },
        onSend = { actions.onSend(PrScreen.NEW_THREAD) },
        error = screen.threads.sendErrors[PrScreen.NEW_THREAD]?.let { commentErrorText(it) },
        placeholder = stringResource(R.string.pr_new_comment),
        modifier = modifier,
        background = background,
    )
}

/** New threads being sent: shown at once, above the composer. */
@Composable
internal fun SendingThreads(screen: PrScreen, now: Instant, actions: PrActions) {
    screen.threads.sending.filter { it.threadId == PrScreen.NEW_THREAD }.forEach { s ->
        val me = io.github.iacoposb.adodash.api.Identity(screen.me?.id.orEmpty(), screen.me?.name.orEmpty())
        val thread = PrThread(-s.localId, ThreadStatus.ACTIVE, listOf(PrComment(-s.localId, -s.localId, me, s.markdown, s.at)))
        ThreadCard(thread, screen, now, actions, expanded = false, onToggle = {}, sending = true)
    }
}

/** The filter of the Threads tab. */
@Composable
internal fun ThreadFilterChips(screen: PrScreen, actions: PrActions, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val shown = screen.threads.shown
        listOf(
            ThreadFilter.ACTIVE to R.string.pr_filter_active,
            ThreadFilter.RESOLVED to R.string.pr_filter_resolved,
            ThreadFilter.ALL to R.string.pr_filter_all,
        ).forEach { (filter, label) ->
            val count = shown.count { filter.accepts(it) }
            io.github.iacoposb.adodash.ui.components.AdoFilterChip(
                "${stringResource(label)} · $count", screen.filter == filter, { actions.onSelectFilter(filter) },
            )
        }
    }
}
