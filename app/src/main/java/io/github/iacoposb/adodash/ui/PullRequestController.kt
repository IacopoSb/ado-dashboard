package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.Person
import io.github.iacoposb.adodash.api.PolicyEvaluation
import io.github.iacoposb.adodash.api.PrThread
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.PullRequestDetail
import io.github.iacoposb.adodash.api.Reviewer
import io.github.iacoposb.adodash.api.ThreadStatus
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.api.isDenied
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

enum class PrTab { OVERVIEW, THREADS }

/** Which threads the Threads tab lists: those still to act on, those done with, or all. */
enum class ThreadFilter {
    ACTIVE, RESOLVED, ALL;

    fun accepts(thread: PrThread) = when (this) {
        ACTIVE -> ThreadStatus.isOpen(thread.status)
        RESOLVED -> !ThreadStatus.isOpen(thread.status)
        ALL -> true
    }
}

/**
 * My vote on the pull request open: [saved] as Azure DevOps has it, [pending] the one being
 * saved, shown already; [error] why the last one was not saved (the saved one is shown again).
 */
data class VoteState(val saved: Int = Vote.NONE, val pending: Int? = null, val error: CommentError? = null) {
    val shown get() = pending ?: saved
    val busy get() = pending != null
}

/** How a vote goes: shown at once, then saved or back to what it was. Pure. */
object VoteMachine {
    /** [vote] tapped: null when there is nothing to do (already the vote, or one being saved). */
    fun start(state: VoteState, vote: Int): VoteState? =
        if (state.busy || vote == state.saved) null else VoteState(state.saved, pending = vote)

    /** Saved: what Azure DevOps answered is the vote now. */
    fun saved(state: VoteState, vote: Int) = VoteState(saved = vote)

    /** Refused: the vote saved before, with the reason. */
    fun failed(state: VoteState, error: CommentError) = VoteState(saved = state.saved, error = error)

    /** The sync read my vote again: taken unless one is being saved. */
    fun synced(state: VoteState, vote: Int) = if (state.busy) state else state.copy(saved = vote)
}

/** A comment posted from the page, shown at once while it is sent; [threadId] 0 for a new thread. */
data class SendingPrComment(val localId: Int, val threadId: Int, val markdown: String, val at: Instant)

/**
 * The threads of the pull request open: [items] as read or saved, [statusPending] the statuses
 * being saved (shown already); errors by thread id, 0 for the new thread's composer.
 */
data class PrThreadsState(
    val loaded: Boolean = false,
    val items: List<PrThread> = emptyList(),
    val error: String? = null,
    val sending: List<SendingPrComment> = emptyList(),
    val statusPending: Map<Int, String> = emptyMap(),
    val sendErrors: Map<Int, CommentError> = emptyMap(),
    val statusErrors: Map<Int, CommentError> = emptyMap(),
) {
    /** People's threads (the system ones have no comments), with the statuses being saved. */
    val shown: List<PrThread>
        get() = items.filter { it.comments.isNotEmpty() && it.status != null }
            .map { t -> statusPending[t.id]?.let { t.copy(status = it) } ?: t }

    val activeCount get() = shown.count { ThreadStatus.isOpen(it.status) }
}

/** A comment to show, from a mention: its thread opened and highlighted. */
data class PrCommentTarget(val threadId: Int, val commentId: Int? = null)

/**
 * The pull request page. [pr] is what is known (the dashboard row, then what was read), null
 * while it is read from an event; [detail] adds the description, labels and work items.
 */
data class PrScreen(
    val id: Int,
    val pr: PullRequest? = null,
    val detail: PullRequestDetail? = null,
    /** The pull request could not be read at all. */
    val error: String? = null,
    /** Null while read; [policiesFailed] when they could not be. */
    val policies: List<PolicyEvaluation>? = null,
    val policiesFailed: Boolean = false,
    /** The linked work items, read after the details. */
    val workItems: List<WorkItem>? = null,
    val threads: PrThreadsState = PrThreadsState(),
    val vote: VoteState = VoteState(),
    /** By thread id, 0 for a new thread. */
    val composers: Map<Int, ComposerState> = emptyMap(),
    val mentions: PeopleSearch = PeopleSearch(),
    /** Display names by lowercase id, for the mentions in the texts. */
    val names: Map<String, String> = emptyMap(),
    val tab: PrTab = PrTab.OVERVIEW,
    val filter: ThreadFilter = ThreadFilter.ACTIVE,
    val target: PrCommentTarget? = null,
    val me: Person? = null,
) {
    fun composer(threadId: Int) = composers[threadId] ?: ComposerState()

    /** "active", "completed" or "abandoned". */
    val status: String get() = detail?.status ?: "active"

    /** Votes are for active pull requests that are not drafts. */
    val canVote: Boolean get() = pr != null && me != null && !pr.draft && status == "active"

    companion object {
        /** The composer of a new thread on the pull request as a whole. */
        const val NEW_THREAD = 0
    }
}

/**
 * Reads the pull request the page shows, and saves my vote, my comments and the threads'
 * statuses: each shown at once and put back as it was if refused.
 */
class PullRequestController(
    private val scope: CoroutineScope,
    private val api: suspend () -> AdoApi,
    private val me: () -> Person?,
    private val errorText: (Throwable) -> String,
    /** After my vote is saved: the dashboard row shows it, and it is never notified to me. */
    private val onVoted: suspend (PullRequest, Int) -> Unit = { _, _ -> },
    /** After I commented in a thread: its comments are stored as seen, never notified to me. */
    private val onCommented: suspend (PullRequest, PrThread) -> Unit = { _, _ -> },
    private val clock: () -> Instant = Instant::now,
) {
    private val _state = MutableStateFlow<PrScreen?>(null)
    val state: StateFlow<PrScreen?> = _state.asStateFlow()

    private val jobs = mutableListOf<Job>()
    private var mentionSearch: Job? = null
    /** What was typed and not sent, by "pr:thread": kept for the session. */
    private val drafts = mutableMapOf<String, Draft>()
    private val recentMentions = ArrayDeque<Person>()
    private var revisions = 0
    private var localIds = 0

    /** [known] (the dashboard row) shows at once; [target] opens a thread from a mention. */
    fun open(id: Int, known: PullRequest? = null, target: PrCommentTarget? = null) {
        cancelAll()
        val mine = me()
        val composers = drafts.filterKeys { it.startsWith("$id:") }
            .mapKeys { it.key.substringAfter(':').toInt() }
            .mapValues { ComposerState(it.value, ++revisions) }
        _state.value = PrScreen(
            id, known, me = mine, composers = composers, target = target,
            tab = if (target != null) PrTab.THREADS else PrTab.OVERVIEW,
            filter = if (target != null) ThreadFilter.ALL else ThreadFilter.ACTIVE,
            vote = VoteState(known?.let { myVote(it, mine) } ?: Vote.NONE),
            names = known?.let { namesOf(it, mine) }.orEmpty(),
        )
        load(id, known)
    }

    fun close() {
        cancelAll()
        _state.value = null
    }

    /** Everything read again, after an error. */
    fun retry() {
        val s = _state.value ?: return
        open(s.id, s.pr, s.target)
    }

    fun selectTab(tab: PrTab) = _state.update { it?.copy(tab = tab) }

    fun selectFilter(filter: ThreadFilter) = _state.update { it?.copy(filter = filter) }

    /** The thread of [PrScreen.target] was scrolled to: not again. */
    fun targetShown() = _state.update { it?.copy(target = null) }

    private fun cancelAll() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        mentionSearch?.cancel()
    }

    private fun update(id: Int, change: (PrScreen) -> PrScreen) {
        _state.update { s -> if (s?.id == id) change(s) else s }
    }

    private fun load(id: Int, known: PullRequest?) {
        jobs += scope.launch {
            val api = api()
            val pr = known ?: try {
                api.pullRequest(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(id) { it.copy(error = errorText(e)) }
                return@launch
            }
            val mine = me()
            if (known == null) {
                update(id) { it.copy(pr = pr, vote = VoteState(myVote(pr, mine)), names = it.names + namesOf(pr, mine)) }
            }
            launch { threads(api, pr) }
            if (pr.projectId.isNotEmpty()) launch { policies(api, pr) }
            val detail = runCatchingNotCancelled { api.pullRequestDetail(pr) }
            if (detail != null) {
                update(id) {
                    it.copy(
                        pr = detail.pr, detail = detail,
                        vote = VoteMachine.synced(it.vote, myVote(detail.pr, mine)),
                        names = it.names + namesOf(detail.pr, mine),
                    )
                }
                if (pr.projectId.isEmpty() && detail.pr.projectId.isNotEmpty()) launch { policies(api, detail.pr) }
                launch {
                    val items = if (detail.workItemIds.isEmpty()) emptyList()
                    else runCatchingNotCancelled { api.workItems(detail.workItemIds) }.orEmpty()
                    update(id) { it.copy(workItems = items) }
                }
                resolveNames(api, id)
            } else {
                update(id) { it.copy(detail = PullRequestDetail(pr), workItems = emptyList()) }
            }
        }
    }

    private suspend fun policies(api: AdoApi, pr: PullRequest) {
        val read = runCatchingNotCancelled { api.policyEvaluations(pr) }
        update(pr.id) { it.copy(policies = read ?: it.policies, policiesFailed = read == null && it.policies == null) }
    }

    /** The threads; for a [PrScreen.target], the reply to its author ready in its composer. */
    private suspend fun threads(api: AdoApi, pr: PullRequest) {
        val id = pr.id
        try {
            val read = api.pullRequestThreads(pr)
            update(id) { s ->
                val known = read.map { it.id }.toSet()
                s.copy(
                    threads = s.threads.copy(loaded = true, error = null, items = read + s.threads.items.filter { it.id !in known }),
                    names = s.names + read.flatMap { t -> t.comments.map { it.author } }
                        .filter { it.name.isNotBlank() }.associate { it.id.lowercase() to it.name },
                )
            }
            val target = _state.value?.takeIf { it.id == id }?.target
            val thread = target?.let { t -> read.firstOrNull { it.id == t.threadId } }
            if (target != null && thread == null) {
                update(id) { it.copy(target = null) }
            } else if (thread != null) {
                val comment = thread.comments.firstOrNull { it.id == target.commentId } ?: thread.comments.lastOrNull()
                val author = comment?.author
                val mine = me()
                if (author != null && author.name.isNotBlank() && !author.id.equals(mine?.id, ignoreCase = true)) {
                    update(id) { s ->
                        if (!s.composer(thread.id).draft.isBlank) return@update s
                        val draft = MentionTyping.reply(Person(author.id, author.name, ""))
                        drafts["$id:${thread.id}"] = draft
                        s.copy(composers = s.composers + (thread.id to ComposerState(draft, ++revisions)))
                    }
                }
            }
            resolveNames(api, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update(id) { it.copy(threads = it.threads.copy(loaded = true, error = errorText(e)), target = null) }
        }
    }

    /** Names for the mentions of people who are neither reviewers nor authors of a comment. */
    private suspend fun resolveNames(api: AdoApi, id: Int) {
        val s = _state.value?.takeIf { it.id == id } ?: return
        val texts = listOfNotNull(s.detail?.description) + s.threads.items.flatMap { t -> t.comments.map { it.text } }
        val missing = texts.flatMap { Markdown.mentionIds(it) }.toSet() - s.names.keys
        if (missing.isEmpty()) return
        val found = runCatchingNotCancelled { api.identityNames(missing) }.orEmpty()
        if (found.isNotEmpty()) update(id) { it.copy(names = it.names + found.mapKeys { k -> k.key.lowercase() }) }
    }

    /** Shows [vote] at once and saves it; refused, my vote before is shown again with the reason. */
    fun vote(vote: Int) {
        val s = _state.value ?: return
        val pr = s.pr ?: return
        val mine = s.me ?: return
        if (!s.canVote) return
        val next = VoteMachine.start(s.vote, vote) ?: return
        val id = s.id
        update(id) { it.copy(vote = next) }
        jobs += scope.launch {
            try {
                val saved = api().votePullRequest(pr, mine.id, vote)
                update(id) { st ->
                    val current = st.pr ?: pr
                    val reviewers = if (current.reviewers.any { it.id.equals(saved.id, ignoreCase = true) }) {
                        current.reviewers.map { if (it.id.equals(saved.id, ignoreCase = true)) it.copy(vote = saved.vote) else it }
                    } else {
                        current.reviewers + Reviewer(saved.id.ifBlank { mine.id }, saved.name.ifBlank { mine.name }, saved.vote, required = false)
                    }
                    st.copy(vote = VoteMachine.saved(st.vote, saved.vote), pr = current.copy(reviewers = reviewers))
                }
                onVoted(_state.value?.pr ?: pr, saved.vote)
                // A minimum number of approvals may be met now
                policies(api(), _state.value?.pr ?: pr)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(id) { it.copy(vote = VoteMachine.failed(it.vote, errorOf(e))) }
            }
        }
    }

    fun dismissVoteError() = _state.update { it?.copy(vote = it.vote.copy(error = null)) }

    /** Typed in a composer: kept for the session, also once the page is closed. */
    fun editDraft(threadId: Int, draft: Draft) {
        val s = _state.value ?: return
        val key = "${s.id}:$threadId"
        if (draft.text.isEmpty()) drafts.remove(key) else drafts[key] = draft
        update(s.id) {
            it.copy(
                composers = it.composers + (threadId to it.composer(threadId).copy(draft = draft)),
                threads = it.threads.copy(sendErrors = it.threads.sendErrors - threadId),
            )
        }
    }

    /**
     * Posts what the composer of [threadId] holds (a reply, or a new thread for
     * [PrScreen.NEW_THREAD]), shown at once as being sent. Refused, it goes back to the composer
     * (unless something else was typed meanwhile) with the reason.
     */
    fun send(threadId: Int) {
        val s = _state.value ?: return
        val pr = s.pr ?: return
        val draft = s.composer(threadId).draft.pruned()
        if (draft.isBlank) return
        val thread = s.threads.items.firstOrNull { it.id == threadId }
        if (threadId != PrScreen.NEW_THREAD && thread == null) return
        val id = s.id
        val markdown = PrMarkup.build(draft)
        val sending = SendingPrComment(++localIds, threadId, markdown, clock())
        drafts.remove("$id:$threadId")
        draft.mentions.forEach { p ->
            recentMentions.removeAll { it.id == p.id }
            recentMentions.addFirst(p)
        }
        update(id) {
            it.copy(
                composers = it.composers + (threadId to ComposerState(Draft(), ++revisions)),
                threads = it.threads.copy(sending = it.threads.sending + sending, sendErrors = it.threads.sendErrors - threadId),
                names = it.names + draft.mentions.associate { p -> p.id.lowercase() to p.name },
            )
        }
        jobs += scope.launch {
            try {
                val api = api()
                val saved = if (thread == null) {
                    api.createPullRequestThread(pr, markdown)
                } else {
                    // Replies go to the thread's first comment, as Azure DevOps' own page does
                    val root = thread.comments.firstOrNull { it.parentId == 0 }?.id ?: thread.comments.first().id
                    val comment = api.addPullRequestComment(pr, threadId, root, markdown)
                    val current = _state.value?.threads?.items?.firstOrNull { it.id == threadId } ?: thread
                    current.copy(comments = current.comments.filterNot { it.id == comment.id } + comment)
                }
                update(id) { st ->
                    val items = if (st.threads.items.any { it.id == saved.id }) {
                        st.threads.items.map { if (it.id == saved.id) saved.copy(status = it.status ?: saved.status) else it }
                    } else {
                        st.threads.items + saved
                    }
                    st.copy(threads = st.threads.copy(items = items, sending = st.threads.sending.filterNot { it.localId == sending.localId }))
                }
                onCommented(pr, saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(id) { st ->
                    val restore = st.composer(threadId).draft.isBlank
                    if (restore) drafts["$id:$threadId"] = draft
                    st.copy(
                        composers = if (restore) st.composers + (threadId to ComposerState(draft, ++revisions)) else st.composers,
                        threads = st.threads.copy(
                            sending = st.threads.sending.filterNot { it.localId == sending.localId },
                            sendErrors = st.threads.sendErrors + (threadId to errorOf(e)),
                        ),
                    )
                }
            }
        }
    }

    /** Resolves, reactivates… a thread: shown at once, its status before again if refused. */
    fun setStatus(threadId: Int, status: String) {
        val s = _state.value ?: return
        val pr = s.pr ?: return
        val thread = s.threads.shown.firstOrNull { it.id == threadId } ?: return
        if (thread.status == status || threadId in s.threads.statusPending) return
        val id = s.id
        update(id) {
            it.copy(
                threads = it.threads.copy(
                    statusPending = it.threads.statusPending + (threadId to status),
                    statusErrors = it.threads.statusErrors - threadId,
                ),
            )
        }
        jobs += scope.launch {
            try {
                val saved = api().setPullRequestThreadStatus(pr, threadId, status)
                update(id) { st ->
                    st.copy(
                        threads = st.threads.copy(
                            items = st.threads.items.map {
                                if (it.id == threadId) it.copy(status = saved.status ?: status) else it
                            },
                            statusPending = st.threads.statusPending - threadId,
                        ),
                    )
                }
                // "Comments must be resolved" may pass now
                policies(api(), pr)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(id) {
                    it.copy(
                        threads = it.threads.copy(
                            statusPending = it.threads.statusPending - threadId,
                            statusErrors = it.threads.statusErrors + (threadId to errorOf(e)),
                        ),
                    )
                }
            }
        }
    }

    /**
     * People for the mention being typed: those mentioned lately, the reviewers and the authors
     * of the comments first, at once, then the directory's search.
     */
    fun searchMentions(query: String) {
        val s = _state.value ?: return
        val q = query.trim()
        mentionSearch?.cancel()
        if (q.isEmpty()) {
            if (s.mentions != PeopleSearch()) _state.update { it?.copy(mentions = PeopleSearch()) }
            return
        }
        val myId = s.me?.id
        val authors = s.threads.items.flatMap { t -> t.comments.map { Person(it.author.id, it.author.name, "") } }.asReversed()
        val reviewers = s.pr?.reviewers.orEmpty().filterNot { it.group }.map { Person(it.id, it.name, "") }
        val author = listOfNotNull(s.pr?.author?.let { Person(it.id, it.name, "") })
        val recent = (recentMentions + authors + author + reviewers)
            .filter { it.name.isNotBlank() && !it.id.equals(myId, ignoreCase = true) && it.name.contains(q, ignoreCase = true) }
            .distinctBy { it.id.lowercase() }
        _state.update { it?.copy(mentions = PeopleSearch(q, loading = true, results = recent.take(MAX_SUGGESTIONS))) }
        mentionSearch = scope.launch {
            delay(SEARCH_DELAY_MS)
            val found = runCatchingNotCancelled { api().searchPeople(q) }.orEmpty()
            val all = (recent + found.filter { it.id.isNotBlank() }).distinctBy { it.id.lowercase() }
            _state.update { it?.copy(mentions = PeopleSearch(q, loading = false, results = all.take(MAX_SUGGESTIONS))) }
        }
    }

    private fun errorOf(e: Exception) =
        CommentError(if (e is AdoException && e.isDenied) SaveProblem.DENIED else SaveProblem.OTHER, errorText(e))

    private fun myVote(pr: PullRequest, me: Person?) =
        me?.let { m -> pr.reviewers.firstOrNull { it.id.equals(m.id, ignoreCase = true) }?.vote } ?: Vote.NONE

    private fun namesOf(pr: PullRequest, me: Person?): Map<String, String> =
        (pr.reviewers.map { it.id to it.name } + (pr.author.id to pr.author.name) + listOfNotNull(me?.let { it.id to it.name }))
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .associate { it.first.lowercase() to it.second }

    private suspend fun <T> runCatchingNotCancelled(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val SEARCH_DELAY_MS = 250L
        const val MAX_SUGGESTIONS = 8
    }
}
