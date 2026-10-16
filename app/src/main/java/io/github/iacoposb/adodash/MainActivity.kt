package io.github.iacoposb.adodash

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.iacoposb.adodash.ui.AdoTheme
import io.github.iacoposb.adodash.ui.AppViewModel
import io.github.iacoposb.adodash.ui.DashboardScreen
import io.github.iacoposb.adodash.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                AppViewModel(application as AdoApp) as T
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** Settings scrolled to the update, from the notification or the dashboard banner. */
    private var showUpdate by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) showUpdate = intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            AdoTheme {
                val settings = vm.settings.collectAsStateWithLifecycle().value ?: return@AdoTheme
                val sync by vm.sync.collectAsStateWithLifecycle()
                val events by vm.events.collectAsStateWithLifecycle()
                val save by vm.save.collectAsStateWithLifecycle()
                val update by vm.update.collectAsStateWithLifecycle()
                val updateBanner by vm.updateBanner.collectAsStateWithLifecycle()
                val catalog by vm.catalog.collectAsStateWithLifecycle()
                var editing by rememberSaveable { mutableStateOf(false) }
                val close = { editing = false; showUpdate = false }

                LaunchedEffect(showUpdate) { if (showUpdate) editing = true }

                LaunchedEffect(settings.keepScreenOn) {
                    if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }

                if (!settings.configured || editing) {
                    SettingsScreen(
                        initial = settings,
                        redirectUri = (application as AdoApp).entra.redirectUri,
                        busy = save.busy,
                        message = save.message,
                        onMicrosoftSignIn = { vm.signInWithMicrosoft(this, it, close) },
                        onPatSignIn = { form, pat -> vm.signInWithPat(form, pat, close) },
                        onSavePreferences = { vm.savePreferences(it, close) },
                        onSignOut = vm::signOut,
                        onBack = if (settings.configured) close else null,
                        version = vm.version,
                        update = update,
                        onCheckUpdates = vm::checkUpdates,
                        onDownloadUpdate = vm::downloadUpdate,
                        onInstallUpdate = vm::installUpdate,
                        showUpdate = showUpdate,
                        catalog = catalog,
                        onLoadCatalog = vm::loadCatalog,
                        onFiltersChange = vm::updateFilters,
                    )
                } else {
                    // Polls only while the dashboard is visible; the worker covers the rest
                    LaunchedEffect(Unit) { repeatOnLifecycle(Lifecycle.State.STARTED) { vm.poll() } }
                    DashboardScreen(
                        organization = settings.organization,
                        state = sync,
                        events = events,
                        onRefresh = vm::refresh,
                        onSettings = { editing = true },
                        onOpen = ::openUrl,
                        onEventOpen = { vm.markRead(it); openUrl(it.url) },
                        onMarkAllRead = vm::markAllRead,
                        updateVersion = updateBanner,
                        onUpdate = { showUpdate = true },
                        onDismissUpdate = { updateBanner?.let(vm::dismissUpdate) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)) showUpdate = true
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_app_for_url, url), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_SHOW_UPDATE = "show_update"
    }
}
