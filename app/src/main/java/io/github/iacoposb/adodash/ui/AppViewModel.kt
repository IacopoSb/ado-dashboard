package io.github.iacoposb.adodash.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iacoposb.adodash.AdoApp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.AdoClient
import io.github.iacoposb.adodash.data.AuthMethod
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.data.DashboardView
import io.github.iacoposb.adodash.data.ViewSet
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.Filters
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class AppViewModel(private val app: AdoApp) : ViewModel() {
    /** Null until read from disk. */
    val settings: StateFlow<Settings?> = app.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val sync = app.sync.state
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

    data class SaveState(val busy: Boolean = false, val message: String? = null)
    private val _save = MutableStateFlow(SaveState())
    val save = _save.asStateFlow()

    fun refresh() {
        viewModelScope.launch { app.sync.sync() }
    }

    /** Syncs every [Settings.pollSeconds] for as long as the caller is active. */
    suspend fun poll() {
        while (true) {
            app.sync.sync()
            delay((settings.value?.pollSeconds ?: 60) * 1000L)
        }
    }

    fun loadCatalog() {
        val s = settings.value ?: return
        if (s.configured) app.catalog.open(s.organization) { app.catalogApi(s) }
    }

    /** Applied at once: saved now, synced once the taps stop. */
    fun updateFilters(filters: Filters) {
        viewModelScope.launch { app.settings.saveFilters(filters) }
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

    /** A change of what the sync reads syncs at once; one of the activity only shows. */
    private fun changeViews(change: (ViewSet) -> ViewSet) {
        val before = settings.value?.views?.current?.queryKey
        viewModelScope.launch {
            app.settings.editViews(change)
            if (app.settings.current().views.current.queryKey != before) app.sync.sync()
        }
    }

    /** Interactive Microsoft sign-in, then checks the token against the organization. */
    fun signInWithMicrosoft(activity: Activity, form: SettingsForm, onDone: () -> Unit) = connect(form, onDone) {
        app.settings.saveEntraConfig(form.entraClientId, form.entraTenantId)
        app.entra.signIn(activity)
        AuthMethod.ENTRA to ""
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
        viewModelScope.launch {
            runCatching { app.entra.signOut() }
            app.catalog.forget()
            app.sync.reset()
            app.settings.signOut()
        }
    }

    private fun connect(form: SettingsForm, onDone: () -> Unit, credentials: suspend () -> Pair<AuthMethod, String>) {
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
                return@launch
            }
            val candidate = current.copy(organization = form.organization, authMethod = method, pat = pat)
            val me = try {
                AdoClient(form.organization, app.authorization(candidate)).me()
            } catch (e: Exception) {
                _save.value = SaveState(message = app.getString(R.string.error_connection_failed, form.organization, app.errorText(e)))
                return@launch
            }
            if (form.organization != current.organization || me.id != current.meId) app.sync.reset()
            if (!form.organization.equals(current.organization, ignoreCase = true)) app.catalog.forget()
            app.settings.saveAccount(form.organization, method, pat, me.id, me.name)
            app.settings.savePreferences(form.pollSeconds, form.keepScreenOn)
            _save.value = SaveState()
            onDone()
            app.sync.sync()
        }
    }

    private companion object {
        const val FILTER_SYNC_DELAY_MS = 1_500L
    }
}
