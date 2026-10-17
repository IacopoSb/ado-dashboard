package io.github.iacoposb.adodash.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.iacoposb.adodash.MainActivity
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.EventKind
import io.github.iacoposb.adodash.update.Release

class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    /** Also renames the channels after a language change: called at every start. */
    fun createChannels() {
        val channels = listOf(
            NotificationChannel(CH_MENTIONS, context.getString(R.string.channel_mentions), NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_WORK_ITEMS, context.getString(R.string.channel_work_items), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_PULL_REQUESTS, context.getString(R.string.channel_pull_requests), NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_LOW),
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(channels)
    }

    fun notify(events: List<Event>) {
        if (events.isEmpty() || !canNotify()) return
        events.forEach { event ->
            val notification = NotificationCompat.Builder(context, channelOf(event.kind))
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(event.title)
                .setContentText(event.body.lineSequence().firstOrNull())
                .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
                .setWhen(event.at)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setGroup(GROUP)
                .setContentIntent(
                    when {
                        event.buildId != null -> openBuild(event)
                        event.workItemComment != null -> openComment(event)
                        else -> openUrl(event)
                    },
                )
                .build()
            send(event.key.hashCode(), notification)
        }
        if (events.size > 1) {
            val summary = NotificationCompat.Builder(context, CH_PULL_REQUESTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_summary))
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .build()
            send(SUMMARY_ID, summary)
        }
    }

    /** A newer version is out: tapping opens the settings on the update. */
    fun notifyUpdate(release: Release) {
        if (!canNotify()) return
        val open = PendingIntent.getActivity(
            context,
            UPDATE_ID,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_available, release.version))
            .setContentText(context.getString(R.string.notification_update_text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        send(UPDATE_ID, notification)
    }

    private fun canNotify() = manager.areNotificationsEnabled() && (
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )

    @Suppress("MissingPermission") // checked by canNotify
    private fun send(id: Int, notification: android.app.Notification) = manager.notify(id, notification)

    private fun openUrl(event: Event): PendingIntent = PendingIntent.getActivity(
        context,
        event.key.hashCode(),
        Intent(Intent.ACTION_VIEW, Uri.parse(event.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** A failed build opens its details over the dashboard. */
    private fun openBuild(event: Event): PendingIntent = PendingIntent.getActivity(
        context,
        event.key.hashCode(),
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_BUILD_ID, event.buildId)
            .putExtra(MainActivity.EXTRA_BUILD_PROJECT, event.project)
            .putExtra(MainActivity.EXTRA_EVENT_KEY, event.key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** A mention in a work item's comments opens the work item at that comment, ready to reply. */
    private fun openComment(event: Event): PendingIntent {
        val ref = requireNotNull(event.workItemComment)
        return PendingIntent.getActivity(
            context,
            event.key.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_WORK_ITEM_ID, ref.workItemId)
                .putExtra(MainActivity.EXTRA_COMMENT_ID, ref.commentId)
                .putExtra(MainActivity.EXTRA_EVENT_KEY, event.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun channelOf(kind: EventKind) = when (kind) {
        EventKind.MENTION -> CH_MENTIONS
        EventKind.ASSIGNED -> CH_WORK_ITEMS
        EventKind.PR_REVIEW, EventKind.PR_COMMENT, EventKind.PR_VOTE, EventKind.BUILD_FAILED -> CH_PULL_REQUESTS
    }

    companion object {
        private const val CH_MENTIONS = "mentions"
        private const val CH_WORK_ITEMS = "work_items"
        private const val CH_PULL_REQUESTS = "pull_requests"
        private const val CH_UPDATES = "updates"
        private const val GROUP = "ado"
        private const val SUMMARY_ID = 1
        private const val UPDATE_ID = 2
    }
}
