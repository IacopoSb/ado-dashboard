package io.github.iacoposb.adodash

import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.PrComment
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.Reviewer
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.sync.SyncTexts
import java.time.Instant

/** Sample data shared by the tests. */
object Fakes {
    val now: Instant = Instant.parse("2026-10-16T10:00:00Z")
    val me = Identity("aaaaaaaa-1111-2222-3333-444444444444", "Iacopo Sbalchiero")
    val anna = Identity("bbbbbbbb-1111-2222-3333-444444444444", "Anna Verdi")
    val marco = Identity("cccccccc-1111-2222-3333-444444444444", "Marco Bianchi")

    fun workItem(id: Int, type: String = "Task", title: String = "Work item $id", state: String = "Active", priority: Int? = 2, ago: Long = 3600) =
        WorkItem(id, "Contoso", type, title, state, priority, me.name, now.minusSeconds(ago), "https://dev.azure.com/org/Contoso/_workitems/edit/$id")

    fun pr(id: Int, author: Identity, title: String = "PR $id", reviewers: List<Reviewer> = emptyList(), draft: Boolean = false, ago: Long = 7200) =
        PullRequest(
            id, "Contoso", "repo-1", "backend", title, author, "feature/x-$id", "main", draft, reviewers,
            now.minusSeconds(ago), "https://dev.azure.com/org/Contoso/_git/backend/pullrequest/$id",
        )

    fun comment(thread: Int, id: Int, author: Identity, text: String, at: Instant = now) = PrComment(thread, id, author, text, at)

    /** Plain texts, so the logic tests do not depend on the resources. */
    object Texts : SyncTexts {
        override fun assigned(id: Int, title: String) = "assigned #$id $title"
        override fun workItemMention(author: String, id: Int) = "$author mentioned #$id"
        override fun reviewRequested(title: String) = "review $title"
        override fun prMention(author: String, id: Int) = "$author mentioned !$id"
        override fun prComment(author: String, id: Int) = "$author commented !$id"
        override fun prVote(reviewer: String, vote: Int) = "$reviewer voted $vote"
        override fun mentionsWarning(error: String) = "mentions: $error"
        override fun projectWarning(project: String, error: String) = "$project: $error"
        override fun statesWarning(type: String, error: String) = "$type states: $error"
        override fun noReadableProject() = "no readable project"
        override fun error(e: Throwable) = e.message ?: e.javaClass.simpleName
    }
}
