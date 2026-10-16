package io.github.iacoposb.adodash.ui

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iacoposb.adodash.AdoApp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.AdoClient
import io.github.iacoposb.adodash.data.AuthMethod
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.Filters
import io.github.iacoposb.adodash.data.Settings
import io.github.iacoposb.adodash.errorText
import io.github.iacoposb.adodash.sync.CatalogState
import io.github.iacoposb.adodash.sync.FilterCatalog
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class AppViewModel(private val app: AdoApp) : ViewModel() {
    /** Null until read from disk. */
    val settings: StateFlow<Settings?> = app.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val sync = app.sync.state
    /** Only what the filters include, also for what was stored before they changed. */
    val events: StateFlow<List<Event>> = combine(app.database.events().recent(), app.settings.settings) { list, s ->
        list.filter(s.filters::acceptsEvent)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _catalog = MutableStateFlow(CatalogState())
    /** Projects, areas and types for the global filters, read when their section opens. */
    val catalog = _catalog.asStateFlow()
    private var catalogJob: Job? = null
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
        if (!s.configured || catalogJob?.isActive == true) return
        _catalog.update { it.copy(loading = true, error = null) }
        catalogJob = viewModelScope.launch {
            try {
                _catalog.value = CatalogState(FilterCatalog.load(AdoClient(s.organization, app.authorization(s))))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _catalog.update { it.copy(loading = false, error = app.errorText(e)) }
            }
        }
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

    fun markAllRead() {
        viewModelScope.launch { app.sync.markAllRead() }
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
            catalogJob?.cancel()
            _catalog.value = CatalogState()
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
