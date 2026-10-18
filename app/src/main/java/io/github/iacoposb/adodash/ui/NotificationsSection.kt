package io.github.iacoposb.adodash.ui

import android.icu.util.ULocale
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.data.NotificationPrefs
import io.github.iacoposb.adodash.data.NotifyKind
import io.github.iacoposb.adodash.data.QuietHours
import io.github.iacoposb.adodash.data.SoundTarget
import io.github.iacoposb.adodash.ui.components.AdoFilterChip
import io.github.iacoposb.adodash.ui.components.Banner
import io.github.iacoposb.adodash.ui.components.BannerTone
import io.github.iacoposb.adodash.ui.components.SectionCard
import io.github.iacoposb.adodash.ui.components.SwitchRow
import io.github.iacoposb.adodash.ui.components.TextAction
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** What the notifications section does outside itself: the system's pickers and settings. */
class NotificationActions(
    val onChange: (NotificationPrefs) -> Unit = {},
    /** Android 13+ permission, or the app's notifications turned off: ask, or open the settings. */
    val onAllow: () -> Unit = {},
    /** The system's sound picker for [SoundTarget]. */
    val onPickSound: (SoundTarget) -> Unit = {},
    val onOpenSystemSettings: () -> Unit = {},
    /** A sound's name from its URI. */
    val soundTitle: (String) -> String = { it },
)

/** The weekdays as the [locale] orders them: Monday first in Italy, Sunday in the US. */
internal fun weekdays(locale: Locale): List<DayOfWeek> {
    val first = WeekFields.of(locale).firstDayOfWeek
    return (0L..6L).map { first.plus(it) }
}

/** "Mon", "Lun": short, capitalized, without the abbreviation's dot. */
internal fun dayLabel(day: DayOfWeek, locale: Locale): String =
    day.getDisplayName(TextStyle.SHORT, locale).removeSuffix(".").replaceFirstChar { it.titlecase(locale) }

/** "19:00", or "7:00 PM" where the clock has 12 hours. */
internal fun timeLabel(minutes: Int, is24Hour: Boolean, locale: Locale): String =
    DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale).format(LocalTime.of(minutes / 60, minutes % 60))

private enum class QuietEnd { FROM, TO }

/**
 * Settings › Notifications: which kinds to notify, quiet hours with their days and the
 * mentions' exception, the two sounds; on top, a banner when the app may not notify at all.
 */
@Composable
internal fun NotificationsSection(prefs: NotificationPrefs, allowed: Boolean, actions: NotificationActions, wide: Boolean) {
    if (!allowed) {
        Banner(stringResource(R.string.notif_permission_off), BannerTone.Warning) {
            TextAction(stringResource(R.string.notif_permission_allow), actions.onAllow)
        }
    }
    SettingsColumns(
        wide,
        left = { WhatToNotifyCard(prefs, actions.onChange) },
        right = {
            QuietHoursCard(prefs.quiet) { actions.onChange(prefs.copy(quiet = it)) }
            SoundsCard(prefs, actions)
        },
        leftWeight = 1f,
    )
}

@Composable
private fun WhatToNotifyCard(prefs: NotificationPrefs, onChange: (NotificationPrefs) -> Unit) {
    SectionCard(stringResource(R.string.notif_what)) {
        Text(stringResource(R.string.notif_what_hint), style = MaterialTheme.typography.bodySmall, color = AdoTheme.colors.textMuted)
        listOf(
            Triple(NotifyKind.MENTIONS, R.string.notif_mentions, R.string.notif_mentions_hint),
            Triple(NotifyKind.ASSIGNMENTS, R.string.notif_assignments, null),
            Triple(NotifyKind.REVIEWS, R.string.notif_reviews, null),
            Triple(NotifyKind.VOTES, R.string.notif_votes, null),
            Triple(NotifyKind.PR_COMMENTS, R.string.notif_pr_comments, R.string.notif_pr_comments_hint),
            Triple(NotifyKind.BUILDS, R.string.notif_builds, R.string.notif_builds_hint),
            Triple(NotifyKind.UPDATES, R.string.notif_updates, R.string.notif_updates_hint),
        ).forEach { (kind, title, hint) ->
            SwitchRow(
                stringResource(title), prefs.isOn(kind), { onChange(prefs.switching(kind, it)) },
                subtitle = hint?.let { stringResource(it) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuietHoursCard(quiet: QuietHours, onChange: (QuietHours) -> Unit) {
    val c = AdoTheme.colors
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    var editing by rememberSaveable { mutableStateOf<QuietEnd?>(null) }
    val on = quiet.enabled
    SectionCard(stringResource(R.string.quiet_title)) {
        Text(stringResource(R.string.quiet_hint), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        SwitchRow(stringResource(R.string.quiet_enable), on, { onChange(quiet.copy(enabled = it)) })
        // Kept in view when off, dimmed, so that the hours are known before turning them on
        Column(Modifier.alpha(if (on) 1f else 0.5f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PickerField(
                    stringResource(R.string.quiet_from), timeLabel(quiet.from, is24Hour, locale), Modifier.weight(1f), enabled = on,
                ) { editing = QuietEnd.FROM }
                PickerField(
                    stringResource(R.string.quiet_to), timeLabel(quiet.to, is24Hour, locale), Modifier.weight(1f), enabled = on,
                ) { editing = QuietEnd.TO }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.quiet_days), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A language alone ("it") has no first day of the week: its likely region ("it-IT") has
                    val regional = if (locale.country.isEmpty()) ULocale.addLikelySubtags(ULocale.forLocale(locale)).toLocale() else locale
                    weekdays(regional).forEach { day ->
                        AdoFilterChip(dayLabel(day, locale), quiet.hasDay(day), { if (on) onChange(quiet.toggling(day)) })
                    }
                }
            }
            SwitchRow(
                stringResource(R.string.quiet_mentions), quiet.mentionsSound, { onChange(quiet.copy(mentionsSound = it)) },
                subtitle = stringResource(R.string.quiet_mentions_hint), enabled = on,
            )
        }
    }
    editing?.let { end ->
        TimeDialog(
            if (end == QuietEnd.FROM) quiet.from else quiet.to, is24Hour,
            onDismiss = { editing = null },
            onPick = { minutes ->
                editing = null
                onChange(if (end == QuietEnd.FROM) quiet.copy(from = minutes) else quiet.copy(to = minutes))
            },
        )
    }
}

/** The Material time picker in a dialog, with the device's 12 or 24 hours. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(minutes: Int, is24Hour: Boolean, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val c = AdoTheme.colors
    val state = rememberTimePickerState(minutes / 60, minutes % 60, is24Hour)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        confirmButton = { TextAction(stringResource(R.string.action_ok), { onPick(state.hour * 60 + state.minute) }) },
        dismissButton = { TextAction(stringResource(R.string.action_cancel), onDismiss) },
        text = {
            TimePicker(
                state,
                colors = TimePickerDefaults.colors(
                    clockDialColor = c.surfaceAlt,
                    selectorColor = c.primary,
                    timeSelectorSelectedContainerColor = c.primaryContainer,
                    timeSelectorSelectedContentColor = c.onPrimaryContainer,
                    timeSelectorUnselectedContainerColor = c.surfaceAlt,
                    timeSelectorUnselectedContentColor = c.text,
                    periodSelectorSelectedContainerColor = c.primaryContainer,
                    periodSelectorSelectedContentColor = c.onPrimaryContainer,
                    periodSelectorUnselectedContentColor = c.textMuted,
                    periodSelectorBorderColor = c.outline,
                    clockDialUnselectedContentColor = c.text,
                ),
            )
        },
    )
}

@Composable
private fun SoundsCard(prefs: NotificationPrefs, actions: NotificationActions) {
    @Composable
    fun name(sound: String?) = when {
        sound == null -> stringResource(R.string.sound_default)
        sound.isEmpty() -> stringResource(R.string.sound_none)
        else -> actions.soundTitle(sound)
    }
    SectionCard(stringResource(R.string.sounds_title)) {
        PickerField(stringResource(R.string.sounds_mentions), name(prefs.mentionsSound)) { actions.onPickSound(SoundTarget.MENTIONS) }
        PickerField(stringResource(R.string.sounds_others), name(prefs.othersSound)) { actions.onPickSound(SoundTarget.OTHERS) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextAction(stringResource(R.string.sounds_system_settings), actions.onOpenSystemSettings)
        }
    }
}

/**
 * A field that looks like the design's dropdown, label above, and opens something of its own
 * (a time picker, the system's sound picker) instead of a menu.
 */
@Composable
private fun PickerField(label: String, value: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = AdoTheme.colors
    val shape = RoundedCornerShape(8.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(c.surface).border(1.dp, c.outline, shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .semantics {
                    contentDescription = label
                    stateDescription = value
                }
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(20.dp), tint = c.textMuted)
        }
    }
}
