package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.EventKind
import java.time.Instant

/**
 * Turns a [Fetched] into events. Only comments after [baselineAt] count:
 * the first sync must not flood the tray with history. Keys are stable, so
 * the database discards the events it already has. Titles come from
 * [SyncTexts] and are stored as they are, in the language of the sync.
 */
object Events {

    fun from(fetched: Fetched, baselineAt: Instant, texts: SyncTexts): List<Event> {
        val snapshot = fetched.snapshot
        val me = snapshot.me
        val events = mutableListOf<Event>()

        snapshot.workItems.forEach { wi ->
            events += Event(
                key = "wi:${wi.id}:assigned",
                kind = EventKind.ASSIGNED,
                title = texts.assigned(wi.id, wi.title),
                body = "${wi.type} · ${wi.project} · ${wi.state}",
                url = wi.url,
                at = wi.changedAt.toEpochMilli(),
                project = wi.project,
                area = wi.areaPath,
            )
        }

        fetched.mentionedWorkItems.forEach { (wi, comments) ->
            comments.filter { it.author.id != me.id && it.createdAt > baselineAt && mentions(it.text, me) }.forEach {
                events += Event(
                    key = "wi:${wi.id}:c${it.id}",
                    kind = EventKind.MENTION,
                    title = texts.workItemMention(it.author.name, wi.id),
                    body = "${wi.title}\n${plainText(it.text, me)}",
                    url = wi.url,
                    at = it.createdAt.toEpochMilli(),
                    project = wi.project,
                    area = wi.areaPath,
                )
            }
        }

        snapshot.reviewPrs.forEach { pr ->
            events += Event(
                key = "pr:${pr.id}:review",
                kind = EventKind.PR_REVIEW,
                title = texts.reviewRequested(pr.title),
                body = "${pr.author.name} · ${pr.repoName} · ${pr.sourceBranch} → ${pr.targetBranch}",
                url = pr.url,
                at = pr.createdAt.toEpochMilli(),
                project = pr.project,
            )
        }

        val myPrIds = snapshot.myPrs.map { it.id }.toSet()
        (snapshot.reviewPrs + snapshot.myPrs).forEach { pr ->
            val comments = fetched.prComments[pr.id].orEmpty()
            relevantComments(comments, me, mine = pr.id in myPrIds)
                .filter { it.publishedAt > baselineAt }
                .forEach { events += prCommentEvent(pr, it, me, texts) }
        }

        snapshot.myPrs.forEach { pr ->
            pr.reviewers.filter { it.vote != Vote.NONE && it.id != me.id }.forEach { r ->
                events += Event(
                    key = "pr:${pr.id}:vote:${r.id}:${r.vote}",
                    kind = EventKind.PR_VOTE,
                    title = texts.prVote(r.name, r.vote),
                    body = pr.title,
                    url = pr.url,
                    at = snapshot.syncedAt.toEpochMilli(),
                    project = pr.project,
                )
            }
        }

        // One event per build: a rerun that fails again is a new build
        fetched.failedPrBuilds.distinctBy { it.build.id }.forEach { (pr, build, failedTests) ->
            events += Event(
                key = Event.buildFailedKey(build.id),
                kind = EventKind.BUILD_FAILED,
                title = texts.buildFailed(pr.id),
                body = texts.buildFailedBody(build.definitionName, build.number, failedTests),
                url = build.url,
                at = build.at.toEpochMilli(),
                project = build.project,
            )
        }
        return events
    }

    /**
     * Splits [found] into history, stored as read and never shown, and news. On the first
     * sync everything is history. After the filters changed ([filtersChangedAt] later than
     * [lastSyncAt]) so is whatever happened before the last sync: what was already shown is
     * stored by then, so only what the filters newly include ends there, and including a
     * project again does not notify all of its work items.
     */
    fun splitHistory(found: List<Event>, firstSync: Boolean, lastSyncAt: Long, filtersChangedAt: Long): Pair<List<Event>, List<Event>> =
        when {
            firstSync -> found to emptyList()
            filtersChangedAt > lastSyncAt -> found.partition { it.at <= lastSyncAt }
            else -> emptyList<Event>() to found
        }

    /**
     * Like Azure DevOps: the author of a PR hears about every comment, a
     * reviewer only about the threads they took part in and their mentions.
     */
    private fun relevantComments(comments: List<PrComment>, me: Identity, mine: Boolean): List<PrComment> {
        val others = comments.filter { it.author.id != me.id }
        if (mine) return others
        val myThreads = comments.filter { it.author.id == me.id }.map { it.threadId }.toSet()
        return others.filter { it.threadId in myThreads || mentions(it.text, me) }
    }

    private fun prCommentEvent(pr: PullRequest, c: PrComment, me: Identity, texts: SyncTexts): Event {
        val mention = mentions(c.text, me)
        return Event(
            key = "pr:${pr.id}:t${c.threadId}:c${c.id}",
            kind = if (mention) EventKind.MENTION else EventKind.PR_COMMENT,
            title = if (mention) texts.prMention(c.author.name, pr.id) else texts.prComment(c.author.name, pr.id),
            body = "${pr.title}\n${plainText(c.text, me)}",
            url = pr.url,
            at = c.publishedAt.toEpochMilli(),
            project = pr.project,
        )
    }

    /**
     * Work item comments mention as `data-vss-mention="version:2.0,<id>"`,
     * PR comments as `@<ID>`: both carry the user id.
     */
    fun mentions(text: String, me: Identity) = me.id.isNotEmpty() && text.contains(me.id, ignoreCase = true)

    private val PR_MENTION = Regex("@<([0-9A-Fa-f-]{36})>")
    private val TAG = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")
    private val ENTITIES = mapOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'")

    /** Comment text for a notification: no HTML, mentions as names. */
    fun plainText(text: String, me: Identity): String {
        var out = PR_MENTION.replace(text) { if (it.groupValues[1].equals(me.id, true)) "@${me.name}" else "@…" }
        out = TAG.replace(out.replace(Regex("<br\\s*/?>|</p>|</div>"), " "), "")
        ENTITIES.forEach { (k, v) -> out = out.replace(k, v) }
        return SPACES.replace(out, " ").trim()
    }
}
