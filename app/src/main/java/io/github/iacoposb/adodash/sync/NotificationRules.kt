package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.data.NotificationPrefs
import io.github.iacoposb.adodash.data.NotifyKind
import io.github.iacoposb.adodash.data.QuietHours
import java.time.LocalDateTime

/** How a notification reaches the tray: with the mentions' sound, the others', or in silence. */
enum class Route { MENTIONS, ACTIVITY, SILENT }

/** What to notify and how: pure, so that it is tested without Android. */
object NotificationRules {

    /**
     * Whether [now] (local time) falls in [quiet]. A period starts on one of its days at its
     * start, included, and lasts to its end, excluded: on the same day, or the next one when it
     * runs overnight (19:00–08:00 on Friday covers Friday evening and Saturday morning, and
     * Saturday morning is quiet even if Saturday is not one of the days). Equal start and end
     * make a whole day from that time.
     */
    fun isQuiet(now: LocalDateTime, quiet: QuietHours): Boolean {
        if (!quiet.enabled) return false
        val minute = now.hour * 60 + now.minute
        val today = quiet.hasDay(now.dayOfWeek)
        val yesterday = quiet.hasDay(now.dayOfWeek.minus(1))
        return if (quiet.from < quiet.to) {
            today && minute >= quiet.from && minute < quiet.to
        } else {
            // Overnight (or a whole day): the evening of a day, or the morning after one
            (today && minute >= quiet.from) || (yesterday && minute < quiet.to)
        }
    }

    /**
     * How to notify something of [kind] at [now]; null when that kind is turned off (it is still
     * stored and shown in Activity). During quiet hours everything is silent, mentions excepted
     * when they still make a sound.
     */
    fun route(kind: NotifyKind, prefs: NotificationPrefs, now: LocalDateTime): Route? {
        if (!prefs.isOn(kind)) return null
        val mention = kind == NotifyKind.MENTIONS
        return when {
            isQuiet(now, prefs.quiet) && !(mention && prefs.quiet.mentionsSound) -> Route.SILENT
            mention -> Route.MENTIONS
            else -> Route.ACTIVITY
        }
    }
}

/**
 * The app's notification channels. On Android 8+ a channel's sound cannot be changed once it is
 * created (only the user can, in the system settings), so a sound chosen in the app makes a new
 * channel with the next version in its id ("mentions_v2"), and the old one is deleted. The
 * version is stored with the settings ([NotificationPrefs.mentionsChannel],
 * [NotificationPrefs.othersChannel]) and only goes up: a deleted channel created again with the
 * same id would come back with its old settings.
 */
object NotificationChannels {
    const val SILENT = "silent"
    const val UPDATES = "updates"
    private const val MENTIONS_PREFIX = "mentions_v"
    private const val ACTIVITY_PREFIX = "activity_v"

    /** Up to 0.0.16: one channel for mentions, one for work items, one for pull requests. */
    private val LEGACY = setOf("mentions", "work_items", "pull_requests")

    fun mentions(prefs: NotificationPrefs) = "$MENTIONS_PREFIX${prefs.mentionsChannel}"
    fun activity(prefs: NotificationPrefs) = "$ACTIVITY_PREFIX${prefs.othersChannel}"

    fun of(route: Route, prefs: NotificationPrefs) = when (route) {
        Route.MENTIONS -> mentions(prefs)
        Route.ACTIVITY -> activity(prefs)
        Route.SILENT -> SILENT
    }

    fun current(prefs: NotificationPrefs) = setOf(mentions(prefs), activity(prefs), SILENT, UPDATES)

    /** Of the channels [existing], the app's that are no longer used: to delete. */
    fun obsolete(existing: Collection<String>, prefs: NotificationPrefs): List<String> {
        val current = current(prefs)
        return existing.filter {
            it !in current && (it in LEGACY || it.startsWith(MENTIONS_PREFIX) || it.startsWith(ACTIVITY_PREFIX))
        }
    }
}
