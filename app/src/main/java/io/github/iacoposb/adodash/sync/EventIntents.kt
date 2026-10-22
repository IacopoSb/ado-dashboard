package io.github.iacoposb.adodash.sync

import android.content.Context
import android.content.Intent
import io.github.iacoposb.adodash.MainActivity
import io.github.iacoposb.adodash.data.Event

/** Where an event opens, from its notification or a widget. */
object EventIntents {
    /**
     * A failed build opens its details over the dashboard; a mention in a work item's comments
     * the work item at that comment, ready to reply; an assignment the work item's details; a pull
     * request's event its page, at the thread for a comment or a mention. Anything else opens in
     * the browser, through the app so that the event is read once opened.
     */
    fun of(context: Context, event: Event): Intent {
        val build = event.buildId
        val comment = event.workItemComment
        val workItem = event.workItemId
        val pr = event.pullRequest
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_EVENT_KEY, event.key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        when {
            build != null -> intent.putExtra(MainActivity.EXTRA_BUILD_ID, build).putExtra(MainActivity.EXTRA_BUILD_PROJECT, event.project)
            comment != null -> intent.putExtra(MainActivity.EXTRA_WORK_ITEM_ID, comment.workItemId)
                .putExtra(MainActivity.EXTRA_COMMENT_ID, comment.commentId)
            workItem != null -> intent.putExtra(MainActivity.EXTRA_WORK_ITEM_ID, workItem)
            pr != null -> {
                intent.putExtra(MainActivity.EXTRA_PR_ID, pr.prId)
                pr.threadId?.let { intent.putExtra(MainActivity.EXTRA_PR_THREAD_ID, it) }
                pr.commentId?.let { intent.putExtra(MainActivity.EXTRA_COMMENT_ID, it) }
            }
            else -> intent.putExtra(MainActivity.EXTRA_URL, event.url)
        }
        return intent
    }
}
