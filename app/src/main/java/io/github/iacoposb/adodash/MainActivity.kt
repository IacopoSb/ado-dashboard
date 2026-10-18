package io.github.iacoposb.adodash

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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
import io.github.iacoposb.adodash.ui.EditorTarget
import io.github.iacoposb.adodash.ui.NotificationActions
import io.github.iacoposb.adodash.data.SoundTarget
import io.github.iacoposb.adodash.ui.SettingsScreen
import io.github.iacoposb.adodash.ui.ViewEditor
import io.github.iacoposb.adodash.ui.ViewPickerScreen
import io.github.iacoposb.adodash.ui.viewChoices
import io.github.iacoposb.adodash.data.DashboardView
import io.github.iacoposb.adodash.data.PrCommentRef
import androidx.compose.runtime.remember

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                AppViewModel(application as AdoApp) as T
        }
    }

    /** Asked from the settings' banner, not at launch: a refusal for good opens the system settings. */
    private var askedFromSettings = false

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshNotificationsAllowed()
        // No dialog shown: the permission was refused for good, only the system settings can grant it
        if (askedFromSettings && !granted && Build.VERSION.SDK_INT >= 33 &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            openNotificationSettings()
        }
        askedFromSettings = false
    }

    /** The app may post notifications: the settings show a banner otherwise. */
    private var notificationsAllowed by mutableStateOf(true)

    /** Which sound the system's picker is choosing. */
    private var pickingSound = SoundTarget.MENTIONS

    private val pickSound = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val picked = result.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
        val sound = when (picked) {
            null -> ""
            android.provider.Settings.System.DEFAULT_NOTIFICATION_URI -> null
            else -> picked.toString()
        }
        vm.setSound(pickingSound, sound)
    }

    /** Sound names by URI: looking one up reads the media store. */
    private val soundTitles = mutableMapOf<String, String>()

    /** Settings scrolled to the update, from the notification or the dashboard banner. */
    private var showUpdate by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            showUpdate = intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)
            openBuildFrom(intent)
            openCommentFrom(intent)
            openPullRequestFrom(intent)
        }
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        refreshNotificationsAllowed()

        setContent {
            AdoTheme {
                val settings = vm.settings.collectAsStateWithLifecycle().value ?: return@AdoTheme
                val sync by vm.sync.collectAsStateWithLifecycle()
                val events by vm.events.collectAsStateWithLifecycle()
                val save by vm.save.collectAsStateWithLifecycle()
                val update by vm.update.collectAsStateWithLifecycle()
                val updateBanner by vm.updateBanner.collectAsStateWithLifecycle()
                val catalog by vm.catalog.collectAsStateWithLifecycle()
                val launchChoiceMade by vm.launchChoiceMade.collectAsStateWithLifecycle()
                val buildDetail by vm.build.collectAsStateWithLifecycle()
                val pipelines by vm.pipelines.collectAsStateWithLifecycle()
                val workItem by vm.workItem.collectAsStateWithLifecycle()
                val workItemsReadOnly by vm.workItemsReadOnly.collectAsStateWithLifecycle()
                val pullRequest by vm.pullRequest.collectAsStateWithLifecycle()
                val choices = viewChoices(settings.views, catalog, settings.filters, sync.snapshot)
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
                        buildsDenied = sync.snapshot?.buildsDenied == true,
                        workItemsReadOnly = workItemsReadOnly,
                        askViewAtLaunch = !settings.views.alwaysOpen,
                        onAskViewAtLaunchChange = vm::setAskAtLaunch,
                        notificationsAllowed = notificationsAllowed,
                        notificationActions = remember(vm) {
                            NotificationActions(
                                onChange = vm::setNotifications,
                                onAllow = ::allowNotifications,
                                onPickSound = { pickSound(it, vm.settings.value?.notifications?.sound(it)) },
                                onOpenSystemSettings = ::openNotificationSettings,
                                soundTitle = ::soundTitle,
                            )
                        },
                    )
                } else if (!launchChoiceMade && !settings.views.alwaysOpen) {
                    var newView by remember { mutableStateOf<EditorTarget?>(null) }
                    ViewPickerScreen(
                        choices,
                        onOpen = vm::chooseAtLaunch,
                        onNewView = { newView = EditorTarget(DashboardView.newSaved(""), isNew = true) },
                        onLoadBoards = vm::loadBoards,
                        editorOpen = newView != null,
                        editor = {
                            newView?.let { target ->
                                ViewEditor(
                                    target, choices.options,
                                    onSave = { newView = null; vm.saveView(it) },
                                    onDelete = { newView = null },
                                    onDismiss = { newView = null },
                                )
                            }
                        },
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
                        onEventOpen = { event ->
                            vm.markRead(event)
                            val build = event.buildId
                            val comment = event.workItemComment
                            val pr = event.pullRequest
                            when {
                                build != null && event.project != null -> vm.openBuild(event.project, build)
                                comment != null -> vm.openWorkItemComment(comment.workItemId, comment.commentId)
                                pr != null -> vm.openPullRequest(pr)
                                else -> openUrl(event.url)
                            }
                        },
                        onMarkAllRead = vm::markAllRead,
                        updateVersion = updateBanner,
                        onUpdate = { showUpdate = true },
                        onDismissUpdate = { updateBanner?.let(vm::dismissUpdate) },
                        choices = choices,
                        onOpenView = vm::openView,
                        onOpenBoard = vm::openBoard,
                        onSaveView = vm::saveView,
                        onDeleteView = { vm.deleteView(it.id) },
                        onLoadBoards = vm::loadBoards,
                        buildDetail = buildDetail,
                        pipelines = pipelines,
                        onOpenPipelines = vm::openPipelines,
                        pipelinesActions = remember(vm) { vm.pipelinesActions(::openUrl) },
                        onRerunBuild = vm::rerunBuild,
                        onCloseBuild = vm::closeBuild,
                        onOpenWorkItem = vm::openWorkItem,
                        workItem = workItem,
                        workItemActions = remember(vm) { vm.workItemActions(::openUrl) },
                        onOpenPullRequest = vm::openPullRequest,
                        pullRequest = pullRequest,
                        prActions = remember(vm) { vm.prActions(::openUrl) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the system settings, maybe with notifications allowed
        refreshNotificationsAllowed()
    }

    private fun refreshNotificationsAllowed() {
        notificationsAllowed = NotificationManagerCompat.from(this).areNotificationsEnabled() && (
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )
    }

    /** The permission's dialog when it can still be shown, the app's notification settings otherwise. */
    private fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedFromSettings = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        val intent = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    /** The system's notification sound picker, on [current] (null: the default, "": none). */
    private fun pickSound(target: SoundTarget, current: String?) {
        pickingSound = target
        val existing = when {
            current == null -> android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
            current.isEmpty() -> null
            else -> Uri.parse(current)
        }
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
            .putExtra(
                RingtoneManager.EXTRA_RINGTONE_TITLE,
                getString(if (target == SoundTarget.MENTIONS) R.string.sounds_mentions else R.string.sounds_others),
            )
        try {
            pickSound.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.no_app_for_url, RingtoneManager.ACTION_RINGTONE_PICKER), Toast.LENGTH_SHORT).show()
        }
    }

    private fun soundTitle(uri: String): String = soundTitles.getOrPut(uri) {
        runCatching { RingtoneManager.getRingtone(this, Uri.parse(uri))?.getTitle(this) }.getOrNull() ?: uri.substringAfterLast('/')
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)) showUpdate = true
        openBuildFrom(intent)
        openCommentFrom(intent)
        openPullRequestFrom(intent)
    }

    /**
     * From a pull request's notification: its page; for a comment or a mention, at its thread
     * with the reply to its author ready.
     */
    private fun openPullRequestFrom(intent: Intent) {
        val pr = intent.getIntExtra(EXTRA_PR_ID, -1)
        if (pr < 0) return
        val thread = intent.getIntExtra(EXTRA_PR_THREAD_ID, -1).takeIf { it >= 0 }
        val comment = intent.getIntExtra(EXTRA_COMMENT_ID, -1).takeIf { it >= 0 }
        intent.getStringExtra(EXTRA_EVENT_KEY)?.let { vm.markRead(it) }
        vm.openPullRequest(PrCommentRef(pr, thread, comment))
        intent.removeExtra(EXTRA_PR_ID)
    }

    /** From a mention in a work item's comments: the work item at that comment, ready to reply. */
    private fun openCommentFrom(intent: Intent) {
        val workItem = intent.getIntExtra(EXTRA_WORK_ITEM_ID, -1)
        val comment = intent.getIntExtra(EXTRA_COMMENT_ID, -1)
        if (workItem < 0 || comment < 0) return
        intent.getStringExtra(EXTRA_EVENT_KEY)?.let { vm.markRead(it) }
        vm.openWorkItemComment(workItem, comment)
        intent.removeExtra(EXTRA_WORK_ITEM_ID)
    }

    /** From a "build failed" notification: its details over the dashboard. */
    private fun openBuildFrom(intent: Intent) {
        val id = intent.getIntExtra(EXTRA_BUILD_ID, -1)
        val project = intent.getStringExtra(EXTRA_BUILD_PROJECT)
        if (id < 0 || project == null) return
        intent.getStringExtra(EXTRA_EVENT_KEY)?.let { vm.markRead(it) }
        // Settings or the launch screen may be showing: the details wait for the dashboard
        vm.openBuild(project, id)
        intent.removeExtra(EXTRA_BUILD_ID)
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
        const val EXTRA_BUILD_ID = "build_id"
        const val EXTRA_BUILD_PROJECT = "build_project"
        const val EXTRA_EVENT_KEY = "event_key"
        const val EXTRA_WORK_ITEM_ID = "work_item_id"
        const val EXTRA_COMMENT_ID = "comment_id"
        const val EXTRA_PR_ID = "pr_id"
        const val EXTRA_PR_THREAD_ID = "pr_thread_id"
    }
}
