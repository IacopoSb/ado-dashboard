package io.github.iacoposb.adodash

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
import io.github.iacoposb.adodash.sync.Notifier
import io.github.iacoposb.adodash.sync.ResourceSyncTexts
import io.github.iacoposb.adodash.sync.SyncRepository
import io.github.iacoposb.adodash.sync.SyncWorker
import io.github.iacoposb.adodash.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

class AdoApp : Application() {
    /** Work that outlives the screens, such as an update download. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "ado.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build() }
    val settings by lazy { SettingsStore(this) }
    private val notifier by lazy { Notifier(this) }
    val entra by lazy { EntraAuth(this, settings) }
    val catalog by lazy {
        CatalogRepository(CatalogCache(File(filesDir, "filter-catalog.json")), scope, ::errorText)
    }
    val sync by lazy {
        SyncRepository(
            settings, database.events(), { events, s -> notifier.notify(events, s.notifications) }, ResourceSyncTexts(this),
            apiFor = { AdoClient(it.organization, authorization(it)) },
            onSynced = { s, snapshot ->
                // Only a convenience for the filters: never a reason for the sync to fail
                try {
                    catalog.synced(s.organization, snapshot.workItems.map { it.type }.toSet()) { catalogApi(s) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            },
        )
    }

    /** For calls outside a sync, such as the build details. */
    fun api(s: Settings): AdoApi = AdoClient(s.organization, authorization(s))

    /** For the filters' catalog: many calls in a burst, so one token for all of them. */
    fun catalogApi(s: Settings): AdoApi = AdoClient(s.organization, AdoClient.reused(authorization(s)))

    val updater by lazy { Updater(this, scope, { notifier.notifyUpdate(it, settings.current().notifications) }) }

    fun authorization(s: Settings): suspend () -> String = when (s.authMethod) {
        AuthMethod.ENTRA -> AdoClient.bearer(entra::accessToken)
        AuthMethod.PAT -> AdoClient.pat(s.pat)
    }

    override fun onCreate() {
        super.onCreate()
        // At start (also renaming the channels after a language change) and at every change
        scope.launch {
            settings.settings.map { it.notifications }.distinctUntilChanged().collect { notifier.createChannels(it) }
        }
        SyncWorker.schedule(this)
        scope.launch { updater.checkIfDue() }
    }
}
