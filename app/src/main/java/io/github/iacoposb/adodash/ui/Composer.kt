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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.Person
import io.github.iacoposb.adodash.ui.components.Avatar

/**
 * A comment composer: the pill with the text and the send button, the people offered for the
 * mention being typed above it, [error] (why the last one was not sent) below it. [composer]
 * changes the text only when its revision does; [key] tells one composer from another (a work
 * item, a thread) so that each keeps its own text.
 */
@Composable
fun CommentComposer(
    key: Any,
    composer: ComposerState,
    search: PeopleSearch,
    onSearchMentions: (String) -> Unit,
    onDraftChange: (Draft) -> Unit,
    onSend: () -> Unit,
    error: String?,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
    /** A reply in a thread: lower, with a smaller send button. */
    compact: Boolean = false,
    background: Color = AdoTheme.colors.surfaceAlt,
    /** The field took or lost the focus: only the focused composer looks for mentions. */
    onFocus: (Boolean) -> Unit = {},
) {
    val c = AdoTheme.colors
    var value by remember(key, composer.revision) {
        mutableStateOf(TextFieldValue(composer.draft.text, TextRange(composer.draft.text.length)))
    }
    var mentions by remember(key, composer.revision) { mutableStateOf(composer.draft.mentions) }
    var focused by remember(key) { mutableStateOf(false) }
    val draft = Draft(value.text, mentions)
    val query = remember(value, mentions) {
        if (value.selection.collapsed) MentionTyping.query(draft, value.selection.start) else null
    }
    LaunchedEffect(query?.query, focused) { if (focused || query == null) onSearchMentions(query?.query.orEmpty()) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val suggestions = search.results
        if (query != null && search.query.equals(query.query.trim(), ignoreCase = true) && (suggestions.isNotEmpty() || search.loading)) {
            MentionSuggestions(search, Modifier.padding(bottom = 2.dp)) { person ->
                val (picked, cursor) = MentionTyping.insert(draft, query, person)
                value = TextFieldValue(picked.text, TextRange(cursor))
                mentions = picked.mentions
                onDraftChange(picked)
            }
        }
        val height: Dp = if (compact) 44.dp else 52.dp
        Row(
            Modifier.fillMaxWidth().heightIn(min = height).clip(RoundedCornerShape(height / 2)).background(background)
                .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
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
                    onDraftChange(edited)
                },
                Modifier.weight(1f).padding(vertical = 8.dp).focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
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
            val button = if (compact) 34.dp else 40.dp
            Box(
                Modifier.size(button).clip(CircleShape).background(if (canSend || !compact) c.primary else c.outline)
                    .clickable(enabled = canSend, role = Role.Button, onClickLabel = send) { onSend() }
                    .semantics { contentDescription = send },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(if (compact) 17.dp else 20.dp), tint = c.onPrimary)
            }
        }
        if (error != null) {
            Text(error, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall, color = c.danger)
        }
    }
}

/** The mentions picked, in the text as they will read: "@Anna Verdi" highlighted. */
private class MentionHighlight(
    private val people: List<Person>,
    private val color: Color,
    private val background: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
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
