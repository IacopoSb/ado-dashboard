package io.github.iacoposb.adodash.widget

import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.EventKind
import io.github.iacoposb.adodash.data.Filters
import io.github.iacoposb.adodash.sync.BuildSignal
import io.github.iacoposb.adodash.sync.Snapshot
import kotlinx.serialization.Serializable
import java.time.Duration

/** A mention as the large widget lists it: who, where and what, and the event to open. */
@Serializable
data class WidgetMention(
    val key: String,
    val who: String,
    /** "PR !311 · the comment" or "#4812 · the comment". */
    val context: String,
    /** Epoch millis. */
    val at: Long,
    val url: String,
    val project: String? = null,
) {
    /** The event back, for the same routing as its notification. */
    fun toEvent() = Event(key = key, kind = EventKind.MENTION, title = who, body = context, url = url, at = at, project = project)
}

/**
 * What the widgets show, saved after each sync so they have it after a restart, without reading
 * the dashboard's larger saved snapshots ([io.github.iacoposb.adodash.sync.SnapshotStore]).
 */
@Serializable
data class WidgetSummary(
    val organization: String = "",
    /** The last successful sync, epoch millis; 0 before the first one. */
    val syncedAt: Long = 0,
    /** Pull requests waiting for my review. */
    val toReview: Int = 0,
    /**
     * My open work items as the global view shows them by default (with the global filters),
     * whatever view the app has open.
     */
    val workItems: Int = 0,
    /** My pull requests whose build failed, all of them whatever view the app has open. */
    val failedBuilds: Int = 0,
    val unreadMentions: Int = 0,
    /** The latest mentions, newest first. */
    val mentions: List<WidgetMention> = emptyList(),
    /** When a refresh was asked from the widget; 0 when none is running. */
    val refreshingSince: Long = 0,
) {
    val hasData get() = syncedAt > 0

    /** The last sync is more than an hour old. */
    fun stale(now: Long) = hasData && now - syncedAt > STALE.toMillis()

    /** A refresh asked from the widget is running; one that never ended is forgotten after a while. */
    fun refreshing(now: Long) = refreshingSince > 0 && now - refreshingSince < REFRESH_TIMEOUT.toMillis()

    companion object {
        val STALE: Duration = Duration.ofHours(1)
        val REFRESH_TIMEOUT: Duration = Duration.ofMinutes(3)
    }
}

/** Builds the [WidgetSummary] from a sync's snapshot and the stored events. Pure, for the tests. */
object WidgetSummaries {
    const val MENTIONS = 3

    /**
     * The counters of [snapshot] over [previous] (its mentions are kept). They never depend on
     * the view the sync read: every sync reads my open work items (the global view's default
     * panel, with the global filters), my pull requests and their builds for the notifications,
     * so a board view open in the app counts the same as the global view.
     */
    fun synced(previous: WidgetSummary, organization: String, snapshot: Snapshot) = previous.copy(
        organization = organization,
        syncedAt = snapshot.syncedAt.toEpochMilli(),
        toReview = snapshot.reviewPrs.size,
        workItems = snapshot.workItems.size,
        failedBuilds = snapshot.myPrs.count { snapshot.signals[it.id]?.build == BuildSignal.FAILED },
    )

    /**
     * The mentions among [events] that [filters] include: how many are unread, and the latest
     * [MENTIONS] of them. Views do not matter here, as for the notifications.
     */
    fun withMentions(summary: WidgetSummary, events: List<Event>, filters: Filters): WidgetSummary {
        val mentions = events.filter { it.kind == EventKind.MENTION && !it.hidden && filters.acceptsEvent(it) }
        return summary.copy(
            unreadMentions = mentions.count { !it.read },
            mentions = mentions.sortedByDescending { it.at }.take(MENTIONS).map(::mention),
        )
    }

    /** How many mentions fit under the large widget's counters at [heightDp]: two in the 4×2 of the design. */
    fun mentionRows(heightDp: Float): Int {
        // Padding, header, counters and the section's label; then 48 dp a row, 8 between rows
        val free = heightDp - 130f
        return ((free + 8f) / 56f).toInt().coerceIn(0, MENTIONS)
    }

    fun mention(event: Event): WidgetMention {
        val where = event.workItemComment?.let { "#${it.workItemId}" }
            ?: event.pullRequest?.let { "PR !${it.prId}" }
        // The body is the item's title, then the comment
        val lines = event.body.lines()
        val text = lines.drop(1).joinToString(" ").trim().ifEmpty { lines.first().trim() }
        return WidgetMention(
            key = event.key,
            // Mentions stored before 0.0.18 do not know their author: the notification's title then
            who = event.author?.takeIf { it.isNotBlank() } ?: event.title,
            context = listOfNotNull(where, text.takeIf { it.isNotEmpty() }).joinToString(" · "),
            at = event.at,
            url = event.url,
            project = event.project,
        )
    }
}
