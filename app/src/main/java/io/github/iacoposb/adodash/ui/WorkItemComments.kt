package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.Person
import io.github.iacoposb.adodash.sync.Events
import io.github.iacoposb.adodash.ui.components.Avatar
import io.github.iacoposb.adodash.ui.components.TextAction
import kotlinx.coroutines.delay
import java.time.Instant

/** How long the comment opened from a mention stays highlighted. */
private const val HIGHLIGHT_MS = 2_500L

/** The comments section, for the screenshots to scroll to. */
const val COMMENTS_TAG = "work-item-comments"

/**
 * The comments, oldest first like in Azure DevOps, with "Load earlier comments" above them and
 * those being sent below. The [WorkItemPage.target] is scrolled to and highlighted once read,
 * and [composerFocus] focused for the reply.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CommentsSection(
    page: WorkItemPage,
    me: Person?,
    now: Instant,
    actions: WorkItemActions,
    composerFocus: FocusRequester,
) {
    val c = AdoTheme.colors
    val comments = page.comments
    val count = maxOf(comments.total, comments.items.size, if (comments.loaded) 0 else page.detail?.commentCount ?: 0) +
        comments.sending.size
    val requesters = remember(page.id) { mutableMapOf<Int, BringIntoViewRequester>() }
    var highlighted by remember(page.id) { mutableStateOf<Int?>(null) }
    val target = page.target
    LaunchedEffect(target, comments.loaded) {
        if (target != null && comments.loaded && comments.items.any { it.id == target }) {
            highlighted = target
            actions.onTargetShown()
        }
    }
    LaunchedEffect(highlighted) {
        val id = highlighted ?: return@LaunchedEffect
        // Laid out first, then scrolled to
        delay(50)
        requesters[id]?.bringIntoView()
        runCatching { composerFocus.requestFocus() }
        delay(HIGHLIGHT_MS)
        highlighted = null
    }
    Column(Modifier.testTag(COMMENTS_TAG)) {
        Text(
            (if (count > 0) "${stringResource(R.string.wi_comments)} · $count" else stringResource(R.string.wi_comments)).uppercase(),
            Modifier.padding(bottom = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.labelSmall, color = c.textMuted,
        )
        when {
            !comments.loaded -> CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), color = c.primary, strokeWidth = 2.dp)
            comments.error != null && comments.items.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.wi_comments_failed, comments.error), Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium, color = c.danger,
                )
                TextAction(stringResource(R.string.action_retry), actions.onRetryComments)
            }
            comments.items.isEmpty() && comments.sending.isEmpty() ->
                Text(stringResource(R.string.wi_no_comments), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (comments.earlier != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextAction(stringResource(R.string.wi_comments_earlier), actions.onLoadEarlierComments)
                    if (comments.loadingEarlier) CircularProgressIndicator(Modifier.size(16.dp), color = c.primary, strokeWidth = 2.dp)
                }
            }
            comments.items.forEach { comment ->
                val requester = requesters.getOrPut(comment.id) { BringIntoViewRequester() }
                CommentCard(
                    author = comment.author.name,
                    html = comment.text,
                    mentionsMe = me != null && comment.author.id != me.id && Events.mentions(comment.text, Identity(me.id, me.name)),
                    edited = comment.edited,
                    at = comment.createdAt,
                    now = now,
                    highlighted = highlighted == comment.id,
                    sending = false,
                    onLink = actions.onOpenUrl,
                    modifier = Modifier.bringIntoViewRequester(requester),
                )
            }
            comments.sending.forEach { s ->
                CommentCard(
                    author = me?.name.orEmpty(), html = s.html, mentionsMe = false, edited = false, at = s.at, now = now,
                    highlighted = false, sending = true, onLink = actions.onOpenUrl,
                )
            }
            if (comments.error != null && comments.items.isNotEmpty()) {
                Text(stringResource(R.string.wi_comments_failed, comments.error), style = MaterialTheme.typography.bodySmall, color = c.danger)
            }
        }
    }
}

/**
 * A comment as in the design system's thread: avatar, author and how long ago (the date and
 * time on a long press), "mentioned you", then the text with its mentions, links and code.
 */
@Composable
private fun CommentCard(
    author: String,
    html: String,
    mentionsMe: Boolean,
    edited: Boolean,
    at: Instant,
    now: Instant,
    highlighted: Boolean,
    sending: Boolean,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AdoTheme.colors
    val shape = RoundedCornerShape(12.dp)
    var absolute by remember { mutableStateOf(false) }
    val blocks = remember(html) { HtmlText.parse(html) }
    Column(
        modifier.fillMaxWidth().clip(shape)
            .background(if (highlighted) c.primaryContainer else c.surface)
            .border(if (highlighted) 2.dp else 1.dp, if (highlighted) c.primary else c.outline, shape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onLongPress = { absolute = !absolute }) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(author.ifBlank { "?" }, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                author, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, color = c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            if (sending) {
                CircularProgressIndicator(Modifier.size(12.dp), color = c.primary, strokeWidth = 1.5.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.wi_comment_sending), style = MaterialTheme.typography.bodySmall, color = c.primary, maxLines = 1)
            } else {
                val time = if (absolute) dateTime(at) else ago(at, now)
                val full = dateTime(at)
                Text(
                    listOfNotNull(time, stringResource(R.string.wi_comment_edited).takeIf { edited }).joinToString(" · "),
                    Modifier.semantics { contentDescription = full },
                    style = MaterialTheme.typography.bodySmall, color = c.textFaint, maxLines = 1,
                )
            }
        }
        if (mentionsMe) {
            Text(
                stringResource(R.string.wi_comment_mentioned_you), Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = c.primary,
            )
        }
        RichText(blocks, onLink, Modifier.padding(top = 8.dp), color = if (sending) c.textMuted else c.text)
    }
}

/**
 * The composer at the bottom of the details: what to write, with the people offered for the
 * mention being typed above it, and why the last comment was not sent below it.
 */
@Composable
internal fun ComposerArea(
    modal: WorkItemModal,
    actions: WorkItemActions,
    focusRequester: FocusRequester,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val c = AdoTheme.colors
    val page = modal.page
    val composer = page.composer
    var value by remember(page.id, composer.revision) {
        mutableStateOf(TextFieldValue(composer.draft.text, TextRange(composer.draft.text.length)))
    }
    var mentions by remember(page.id, composer.revision) { mutableStateOf(composer.draft.mentions) }
    val draft = Draft(value.text, mentions)
    val query = remember(value, mentions) {
        if (value.selection.collapsed) MentionTyping.query(draft, value.selection.start) else null
    }
    LaunchedEffect(query?.query) { actions.onSearchMentions(query?.query.orEmpty()) }

    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = c.outline)
        val search = modal.mentions
        val suggestions = search.results
        if (query != null && search.query.equals(query.query.trim(), ignoreCase = true) && (suggestions.isNotEmpty() || search.loading)) {
            MentionSuggestions(search, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) { person ->
                val (picked, cursor) = MentionTyping.insert(draft, query, person)
                value = TextFieldValue(picked.text, TextRange(cursor))
                mentions = picked.mentions
                actions.onDraftChange(picked)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(c.surfaceAlt)
                    .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val primary = c.primary
                val container = c.primaryContainer
                BasicTextField(
                    value,
                    { v ->
                        value = v
                        val edited = Draft(v.text, mentions).pruned()
                        mentions = edited.mentions
                        actions.onDraftChange(edited)
                    },
                    Modifier.weight(1f).padding(vertical = 8.dp).focusRequester(focusRequester)
                        .semantics { contentDescription = placeholder },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
                    cursorBrush = SolidColor(c.primary),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    visualTransformation = remember(mentions, primary, container) { MentionHighlight(mentions, primary, container) },
                    decorationBox = { inner ->
                        Box {
                            if (value.text.isEmpty()) {
                                Text(
                                    placeholder, style = MaterialTheme.typography.bodyMedium, color = c.textFaint,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                val canSend = !draft.isBlank
                val send = stringResource(R.string.wi_comment_send)
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(c.primary)
                        .clickable(enabled = canSend, role = Role.Button, onClickLabel = send) { actions.onSend() }
                        .semantics { contentDescription = send },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(20.dp), tint = c.onPrimary)
                }
            }
            page.comments.sendError?.let { error ->
                Text(
                    when (error.problem) {
                        SaveProblem.DENIED -> stringResource(R.string.wi_comment_denied)
                        else -> stringResource(R.string.wi_comment_not_sent, error.message)
                    },
                    Modifier.padding(horizontal = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = c.danger,
                )
            }
        }
    }
}

/** The mentions picked, in the text as they will read: "@Anna Verdi" highlighted. */
private class MentionHighlight(
    private val people: List<Person>,
    private val color: androidx.compose.ui.graphics.Color,
    private val background: androidx.compose.ui.graphics.Color,
) : VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): TransformedText {
        val ranges = CommentMarkup.ranges(text.text, people)
        if (ranges.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val styled = buildAnnotatedString {
            append(text)
            ranges.forEach { (range, _) ->
                addStyle(SpanStyle(color = color, background = background, fontWeight = FontWeight.Medium), range.first, range.last + 1)
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is MentionHighlight && other.people == people && other.color == color &&
        other.background == background

    override fun hashCode() = people.hashCode()
}

/** The people offered for a mention: avatar, name and e-mail, those of the comments first. */
@Composable
private fun MentionSuggestions(search: PeopleSearch, modifier: Modifier, onPick: (Person) -> Unit) {
    val c = AdoTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().shadow(6.dp, shape).clip(shape).background(c.surface).border(1.dp, c.outline, shape)
            .padding(vertical = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.wi_mention_people).uppercase(), Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall, color = c.textMuted,
            )
            if (search.loading) CircularProgressIndicator(Modifier.size(12.dp), color = c.textMuted, strokeWidth = 1.5.dp)
        }
        search.results.forEach { p ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onPick(p) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(p.name, size = 28.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    highlightMatch(p.name, search.query), style = MaterialTheme.typography.bodyMedium, color = c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                p.uniqueName.takeIf { it.isNotBlank() && it != p.name }?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, color = c.textMuted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** [name] with the part matching [query] in bold. */
private fun highlightMatch(name: String, query: String) = buildAnnotatedString {
    val at = name.indexOf(query, ignoreCase = true)
    if (query.isBlank() || at < 0) {
        append(name)
    } else {
        append(name.substring(0, at))
        pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
        append(name.substring(at, at + query.length))
        pop()
        append(name.substring(at + query.length))
    }
}

/** "Reply to Luca Rossi… (@ to mention)": to the latest comment's author, unless it is me. */
@Composable
internal fun composerPlaceholder(page: WorkItemPage, me: Person?, short: Boolean): String {
    val last = page.comments.items.lastOrNull()?.author?.takeIf { a -> a.name.isNotBlank() && !a.id.equals(me?.id, ignoreCase = true) }
    val name = last?.name?.let { if (short) it.substringBefore(' ') else it }
    return when {
        name != null && short -> stringResource(R.string.wi_comment_reply_short, name)
        name != null -> stringResource(R.string.wi_comment_reply, name)
        short -> stringResource(R.string.wi_comment_add_short)
        else -> stringResource(R.string.wi_comment_add)
    }
}
