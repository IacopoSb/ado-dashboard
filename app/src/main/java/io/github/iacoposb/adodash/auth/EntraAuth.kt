package io.github.iacoposb.adodash.auth

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import io.github.iacoposb.adodash.data.Settings
import io.github.iacoposb.adodash.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Blank [tenantId]: multi-tenant `organizations` endpoint. */
data class EntraConfig(val clientId: String, val tenantId: String)

class SignInRequiredException : Exception("Microsoft session expired: sign in again")

class EntraNotConfiguredException : IllegalStateException("Entra application ID missing in the settings")

/**
 * Microsoft Entra ID sign-in through MSAL, for organizations backed by
 * Entra. The account and its refresh token live in MSAL's own cache.
 * The app registration is entered in the settings: nothing is baked into
 * the build, so the same APK works for any tenant.
 */
class EntraAuth(private val context: Context, private val settings: SettingsStore) {
    private val mutex = Mutex()
    private var app: ISingleAccountPublicClientApplication? = null
    private var appConfig: EntraConfig? = null

    fun config(s: Settings) = EntraConfig(s.entraClientId.trim(), s.entraTenantId.trim())

    /** What to register in Entra as the Android platform's redirect URI. */
    val redirectUri get() = redirectUri(context)

    /** Interactive: opens the Microsoft sign-in page (or the broker app). */
    suspend fun signIn(activity: Activity): IAuthenticationResult {
        val app = app()
        withContext(Dispatchers.IO) { if (app.currentAccount.currentAccount != null) app.signOut() }
        return suspendCancellableCoroutine { cont ->
            val params = SignInParameters.builder()
                .withActivity(activity)
                .withScopes(SCOPES)
                .withCallback(object : AuthenticationCallback {
                    override fun onSuccess(result: IAuthenticationResult) = cont.resume(result)
                    override fun onError(e: MsalException) = cont.resumeWithException(e)
                    override fun onCancel() { cont.cancel() }
                })
                .build()
            app.signIn(params)
        }
    }

    /** A valid access token, refreshed silently; works from the background worker. */
    suspend fun accessToken(): String = withContext(Dispatchers.IO) {
        val app = app()
        val account = app.currentAccount.currentAccount ?: throw SignInRequiredException()
        try {
            app.acquireTokenSilent(
                AcquireTokenSilentParameters.Builder()
                    .forAccount(account)
                    .fromAuthority(account.authority)
                    .withScopes(SCOPES)
                    .build(),
            ).accessToken
        } catch (_: MsalUiRequiredException) {
            throw SignInRequiredException()
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val app = app()
        if (app.currentAccount.currentAccount != null) app.signOut()
    }

    /** Built again when the configuration changes. */
    private suspend fun app(): ISingleAccountPublicClientApplication = mutex.withLock {
        val config = config(settings.current())
        app?.takeIf { config == appConfig } ?: withContext(Dispatchers.IO) {
            if (config.clientId.isBlank()) throw EntraNotConfiguredException()
            PublicClientApplication.createSingleAccountPublicClientApplication(context, writeConfig(config))
        }.also { app = it; appConfig = config }
    }

    /**
     * MSAL wants its configuration as a file. Written at runtime so the
     * redirect URI follows the signing key: debug and release both work.
     */
    private fun writeConfig(entra: EntraConfig): File {
        val tenant = entra.tenantId
        val config = buildJsonObject {
            put("client_id", entra.clientId)
            put("redirect_uri", redirectUri(context))
            put("broker_redirect_uri_registered", true)
            put("account_mode", "SINGLE")
            put("authorization_user_agent", "DEFAULT")
            putJsonArray("authorities") {
                addJsonObject {
                    put("type", "AAD")
                    put("default", true)
                    putJsonObject("audience") {
                        if (tenant.isBlank()) put("type", "AzureADMultipleOrgs")
                        else {
                            put("type", "AzureADMyOrg")
                            put("tenant_id", tenant)
                        }
                    }
                }
            }
        }
        return File(context.noBackupFilesDir, "msal_config.json").apply { writeText(config.toString()) }
    }

    companion object {
        /** Azure DevOps as an Entra resource: the permissions granted to the app registration. */
        private val SCOPES = listOf("499b84ac-1321-427f-aa17-267ca6975798/.default")

        /** msauth://<package>/<base64 SHA-1 of the signing certificate>, URL-encoded. */
        fun redirectUri(context: Context): String =
            "msauth://${context.packageName}/${URLEncoder.encode(signatureHash(context), "UTF-8")}"

        fun signatureHash(context: Context): String {
            val pm = context.packageManager
            val cert = if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo!!.apkContentsSigners.first()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures!!.first()
            }
            val sha1 = MessageDigest.getInstance("SHA-1").digest(cert.toByteArray())
            return Base64.encodeToString(sha1, Base64.NO_WRAP)
        }
    }
}
