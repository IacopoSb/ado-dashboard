package io.github.iacoposb.adodash

import android.app.Application
import androidx.room.Room
import io.github.iacoposb.adodash.api.AdoClient
import io.github.iacoposb.adodash.auth.EntraAuth
import io.github.iacoposb.adodash.data.AppDatabase
import io.github.iacoposb.adodash.data.AuthMethod
import io.github.iacoposb.adodash.data.Settings
import io.github.iacoposb.adodash.data.SettingsStore
import io.github.iacoposb.adodash.sync.Notifier
import io.github.iacoposb.adodash.sync.ResourceSyncTexts
import io.github.iacoposb.adodash.sync.SyncRepository
import io.github.iacoposb.adodash.sync.SyncWorker
import io.github.iacoposb.adodash.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AdoApp : Application() {
    /** Work that outlives the screens, such as an update download. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "ado.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build() }
    val settings by lazy { SettingsStore(this) }
    private val notifier by lazy { Notifier(this) }
    val entra by lazy { EntraAuth(this, settings) }
    val sync by lazy {
        SyncRepository(settings, database.events(), notifier::notify, ResourceSyncTexts(this)) { AdoClient(it.organization, authorization(it)) }
    }

    val updater by lazy { Updater(this, scope, notifier::notifyUpdate) }

    fun authorization(s: Settings): suspend () -> String = when (s.authMethod) {
        AuthMethod.ENTRA -> AdoClient.bearer(entra::accessToken)
        AuthMethod.PAT -> AdoClient.pat(s.pat)
    }

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        SyncWorker.schedule(this)
        scope.launch { updater.checkIfDue() }
    }
}
