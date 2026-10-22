package io.github.iacoposb.adodash.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iacoposb.adodash.AdoApp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.AdoClient
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.Build
import io.github.iacoposb.adodash.api.BuildState
import io.github.iacoposb.adodash.api.Person
import io.github.iacoposb.adodash.api.PullRequest
import io.github.iacoposb.adodash.api.WorkItem
import io.github.iacoposb.adodash.data.PrCommentRef
import io.github.iacoposb.adodash.sync.BuildSteps
import io.github.iacoposb.adodash.data.AuthMethod
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.data.DashboardView
import io.github.iacoposb.adodash.data.ViewSet
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.Filters
import io.github.iacoposb.adodash.data.NotificationPrefs
import io.github.iacoposb.adodash.data.SoundTarget
import io.github.iacoposb.adodash.data.Settings
import io.github.iacoposb.adodash.errorText
import io.github.iacoposb.adodash.update.Release
import io.github.iacoposb.adodash.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class AppViewModel(private val app: AdoApp) : ViewModel() {
    /** Null until read from disk. */
    val settings: StateFlow<Settings?> = app.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val sync = app.sync.state
    /** For the offline banner. */
    val online: StateFlow<Boolean> = app.network.online

    init {
        // What an earlier run saved, on screen at once while the first sync runs
        viewModelScope.launch { app.sync.restoreSaved() }
        // Back online: the data again, without waiting for the next poll
        viewModelScope.launch {
            app.network.online.drop(1).filter { it }.collect {
                if (app.settings.current().configured) app.sync.sync()
            }
        }
    }

    /**
     * Only what the filters include, also for what was stored before they changed, and of that
     * what the current view shows. Notifications are not filtered by the view.
     */
    val events: StateFlow<List<Event>> = combine(app.database.events().recent(), app.settings.settings) { list, s ->
        val view = s.views.current
        list.filter { s.filters.acceptsEvent(it) && view.acceptsEvent(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** "What do you want to see?" answered (or skipped) since the app started. */
    private val _launchChoiceMade = MutableStateFlow(false)
    val launchChoiceMade = _launchChoiceMade.asStateFlow()

    /** Projects, areas and types for the global filters: saved, and read again when they open. */
    val catalog = app.catalog.state
    private var filterSync: Job? = null

    val update = app.updater.state
    /** Version for the dashboard banner: known, newer and not dismissed. */
    val updateBanner: StateFlow<String?> = combine(app.updater.state, app.updater.dismissed) { s, dismissed ->
        (s as? UpdateState.WithRelease)?.release?.version?.takeIf { it != dismissed }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val version get() = app.updater.currentVersion

    fun checkUpdates() = app.updater.check()
    fun downloadUpdate(release: Release) = app.updater.download(release)
    fun installUpdate(apk: File) = app.updater.install(apk)
    fun dismissUpdate(version: String) = app.updater.dismiss(version)

    /** The build details open over the dashboard. */
    private val _build = MutableStateFlow<BuildDetail?>(null)
    val build = _build.asStateFlow()
    private var buildJob: Job? = null
    /** The credentials were refused a rerun: not offered again until the app restarts. */
    private var rerunDenied = false

    /**
     * Opens the details of a build: [known] (from the pipelines) shows at once, otherwise
     * (from an event) it is read first; then its timeline and failed tests.
     */
    fun openBuild(project: String, id: Int, known: Build? = null) {
        buildJob?.cancel()
        _build.value = BuildDetail(project, id, known, canRerun = !rerunDenied)
        buildJob = viewModelScope.launch {
            // Also from a notification, before the settings are on screen
            val api = app.api(app.settings.current())
            val build = known ?: try {
                api.build(project, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateBuild(id) { it.copy(error = app.errorText(e)) }
                return@launch
            }
            updateBuild(id) { it.copy(build = build) }
            launch {
                val steps = runCatching { BuildSteps.of(api.buildTimeline(project, id)) }.getOrDefault(emptyList())
                updateBuild(id) { it.copy(steps = steps) }
            }
            if (build.state != BuildState.SUCCEEDED) launch {
                val tests = try {
                    val runs = api.testRuns(project, id).filter { it.failed > 0 }
                    val total = runs.sumOf { it.failed }
                    val first = runs.take(MAX_TEST_RUNS).flatMap { api.failedTests(project, it.id, MAX_FAILED_TESTS) }
                    TestsState.Loaded(first.take(MAX_FAILED_TESTS), maxOf(total, first.size))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    TestsState.Unavailable
                }
                updateBuild(id) { it.copy(tests = tests) }
            }
        }
    }

    /** A board's pipelines open over the dashboard; their runs open in the build details. */
    private val pipelinesController = PipelinesController(
        viewModelScope, api = { app.api(app.settings.current()) }, errorText = app::errorText,
    )
    val pipelines = pipelinesController.state

    fun openPipelines(project: String, team: String?) = pipelinesController.open(project, team)

    fun pipelinesActions(openUrl: (String) -> Unit) = PipelinesActions(
        onLoadRuns = pipelinesController::loadRuns,
        onOpenRun = { openBuild(it.project, it.id, it) },
        onBackToList = ::closeBuild,
        onRetry = pipelinesController::retry,
        onOpenUrl = openUrl,
        onRerun = ::rerunBuild,
        onClose = {
            closeBuild()
            pipelinesController.close()
        },
    )

    fun markRead(key: String) {
        viewModelScope.launch { app.sync.markRead(key) }
    }

    /** The work item details open over the dashboard, and their changes. */
    private val workItems = WorkItemController(
        viewModelScope,
        api = { app.api(app.settings.current()) },
        catalog = { app.catalog.state.value.catalog },
        me = { settings.value?.takeIf { it.meId.isNotEmpty() }?.let { Person(it.meId, it.meName, "") } },
        onSaved = { item, mine -> app.sync.applyOwnChange(item, mine) },
        errorText = app::errorText,
        onCommented = { item, comment -> app.sync.applyOwnComment(item, comment) },
    )
    val workItem = workItems.state
    /** A change was refused for lack of permission: for the hint in the settings. */
    val workItemsReadOnly = workItems.readOnly

    fun openWorkItem(item: WorkItem) {
        // The areas come from the saved catalog
        loadBoards()
        workItems.open(item.id, item)
    }

    /**
     * From an event or its notification: an assignment opens the work item's details; a mention
     * ([commentId]) scrolls them to the comment, with the reply to its author ready in the composer.
     */
    fun openWorkItem(workItemId: Int, commentId: Int? = null) {
        loadBoards()
        val known = app.sync.state.value.snapshot?.workItems?.firstOrNull { it.id == workItemId }
        workItems.open(workItemId, known, commentId)
    }

    fun workItemActions(openUrl: (String) -> Unit) = WorkItemActions(
        onOpenUrl = openUrl,
        onOpenWorkItem = workItems::openLinked,
        onChange = workItems::change,
        onSearchPeople = workItems::searchPeople,
        onReload = workItems::reload,
        onBack = workItems::back,
        onClose = workItems::close,
        onDraftChange = workItems::editDraft,
        onSend = workItems::sendComment,
        onSearchMentions = workItems::searchMentions,
        onLoadEarlierComments = workItems::loadEarlierComments,
        onRetryComments = workItems::retryComments,
        onTargetShown = workItems::targetShown,
    )

    /** The sprint backlog's changes: its order, and a field of all the selected work items. */
    private val backlogEdits = BacklogEditController(
        viewModelScope,
        api = { app.api(app.settings.current()) },
        onSprint = app.sync::applyOwnSprint,
        errorText = app::errorText,
        catalog = { app.catalog.state.value.catalog },
        me = { settings.value?.takeIf { it.meId.isNotEmpty() }?.let { Person(it.meId, it.meName, "") } },
        onSaved = { item, mine -> app.sync.applyOwnChange(item, mine) },
        onBatchDone = ::refresh,
    )
    val backlogEdit = backlogEdits.state

    fun backlogEditActions() = BacklogEditActions(
        onReorder = backlogEdits::reorder,
        onDismissReorderError = backlogEdits::dismissReorderError,
        onLoadOptions = {
            // The areas come from the saved catalog
            loadBoards()
            backlogEdits.loadOptions(it)
        },
        onSearchPeople = { sprint, query -> backlogEdits.searchPeople(sprint.board, query) },
        onBatch = backlogEdits::runBatch,
        onRetryBatch = backlogEdits::retryBatch,
        onDismissBatch = backlogEdits::dismissBatch,
    )

    /** The pull request page, over the dashboard. */
    private val pullRequests = PullRequestController(
        viewModelScope,
        api = { app.api(app.settings.current()) },
        me = { settings.value?.takeIf { it.meId.isNotEmpty() }?.let { Person(it.meId, it.meName, "") } },
        errorText = app::errorText,
        onVoted = { pr, vote -> app.sync.applyOwnVote(pr.id, vote) },
        onCommented = { pr, thread -> app.sync.applyOwnPrComments(pr, thread) },
    )
    val pullRequest = pullRequests.state

    /** A pull request row tapped: its page, with the row's data shown at once. */
    fun openPullRequest(pr: PullRequest) = pullRequests.open(pr.id, pr)

    /**
     * From an event or a notification: the pull request's page, at the thread of the comment
     * when there is one, with the reply to its author ready.
     */
    fun openPullRequest(ref: PrCommentRef) {
        val known = app.sync.state.value.snapshot?.pullRequest(ref.prId)
        val target = ref.threadId?.let { PrCommentTarget(it, ref.commentId) }
        pullRequests.open(ref.prId, known, target)
    }

    fun prActions(openUrl: (String) -> Unit) = PrActions(
        onBack = pullRequests::close,
        onOpenUrl = openUrl,
        onVote = pullRequests::vote,
        onDismissVoteError = pullRequests::dismissVoteError,
        onSelectTab = pullRequests::selectTab,
        onSelectFilter = pullRequests::selectFilter,
        onDraftChange = pullRequests::editDraft,
        onSend = pullRequests::send,
        onSetStatus = pullRequests::setStatus,
        onSearchMentions = pullRequests::searchMentions,
        onRetry = pullRequests::retry,
        onOpenBuild = { project, id -> openBuild(project, id) },
        onOpenWorkItem = ::openWorkItem,
        onTargetShown = pullRequests::targetShown,
        onSelectFile = pullRequests::selectFile,
        onToggleViewed = pullRequests::toggleViewed,
        onToggleFolder = pullRequests::toggleFolder,
        onSetSideBySide = pullRequests::setSideBySide,
        onExpandGap = pullRequests::expandGap,
        onRetryFile = pullRequests::retryFile,
        onStartFileThread = pullRequests::startFileThread,
        onCancelFileThread = pullRequests::cancelFileThread,
    )

    fun closeBuild() {
        buildJob?.cancel()
        _build.value = null
    }

    /** "Rerun failed jobs": one call; refused, it is not offered again. */
    fun rerunBuild() {
        val detail = _build.value ?: return
        val s = settings.value ?: return
        updateBuild(detail.buildId) { it.copy(rerun = RerunState.BUSY) }
        viewModelScope.launch {
            try {
                app.api(s).retryBuild(detail.project, detail.buildId)
                updateBuild(detail.buildId) { it.copy(rerun = RerunState.STARTED) }
                app.sync.sync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdoException) {
                if (e.code == 401 || e.code == 403) {
                    rerunDenied = true
                    updateBuild(detail.buildId) { it.copy(rerun = RerunState.DENIED, canRerun = false) }
                } else {
                    updateBuild(detail.buildId) { it.copy(rerun = RerunState.FAILED, rerunError = app.errorText(e)) }
                }
            } catch (e: Exception) {
                updateBuild(detail.buildId) { it.copy(rerun = RerunState.FAILED, rerunError = app.errorText(e)) }
            }
        }
    }

    private fun updateBuild(id: Int, change: (BuildDetail) -> BuildDetail) {
        _build.update { current -> current?.takeIf { it.buildId == id }?.let(change) ?: current }
    }

    data class SaveState(val busy: Boolean = false, val message: String? = null)
    private val _save = MutableStateFlow(SaveState())
    val save = _save.asStateFlow()

    fun refresh() {
        viewModelScope.launch { app.sync.sync() }
    }

    /**
     * Syncs every [Settings.pollSeconds] for as long as the caller is active; offline only what
     * was saved is shown, and the sync runs as soon as the network is back (see init).
     */
    suspend fun poll() {
        while (true) {
            if (app.network.online.value) app.sync.sync() else app.sync.restoreSaved()
            delay((settings.value?.pollSeconds ?: 60) * 1000L)
        }
    }

    fun loadCatalog() {
        val s = settings.value ?: return
        if (s.configured) app.catalog.open(s.organization) { app.catalogApi(s) }
    }

    /**
     * Applied at once to the filters as stored (not as on screen, which a quick second tap may
     * find not updated yet): saved now, synced once the taps stop.
     */
    fun updateFilters(change: (Filters) -> Filters) {
        viewModelScope.launch { app.settings.editFilters(change = change) }
        filterSync?.cancel()
        filterSync = viewModelScope.launch {
            delay(FILTER_SYNC_DELAY_MS)
            app.sync.sync()
        }
    }

    fun markRead(event: Event) {
        viewModelScope.launch { app.sync.markRead(event.key) }
    }

    /** Only what the view shows: the rest stays unread for the views that show it. */
    fun markAllRead() {
        val keys = events.value.filterNot { it.read }.map { it.key }
        viewModelScope.launch { app.sync.markRead(keys) }
    }

    /** The views' pickers need the boards: the saved catalog, read again only when missing. */
    fun loadBoards() {
        val s = settings.value ?: return
        if (s.configured) app.catalog.openForViews(s.organization) { app.catalogApi(s) }
    }

    fun openView(id: String) = changeViews { it.open(id) }

    fun openBoard(board: BoardRef) = changeViews { it.openBoard(board) }

    /** Saves [view] and opens it. */
    fun saveView(view: DashboardView) = changeViews { it.put(view) }

    fun deleteView(id: String) = changeViews { it.delete(id) }

    /** From the launch screen: [id] opened, and from now on without asking if [always]. */
    fun chooseAtLaunch(id: String?, board: BoardRef?, always: Boolean) {
        _launchChoiceMade.value = true
        changeViews { set ->
            val opened = when {
                board != null -> set.openBoard(board)
                id != null -> set.open(id)
                else -> set
            }
            opened.copy(alwaysOpen = always, chosenAtLaunch = true)
        }
    }

    /** From the settings: ask again at the next launch, or not. */
    fun setAskAtLaunch(ask: Boolean) {
        viewModelScope.launch { app.settings.editViews { it.copy(alwaysOpen = !ask) } }
    }

    /**
     * What to notify and quiet hours, from the settings; applied at once to what is stored (see
     * [updateFilters]). The sounds and their channels stay as stored: only [setSound] changes them.
     */
    fun setNotifications(change: (NotificationPrefs) -> NotificationPrefs) {
        viewModelScope.launch {
            app.settings.editNotifications { stored ->
                change(stored).copy(
                    mentionsSound = stored.mentionsSound, othersSound = stored.othersSound,
                    mentionsChannel = stored.mentionsChannel, othersChannel = stored.othersChannel,
                )
            }
        }
    }

    /** A sound chosen in the system's picker: a new channel for it (see [NotificationPrefs.withSound]). */
    fun setSound(target: SoundTarget, sound: String?) {
        viewModelScope.launch { app.settings.editNotifications { it.withSound(target, sound) } }
    }

    /** A change of what the sync reads syncs at once; one of the activity only shows. */
    private fun changeViews(change: (ViewSet) -> ViewSet) {
        val before = settings.value?.views?.current?.queryKey
        viewModelScope.launch {
            app.settings.editViews(change)
            if (app.settings.current().views.current.queryKey != before) app.sync.sync()
        }
    }

    /** Interactive Microsoft sign-in, then checks the token against the organization. */
    fun signInWithMicrosoft(activity: Activity, form: SettingsForm, onDone: () -> Unit, onError: (String) -> Unit = {}) = connect(form, onDone, onError) {
        val before = app.settings.current()
        app.settings.saveEntraConfig(form.entraClientId, form.entraTenantId)
        try {
            app.entra.signIn(activity)
        } catch (e: Exception) {
            // New IDs that do not sign in: the registration that worked stays
            app.settings.saveEntraConfig(before.entraClientId, before.entraTenantId)
            throw e
        }
        AuthMethod.ENTRA to ""
    }

    /**
     * "Sign in" from the expired session's banner or notification: Microsoft's sign-in again with
     * the saved organization and registration; [onError] with what went wrong.
     */
    fun signInAgain(activity: Activity, onError: (String) -> Unit) {
        val s = settings.value ?: return
        val form = SettingsForm(s.organization, s.pollSeconds, s.keepScreenOn, s.entraClientId, s.entraTenantId)
        signInWithMicrosoft(activity, form, onDone = {}, onError = onError)
    }

    fun signInWithPat(form: SettingsForm, pat: String, onDone: () -> Unit) = connect(form, onDone) {
        AuthMethod.PAT to pat.ifBlank { settings.value?.pat.orEmpty() }
    }

    fun savePreferences(form: SettingsForm, onDone: () -> Unit) {
        viewModelScope.launch {
            app.settings.savePreferences(form.pollSeconds, form.keepScreenOn)
            onDone()
        }
    }

    fun signOut() {
        viewModelScope.launch { signOutNow() }
    }

    /**
     * Another organization, confirmed in the account settings: signed out, its local data
     * cleared (activity, saved snapshots, filters and views), the new one kept for the sign-in.
     */
    fun changeOrganization(organization: String) {
        viewModelScope.launch {
            signOutNow()
            app.settings.changeOrganization(organization)
        }
    }

    private suspend fun signOutNow() {
        runCatching { app.entra.signOut() }
        app.catalog.forget()
        pipelinesController.forget()
        app.sync.switchAccount(clear = true) { app.settings.signOut() }
    }

    private fun connect(
        form: SettingsForm,
        onDone: () -> Unit,
        onError: (String) -> Unit = {},
        credentials: suspend () -> Pair<AuthMethod, String>,
    ) {
        val current = settings.value ?: return
        _save.value = SaveState(busy = true)
        viewModelScope.launch {
            val (method, pat) = try {
                credentials()
            } catch (e: CancellationException) {
                _save.value = SaveState()
                throw e
            } catch (e: Exception) {
                _save.value = SaveState(message = app.getString(R.string.error_sign_in_failed, app.errorText(e)))
                onError(_save.value.message.orEmpty())
                return@launch
            }
            val candidate = current.copy(organization = form.organization, authMethod = method, pat = pat)
            val me = try {
                AdoClient(form.organization, app.authorization(candidate)).me()
            } catch (e: Exception) {
                _save.value = SaveState(message = app.getString(R.string.error_connection_failed, form.organization, app.errorText(e)))
                onError(_save.value.message.orEmpty())
                return@launch
            }
            // The organization's name as Azure DevOps does: case aside
            val sameOrganization = form.organization.equals(current.organization, ignoreCase = true)
            if (!sameOrganization) {
                app.catalog.forget()
                pipelinesController.forget()
            }
            // Cleared and saved together: a sync waiting meanwhile never runs with the old account
            app.sync.switchAccount(clear = !sameOrganization || !me.id.equals(current.meId, ignoreCase = true)) {
                app.settings.saveAccount(form.organization, method, pat, me.id, me.name)
                app.settings.savePreferences(form.pollSeconds, form.keepScreenOn)
            }
            _save.value = SaveState()
            onDone()
            app.sync.sync()
        }
    }

    private companion object {
        const val FILTER_SYNC_DELAY_MS = 1_500L
        const val MAX_FAILED_TESTS = 10
        const val MAX_TEST_RUNS = 3
    }
}
