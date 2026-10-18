package io.github.iacoposb.adodash.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import io.github.iacoposb.adodash.BuildConfig
import io.github.iacoposb.adodash.errorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object NoReleases : UpdateState
    data class CheckFailed(val message: String) : UpdateState
    data class Available(override val release: Release) : WithRelease
    /** [progress] in 0..1, null when the size is unknown. */
    data class Downloading(override val release: Release, val progress: Float?) : WithRelease
    /** Downloaded and verified: the system installer takes it from here. */
    data class Ready(override val release: Release, val apk: File) : WithRelease
    data class DownloadFailed(override val release: Release, val message: String) : WithRelease

    /** The states where a newer version is known. */
    sealed interface WithRelease : UpdateState { val release: Release }
}

/**
 * Looks for a newer APK among the GitHub releases of [BuildConfig.UPDATE_REPO]
 * (published by .github/workflows/release.yml), downloads it and hands it to
 * the system installer. The releases must be publicly readable: no token.
 */
class Updater(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Called by the automatic check, once per new version. */
    private val onFound: suspend (Release) -> Unit,
    private val source: GitHubReleases = GitHubReleases(BuildConfig.UPDATE_REPO),
) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var download: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _dismissed = MutableStateFlow(prefs.getString(KEY_DISMISSED, null))
    /** Version whose dashboard banner was closed. */
    val dismissed: StateFlow<String?> = _dismissed.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Automatic check, at most once every [CHECK_EVERY_MS]; failures stay silent. */
    suspend fun checkIfDue() {
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_EVERY_MS) return
        run(manual = false)
    }

    /** From the settings: shows the outcome, errors included. */
    fun check() {
        scope.launch { run(manual = true) }
    }

    private suspend fun run(manual: Boolean) {
        // Never interrupt a download, nor run two checks at once
        if (_state.value is UpdateState.Downloading || !mutex.tryLock()) return
        val before = _state.value
        try {
            if (manual) _state.value = UpdateState.Checking
            val result = source.check(currentVersion)
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            _state.value = when (result) {
                is CheckResult.Available -> {
                    if (!manual && prefs.getString(KEY_NOTIFIED, null) != result.release.version) {
                        prefs.edit().putString(KEY_NOTIFIED, result.release.version).apply()
                        onFound(result.release)
                    }
                    // Keep a finished download of the same version
                    (before as? UpdateState.Ready)?.takeIf { it.release == result.release }
                        ?: UpdateState.Available(result.release)
                }
                CheckResult.UpToDate -> UpdateState.UpToDate
                CheckResult.NoReleases -> UpdateState.NoReleases
            }
        } catch (e: CancellationException) {
            _state.value = before
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            _state.value = if (manual) UpdateState.CheckFailed(context.errorText(e)) else before
        } finally {
            mutex.unlock()
        }
    }

    fun download(release: Release) {
        if (download?.isActive == true) return
        download = scope.launch {
            _state.value = UpdateState.Downloading(release, if (release.apkSize > 0) 0f else null)
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val apk = File(dir, "ado-dashboard-${release.version}.apk")
            _state.value = try {
                source.download(release, apk) { _state.value = UpdateState.Downloading(release, it) }
                UpdateState.Ready(release, apk).also { install(it.apk) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update download failed", e)
                apk.delete()
                UpdateState.DownloadFailed(release, context.errorText(e))
            }
        }
    }

    /**
     * Opens the system installer. Until the user allows installs from this
     * app, opens that settings page instead: then they tap install again.
     */
    fun install(apk: File) {
        val intent = if (!context.packageManager.canRequestPackageInstalls()) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no installer", e)
        }
    }

    fun dismiss(version: String) {
        prefs.edit().putString(KEY_DISMISSED, version).apply()
        _dismissed.value = version
    }

    companion object {
        private const val TAG = "Updater"
        const val CHECK_EVERY_MS = 24 * 3600_000L
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_NOTIFIED = "notified_version"
        private const val KEY_DISMISSED = "dismissed_version"
    }
}
