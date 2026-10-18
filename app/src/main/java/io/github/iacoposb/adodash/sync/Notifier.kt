package io.github.iacoposb.adodash.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.iacoposb.adodash.MainActivity
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.NotificationPrefs
import io.github.iacoposb.adodash.data.NotifyKind
import io.github.iacoposb.adodash.update.Release
import java.time.LocalDateTime

class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    /**
     * The channels for [prefs] (see [NotificationChannels]): "Mentions" and "Activity" with the
     * sounds chosen, "Quiet hours" without sound or vibration, "App updates". Channels of sounds
     * chosen before, and those of 0.0.16, are deleted. Also renames them after a language change:
     * called at every start and whenever the settings change.
     */
    fun createChannels(prefs: NotificationPrefs) {
        val system = context.getSystemService(NotificationManager::class.java)
        val channels = listOf(
            NotificationChannel(NotificationChannels.mentions(prefs), context.getString(R.string.channel_mentions), NotificationManager.IMPORTANCE_HIGH)
                .withSound(prefs.mentionsSound),
            NotificationChannel(NotificationChannels.activity(prefs), context.getString(R.string.channel_activity), NotificationManager.IMPORTANCE_DEFAULT)
                .withSound(prefs.othersSound),
            // Low importance: no sound, no vibration, no heads-up, but in the tray
            NotificationChannel(NotificationChannels.SILENT, context.getString(R.string.channel_silent), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            },
            NotificationChannel(NotificationChannels.UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_LOW),
        )
        system.createNotificationChannels(channels)
        NotificationChannels.obsolete(system.notificationChannels.map { it.id }, prefs).forEach(system::deleteNotificationChannel)
    }

    /** null: the system's default sound, which a new channel has anyway; "": none. */
    private fun NotificationChannel.withSound(sound: String?) = apply {
        when {
            sound == null -> Unit
            sound.isEmpty() -> setSound(null, null)
            else -> setSound(
                Uri.parse(sound),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
        }
    }

    /**
     * Notifies [events] as [prefs] say: the kinds turned off are left out (they are still in
     * Activity), and during quiet hours the rest arrives without sound.
     */
    fun notify(events: List<Event>, prefs: NotificationPrefs, now: LocalDateTime = LocalDateTime.now()) {
        if (events.isEmpty() || !canNotify()) return
        // A sync in the background may come before the channels of a new sound are made
        val system = context.getSystemService(NotificationManager::class.java)
        if (NotificationChannels.current(prefs).any { system.getNotificationChannel(it) == null }) createChannels(prefs)
        val routed = events.mapNotNull { e -> NotificationRules.route(NotifyKind.of(e.kind), prefs, now)?.let { e to it } }
        routed.forEach { (event, route) ->
            val notification = NotificationCompat.Builder(context, NotificationChannels.of(route, prefs))
                .setSilent(route == Route.SILENT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(event.title)
                .setContentText(event.body.lineSequence().firstOrNull())
                .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
                .setWhen(event.at)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setGroup(GROUP)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setContentIntent(
                    when {
                        event.buildId != null -> openBuild(event)
                        event.workItemComment != null -> openComment(event)
                        event.pullRequest != null -> openPullRequest(event)
                        else -> openUrl(event)
                    },
                )
                .build()
            send(event.key.hashCode(), notification)
        }
        if (routed.size > 1) {
            // The summary never makes a sound of its own: its notifications do, or not
            val summary = NotificationCompat.Builder(context, NotificationChannels.SILENT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_summary))
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setAutoCancel(true)
                .build()
            send(SUMMARY_ID, summary)
        }
    }

    /** A newer version is out, unless updates are turned off: tapping opens the settings on the update. */
    fun notifyUpdate(release: Release, prefs: NotificationPrefs) {
        if (!canNotify() || !prefs.isOn(NotifyKind.UPDATES)) return
        val open = PendingIntent.getActivity(
            context,
            UPDATE_ID,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHOW_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, NotificationChannels.UPDATES)
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

    /** A pull request's event opens its page; a comment or a mention at its thread, ready to reply. */
    private fun openPullRequest(event: Event): PendingIntent {
        val ref = requireNotNull(event.pullRequest)
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_PR_ID, ref.prId)
            .putExtra(MainActivity.EXTRA_EVENT_KEY, event.key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ref.threadId?.let { intent.putExtra(MainActivity.EXTRA_PR_THREAD_ID, it) }
        ref.commentId?.let { intent.putExtra(MainActivity.EXTRA_COMMENT_ID, it) }
        return PendingIntent.getActivity(
            context, event.key.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val GROUP = "ado"
        private const val SUMMARY_ID = 1
        private const val UPDATE_ID = 2
    }
}
