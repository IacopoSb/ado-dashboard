package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.AreaNode
import io.github.iacoposb.adodash.api.Comment
import io.github.iacoposb.adodash.api.FieldChange
import io.github.iacoposb.adodash.api.Iteration
import io.github.iacoposb.adodash.api.LinkKind
import io.github.iacoposb.adodash.api.Person
import io.github.iacoposb.adodash.api.StateInfo
import io.github.iacoposb.adodash.api.WiFields
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.api.WorkItemDetail
import io.github.iacoposb.adodash.api.isConflict
import io.github.iacoposb.adodash.api.isDenied
import io.github.iacoposb.adodash.sync.FilterCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/** A link of the work item open, with what is known of its target. */
data class LinkRow(
    val kind: LinkKind,
    val id: Int,
    /** The work item's type, title and state; null for pull requests or when unreadable. */
    val type: String? = null,
    val title: String? = null,
    val state: String? = null,
    /** A pull request's status ("active", "completed", "abandoned"). */
    val prStatus: String? = null,
    val draft: Boolean = false,
    val url: String = "",
)

enum class SaveProblem { CONFLICT, DENIED, OTHER }

/** Why the change of [field] was not saved: it shows its saved value again. */
data class FieldError(val field: String, val problem: SaveProblem, val message: String = "")

/** "Assigned to"'s search: [results] are for [query]. */
data class PeopleSearch(val query: String = "", val loading: Boolean = false, val results: List<Person> = emptyList())

/** A comment posted from the composer, shown at once while it is sent. */
data class SendingComment(val localId: Int, val draft: Draft, val html: String, val at: Instant)

/** Why a comment was not posted: its text is back in the composer. */
data class CommentError(val problem: SaveProblem, val message: String = "")

/**
 * The comments of a work item, oldest first: [earlier] reads those before them; [total] all of
 * them, also those not read yet.
 */
data class CommentsState(
    val loaded: Boolean = false,
    val items: List<Comment> = emptyList(),
    val error: String? = null,
    val earlier: String? = null,
    val loadingEarlier: Boolean = false,
    val total: Int = 0,
    val sending: List<SendingComment> = emptyList(),
    val sendError: CommentError? = null,
)

/**
 * What the composer shows. [revision] changes only when the controller sets the draft (a reply,
 * a failed send, a sent one cleared): the text field takes it then, and otherwise keeps its own.
 */
data class ComposerState(val draft: Draft = Draft(), val revision: Int = 0)

/**
 * A work item in the details. [saved] is what Azure DevOps has (null while it is read, [known]
 * from the dashboard row standing in); [pending] the changes being saved, shown already.
 */
data class WorkItemPage(
    val id: Int,
    val known: WorkItem? = null,
    val saved: WorkItemDetail? = null,
    val pending: Map<String, FieldChange> = emptyMap(),
    val error: String? = null,
    val links: List<LinkRow>? = null,
    val fieldError: FieldError? = null,
    /** All the states of the type, in the process' order; null while read. */
    val states: List<StateInfo>? = null,
    val iterations: List<Iteration>? = null,
    val areas: AreaNode? = null,
    /** The type has Remaining Work (tasks and the like). */
    val hasRemainingWork: Boolean = false,
    val comments: CommentsState = CommentsState(),
    val composer: ComposerState = ComposerState(),
    /**
     * The comment to show (from a mention): scrolled to and highlighted once read, with the
     * composer focused for the reply; null once shown.
     */
    val target: Int? = null,
) {
    /** As shown: saved, with the pending changes over it. */
    val detail: WorkItemDetail? get() = saved?.let { pending.values.fold(it) { d, c -> d.with(c) } }
    val item: WorkItem? get() = detail?.item ?: known

    fun saving(field: String) = field in pending
}

/**
 * The work item details open over the dashboard: [pages] is the way there, the last one shown
 * (a link opens its work item over the one before, back returns to it).
 */
data class WorkItemModal(
    val pages: List<WorkItemPage>,
    /** Changes were refused for lack of permission: fields read-only until the app restarts. */
    val readOnly: Boolean = false,
    val people: PeopleSearch = PeopleSearch(),
    /** Me, offered first in "Assigned to". */
    val me: Person? = null,
    /** The people offered for the mention being typed in the composer. */
    val mentions: PeopleSearch = PeopleSearch(),
) {
    val page: WorkItemPage get() = pages.last()
    val canGoBack get() = pages.size > 1
}

/**
 * Reads the work items the details show and saves their changes, one at a time and each against
 * the revision last read, so that nobody else's change is overwritten.
 */
class WorkItemController(
    private val scope: CoroutineScope,
    private val api: suspend () -> AdoApi,
    private val catalog: () -> FilterCatalog?,
    private val me: () -> Person?,
    /** After a save: the dashboard row and, for my own assignment, no notification. */
    private val onSaved: suspend (WorkItemDetail, assignedToMe: Boolean) -> Unit,
    private val errorText: (Throwable) -> String,
    /** After a comment is posted: stored as seen, so that it never comes back as a notification. */
    private val onCommented: suspend (WorkItem, Comment) -> Unit = { _, _ -> },
    private val clock: () -> Instant = Instant::now,
) {
    private val _state = MutableStateFlow<WorkItemModal?>(null)
    val state: StateFlow<WorkItemModal?> = _state.asStateFlow()

    private val _readOnly = MutableStateFlow(false)
    /** Refused once, refused for the session. */
    val readOnly: StateFlow<Boolean> = _readOnly.asStateFlow()
    private val saves = Mutex()
    /** Reads of the work items shown: cancelled when the details close or another opens. */
    private val jobs = mutableListOf<Job>()
    /**
     * The latest revision known of each work item, for the next save: kept across pages, so that
     * a save queued for a work item no longer shown still goes against the right revision.
     */
    private val revisions = mutableMapOf<Int, Int>()
    private var search: Job? = null

    private val statesCache = mutableMapOf<String, List<StateInfo>>()
    private val iterationsCache = mutableMapOf<String, List<Iteration>>()
    private val fieldsCache = mutableMapOf<String, Set<String>>()
    private val membersCache = mutableMapOf<String, List<Person>>()

    /** What was typed in each work item's composer and not sent: kept for the session. */
    private val drafts = mutableMapOf<Int, Draft>()
    /** The people mentioned from the app, the latest first: offered first. */
    private val recentMentions = ArrayDeque<Person>()
    private var composerRevisions = 0
    private var localIds = 0
    private var mentionSearch: Job? = null

    /** [commentId]: from a mention, that comment shown and the reply to its author ready. */
    fun open(id: Int, known: WorkItem? = null, commentId: Int? = null) {
        cancelAll()
        _state.value = WorkItemModal(listOf(newPage(id, known, commentId)), readOnly = _readOnly.value, me = me())
        load(id)
    }

    private fun newPage(id: Int, known: WorkItem? = null, target: Int? = null) =
        WorkItemPage(id, known, composer = ComposerState(drafts[id] ?: Draft(), ++composerRevisions), target = target)

    /** A linked work item, over the one open. */
    fun openLinked(id: Int) {
        val modal = _state.value ?: return open(id)
        if (modal.page.id == id) return
        _state.value = modal.copy(pages = modal.pages + newPage(id), people = PeopleSearch(), mentions = PeopleSearch())
        load(id)
    }

    /** Back to the previous work item, or closed. */
    fun back() {
        val modal = _state.value ?: return
        if (modal.canGoBack) {
            _state.value = modal.copy(pages = modal.pages.dropLast(1), people = PeopleSearch(), mentions = PeopleSearch())
        } else {
            close()
        }
    }

    fun close() {
        cancelAll()
        _state.value = null
    }

    /** Reads the work item shown again, after a conflict. */
    fun reload() {
        val page = _state.value?.page ?: return
        updatePage(page.id) { WorkItemPage(page.id, page.saved?.item ?: page.known, comments = it.comments, composer = it.composer) }
        load(page.id, withComments = false)
    }

    /** Only reads: changes and comments already sent go on, closed or not (see [change]). */
    private fun cancelAll() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        search?.cancel()
        mentionSearch?.cancel()
    }

    private fun knownRevision(id: Int, rev: Int) {
        revisions[id] = maxOf(revisions[id] ?: rev, rev)
    }

    private fun updatePage(id: Int, change: (WorkItemPage) -> WorkItemPage) {
        _state.update { modal -> modal?.copy(pages = modal.pages.map { if (it.id == id) change(it) else it }) }
    }

    private fun load(id: Int, withComments: Boolean = true) {
        jobs += scope.launch {
            val api = api()
            val detail = try {
                api.workItemDetail(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updatePage(id) { it.copy(error = errorText(e)) }
                return@launch
            }
            knownRevision(id, detail.rev)
            updatePage(id) { it.copy(saved = detail, error = null) }
            val item = detail.item
            if (withComments) launch { comments(api, item.project, id) }
            launch { links(api, detail).let { l -> updatePage(id) { it.copy(links = l) } } }
            launch { states(api, item).let { l -> updatePage(id) { it.copy(states = l) } } }
            launch { iterations(api, item.project).let { l -> updatePage(id) { it.copy(iterations = l) } } }
            launch { areas(api, item.project).let { a -> updatePage(id) { it.copy(areas = a) } } }
            launch {
                val fields = fields(api, item)
                updatePage(id) { it.copy(hasRemainingWork = WiFields.REMAINING_WORK in fields || detail.remainingWork != null) }
            }
        }
    }

    private suspend fun links(api: AdoApi, detail: WorkItemDetail): List<LinkRow> = kotlinx.coroutines.coroutineScope {
        val order = listOf(LinkKind.PARENT, LinkKind.CHILD, LinkKind.RELATED, LinkKind.PULL_REQUEST)
        val links = detail.links.sortedBy { order.indexOf(it.kind) }
        val ids = links.filter { it.kind != LinkKind.PULL_REQUEST }.map { it.targetId }.distinct()
        val items = async { runCatchingNotCancelled { api.workItems(ids) }.orEmpty().associateBy { it.id } }
        val prs = links.filter { it.kind == LinkKind.PULL_REQUEST }.map { link ->
            async { link.targetId to runCatchingNotCancelled { api.pullRequestById(link.targetId) } }
        }.awaitAll().toMap()
        val found = items.await()
        links.map { link ->
            if (link.kind == LinkKind.PULL_REQUEST) {
                val pr = prs[link.targetId]
                LinkRow(link.kind, link.targetId, title = pr?.title, prStatus = pr?.status, draft = pr?.draft == true, url = pr?.url ?: link.url)
            } else {
                val wi = found[link.targetId]
                LinkRow(link.kind, link.targetId, wi?.type, wi?.title, wi?.state, url = wi?.url.orEmpty())
            }
        }
    }

    private suspend fun states(api: AdoApi, item: WorkItem): List<StateInfo> {
        val key = "${item.project}/${item.type}".lowercase()
        statesCache[key]?.let { return it }
        val read = runCatchingNotCancelled { api.stateCategories(item.project, item.type) }
            ?.map { (name, category) -> StateInfo(name, category) }
        if (read != null) statesCache[key] = read
        // The saved catalog has only the open states: better than nothing
        return read ?: catalog()?.types?.firstOrNull { it.name.equals(item.type, ignoreCase = true) }
            ?.states?.map { StateInfo(it, "") }.orEmpty().let { open ->
                if (open.any { it.name == item.state }) open else listOf(StateInfo(item.state, "")) + open
            }
    }

    private suspend fun iterations(api: AdoApi, project: String): List<Iteration> {
        val key = project.lowercase()
        iterationsCache[key]?.let { return it }
        val read = runCatchingNotCancelled { api.iterations(project) } ?: return emptyList()
        iterationsCache[key] = read
        return read
    }

    private suspend fun areas(api: AdoApi, project: String): AreaNode? =
        catalog()?.projects?.firstOrNull { it.name.equals(project, ignoreCase = true) }
            ?: runCatchingNotCancelled { api.areas(project) }

    private suspend fun fields(api: AdoApi, item: WorkItem): Set<String> {
        val key = "${item.project}/${item.type}".lowercase()
        fieldsCache[key]?.let { return it }
        val read = runCatchingNotCancelled { api.typeFields(item.project, item.type) } ?: return emptySet()
        fieldsCache[key] = read
        return read
    }

    /**
     * Shows [change] at once and saves it. Saves go one at a time, each against the revision the
     * previous one returned; a failure shows the saved value again with the reason. Once made, a
     * change is saved even if the details close or another work item opens meanwhile.
     */
    fun change(change: FieldChange) {
        val modal = _state.value ?: return
        if (modal.readOnly) return
        val page = modal.page
        val saved = page.saved ?: return
        val shown = page.detail ?: return
        if (shown.with(change) == shown) return
        val id = page.id
        knownRevision(id, saved.rev)
        updatePage(id) { it.copy(pending = it.pending + (change.field to change), fieldError = null) }
        // Not among the reads: closing never cancels it
        scope.launch {
            saves.withLock {
                try {
                    val api = api()
                    val sent = resolved(api, change)
                    val result = api.updateWorkItem(id, revisions.getValue(id), sent)
                    knownRevision(id, result.rev)
                    updatePage(id) {
                        it.copy(
                            saved = result,
                            pending = if (it.pending[change.field] == change) it.pending - change.field else it.pending,
                        )
                    }
                    val mine = me()
                    val assignedToMe = sent is FieldChange.AssignedTo && sent.person != null && mine != null &&
                        (sent.person.id.equals(mine.id, ignoreCase = true) || sent.person.name == mine.name)
                    onSaved(result, assignedToMe)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val problem = when {
                        e is AdoException && e.isConflict -> SaveProblem.CONFLICT
                        e is AdoException && e.isDenied -> SaveProblem.DENIED
                        else -> SaveProblem.OTHER
                    }
                    if (problem == SaveProblem.DENIED) {
                        _readOnly.value = true
                        _state.update { it?.copy(readOnly = true) }
                    }
                    updatePage(id) {
                        it.copy(
                            pending = it.pending - change.field,
                            fieldError = FieldError(change.field, problem, errorText(e)),
                        )
                    }
                }
            }
        }
    }

    /** Me without a unique name (the sign-in gives only the name): looked up first. */
    private suspend fun resolved(api: AdoApi, change: FieldChange): FieldChange {
        val person = (change as? FieldChange.AssignedTo)?.person ?: return change
        if (person.uniqueName.isNotBlank()) return change
        val found = runCatchingNotCancelled { api.searchPeople(person.name) }.orEmpty()
            .firstOrNull { it.id.equals(person.id, ignoreCase = true) } ?: return change
        return FieldChange.AssignedTo(person.copy(uniqueName = found.uniqueName))
    }

    /**
     * People for "Assigned to": the directory's search, or where it is not available (Azure
     * DevOps Server without it) the members of the project's teams whose name matches.
     */
    fun searchPeople(query: String) {
        val modal = _state.value ?: return
        val q = query.trim()
        search?.cancel()
        if (q.isEmpty()) {
            _state.update { it?.copy(people = PeopleSearch()) }
            return
        }
        _state.update { it?.copy(people = it.people.copy(query = q, loading = true)) }
        val project = modal.page.item?.project
        search = scope.launch {
            delay(SEARCH_DELAY_MS)
            val api = api()
            val found = runCatchingNotCancelled { api.searchPeople(q) }?.takeIf { it.isNotEmpty() }
                ?: project?.let { p -> members(api, p).filter { it.name.contains(q, ignoreCase = true) } }.orEmpty()
            _state.update { it?.copy(people = PeopleSearch(q, loading = false, results = found.take(MAX_PEOPLE))) }
        }
    }

    private suspend fun members(api: AdoApi, project: String): List<Person> {
        val key = project.lowercase()
        membersCache[key]?.let { return it }
        val teams = catalog()?.teams?.entries?.firstOrNull { it.key.equals(project, ignoreCase = true) }?.value?.map { it.name }
            ?: runCatchingNotCancelled { api.teams(project) }.orEmpty()
        val read = kotlinx.coroutines.coroutineScope {
            teams.take(MAX_TEAMS).map { team -> async { runCatchingNotCancelled { api.teamMembers(project, team) }.orEmpty() } }
                .awaitAll().flatten().distinctBy { it.id }.sortedBy { it.name.lowercase() }
        }
        membersCache[key] = read
        return read
    }

    /**
     * The latest comments; for a [WorkItemPage.target] not among them, the pages before too
     * (a few at most). Then the reply to the target's author is put in an empty composer.
     */
    private suspend fun comments(api: AdoApi, project: String, id: Int) {
        try {
            var page = api.workItemCommentPage(project, id)
            var items = page.comments
            val target = _state.value?.pages?.firstOrNull { it.id == id }?.target
            var extra = 0
            while (target != null && items.none { it.id == target } && page.continuation != null && extra < MAX_TARGET_PAGES) {
                page = api.workItemCommentPage(project, id, page.continuation)
                items = page.comments + items
                extra++
            }
            val earlier = page.continuation
            updatePage(id) { p ->
                val known = items.map { it.id }.toSet()
                p.copy(
                    comments = p.comments.copy(
                        loaded = true, error = null, earlier = earlier,
                        // Posted meanwhile: after those read
                        items = items + p.comments.items.filter { it.id !in known },
                        total = maxOf(page.total, items.size),
                    ),
                )
            }
            val comment = target?.let { t -> items.firstOrNull { it.id == t } }
            if (comment == null) {
                updatePage(id) { it.copy(target = null) }
            } else {
                val author = Person(comment.author.id, comment.author.name, "")
                val mine = me()?.id?.equals(author.id, ignoreCase = true) == true
                updatePage(id) { p ->
                    if (p.composer.draft.isBlank && !mine && author.name.isNotBlank()) {
                        val draft = MentionTyping.reply(author)
                        drafts[id] = draft
                        p.copy(composer = ComposerState(draft, ++composerRevisions))
                    } else {
                        p
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updatePage(id) { it.copy(comments = it.comments.copy(loaded = true, error = errorText(e)), target = null) }
        }
    }

    /** The comments read again, after an error. */
    fun retryComments() {
        val page = _state.value?.page ?: return
        val project = page.item?.project ?: return
        updatePage(page.id) { it.copy(comments = it.comments.copy(loaded = false, error = null)) }
        jobs += scope.launch { comments(api(), project, page.id) }
    }

    /** "Load earlier comments". */
    fun loadEarlierComments() {
        val page = _state.value?.page ?: return
        val project = page.item?.project ?: return
        val token = page.comments.earlier ?: return
        if (page.comments.loadingEarlier) return
        val id = page.id
        updatePage(id) { it.copy(comments = it.comments.copy(loadingEarlier = true)) }
        jobs += scope.launch {
            try {
                val read = api().workItemCommentPage(project, id, token)
                updatePage(id) { p ->
                    val known = p.comments.items.map { it.id }.toSet()
                    p.copy(
                        comments = p.comments.copy(
                            items = read.comments.filter { it.id !in known } + p.comments.items,
                            earlier = read.continuation, loadingEarlier = false, error = null,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updatePage(id) { it.copy(comments = it.comments.copy(loadingEarlier = false, error = errorText(e))) }
            }
        }
    }

    /** The comment of [WorkItemPage.target] was scrolled to: not again. */
    fun targetShown() {
        val page = _state.value?.page ?: return
        updatePage(page.id) { it.copy(target = null) }
    }

    /** Typed in the composer: kept for the session, also once the work item is closed. */
    fun editDraft(draft: Draft) {
        val page = _state.value?.page ?: return
        if (draft.isBlank && draft.text.isEmpty()) drafts.remove(page.id) else drafts[page.id] = draft
        updatePage(page.id) {
            it.copy(composer = it.composer.copy(draft = draft), comments = it.comments.copy(sendError = null))
        }
    }

    /**
     * Posts the composer's text, shown at once as being sent. Refused, it goes back to the
     * composer (unless something else was typed meanwhile) with the reason.
     */
    fun sendComment() {
        val modal = _state.value ?: return
        val page = modal.page
        val item = page.item ?: return
        val draft = page.composer.draft.pruned()
        if (draft.isBlank) return
        val id = page.id
        val sending = SendingComment(++localIds, draft, CommentMarkup.build(draft), clock())
        drafts.remove(id)
        updatePage(id) {
            it.copy(
                composer = ComposerState(Draft(), ++composerRevisions),
                comments = it.comments.copy(sending = it.comments.sending + sending, sendError = null),
            )
        }
        draft.mentions.forEach { p ->
            recentMentions.removeAll { it.id == p.id }
            recentMentions.addFirst(p)
        }
        // Not among the reads: closing never cancels it
        scope.launch {
            try {
                val posted = api().addWorkItemComment(item.project, id, sending.html)
                updatePage(id) { p ->
                    p.copy(
                        comments = p.comments.copy(
                            items = p.comments.items.filterNot { it.id == posted.id } + posted,
                            sending = p.comments.sending.filterNot { it.localId == sending.localId },
                            total = p.comments.total + 1,
                        ),
                    )
                }
                onCommented(item, posted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val problem = if (e is AdoException && e.isDenied) SaveProblem.DENIED else SaveProblem.OTHER
                updatePage(id) { p ->
                    val restore = p.composer.draft.isBlank
                    if (restore) drafts[id] = draft
                    p.copy(
                        composer = if (restore) ComposerState(draft, ++composerRevisions) else p.composer,
                        comments = p.comments.copy(
                            sending = p.comments.sending.filterNot { it.localId == sending.localId },
                            sendError = CommentError(problem, errorText(e)),
                        ),
                    )
                }
            }
        }
    }

    /**
     * People for the mention being typed: those mentioned lately and the authors of the
     * comments first, at once, then the directory's search (the project's teams where it is
     * not available).
     */
    fun searchMentions(query: String) {
        val modal = _state.value ?: return
        val q = query.trim()
        mentionSearch?.cancel()
        if (q.isEmpty()) {
            _state.update { it?.copy(mentions = PeopleSearch()) }
            return
        }
        val myId = me()?.id
        val authors = modal.page.comments.items.asReversed().map { Person(it.author.id, it.author.name, "") }
        val recent = (recentMentions + authors)
            .filter { it.name.isNotBlank() && !it.id.equals(myId, ignoreCase = true) && matches(it, q) }
            .distinctBy { it.id.lowercase() }
        _state.update { it?.copy(mentions = PeopleSearch(q, loading = true, results = recent.take(MAX_SUGGESTIONS))) }
        val project = modal.page.item?.project
        mentionSearch = scope.launch {
            delay(SEARCH_DELAY_MS)
            val api = api()
            val found = runCatchingNotCancelled { api.searchPeople(q) }?.takeIf { it.isNotEmpty() }
                ?: project?.let { p -> members(api, p).filter { matches(it, q) } }.orEmpty()
            val all = (recent + found.filter { it.mentionable }).distinctBy { it.id.lowercase() }
            _state.update { it?.copy(mentions = PeopleSearch(q, loading = false, results = all.take(MAX_SUGGESTIONS))) }
        }
    }

    /** "an" finds "Anna Verdi" and "Marco Ancona". */
    private fun matches(p: Person, q: String) = p.name.contains(q, ignoreCase = true)

    private suspend fun <T> runCatchingNotCancelled(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val SEARCH_DELAY_MS = 250L
        const val MAX_PEOPLE = 20
        const val MAX_TEAMS = 5
        const val MAX_SUGGESTIONS = 8
        /** Pages read before the latest to find a mentioned comment. */
        const val MAX_TARGET_PAGES = 4
    }
}
