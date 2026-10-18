package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.FileAnchor
import io.github.iacoposb.adodash.api.FileContent
import io.github.iacoposb.adodash.api.PrCommit
import io.github.iacoposb.adodash.api.PrFileChange
import io.github.iacoposb.adodash.api.PrIteration
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.coroutines.CoroutineContext

enum class PrTab { OVERVIEW, FILES, THREADS, COMMITS }

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

/**
 * A comment posted from the page, shown at once while it is sent; [threadId] 0 for a new thread,
 * [PrScreen.NEW_FILE_THREAD] for one on the line of a file at [anchor].
 */
data class SendingPrComment(val localId: Int, val threadId: Int, val markdown: String, val at: Instant, val anchor: FileAnchor? = null)

/** A file's diff in the Files tab: read and computed when the file is first shown. */
sealed interface FileDiff {
    data object Loading : FileDiff
    data class Ready(val lines: List<DiffLine>, val added: Int, val deleted: Int) : FileDiff
    data object TooLarge : FileDiff
    data object Binary : FileDiff
    data class Failed(val message: String) : FileDiff
}

/**
 * The Files tab: the files of the latest push ([iteration]) and the one [selected]; [diffs] by
 * path; [viewed] the files marked as seen (for the session); [expandedGaps] the hidden stretches
 * shown, by path; [newThread] the line a new thread is being written on.
 */
data class PrFilesState(
    val loaded: Boolean = false,
    val error: String? = null,
    val iteration: PrIteration? = null,
    val changes: List<PrFileChange> = emptyList(),
    val selected: String? = null,
    val diffs: Map<String, FileDiff> = emptyMap(),
    val viewed: Set<String> = emptySet(),
    val collapsedFolders: Set<String> = emptySet(),
    val sideBySide: Boolean = false,
    val expandedGaps: Map<String, Set<Int>> = emptyMap(),
    val newThread: FileAnchor? = null,
) {
    val selectedChange: PrFileChange? get() = changes.firstOrNull { it.path == selected }

    /** Lines added and deleted in all the files, once every diff is known. */
    val totals: Pair<Int, Int>?
        get() {
            val ready = changes.map { diffs[it.path] as? FileDiff.Ready ?: return null }
            return ready.sumOf { it.added } to ready.sumOf { it.deleted }
        }
}

/** The files by folder, folders and files in path order. Pure. */
object PrFileList {
    fun groups(changes: List<PrFileChange>): List<Pair<String, List<PrFileChange>>> =
        changes.groupBy { it.folder }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .map { (folder, files) -> folder to files.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }) }

    fun ordered(changes: List<PrFileChange>): List<PrFileChange> = groups(changes).flatMap { it.second }
}

data class PrCommitsState(val loaded: Boolean = false, val error: String? = null, val items: List<PrCommit> = emptyList())

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
    val files: PrFilesState = PrFilesState(),
    val commits: PrCommitsState = PrCommitsState(),
) {
    fun composer(threadId: Int) = composers[threadId] ?: ComposerState()

    /** "active", "completed" or "abandoned". */
    val status: String get() = detail?.status ?: "active"

    /** Votes are for active pull requests that are not drafts. */
    val canVote: Boolean get() = pr != null && me != null && !pr.draft && status == "active"

    companion object {
        /** The composer of a new thread on the pull request as a whole. */
        const val NEW_THREAD = 0

        /** The composer of a new thread on a line of a file ([PrFilesState.newThread]). */
        const val NEW_FILE_THREAD = -1
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
    /** Where diffs are computed: off the main thread. */
    private val compute: CoroutineContext = Dispatchers.Default,
) {
    private val _state = MutableStateFlow<PrScreen?>(null)
    val state: StateFlow<PrScreen?> = _state.asStateFlow()

    /** Reads of the pull request shown: cancelled when the page closes or another opens. */
    private val jobs = mutableListOf<Job>()
    private var mentionSearch: Job? = null
    /** What was typed and not sent, by "pr:thread": kept for the session. */
    private val drafts = mutableMapOf<String, Draft>()
    private val recentMentions = ArrayDeque<Person>()
    private var revisions = 0
    private var localIds = 0
    /** The files marked as viewed, by pull request, and the diff's layout: kept for the session. */
    private val viewed = mutableMapOf<Int, Set<String>>()
    private var sideBySide = false
    /** Diffs read ahead for the +/- counts, a few at a time. */
    private val prefetch = Semaphore(PREFETCH_PARALLEL)

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
            files = PrFilesState(viewed = viewed[id].orEmpty(), sideBySide = sideBySide),
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

    /** Only reads: votes, comments and statuses already sent go on, closed or not. */
    private fun cancelAll() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        mentionSearch?.cancel()
    }

    private fun update(id: Int, change: (PrScreen) -> PrScreen) {
        _state.update { s -> if (s?.id == id) change(s) else s }
    }

    /** The page of [id], if it is still the one open. */
    private fun shown(id: Int): PrScreen? = _state.value?.takeIf { it.id == id }

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
            // Read once for the files and for the threads' lines in them
            val iterations = async {
                try {
                    Result.success(api.pullRequestIterations(pr))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            launch { threads(api, pr) { iterations.await().getOrNull()?.maxOfOrNull { it.id } } }
            launch { files(api, pr) { iterations.await().getOrThrow() } }
            launch { commits(api, pr) }
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

    /**
     * The threads, with their lines tracked to the latest push when it is known (as the Files tab
     * shows it); for a [PrScreen.target], the reply to its author ready in its composer.
     */
    private suspend fun threads(api: AdoApi, pr: PullRequest, latestIteration: suspend () -> Int?) {
        val id = pr.id
        try {
            val read = latestIteration()?.let { api.pullRequestThreads(pr, it) } ?: api.pullRequestThreads(pr)
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

    /** The files of the latest push; the first one's diff, then the others' for their counts. */
    private suspend fun files(api: AdoApi, pr: PullRequest, iterations: suspend () -> List<PrIteration>) {
        val id = pr.id
        try {
            val iteration = iterations().maxByOrNull { it.id }
            val changes = iteration?.let { PrFileList.ordered(api.iterationChanges(pr, it.id)) }.orEmpty()
            update(id) {
                it.copy(
                    files = it.files.copy(
                        loaded = true, error = null, iteration = iteration, changes = changes,
                        selected = it.files.selected?.takeIf { p -> changes.any { c -> c.path == p } } ?: changes.firstOrNull()?.path,
                    ),
                )
            }
            val selected = _state.value?.takeIf { it.id == id }?.files?.selected ?: return
            loadDiff(selected)
            if (changes.size <= PREFETCH_MAX_FILES) changes.filter { it.path != selected }.forEach { loadDiff(it.path, ahead = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update(id) { it.copy(files = it.files.copy(loaded = true, error = errorText(e))) }
        }
    }

    private suspend fun commits(api: AdoApi, pr: PullRequest) {
        val id = pr.id
        try {
            val read = api.pullRequestCommits(pr)
            update(id) { it.copy(commits = PrCommitsState(loaded = true, items = read)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update(id) { it.copy(commits = PrCommitsState(loaded = true, error = errorText(e))) }
        }
    }

    /** Reads and diffs a file unless it is already (or being); [ahead] when not shown yet, queued. */
    private fun loadDiff(path: String, ahead: Boolean = false) {
        val s = _state.value ?: return
        val pr = s.pr ?: return
        val change = s.files.changes.firstOrNull { it.path == path } ?: return
        val iteration = s.files.iteration ?: return
        val current = s.files.diffs[path]
        if (current != null && current !is FileDiff.Failed) return
        val id = s.id
        update(id) { it.copy(files = it.files.copy(diffs = it.files.diffs + (path to FileDiff.Loading))) }
        jobs += scope.launch {
            val diff = try {
                if (ahead) prefetch.withPermit { readDiff(api(), pr, change, iteration) } else readDiff(api(), pr, change, iteration)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileDiff.Failed(errorText(e))
            }
            update(id) { it.copy(files = it.files.copy(diffs = it.files.diffs + (path to diff))) }
        }
    }

    private suspend fun readDiff(api: AdoApi, pr: PullRequest, change: PrFileChange, iteration: PrIteration): FileDiff = coroutineScope {
        val base = change.basePath?.let { async { api.fileContent(pr, it, iteration.baseCommit) } }
        val target = change.targetPath?.let { async { api.fileContent(pr, it, iteration.sourceCommit) } }
        val old = base?.await() ?: FileContent.Missing
        val new = target?.await() ?: FileContent.Missing
        when {
            old is FileContent.Binary || new is FileContent.Binary -> FileDiff.Binary
            old is FileContent.TooLarge || new is FileContent.TooLarge -> FileDiff.TooLarge
            else -> withContext(compute) {
                val oldLines = LineDiff.lines((old as? FileContent.Text)?.text.orEmpty())
                val newLines = LineDiff.lines((new as? FileContent.Text)?.text.orEmpty())
                if (oldLines.size > FileContent.MAX_LINES || newLines.size > FileContent.MAX_LINES) {
                    FileDiff.TooLarge
                } else {
                    val lines = LineDiff.diff(oldLines, newLines)
                    val (added, deleted) = DiffRows.counts(lines)
                    FileDiff.Ready(lines, added, deleted)
                }
            }
        }
    }

    /** A file of the list tapped: its diff, read if needed. */
    fun selectFile(path: String) {
        val s = _state.value ?: return
        if (s.files.changes.none { it.path == path }) return
        update(s.id) { it.copy(files = it.files.copy(selected = path, newThread = it.files.newThread?.takeIf { a -> a.path == path })) }
        loadDiff(path)
    }

    /** A file whose diff could not be read: read again. */
    fun retryFile(path: String) = loadDiff(path)

    /** Marks a file as viewed, or not any more: for the session. */
    fun toggleViewed(path: String) {
        val s = _state.value ?: return
        val next = if (path in s.files.viewed) s.files.viewed - path else s.files.viewed + path
        viewed[s.id] = next
        update(s.id) { it.copy(files = it.files.copy(viewed = next)) }
    }

    fun toggleFolder(folder: String) = _state.update { s ->
        s?.copy(files = s.files.copy(collapsedFolders = s.files.collapsedFolders.let { if (folder in it) it - folder else it + folder }))
    }

    /** Unified or side by side: kept for the session. */
    fun setSideBySide(on: Boolean) {
        sideBySide = on
        _state.update { it?.copy(files = it.files.copy(sideBySide = on)) }
    }

    /** "N unchanged lines" tapped: shown. */
    fun expandGap(path: String, start: Int) = _state.update { s ->
        s?.copy(files = s.files.copy(expandedGaps = s.files.expandedGaps + (path to (s.files.expandedGaps[path].orEmpty() + start))))
    }

    /** A line long-pressed (or its number tapped): the composer of a new thread under it. */
    fun startFileThread(line: LineRef) = _state.update { s ->
        val path = s?.files?.selected ?: return@update s
        s.copy(files = s.files.copy(newThread = FileAnchor(path, line.line, line.left)))
    }

    fun cancelFileThread() = _state.update { it?.copy(files = it.files.copy(newThread = null)) }

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
        // Not among the reads: closing never cancels it
        scope.launch {
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
                onVoted(shown(id)?.pr ?: pr, saved.vote)
                // A minimum number of approvals may be met now
                shown(id)?.let { policies(api(), it.pr ?: pr) }
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
        val anchor = if (threadId == PrScreen.NEW_FILE_THREAD) s.files.newThread ?: return else null
        if (threadId != PrScreen.NEW_THREAD && anchor == null && thread == null) return
        val change = anchor?.let { a -> s.files.changes.firstOrNull { it.path == a.path } }
        val iteration = s.files.iteration?.id ?: 0
        val id = s.id
        val markdown = PrMarkup.build(draft)
        val sending = SendingPrComment(++localIds, threadId, markdown, clock(), anchor)
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
                files = if (anchor != null) it.files.copy(newThread = null) else it.files,
            )
        }
        // Not among the reads: closing never cancels it
        scope.launch {
            try {
                val api = api()
                val saved = if (anchor != null) {
                    api.createPullRequestFileThread(pr, markdown, anchor, iteration, change?.changeTrackingId ?: 0).let { t ->
                        // Placed where it was written even if the answer leaves the context out
                        if (t.filePath == null) t.copy(filePath = anchor.path, line = anchor.line, leftSide = anchor.left) else t
                    }
                } else if (thread == null) {
                    api.createPullRequestThread(pr, markdown)
                } else {
                    // Replies go to the thread's first comment, as Azure DevOps' own page does
                    val root = thread.comments.firstOrNull { it.parentId == 0 }?.id ?: thread.comments.first().id
                    val comment = api.addPullRequestComment(pr, threadId, root, markdown)
                    val current = shown(id)?.threads?.items?.firstOrNull { it.id == threadId } ?: thread
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
                        // The composer back on its line, unless another one was opened
                        files = if (anchor != null && restore && st.files.newThread == null) st.files.copy(newThread = anchor) else st.files,
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
        // Not among the reads: closing never cancels it
        scope.launch {
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
                if (shown(id) != null) policies(api(), pr)
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
        /** Up to this many files, every diff is read ahead for the counts. */
        const val PREFETCH_MAX_FILES = 30
        const val PREFETCH_PARALLEL = 3
    }
}
