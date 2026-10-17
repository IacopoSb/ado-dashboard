package io.github.iacoposb.adodash.sync

import android.content.Context
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.errorText

/**
 * The user-visible texts of a sync: event titles and warnings. An interface
 * so that [Events] and [SyncEngine] stay pure; the app reads them from the
 * string resources ([ResourceSyncTexts]), the tests use plain strings.
 */
interface SyncTexts {
    fun assigned(id: Int, title: String): String
    fun workItemMention(author: String, id: Int): String
    fun reviewRequested(title: String): String
    fun prMention(author: String, id: Int): String
    fun prComment(author: String, id: Int): String
    /** [vote] as in [Vote]. */
    fun prVote(reviewer: String, vote: Int): String
    fun buildFailed(prId: Int): String
    /** "backend-ci #20261016.3 · 2 tests failed"; [failedTests] null or 0 leaves the tests out. */
    fun buildFailedBody(pipeline: String, number: String, failedTests: Int?): String

    fun mentionsWarning(error: String): String
    fun projectWarning(project: String, error: String): String
    fun statesWarning(type: String, error: String): String
    fun noReadableProject(): String

    /** What to show for a failure. */
    fun error(e: Throwable): String
}

/** In the device language at the time of the sync: events keep the language they were created in. */
class ResourceSyncTexts(private val context: Context) : SyncTexts {
    private fun s(id: Int, vararg args: Any) = context.getString(id, *args)

    override fun assigned(id: Int, title: String) = s(R.string.event_assigned, id, title)
    override fun workItemMention(author: String, id: Int) = s(R.string.event_work_item_mention, author, id)
    override fun reviewRequested(title: String) = s(R.string.event_review_requested, title)
    override fun prMention(author: String, id: Int) = s(R.string.event_pr_mention, author, id)
    override fun prComment(author: String, id: Int) = s(R.string.event_pr_comment, author, id)
    override fun prVote(reviewer: String, vote: Int) = s(
        when (vote) {
            Vote.APPROVED -> R.string.event_vote_approved
            Vote.APPROVED_WITH_SUGGESTIONS -> R.string.event_vote_approved_with_suggestions
            Vote.WAITING -> R.string.event_vote_waiting
            Vote.REJECTED -> R.string.event_vote_rejected
            else -> R.string.event_vote_other
        },
        reviewer,
    )

    override fun buildFailed(prId: Int) = s(R.string.event_build_failed, prId)
    override fun buildFailedBody(pipeline: String, number: String, failedTests: Int?): String {
        val run = "$pipeline #$number"
        if (failedTests == null || failedTests <= 0) return run
        return "$run · " + context.resources.getQuantityString(R.plurals.tests_failed, failedTests, failedTests)
    }

    override fun mentionsWarning(error: String) = s(R.string.warning_mentions, error)
    override fun projectWarning(project: String, error: String) = s(R.string.warning_project, project, error)
    override fun statesWarning(type: String, error: String) = s(R.string.warning_states, type, error)
    override fun noReadableProject() = s(R.string.error_no_readable_project)

    override fun error(e: Throwable) = context.errorText(e)
}
