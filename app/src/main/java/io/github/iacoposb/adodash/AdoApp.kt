package io.github.iacoposb.adodash

import androidx.room.withTransaction
import android.app.Application
import androidx.room.Room
import io.github.iacoposb.adodash.api.AdoApi
import io.github.iacoposb.adodash.api.AdoClient
import io.github.iacoposb.adodash.auth.EntraAuth
import io.github.iacoposb.adodash.data.AppDatabase
import io.github.iacoposb.adodash.data.AuthMethod
import io.github.iacoposb.adodash.data.CatalogCache
import io.github.iacoposb.adodash.data.Settings
import io.github.iacoposb.adodash.data.SettingsStore
import io.github.iacoposb.adodash.sync.CatalogRepository
import io.github.iacoposb.adodash.sync.NetworkMonitor
import io.github.iacoposb.adodash.sync.Notifier
import io.github.iacoposb.adodash.sync.SnapshotStore
import io.github.iacoposb.adodash.sync.ResourceSyncTexts
import io.github.iacoposb.adodash.sync.SyncRepository
import io.github.iacoposb.adodash.sync.SyncWorker
import io.github.iacoposb.adodash.update.Updater
import io.github.iacoposb.adodash.widget.WidgetStore
import io.github.iacoposb.adodash.widget.Widgets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

class AdoApp : Application() {
    /** Work that outlives the screens, such as an update download. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "ado.db")
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build() }
    val settings by lazy { SettingsStore(this) }
    private val notifier by lazy { Notifier(this) }
    /** Online or not, for the dashboard's offline banner and its sync when the network is back. */
    val network by lazy { NetworkMonitor(this) }
    val entra by lazy { EntraAuth(this, settings) }
    private val catalogCache by lazy { CatalogCache(File(filesDir, "filter-catalog.json")) }
    val catalog by lazy { CatalogRepository(catalogCache, scope, ::errorText) }
    val sync by lazy {
        SyncRepository(
            settings, database.events(), { events, s -> notifier.notify(events, s.notifications) }, ResourceSyncTexts(this),
            apiFor = { AdoClient(it.organization, authorization(it)) },
            transaction = { writes -> database.withTransaction { writes() } },
            onSynced = { s, snapshot ->
                // Only conveniences for the filters and the widgets: never a reason for the sync to fail
                try {
                    catalog.synced(s.organization, snapshot.workItems.map { it.type }.toSet()) { catalogApi(s) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                try {
                    widgets.synced(s, snapshot, database.events().recentOnce())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                // Signed in again: the "sign in" notification goes, and comes back at the next refusal
                if (s.signInNotified) {
                    notifier.cancelSignIn()
                    settings.setSignInNotified(false)
                }
            },
            snapshots = SnapshotStore(File(filesDir, "snapshots")),
            onSignInRequired = { s ->
                // Once, not at every sync of the worker
                if (!s.signInNotified) {
                    notifier.notifySignIn(s.notifications)
                    settings.setSignInNotified(true)
                }
            },
            catalog = { s -> catalogCache.load(s.organization)?.catalog },
            onRead = { notifier.cancel(it) },
        )
    }

    val widgets by lazy { Widgets(this, WidgetStore(File(filesDir, "widget-summary.json")), settings) }

    /** For calls outside a sync, such as the build details. */
    fun api(s: Settings): AdoApi = AdoClient(s.organization, authorization(s))

    /** For the filters' catalog: many calls in a burst, so one token for all of them. */
    fun catalogApi(s: Settings): AdoApi = AdoClient(s.organization, AdoClient.reused(authorization(s)))

    val updater by lazy { Updater(this, scope, { notifier.notifyUpdate(it, settings.current().notifications) }) }

    fun authorization(s: Settings): suspend () -> String = when (s.authMethod) {
        AuthMethod.ENTRA -> AdoClient.bearer(entra::accessToken)
        AuthMethod.PAT -> AdoClient.pat(s.pat)
    }

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        // At start (also renaming the channels after a language change) and at every change
        scope.launch {
            settings.settings.map { it.notifications }.distinctUntilChanged().collect { notifier.createChannels(it) }
        }
        // Mentions read or stored, filters changed, signed in or out: the widgets follow
        scope.launch {
            val shown = settings.settings.distinctUntilChanged { a, b -> a.configured == b.configured && a.filters == b.filters }
            combine(shown, database.events().recent()) { s, events -> s to events }
                .debounce(500)
                .collectLatest { (s, events) -> widgets.eventsChanged(s, events) }
        }
        SyncWorker.schedule(this)
        scope.launch { updater.checkIfDue() }
    }
}
