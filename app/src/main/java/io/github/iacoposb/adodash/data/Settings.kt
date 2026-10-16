package io.github.iacoposb.adodash.data

import android.content.Context
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
) {
    val configured get() = organization.isNotBlank() && meId.isNotBlank() &&
        (authMethod == AuthMethod.ENTRA || pat.isNotBlank())
}

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
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
    }

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

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
    )

    /** Saving a different account restarts the baseline. */
    suspend fun saveAccount(organization: String, method: AuthMethod, pat: String, meId: String, meName: String) {
        context.dataStore.edit {
            if (it[K.org] != organization || it[K.meId] != meId) {
                it[K.baseline] = 0
                it[K.lastSync] = 0
            }
            it[K.org] = organization
            it[K.auth] = method.name
            if (method == AuthMethod.PAT) it[K.pat] = TokenCipher.encrypt(pat) else it.remove(K.pat)
            it[K.meId] = meId
            it[K.meName] = meName
        }
    }

    suspend fun savePreferences(pollSeconds: Int, keepScreenOn: Boolean) {
        context.dataStore.edit {
            it[K.poll] = pollSeconds
            it[K.keepOn] = keepScreenOn
        }
    }

    /** Identifiers, not secrets: stored in clear. */
    suspend fun saveEntraConfig(clientId: String, tenantId: String) {
        context.dataStore.edit {
            it[K.entraClient] = clientId
            it[K.entraTenant] = tenantId
        }
    }

    /** Back to the sign-in screen, keeping the organization and preferences. */
    suspend fun signOut() {
        context.dataStore.edit {
            it.remove(K.pat)
            it.remove(K.meId)
            it.remove(K.meName)
            it[K.baseline] = 0
            it[K.lastSync] = 0
        }
    }

    suspend fun markSynced(at: Long) {
        context.dataStore.edit {
            if ((it[K.baseline] ?: 0) == 0L) it[K.baseline] = at
            it[K.lastSync] = at
        }
    }
}
