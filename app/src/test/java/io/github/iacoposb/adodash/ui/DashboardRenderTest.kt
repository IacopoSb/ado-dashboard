package io.github.iacoposb.adodash.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.anna
import io.github.iacoposb.adodash.Fakes.marco
import io.github.iacoposb.adodash.Fakes.me
import io.github.iacoposb.adodash.Fakes.now
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.Reviewer
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.EventKind
import io.github.iacoposb.adodash.data.ActivityKind
import io.github.iacoposb.adodash.data.ActivityPanel
import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.data.Condition
import io.github.iacoposb.adodash.data.DashboardView
import io.github.iacoposb.adodash.data.Filters
import io.github.iacoposb.adodash.data.PrScope
import io.github.iacoposb.adodash.data.PullRequestsPanel
import io.github.iacoposb.adodash.data.ViewSet
import io.github.iacoposb.adodash.data.WiField
import io.github.iacoposb.adodash.data.WorkItemsPanel
import io.github.iacoposb.adodash.sync.Shown
import io.github.iacoposb.adodash.sync.TeamInfo
import io.github.iacoposb.adodash.data.Settings
import io.github.iacoposb.adodash.errorText
import io.github.iacoposb.adodash.sync.CatalogState
import io.github.iacoposb.adodash.sync.ResourceSyncTexts
import io.github.iacoposb.adodash.sync.Snapshot
import io.github.iacoposb.adodash.sync.SyncState
import io.github.iacoposb.adodash.update.Release
import io.github.iacoposb.adodash.update.UpdateException
import io.github.iacoposb.adodash.update.UpdateState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screens with fake data as PNGs in app/build/outputs/roborazzi: in
 * English, plus `-it` variants in Italian (`+it` adds the locale to the
 * class qualifiers).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w1280dp-h800dp-land-mdpi", application = android.app.Application::class)
class DashboardRenderTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val snapshot = Snapshot(
        me = me,
        workItems = listOf(
            Fakes.workItem(4812, "Bug", "SSO sign-in fails after the token is renewed", "Active", 1, 1800),
            Fakes.workItem(4790, "User Story", "CSV export of the monthly reports", "New", 2, 7200),
            Fakes.workItem(4755, "Task", "Update the build dependencies", "Active", 3, 90_000),
            Fakes.workItem(4701, "Feature", "Push notifications for customers", "In Progress", 2, 300_000),
        ),
        reviewPrs = listOf(
            Fakes.pr(311, anna, "Retry handling for payment gateway calls",
                listOf(Reviewer(me.id, me.name, Vote.NONE, true))),
            Fakes.pr(305, marco, "Refactor the authentication module",
                listOf(Reviewer(me.id, me.name, Vote.WAITING, false)), draft = true, ago = 200_000),
        ),
        myPrs = listOf(
            Fakes.pr(318, me, "Dashboard: filter by area path", listOf(
                Reviewer(anna.id, anna.name, Vote.APPROVED, true),
                Reviewer(marco.id, marco.name, Vote.WAITING, false),
            ), ago = 3000),
        ),
        syncedAt = now,
    )

    /** Titles in the language of the test, as a sync would store them. */
    private fun events() = ResourceSyncTexts(context).run {
        listOf(
            Event("1", EventKind.MENTION, prMention(anna.name, 311), "Retry handling\n@Iacopo Sbalchiero is exponential backoff fine here?", "", now.minusSeconds(240).toEpochMilli()),
            Event("2", EventKind.ASSIGNED, assigned(4812, "SSO sign-in fails"), "Bug · Contoso · Active", "", now.minusSeconds(1800).toEpochMilli()),
            Event("3", EventKind.PR_VOTE, prVote(anna.name, Vote.APPROVED), "Dashboard: filter by area path", "", now.minusSeconds(2400).toEpochMilli(), read = true),
            Event("4", EventKind.PR_COMMENT, prComment(marco.name, 318), "Dashboard: filter\nThe empty case has no test", "", now.minusSeconds(2700).toEpochMilli(), read = true),
            Event("5", EventKind.PR_REVIEW, reviewRequested("Retry handling for payment gateway calls"), "Anna Verdi · backend · feature/x-311 → main", "", now.minusSeconds(7200).toEpochMilli(), read = true),
        )
    }

    private fun dashboard(
        name: String,
        dark: Boolean,
        state: SyncState = SyncState(snapshot),
        update: String? = null,
        events: List<Event> = events(),
    ) {
        compose.setContent {
            AdoTheme(dark = dark, dynamic = false) {
                DashboardScreen("contoso", state, events, {}, {}, {}, {}, {}, now = now, updateVersion = update)
            }
        }
        compose.onRoot().captureRoboImage("$name.png")
    }

    @Test fun tabletLight() = dashboard("dashboard-tablet-light", dark = false)
    @Test fun tabletDark() = dashboard("dashboard-tablet-dark", dark = true)
    @Test fun tabletError() = dashboard(
        "dashboard-tablet-error", dark = false, SyncState(snapshot, error = context.getString(R.string.error_credentials)),
    )

    /** Opening the app: no data yet, the first sync running. */
    @Test fun tabletLoading() = dashboard("dashboard-tablet-loading", dark = false, SyncState(syncing = true), events = emptyList())

    @Config(qualifiers = "+it")
    @Test fun tabletLoadingItalian() =
        dashboard("dashboard-tablet-loading-it", dark = false, SyncState(syncing = true), events = emptyList())

    /** The first sync failed: the error and the empty panels, no endless placeholder. */
    @Test fun tabletFirstSyncFailed() = dashboard(
        "dashboard-tablet-first-sync-failed", dark = false,
        SyncState(error = context.getString(R.string.error_credentials)), events = emptyList(),
    )

    @Config(qualifiers = "+it")
    @Test fun tabletLightItalian() = dashboard("dashboard-tablet-light-it", dark = false)

    @Test fun tabletUpdate() = dashboard("dashboard-tablet-update", dark = false, update = "0.2.0")

    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    @Test fun phone() = dashboard("dashboard-phone", dark = false)

    /** First launch: the Entra registration is entered in the app. */
    @Test fun settings() = signIn("settings")

    @Config(qualifiers = "+it")
    @Test fun settingsItalian() = signIn("settings-it")

    @Test fun settingsDark() = signIn("settings-dark", dark = true)

    /** Narrow: the sections as a list first. */
    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    @Test fun settingsPhone() = signedIn("settings-phone", section = null)

    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    @Test fun settingsPhoneAccount() = signIn("settings-phone-account")

    @Test fun settingsSignedIn() = signedIn("settings-account-signed-in", section = SettingsSection.ACCOUNT)

    @Config(qualifiers = "+it")
    @Test fun settingsSignedInItalian() = signedIn("settings-account-signed-in-it", section = SettingsSection.ACCOUNT)

    @Test fun settingsScreen() = signedIn("settings-screen", section = SettingsSection.SCREEN)

    @Config(qualifiers = "+it")
    @Test fun settingsScreenItalian() = signedIn("settings-screen-it", section = SettingsSection.SCREEN)

    /** As in the design: Contoso partly selected, Resolved hidden for the Epics. */
    private val designFilters = Filters(areas = Fakes.designAreas, hiddenStates = mapOf("Epic" to setOf("Resolved")))

    /** My work items are Bugs and User Stories; Epics are listed for their hidden state. */
    private val usedTypes = setOf("Bug", "User Story")

    private fun filters(
        name: String,
        query: String = "",
        catalog: CatalogState = CatalogState(Fakes.catalog, usedTypes = usedTypes),
        dark: Boolean = false,
        filters: Filters = designFilters,
        scrolledToEnd: Boolean = false,
    ) {
        compose.setContent {
            AdoTheme(dark = dark, dynamic = false) {
                SettingsScreen(
                    Settings(organization = "contoso", meId = "u1", meName = "Iacopo Sbalchiero", filters = filters),
                    busy = false, message = null, {}, { _, _ -> }, {}, {}, onBack = {},
                    initialSection = SettingsSection.FILTERS, catalog = catalog,
                    filtersExpanded = setOf("Contoso", "Contoso\\Backend"), filtersQuery = query,
                )
            }
        }
        if (scrolledToEnd) repeat(3) { compose.onNodeWithTag(RIGHT_COLUMN_TAG).performTouchInput { swipeUp() } }
        compose.onRoot().captureRoboImage("$name.png")
    }

    @Test fun settingsFilters() = filters("settings-filters")

    @Config(qualifiers = "+it")
    @Test fun settingsFiltersItalian() = filters("settings-filters-it")

    @Test fun settingsFiltersSearch() = filters("settings-filters-search", query = "pag")

    @Test fun settingsFiltersDark() = filters("settings-filters-dark", dark = true)

    @Test fun settingsFiltersLoading() = filters("settings-filters-loading", catalog = CatalogState(loading = true))

    /** Opened again: the saved catalog at once, a fresh one being read. */
    @Test fun settingsFiltersRefreshing() =
        filters("settings-filters-refreshing", catalog = CatalogState(Fakes.catalog, loading = true, usedTypes = usedTypes))

    @Config(qualifiers = "+it")
    @Test fun settingsFiltersRefreshingItalian() =
        filters("settings-filters-refreshing-it", catalog = CatalogState(Fakes.catalog, loading = true, usedTypes = usedTypes))

    /** A failed refresh with a saved catalog: no banner, the saved one stays. */
    @Test fun settingsFiltersRefreshFailed() = filters(
        "settings-filters-refresh-failed",
        catalog = CatalogState(Fakes.catalog, error = context.getString(R.string.error_credentials), usedTypes = usedTypes),
    )

    /** A real process: only the types of my work items, the right column scrolls to the end. */
    private val manyTypes = CatalogState(Fakes.manyTypesCatalog, usedTypes = setOf("Epic", "Feature", "User Story", "Bug", "Task", "Issue"))
    private val manyTypesFilters = Filters(hiddenStates = mapOf("Epic" to setOf("Resolved"), "Risk" to setOf("Monitoring")))

    @Test fun settingsFiltersManyTypes() =
        filters("settings-filters-many-types", catalog = manyTypes, filters = manyTypesFilters)

    @Test fun settingsFiltersManyTypesScrolled() = filters(
        "settings-filters-many-types-scrolled", catalog = manyTypes, filters = manyTypesFilters,
        scrolledToEnd = true,
    )

    @Test fun settingsFiltersError() =
        filters("settings-filters-error", catalog = CatalogState(error = context.getString(R.string.error_credentials)))

    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    @Test fun settingsFiltersPhone() = filters("settings-filters-phone")

    private fun signedIn(name: String, section: SettingsSection?) {
        compose.setContent {
            AdoTheme(dark = false, dynamic = false) {
                SettingsScreen(
                    Settings(organization = "contoso", meId = "u1", meName = "Iacopo Sbalchiero"),
                    busy = false, message = null, {}, { _, _ -> }, {}, {}, onBack = {},
                    version = "0.0.2", initialSection = section,
                )
            }
        }
        compose.onRoot().captureRoboImage("$name.png")
    }

    private fun signIn(name: String, dark: Boolean = false) {
        compose.setContent {
            AdoTheme(dark = dark, dynamic = false) {
                SettingsScreen(
                    Settings(organization = "contoso", entraClientId = "3f2a9c1e-7b4d-4e8a-9c21-5d6e7f809a1b"),
                    busy = false, message = null, {}, { _, _ -> }, {}, {}, onBack = null,
                    redirectUri = "msauth://io.github.iacoposb.adodash/Xo8WBi6jzSxKDVR4drqm84yr9iU%3D",
                )
            }
        }
        compose.onRoot().captureRoboImage("$name.png")
    }

    private val release = Release(
        "0.2.0", "release/0.2.0",
        // From GitHub as is: English headings whatever the device language
        "### New\n- Check github releases for updates and install them from the settings\n" +
            "- Show a dashboard banner when a new version is out\n\n" +
            "### Fixes\n- Show only open work items and hide first-sync events from the feed\n",
        "ado-dashboard-0.2.0.apk", "", 8_400_000,
    )

    /** The "About" section, opened as from the update banner. */
    private fun settingsUpdate(name: String, update: UpdateState, dark: Boolean = false) {
        compose.setContent {
            AdoTheme(dark = dark, dynamic = false) {
                SettingsScreen(
                    Settings(organization = "contoso", meId = "u1", meName = "Iacopo Sbalchiero"),
                    busy = false, message = null, {}, { _, _ -> }, {}, {}, onBack = {},
                    version = "0.1.0", update = update, showUpdate = true,
                )
            }
        }
        compose.onRoot().captureRoboImage("$name.png")
    }

    @Test fun settingsUpdateAvailable() = settingsUpdate("settings-update-available", UpdateState.Available(release))
    @Test fun settingsUpdateDownloading() =
        settingsUpdate("settings-update-downloading", UpdateState.Downloading(release, 0.42f), dark = true)
    @Test fun settingsUpdateFailed() = settingsUpdate(
        "settings-update-failed",
        UpdateState.DownloadFailed(release, context.errorText(UpdateException(UpdateException.Reason.CHECKSUM_MISMATCH))),
    )
    @Test fun settingsNoReleases() = settingsUpdate("settings-no-releases", UpdateState.NoReleases)

    @Config(qualifiers = "+it")
    @Test fun settingsUpdateAvailableItalian() = settingsUpdate("settings-update-available-it", UpdateState.Available(release))

    @Config(qualifiers = "+it")
    @Test fun tabletDarkItalian() = dashboard("dashboard-tablet-dark-it", dark = true)

    private val italian get() = context.resources.configuration.locales[0].language == "it"

    /** The views of the design: the global one, reviews only and urgent bugs. */
    private fun designViews(current: String = DashboardView.GLOBAL_ID): ViewSet {
        val reviews = DashboardView(
            "reviews", if (italian) "Solo revisioni" else "Reviews only",
            workItems = WorkItemsPanel(enabled = false),
            pullRequests = PullRequestsPanel(show = setOf(PrScope.TO_REVIEW)),
            activity = ActivityPanel(kinds = setOf(ActivityKind.MENTIONS, ActivityKind.REVIEW_REQUESTS, ActivityKind.COMMENTS)),
        )
        val bugs = DashboardView(
            "bugs", if (italian) "Bug urgenti" else "Urgent bugs",
            workItems = WorkItemsPanel(
                conditions = listOf(
                    Condition(WiField.TYPE, values = listOf("Bug")),
                    Condition.assignedTo(Condition.ANYONE),
                    Condition.notClosed,
                ),
            ),
        )
        return ViewSet(
            listOf(DashboardView.global, reviews, bugs), currentId = current,
            recentBoards = listOf(BoardRef("Contoso", "Backend"), BoardRef("Interno", "Infrastruttura")),
        )
    }

    private val designBoards = listOf(
        ProjectBoards("Contoso", listOf(TeamInfo("Backend", "Sprint 42"), TeamInfo("Frontend", "Sprint 42"), TeamInfo("Mobile", "Sprint 42"))),
        ProjectBoards("Interno", listOf(TeamInfo("Infrastruttura", "Sprint 18"))),
    )

    /** A real organization: many projects, some with dozens of teams. */
    private val manyBoards = listOf(
        ProjectBoards("Clienti", (1..6).map { TeamInfo("Cliente $it", "Sprint 7") }),
        ProjectBoards("Contoso", listOf("Backend", "Frontend", "Mobile", "Data", "Platform", "QA", "Design", "Security").map { TeamInfo(it, "Sprint 42") }),
        ProjectBoards("Fatturazione", listOf(TeamInfo("Core", "Sprint 12"), TeamInfo("Integrazioni", "Sprint 12"))),
        ProjectBoards("Interno", listOf(TeamInfo("Infrastruttura", "Sprint 18"), TeamInfo("Helpdesk"))),
        ProjectBoards("Logistica", (1..12).map { TeamInfo("Magazzino $it") }),
        ProjectBoards("Marketing", listOf(TeamInfo("Web"), TeamInfo("Campagne"))),
        ProjectBoards("Mobile App", listOf(TeamInfo("iOS", "Sprint 30"), TeamInfo("Android", "Sprint 30"), TeamInfo("Backend", "Sprint 30"))),
        ProjectBoards("Payments", listOf(TeamInfo("Gateway", "Sprint 9"), TeamInfo("Frontend", "Sprint 9"))),
        ProjectBoards("Portale", listOf(TeamInfo("Portale Team"))),
        ProjectBoards("Ricerca", listOf(TeamInfo("ML"), TeamInfo("Search"))),
        ProjectBoards("Supporto", listOf(TeamInfo("L1"), TeamInfo("L2"))),
        ProjectBoards("Vendite", listOf(TeamInfo("CRM"))),
        ProjectBoards("Zeta", listOf(TeamInfo("Zeta Team"))),
    )

    private fun choices(views: ViewSet = designViews(), boards: List<ProjectBoards>? = designBoards) = ViewChoices(
        views, boards,
        EditorOptions(
            types = listOf("Epic", "Feature", "User Story", "Bug", "Task"),
            states = listOf("New", "Active", "Resolved"),
            people = listOf(anna.name, marco.name),
            repositories = listOf("backend", "payments"),
        ),
    )

    private fun picker(name: String, boards: List<ProjectBoards>? = designBoards, views: ViewSet = designViews()) {
        compose.setContent {
            AdoTheme(dark = false, dynamic = false) {
                ViewPickerScreen(choices(views, boards), onOpen = { _, _, _ -> }, onNewView = {})
            }
        }
        compose.onRoot().captureRoboImage("$name.png")
    }

    @Test fun viewsPicker() = picker("views-picker")

    @Config(qualifiers = "+it")
    @Test fun viewsPickerItalian() = picker("views-picker-it")

    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    @Test fun viewsPickerPhone() = picker("views-picker-phone")

    /** The boards being read for the first time: the card keeps its size, the recent ones are there. */
    @Test fun viewsPickerLoading() = picker("views-picker-loading", boards = null)

    @Config(qualifiers = "+it")
    @Test fun viewsPickerLoadingItalian() = picker("views-picker-loading-it", boards = null)

    /** Many projects, the board open expanded. */
    @Test fun viewsPickerManyBoards() =
        picker("views-picker-many-boards", boards = manyBoards, views = designViews().openBoard(BoardRef("Contoso", "Backend")))

    private fun viewsDashboard(
        name: String,
        views: ViewSet = designViews(),
        switcher: Boolean = false,
        editor: EditorTarget? = null,
        dark: Boolean = false,
        state: SyncState = SyncState(snapshot),
        boards: List<ProjectBoards>? = designBoards,
        query: String = "",
    ) {
        compose.setContent {
            AdoTheme(dark = dark, dynamic = false) {
                DashboardScreen(
                    "contoso", state, events(), {}, {}, {}, {}, {}, now = now,
                    choices = choices(views, boards), initialSwitcher = switcher, initialSwitcherQuery = query, initialEditor = editor,
                )
            }
        }
        compose.onRoot().captureRoboImage("$name.png")
    }

    /** On a board, with many others: the recent ones, then the projects, only the open board's expanded. */
    private fun switcher(name: String, query: String = "", boards: List<ProjectBoards>? = manyBoards) {
        val board = BoardRef("Contoso", "Backend")
        val views = designViews().openBoard(BoardRef("Payments", "Gateway")).openBoard(BoardRef("Interno", "Infrastruttura")).openBoard(board)
        val state = SyncState(snapshot.copy(shown = Shown(views.current.queryKey, snapshot.workItems, snapshot.reviewPrs, snapshot.myPrs)))
        viewsDashboard(name, views = views, switcher = true, state = state, boards = boards, query = query)
    }

    @Test fun viewsSwitcher() = switcher("views-switcher")

    @Config(qualifiers = "+it")
    @Test fun viewsSwitcherItalian() = switcher("views-switcher-it")

    @Test fun viewsSwitcherSearch() = switcher("views-switcher-search", query = "front")

    @Config(qualifiers = "+it")
    @Test fun viewsSwitcherSearchItalian() = switcher("views-switcher-search-it", query = "front")

    @Test fun viewsSwitcherLoading() = switcher("views-switcher-loading", boards = null)

    @Test fun viewsEditor() = viewsDashboard("views-editor", editor = EditorTarget(DashboardView.global))

    @Config(qualifiers = "+it")
    @Test fun viewsEditorItalian() = viewsDashboard("views-editor-it", editor = EditorTarget(DashboardView.global))

    @Test fun viewsEditorDark() = viewsDashboard("views-editor-dark", editor = EditorTarget(DashboardView.global), dark = true)

    /** Opened from the pull requests panel's header: scrolled to its section. */
    @Test fun viewsEditorPullRequests() = viewsDashboard(
        "views-editor-pull-requests", editor = EditorTarget(designViews().find("bugs")!!, focus = ViewPanel.PULL_REQUESTS),
    )

    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    @Test fun viewsEditorPhone() = viewsDashboard("views-editor-phone", editor = EditorTarget(DashboardView.global))

    @Test fun dashboardBoardView() {
        val board = BoardRef("Contoso", "Backend")
        val views = designViews().openBoard(board)
        val view = views.current
        val state = SyncState(snapshot.copy(shown = Shown(view.queryKey, snapshot.workItems, snapshot.reviewPrs, snapshot.myPrs)))
        viewsDashboard("dashboard-board-view", views = views, state = state)
    }

    /** A view of reviews only: the work items panel is off, the others share the width. */
    @Test fun dashboardReviewsOnly() {
        val views = designViews("reviews")
        val state = SyncState(snapshot.copy(shown = Shown(views.current.queryKey, emptyList(), snapshot.reviewPrs, emptyList())))
        viewsDashboard("dashboard-reviews-only", views = views, state = state)
    }

    /** Just switched view: the panels wait for the sync of the new one. */
    @Test fun dashboardViewLoading() =
        viewsDashboard("dashboard-view-loading", views = designViews("bugs"), state = SyncState(snapshot.copy(shown = Shown("old", emptyList(), emptyList(), emptyList())), syncing = true))
}
