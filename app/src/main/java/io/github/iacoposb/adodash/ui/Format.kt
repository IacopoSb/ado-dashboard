package io.github.iacoposb.adodash.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.Vote
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
@ReadOnlyComposable
private fun locale(): Locale = LocalConfiguration.current.locales[0]

/** "5 min ago", "2 hr ago", "3 days ago", then the date. */
@Composable
@ReadOnlyComposable
fun ago(at: Instant, now: Instant = Instant.now()): String {
    val d = Duration.between(at, now)
    return when {
        d.toMinutes() < 1 -> stringResource(R.string.ago_now)
        d.toMinutes() < 60 -> d.toMinutes().toInt().let { pluralStringResource(R.plurals.ago_minutes, it, it) }
        d.toHours() < 24 -> d.toHours().toInt().let { pluralStringResource(R.plurals.ago_hours, it, it) }
        d.toDays() < 7 -> d.toDays().toInt().let { pluralStringResource(R.plurals.ago_days, it, it) }
        else -> DateTimeFormatter.ofPattern("d MMM", locale()).format(at.atZone(ZoneId.systemDefault()))
    }
}

/** Date and time in the device's locale: "16 Oct 2026, 11:56". */
@Composable
@ReadOnlyComposable
fun dateTime(at: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale())
        .format(at.atZone(ZoneId.systemDefault()))

/**
 * When the data was read: the time today, the date and time before (data saved by an earlier
 * run can be days old).
 */
@Composable
@ReadOnlyComposable
fun updatedAt(at: Instant, now: Instant = Instant.now()): String {
    val zone = ZoneId.systemDefault()
    val sameDay = at.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()
    val style = if (sameDay) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    return style.withLocale(locale()).format(at.atZone(zone))
}

/** Short time in the device's locale: "14:05", "2:05 PM". */
@Composable
@ReadOnlyComposable
fun timeOfDay(at: Instant): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale()).format(at.atZone(ZoneId.systemDefault()))

/** "8 s", "2 min 31 s", "1 h 5 min". */
@Composable
@ReadOnlyComposable
fun duration(d: Duration): String {
    val s = d.seconds.coerceAtLeast(0)
    return when {
        s < 60 -> stringResource(R.string.duration_s, s.toInt())
        s < 3600 -> stringResource(R.string.duration_min_s, (s / 60).toInt(), (s % 60).toInt())
        else -> stringResource(R.string.duration_h_min, (s / 3600).toInt(), (s % 3600 / 60).toInt())
    }
}

/** A reviewer's vote, as shown on a pull request. */
@StringRes
fun voteLabel(vote: Int): Int = when (vote) {
    Vote.APPROVED -> R.string.vote_approved
    Vote.APPROVED_WITH_SUGGESTIONS -> R.string.vote_approved_with_suggestions
    Vote.WAITING -> R.string.vote_waiting
    Vote.REJECTED -> R.string.vote_rejected
    else -> R.string.vote_none
}
