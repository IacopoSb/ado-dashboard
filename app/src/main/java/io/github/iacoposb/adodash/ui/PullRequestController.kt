package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.FileAnchor
import io.github.iacoposb.adodash.api.FileContent
import io.github.iacoposb.adodash.api.Identity
import io.github.iacoposb.adodash.api.PrCommit
import io.github.iacoposb.adodash.api.PrFileChange
import io.github.iacoposb.adodash.api.PrIteration
import io.github.iacoposb.adodash.api.Person
import io.github.iacoposb.adodash.api.PolicyEvaluation
import io.github.iacoposb.adodash.api.PrStatus
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
 * The build checks being queued again, by evaluation id ([busy]: until the policies are read
 * again), and why the last ones were not.
 */
data class RequeueState(val busy: Set<String> = emptySet(), val error: CommentError? = null)

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
        // Case aside first, but /Docs and /docs stay two folders: a git repository can hold both
        changes.groupBy { it.folder }.toSortedMap(String.CASE_INSENSITIVE_ORDER.thenComparing(naturalOrder()))
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
    /** The latest status each service posted (SonarQube…); empty while read or if they cannot be. */
    val statuses: List<PrStatus> = emptyList(),
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
    val close: CloseState = CloseState(),
    val requeue: RequeueState = RequeueState(),
) {
    fun composer(threadId: Int) = composers[threadId] ?: ComposerState()

    /** "active", "completed" or "abandoned". */
    val status: String get() = detail?.status ?: PullRequestDetail.ACTIVE

    /** Votes are for active pull requests that are not drafts. */
    val canVote: Boolean get() = pr != null && me != null && !pr.draft && status == PullRequestDetail.ACTIVE

    /**
     * Complete and Abandon are offered on active pull requests (drafts too, Complete disabled):
     * who may close it is Azure DevOps' to say.
     */
    val canClose: Boolean get() = pr != null && detail != null && status == PullRequestDetail.ACTIVE

    /** Build checks can be queued again on active pull requests, as on Azure DevOps' page. */
    val canRequeue: Boolean get() = pr != null && pr.projectId.isNotEmpty() && status == PullRequestDetail.ACTIVE

    /** Something keeps it from completing without bypassing the policies (see [PrCompletion.blocked]). */
    val blocked: Boolean get() = pr != null && PrCompletion.blocked(pr, policies)

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
    /** After I completed or abandoned it: off the dashboard's lists. */
    private val onClosed: suspend (PullRequest) -> Unit = {},
    /** After I reactivated it: back on the dashboard at the next sync. */
    private val onReactivated: suspend (PullRequest) -> Unit = {},
    private val clock: () -> Instant = Instant::now,
    /** Where diffs are computed: off the main thread. */
    private val compute: CoroutineContext = Dispatchers.Default,
) {
    private val _state = MutableStateFlow<PrScreen?>(null)
    val state: StateFlow<PrScreen?> = _state.asStateFlow()

    /** Reads of the pull request shown: cancelled when the page closes or another opens. */
    private val jobs = mutableListOf<Job>()
    private var mentionSearch: Job? = null
    /** The policies read again while re-queued builds run; also among [jobs]. */
    private var checksWatch: Job? = null
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
            launch { statuses(api, pr) }
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

    /** Statuses are extra: when they cannot be read, the policies are shown alone. */
    private suspend fun statuses(api: AdoApi, pr: PullRequest) {
        val read = runCatchingNotCancelled { api.pullRequestStatuses(pr) } ?: return
        update(pr.id) { it.copy(statuses = read) }
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

    /** "Complete" tapped: the dialog with the options Azure DevOps would offer. */
    fun openComplete() = _state.update { s ->
        val pr = s?.pr ?: return@update s
        if (!s.canClose || s.close.dialog != null) return@update s
        s.copy(close = s.close.copy(dialog = PrCompletion.draft(pr, s.detail, s.policies), error = null))
    }

    /** A choice changed in the dialog. */
    fun editComplete(draft: CompleteDraft) = _state.update { s ->
        if (s?.close?.dialog == null || s.close.busy != null) s else s.copy(close = s.close.copy(dialog = draft, error = null))
    }

    /** Closed without completing; while completing, the page goes on showing it. */
    fun dismissComplete() = _state.update { s -> s?.copy(close = s.close.copy(dialog = null, error = null)) }

    fun dismissCloseError() = _state.update { s -> s?.copy(close = s.close.copy(error = null)) }

    /**
     * Completes the pull request with the dialog's options at the commit the page shows; Azure
     * DevOps merges it, perhaps after the call: the page shows it completing until it is read
     * completed, or with why the merge failed.
     */
    fun complete() {
        val s = _state.value ?: return
        val pr = s.pr ?: return
        val draft = s.close.dialog ?: return
        val commit = pr.lastCommit ?: return
        if (s.close.busy != null || s.close.merging || !PrCompletion.canComplete(pr, s.status, s.blocked, draft)) return
        closeAction(s.id, CloseAction.COMPLETE, bypass = draft.bypass) { api ->
            val saved = api.completePullRequest(pr, commit, draft.options())
            applyDetail(s.id, saved)
            awaitMerge(api, s.id, saved)
        }
    }

    /** Auto-complete set by me, with the dialog's options: it completes once the policies pass. */
    fun setAutoComplete() {
        val s = _state.value ?: return
        val pr = s.pr ?: return
        val mine = s.me ?: return
        val draft = s.close.dialog ?: return
        if (s.close.busy != null || !PrCompletion.canAutoComplete(pr, s.status, draft)) return
        closeAction(s.id, CloseAction.AUTO_COMPLETE) { api ->
            val saved = api.setAutoComplete(pr, mine.id, draft.options(bypassing = false))
            applyDetail(s.id, saved.copy(autoCompleteSetBy = saved.autoCompleteSetBy ?: Identity(mine.id, mine.name)))
            update(s.id) { it.copy(close = it.close.copy(dialog = null)) }
        }
    }

    fun cancelAutoComplete() {
        val s = _state.value ?: return
        val pr = s.pr ?: return
        if (s.close.busy != null || s.detail?.autoCompleteSetBy == null) return
        closeAction(s.id, CloseAction.CANCEL_AUTO_COMPLETE) { api ->
            applyDetail(s.id, api.setAutoComplete(pr, null, null).copy(autoCompleteSetBy = null))
        }
    }

    /** Abandons it (the page asked first): it leaves the dashboard, and can be reactivated. */
    fun abandon() {
        val s = _state.value ?: return
        val pr = s.pr ?: return
        if (s.close.busy != null || !s.canClose) return
        closeAction(s.id, CloseAction.ABANDON) { api ->
            val saved = api.setPullRequestStatus(pr, PullRequestDetail.ABANDONED)
            applyDetail(s.id, saved)
            update(s.id) { it.copy(close = it.close.copy(dialog = null)) }
            if (saved.status == PullRequestDetail.ABANDONED) onClosed(saved.pr)
        }
    }

    fun reactivate() {
        val s = _state.value ?: return
        val pr = s.pr ?: return
        if (s.close.busy != null || s.status != PullRequestDetail.ABANDONED) return
        closeAction(s.id, CloseAction.REACTIVATE) { api ->
            val saved = api.setPullRequestStatus(pr, PullRequestDetail.ACTIVE)
            applyDetail(s.id, saved)
            if (saved.status == PullRequestDetail.ACTIVE) onReactivated(saved.pr)
            // The policies run again
            if (shown(s.id) != null) policies(api, saved.pr)
        }
    }

    /**
     * Sends [action]; refused, the reason, and for a pull request that changed meanwhile, it read
     * again. Not among the reads: closing the page never cancels it.
     */
    private fun closeAction(id: Int, action: CloseAction, bypass: Boolean = false, send: suspend (AdoApi) -> Unit) {
        update(id) { it.copy(close = it.close.copy(busy = action, error = null)) }
        scope.launch {
            val api = api()
            try {
                send(api)
                update(id) { it.copy(close = it.close.copy(busy = null)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val problem = when {
                    e is AdoException && e.isDenied -> CloseProblem.DENIED
                    e is AdoException && (e.code == 409 || e.code == 412) -> CloseProblem.STALE
                    else -> CloseProblem.OTHER
                }
                update(id) {
                    it.copy(close = it.close.copy(busy = null, merging = false, error = CloseError(action, problem, errorText(e), bypass)))
                }
                if (problem == CloseProblem.STALE) shown(id)?.pr?.let { reread(api, it) }
            }
        }
    }

    /**
     * Completed: done when Azure DevOps answers it completed; otherwise it merges in the
     * background, read again until it is completed or its merge failed.
     */
    private suspend fun awaitMerge(api: AdoApi, id: Int, first: PullRequestDetail) {
        var detail = first
        var polls = 0
        update(id) { it.copy(close = it.close.copy(merging = true)) }
        while (detail.status == PullRequestDetail.ACTIVE && !PrCompletion.mergeFailed(detail.pr) && polls < MERGE_POLLS) {
            delay(MERGE_POLL_MS)
            detail = api.pullRequestDetail(detail.pr)
            applyDetail(id, detail)
            polls++
        }
        val error = when {
            detail.status == PullRequestDetail.COMPLETED -> null
            PrCompletion.mergeFailed(detail.pr) ->
                CloseError(CloseAction.COMPLETE, CloseProblem.MERGE_FAILED, detail.mergeFailure ?: detail.pr.mergeStatus.orEmpty())
            else -> CloseError(CloseAction.COMPLETE, CloseProblem.SLOW)
        }
        // A failed merge keeps the dialog open with the reason; otherwise the page says how it went
        update(id) {
            it.copy(close = it.close.copy(merging = false, error = error, dialog = it.close.dialog.takeIf { error?.problem == CloseProblem.MERGE_FAILED }))
        }
        if (detail.status == PullRequestDetail.COMPLETED) onClosed(detail.pr)
    }

    /** What Azure DevOps answered is the pull request now; it leaves out the work items. */
    private fun applyDetail(id: Int, saved: PullRequestDetail) = update(id) { s ->
        val old = s.detail
        val detail = if (old == null) saved else saved.copy(
            workItemIds = saved.workItemIds.ifEmpty { old.workItemIds },
            labels = saved.labels.ifEmpty { old.labels },
            description = saved.description.ifEmpty { old.description },
        )
        s.copy(pr = saved.pr, detail = detail, vote = VoteMachine.synced(s.vote, myVote(saved.pr, s.me)))
    }

    /** "Re-queue" on a build check: a new build, as Azure DevOps' own button. */
    fun requeueCheck(evaluationId: String) = requeue(listOf(evaluationId))

    /** "Re-queue all expired": every build check outdated by the target branch. */
    fun requeueExpired() = requeue(PrChecks.expiredBuilds(_state.value?.policies))

    fun dismissRequeueError() = _state.update { it?.copy(requeue = it.requeue.copy(error = null)) }

    /**
     * Queues the evaluations [ids] again, one after the other; once refused for lack of
     * permission the others are not tried. The policies are then read again (each row turns to
     * queued or running), and again now and then while the builds run.
     */
    private fun requeue(ids: List<String>) {
        val s = _state.value ?: return
        val pr = s.pr ?: return
        if (!s.canRequeue) return
        val todo = ids.filter { it.isNotEmpty() && it !in s.requeue.busy }.distinct()
        if (todo.isEmpty()) return
        val id = s.id
        update(id) { it.copy(requeue = RequeueState(busy = it.requeue.busy + todo)) }
        // Not among the reads: closing never cancels it
        scope.launch {
            val api = api()
            var error: CommentError? = null
            var queued = false
            for (evaluationId in todo) {
                try {
                    val saved = api.requeuePolicyEvaluation(pr, evaluationId)
                    queued = true
                    update(id) { st ->
                        st.copy(policies = st.policies?.map { if (it.evaluationId == evaluationId) requeued(it, saved) else it })
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = errorOf(e)
                    if (e is AdoException && e.isDenied) break
                }
            }
            if (queued) shown(id)?.let { policies(api, it.pr ?: pr) }
            update(id) { it.copy(requeue = it.requeue.copy(busy = it.requeue.busy - todo.toSet(), error = error)) }
            if (queued) watchChecks(id)
        }
    }

    /** What the re-queue answered of an evaluation; the rest (policy, name) as read before. */
    private fun requeued(old: PolicyEvaluation, saved: PolicyEvaluation) = old.copy(
        status = saved.status.ifEmpty { "queued" },
        buildId = saved.buildId,
        expired = saved.expired,
        completedAt = saved.completedAt,
    )

    /**
     * The policies read again every [CHECKS_POLL_MS] while a build check is queued or running,
     * as long as the page shows the pull request; for a while at most.
     */
    private fun watchChecks(id: Int) {
        checksWatch?.cancel()
        val job = scope.launch {
            repeat(CHECKS_POLLS) {
                delay(CHECKS_POLL_MS)
                val pr = shown(id)?.pr ?: return@launch
                policies(api(), pr)
                if (shown(id)?.policies.orEmpty().none { it.building }) return@launch
            }
        }
        checksWatch = job
        jobs += job
    }

    /** The pull request and its policies read again: after a push made an action stale. */
    private suspend fun reread(api: AdoApi, pr: PullRequest) {
        runCatchingNotCancelled { api.pullRequestDetail(pr) }?.let { applyDetail(pr.id, it) }
        shown(pr.id)?.let { policies(api, it.pr ?: pr) }
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
            val all = (recent + found.filter { it.mentionable }).distinctBy { it.id.lowercase() }
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
        /** A completed merge read again every 2 s, for a minute at most. */
        const val MERGE_POLL_MS = 2_000L
        const val MERGE_POLLS = 30
        /** Re-queued builds: their checks read again every 15 s, for 20 minutes at most. */
        const val CHECKS_POLL_MS = 15_000L
        const val CHECKS_POLLS = 80
    }
}
