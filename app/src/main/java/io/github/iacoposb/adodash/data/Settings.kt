package io.github.iacoposb.adodash.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class AuthMethod { ENTRA, PAT }

data class Settings(
    val organization: String = "",
    val authMethod: AuthMethod = AuthMethod.ENTRA,
    /** Only with [AuthMethod.PAT]; Entra tokens live in MSAL's cache. */
    val pat: String = "",
    /** Polling interval while the dashboard is on screen. */
    val pollSeconds: Int = 60,
    val keepScreenOn: Boolean = true,
    val meId: String = "",
    val meName: String = "",
    /** First successful sync: what happened before it is never notified. */
    val baselineAt: Long = 0,
    val lastSyncAt: Long = 0,
    /** Entra app registration, set from the app only in the configurable (public) build. */
    val entraClientId: String = "",
    val entraTenantId: String = "",
    val filters: Filters = Filters(),
    /** Last change of [filters]: the next sync takes what they newly include as history. */
    val filtersChangedAt: Long = 0,
    /** The organization's views and the one open; another organization starts from the defaults. */
    val views: ViewSet = ViewSet(),
    /** What to notify, quiet hours and sounds: the device's, kept across accounts. */
    val notifications: NotificationPrefs = NotificationPrefs(),
    /** "Sign in again" was notified for the current refusal: once, until a sync works again. */
    val signInNotified: Boolean = false,
) {
    val configured get() = organization.isNotBlank() && meId.isNotBlank() &&
        (authMethod == AuthMethod.ENTRA || pat.isNotBlank())
}

private val Context.dataStore by preferencesDataStore("settings")

/** The settings in [dataStore]: the app's own file, or another one in the tests. */
class SettingsStore(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    private object K {
        val org = stringPreferencesKey("organization")
        val auth = stringPreferencesKey("auth_method")
        val pat = stringPreferencesKey("pat_enc")
        val poll = intPreferencesKey("poll_seconds")
        val keepOn = booleanPreferencesKey("keep_screen_on")
        val meId = stringPreferencesKey("me_id")
        val meName = stringPreferencesKey("me_name")
        val baseline = longPreferencesKey("baseline_at")
        val lastSync = longPreferencesKey("last_sync_at")
        val entraClient = stringPreferencesKey("entra_client_id")
        val entraTenant = stringPreferencesKey("entra_tenant_id")
        val filters = stringPreferencesKey("filters")
        val filtersChanged = longPreferencesKey("filters_changed_at")
        val views = stringPreferencesKey("views")
        val notifications = stringPreferencesKey("notifications")
        val signInNotified = booleanPreferencesKey("sign_in_notified")
    }

    val settings: Flow<Settings> = dataStore.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    private fun Preferences.toSettings() = Settings(
        organization = this[K.org].orEmpty(),
        authMethod = this[K.auth]?.let { runCatching { AuthMethod.valueOf(it) }.getOrNull() } ?: AuthMethod.ENTRA,
        pat = this[K.pat]?.let(TokenCipher::decrypt).orEmpty(),
        pollSeconds = this[K.poll] ?: 60,
        keepScreenOn = this[K.keepOn] ?: true,
        meId = this[K.meId].orEmpty(),
        meName = this[K.meName].orEmpty(),
        baselineAt = this[K.baseline] ?: 0,
        lastSyncAt = this[K.lastSync] ?: 0,
        entraClientId = this[K.entraClient].orEmpty(),
        entraTenantId = this[K.entraTenant].orEmpty(),
        filters = Filters.decode(this[K.filters]),
        filtersChangedAt = this[K.filtersChanged] ?: 0,
        views = ViewSet.decode(this[K.views]),
        notifications = NotificationPrefs.decode(this[K.notifications]),
        signInNotified = this[K.signInNotified] ?: false,
    )

    /** Saving a different account restarts the baseline. */
    suspend fun saveAccount(organization: String, method: AuthMethod, pat: String, meId: String, meName: String) {
        dataStore.edit {
            if (it[K.org] != organization || it[K.meId] != meId) {
                it[K.baseline] = 0
                it[K.lastSync] = 0
            }
            // Another organization's projects and areas mean nothing here
            if (it[K.org] != organization) {
                it.remove(K.filters)
                it.remove(K.views)
            }
            it[K.org] = organization
            it[K.auth] = method.name
            if (method == AuthMethod.PAT) it[K.pat] = TokenCipher.encrypt(pat) else it.remove(K.pat)
            it[K.meId] = meId
            it[K.meName] = meName
            it.remove(K.signInNotified)
        }
    }

    suspend fun savePreferences(pollSeconds: Int, keepScreenOn: Boolean) {
        dataStore.edit {
            it[K.poll] = pollSeconds
            it[K.keepOn] = keepScreenOn
        }
    }

    suspend fun saveFilters(filters: Filters, at: Long = System.currentTimeMillis()) {
        dataStore.edit {
            it[K.filters] = filters.encode()
            it[K.filtersChanged] = at
        }
    }

    /** Changes the views as they are stored now, so that two quick changes never undo each other. */
    suspend fun editViews(change: (ViewSet) -> ViewSet) {
        dataStore.edit { it[K.views] = change(ViewSet.decode(it[K.views])).encode() }
    }

    /** Changes the notification settings as they are stored now (see [editViews]). */
    suspend fun editNotifications(change: (NotificationPrefs) -> NotificationPrefs) {
        dataStore.edit { it[K.notifications] = change(NotificationPrefs.decode(it[K.notifications])).encode() }
    }

    /** Identifiers, not secrets: stored in clear. */
    suspend fun saveEntraConfig(clientId: String, tenantId: String) {
        dataStore.edit {
            it[K.entraClient] = clientId
            it[K.entraTenant] = tenantId
        }
    }

    /** Back to the sign-in screen, keeping the organization and preferences. */
    suspend fun signOut() {
        dataStore.edit {
            it.remove(K.pat)
            it.remove(K.meId)
            it.remove(K.meName)
            it[K.baseline] = 0
            it[K.lastSync] = 0
            it.remove(K.signInNotified)
        }
    }

    suspend fun setSignInNotified(notified: Boolean) {
        dataStore.edit { if (notified) it[K.signInNotified] = true else it.remove(K.signInNotified) }
    }

    /**
     * Another organization, from the account settings once signed out: its filters and views
     * start from the defaults, as [saveAccount] does for a new account.
     */
    suspend fun changeOrganization(organization: String) {
        dataStore.edit {
            if (!it[K.org].equals(organization, ignoreCase = true)) {
                it.remove(K.filters)
                it.remove(K.views)
                it[K.baseline] = 0
                it[K.lastSync] = 0
            }
            it[K.org] = organization
        }
    }

    suspend fun markSynced(at: Long) {
        dataStore.edit {
            if ((it[K.baseline] ?: 0) == 0L) it[K.baseline] = at
            it[K.lastSync] = at
        }
    }
}
