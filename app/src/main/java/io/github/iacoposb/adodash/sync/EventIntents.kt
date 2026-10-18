package io.github.iacoposb.adodash.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.iacoposb.adodash.MainActivity
import io.github.iacoposb.adodash.data.Event

/** Where an event opens, from its notification or a widget. */
object EventIntents {
    /**
     * A failed build opens its details over the dashboard; a mention in a work item's comments
     * the work item at that comment, ready to reply; a pull request's event its page, at the
     * thread for a comment or a mention. The rest opens in the browser.
     */
    fun of(context: Context, event: Event): Intent {
        val build = event.buildId
        val comment = event.workItemComment
        val pr = event.pullRequest
        if (build == null && comment == null && pr == null) {
            return Intent(Intent.ACTION_VIEW, Uri.parse(event.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_EVENT_KEY, event.key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        when {
            build != null -> intent.putExtra(MainActivity.EXTRA_BUILD_ID, build).putExtra(MainActivity.EXTRA_BUILD_PROJECT, event.project)
            comment != null -> intent.putExtra(MainActivity.EXTRA_WORK_ITEM_ID, comment.workItemId)
                .putExtra(MainActivity.EXTRA_COMMENT_ID, comment.commentId)
            pr != null -> {
                intent.putExtra(MainActivity.EXTRA_PR_ID, pr.prId)
                pr.threadId?.let { intent.putExtra(MainActivity.EXTRA_PR_THREAD_ID, it) }
                pr.commentId?.let { intent.putExtra(MainActivity.EXTRA_COMMENT_ID, it) }
            }
        }
        return intent
    }
}
