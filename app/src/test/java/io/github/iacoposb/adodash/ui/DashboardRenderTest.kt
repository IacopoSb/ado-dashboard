package io.github.iacoposb.adodash.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import io.github.iacoposb.adodash.api.FieldChange
import io.github.iacoposb.adodash.api.Iteration
import io.github.iacoposb.adodash.api.Pipeline
import io.github.iacoposb.adodash.api.LinkKind
import io.github.iacoposb.adodash.api.Person
import io.github.iacoposb.adodash.api.StateInfo
import io.github.iacoposb.adodash.api.WiFields
import io.github.iacoposb.adodash.api.WorkItemDetail
import io.github.iacoposb.adodash.api.WorkItemLink
import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.anna
import io.github.iacoposb.adodash.Fakes.marco
import io.github.iacoposb.adodash.Fakes.me
import io.github.iacoposb.adodash.Fakes.now
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.api.BuildState
import io.github.iacoposb.adodash.api.FailedTest
import io.github.iacoposb.adodash.api.Reviewer
import io.github.iacoposb.adodash.data.AuthMethod
import io.github.iacoposb.adodash.sync.BuildSignal
import io.github.iacoposb.adodash.sync.BuildStep
import io.github.iacoposb.adodash.sync.PrSignals
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
import io.github.iacoposb.adodash.api.TeamIteration
import io.github.iacoposb.adodash.sync.BacklogFilter
import io.github.iacoposb.adodash.sync.Shown
import io.github.iacoposb.adodash.sync.Sprint
import io.github.iacoposb.adodash.sync.SprintGroup
import io.github.iacoposb.adodash.sync.SprintItem
import io.github.iacoposb.adodash.sync.TeamInfo
import io.github.iacoposb.adodash.data.Settings
import io.github.iacoposb.adodash.errorText
import io.github.iacoposb.adodash.sync.CatalogState
import io.github.iacoposb.adodash.sync.ResourceSyncTexts
import io.github.iacoposb.adodash.sync.Snapshot
import io.github.iacoposb.adodash.sync.SyncState
import io.github.iacoposb.adodash.ui.components.PrRow
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

    /** The design's builds: 1 failed, 1 running, 6 ok, the latest first. */
    private val failedBuild = Fakes.build(981, BuildState.FAILED, prId = 305, ago = 1090).copy(
        number = "20261016.3", startedAt = now.minusSeconds(1080), finishedAt = now.minusSeconds(680),
    )
    private val pipeline = listOf(
        failedBuild,
        Fakes.build(980, BuildState.RUNNING, prId = 318, ago = 1200),
    ) + (1..6).map { Fakes.build(970 + it, BuildState.SUCCEEDED, definition = it, repoId = "repo-$it", ago = 1300L + it * 600) }

    private val designPrs = listOf(
        Fakes.pr(311, anna, "Retry handling for payment gateway calls", listOf(Reviewer(me.id, me.name, Vote.NONE, true))),
        Fakes.pr(305, marco, "Refactor the authentication module",
            listOf(Reviewer(me.id, me.name, Vote.WAITING, false)), draft = true, ago = 200_000),
        Fakes.pr(318, me, "Dashboard: filter by area path", listOf(
            Reviewer(anna.id, anna.name, Vote.APPROVED, true),
            Reviewer(marco.id, marco.name, Vote.WAITING, false),
        ), ago = 3000),
        Fakes.pr(320, me, "Update the payments SDK to 4.2", listOf(
            Reviewer(anna.id, anna.name, Vote.APPROVED, true),
            Reviewer("dddddddd-1111-2222-3333-444444444444", "Luca Rossi", Vote.APPROVED_WITH_SUGGESTIONS, false),
        ), ago = 5000).copy(repoName = "payments", sourceBranch = "chore/sdk-4.2"),
    )

    private val signals = mapOf(
        311 to PrSignals(BuildSignal.SUCCEEDED, activeThreads = 2),
        305 to PrSignals(BuildSignal.FAILED, activeThreads = 1, conflicts = true),
        318 to PrSignals(BuildSignal.SUCCEEDED, activeThreads = 1),
        320 to PrSignals(BuildSignal.SUCCEEDED, activeThreads = 0, readyToComplete = true),
    )

    private val snapshot = Snapshot(
        me = me,
        workItems = listOf(
            Fakes.workItem(4812, "Bug", "SSO sign-in fails after the token is renewed", "Active", 1, 1800),
            Fakes.workItem(4790, "User Story", "CSV export of the monthly reports", "New", 2, 7200),
            Fakes.workItem(4755, "Task", "Update the build dependencies", "Active", 3, 90_000),
            Fakes.workItem(4701, "Feature", "Push notifications for customers", "In Progress", 2, 300_000),
        ),
        reviewPrs = designPrs.take(2),
        myPrs = designPrs.drop(2),
        syncedAt = now,
        signals = signals,
    ).let { it.copy(shown = Shown(DashboardView.global.queryKey, it.workItems, it.reviewPrs, it.myPrs, pipeline = pipeline)) }

    /** Titles in the language of the test, as a sync would store them. */
    private fun events() = ResourceSyncTexts(context).run {
        listOf(
            Event("1", EventKind.MENTION, prMention(anna.name, 311), "Retry handling\n@Iacopo Sbalchiero is exponential backoff fine here?", "", now.minusSeconds(240).toEpochMilli()),
            Event(
                "build:981:failed", EventKind.BUILD_FAILED, buildFailed(305),
                buildFailedBody("backend-ci", "20261016.3", 2), "", now.minusSeconds(1080).toEpochMilli(), project = "Contoso",
            ),
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
        build: BuildDetail? = null,
        workItem: WorkItemModal? = null,
        workItemMenu: String? = null,
        workItemMenuQuery: String = "",
        /** The work item page scrolled to its end: the comments. */
        scrollWorkItem: Boolean = false,
    ) {
        compose.setContent {
            AdoTheme(dark = dark, dynamic = false) {
                DashboardScreen(
                    "contoso", state, events, {}, {}, {}, {}, {}, now = now, updateVersion = update, buildDetail = build,
                    workItem = workItem, workItemMenu = workItemMenu, workItemMenuQuery = workItemMenuQuery,
                )
            }
        }
        if (scrollWorkItem) {
            compose.onNodeWithTag(COMMENTS_TAG, useUnmergedTree = true).performScrollTo()
            compose.waitForIdle()
        }
        // A field's menu is a popup: the whole screen, its windows included
        if (workItemMenu != null) captureScreenRoboImage("$name.png") else compose.onRoot().captureRoboImage("$name.png")
    }

    /**
     * Releases as folders of two-week sprints, listed as the API does (root first, depth first):
     * Sprint 42 is the current one, Release 2 is over, the backlog has no dates.
     */
    private val designIterations = run {
        fun day(d: String) = java.time.Instant.parse("${d}T00:00:00Z")
        fun sprint(release: String, n: Int, start: String, finish: String) =
            Iteration("Sprint $n", "Contoso\\$release\\Sprint $n", day(start), day(finish))
        fun folder(name: String) = Iteration(name, "Contoso\\$name")
        listOf(
            Iteration("Contoso", "Contoso"),
            folder("Backlog"),
            folder("Release 2"),
            sprint("Release 2", 38, "2026-08-17", "2026-08-28"),
            sprint("Release 2", 39, "2026-08-31", "2026-09-11"),
            folder("Release 3"),
            sprint("Release 3", 40, "2026-09-14", "2026-09-25"),
            sprint("Release 3", 41, "2026-09-28", "2026-10-09"),
            sprint("Release 3", 42, "2026-10-12", "2026-10-23"),
            sprint("Release 3", 43, "2026-10-26", "2026-11-06"),
            folder("Release 4"),
            sprint("Release 4", 44, "2026-11-09", "2026-11-20"),
            sprint("Release 4", 45, "2026-11-23", "2026-12-04"),
        )
    }

    /** The design's bug: description, a pull request and its parent, three comments, every field. */
    private fun designWorkItem(
        readOnly: Boolean = false,
        fieldError: FieldError? = null,
        iteration: String = "Contoso\\Release 3\\Sprint 42",
    ): WorkItemModal {
        val title = if (italian) "Il login con SSO fallisce dopo il rinnovo del token" else "SSO sign-in fails after the token is renewed"
        val item = Fakes.workItem(4812, "Bug", title, "Active", 1, 1800, area = "Contoso\\Backend")
        val description = if (italian) {
            "<div>Dopo circa un'ora il refresh token viene rifiutato con <b>AADSTS700084</b> e l'utente torna alla pagina di login. " +
                "Succede solo con gli account guest.</div><ul><li>Accedi con <i>guest01</i></li><li>Aspetta un'ora</li></ul>" +
                "<div>Log: <a href=\"https://contoso.example/logs/4812\">contoso.example/logs/4812</a></div>"
        } else {
            "<div>After about an hour the refresh token is refused with <b>AADSTS700084</b> and the user is sent back to the sign-in page. " +
                "It only happens with guest accounts.</div><ul><li>Sign in as <i>guest01</i></li><li>Wait an hour</li></ul>" +
                "<div>Logs: <a href=\"https://contoso.example/logs/4812\">contoso.example/logs/4812</a></div>"
        }
        val detail = WorkItemDetail(
            item, rev = 7, descriptionHtml = description, assignee = Person(me.id, me.name, "iacopo@contoso.com"),
            iterationPath = iteration, remainingWork = 3.0, changedBy = anna.name, commentCount = 3,
            links = listOf(WorkItemLink(LinkKind.PULL_REQUEST, 311, ""), WorkItemLink(LinkKind.PARENT, 4790, "")),
        )
        val links = listOf(
            LinkRow(LinkKind.PULL_REQUEST, 311, title = if (italian) "Gestione retry sulle chiamate al gateway" else "Retry handling for payment gateway calls", prStatus = "active"),
            LinkRow(LinkKind.PARENT, 4790, "User Story", if (italian) "Esportazione CSV dei report mensili" else "CSV export of the monthly reports", "New"),
        )
        val page = WorkItemPage(
            item.id, item, saved = detail, links = links, fieldError = fieldError,
            states = listOf("New", "Active", "Resolved", "Closed", "Removed").map { StateInfo(it, "") },
            iterations = designIterations,
            areas = Fakes.projects.first(),
            hasRemainingWork = true,
            comments = designComments(),
        )
        return WorkItemModal(listOf(page), readOnly = readOnly, me = Person(me.id, me.name, ""))
    }

    private val luca = io.github.iacoposb.adodash.api.Identity("dddddddd-1111-2222-3333-444444444444", "Luca Rossi")
    private fun mentionOf(id: String, name: String) = "<a href=\"#\" data-vss-mention=\"version:2.0,$id\">@$name</a>"

    /** The design's thread: Anna's note, Marco's (edited) with code, Luca's mention of me last. */
    private fun designComments(): CommentsState {
        val it = italian
        fun c(id: Int, who: io.github.iacoposb.adodash.api.Identity, html: String, ago: Long, edited: Boolean = false) =
            io.github.iacoposb.adodash.api.Comment(id, 4812, who, html, now.minusSeconds(ago), edited)
        return CommentsState(
            loaded = true,
            total = 3,
            items = listOf(
                c(
                    1, anna,
                    if (it) "Riprodotto in staging: il token scade dopo <b>60 minuti</b> esatti." else "Reproduced on staging: the token expires after exactly <b>60 minutes</b>.",
                    7200,
                ),
                c(
                    2, marco,
                    if (it) "Nei log c'è <code>AADSTS700084</code>, vedi <a href=\"https://contoso.example/logs/4812\">il log</a>."
                    else "The logs show <code>AADSTS700084</code>, see <a href=\"https://contoso.example/logs/4812\">the log</a>.",
                    3000, edited = true,
                ),
                c(
                    3, luca,
                    "${mentionOf(me.id, me.name)} " + if (it) "riesci a riprodurlo con l'account di test guest01?" else "can you reproduce it with the guest01 test account?",
                    240,
                ),
            ),
        )
    }

    /** The design's modal with something done to its comments. */
    private fun withComments(change: (WorkItemPage) -> WorkItemPage) = designWorkItem().let { m -> m.copy(pages = listOf(change(m.page))) }

    @Test fun workItemComments() = dashboard("work-item-comments", dark = false, workItem = designWorkItem(), scrollWorkItem = true)

    @Config(qualifiers = "+it")
    @Test fun workItemCommentsItalian() = dashboard("work-item-comments-it", dark = false, workItem = designWorkItem(), scrollWorkItem = true)

    @Test fun workItemCommentsDark() = dashboard("work-item-comments-dark", dark = true, workItem = designWorkItem(), scrollWorkItem = true)

    /** "@an" typed: the people offered above the composer, those of the thread first. */
    @Test fun workItemMentionSuggestions() = dashboard(
        "work-item-mention-suggestions", dark = false,
        workItem = withComments { it.copy(composer = ComposerState(Draft("Thanks, @an"), 1)) }.copy(
            mentions = PeopleSearch(
                "an",
                results = listOf(
                    Person(anna.id, anna.name, "anna.verdi@contoso.com"),
                    Person("eeee", "Annabel Neri", "annabel.neri@contoso.com"),
                    Person("ffff", "Giovanni Bianchi", "giovanni.bianchi@contoso.com"),
                ),
            ),
        ),
    )

    /** Just sent: the comment at the bottom of the thread, "Sending…", the composer empty. */
    @Test fun workItemCommentSending() = dashboard(
        "work-item-comment-sending", dark = false, scrollWorkItem = true,
        workItem = withComments { p ->
            val draft = Draft("@Luca Rossi yes, with guest01 it fails after an hour.", listOf(Person(luca.id, luca.name, "")))
            p.copy(comments = p.comments.copy(sending = listOf(SendingComment(1, draft, CommentMarkup.build(draft), now))))
        },
    )

    /** Refused: the text back in the composer, with the permission hint. */
    @Test fun workItemCommentError() = dashboard(
        "work-item-comment-error", dark = false, scrollWorkItem = true,
        workItem = withComments { p ->
            val draft = Draft("@Luca Rossi yes, with guest01 it fails after an hour.", listOf(Person(luca.id, luca.name, "")))
            p.copy(composer = ComposerState(draft, 2), comments = p.comments.copy(sendError = CommentError(SaveProblem.DENIED, "HTTP 403")))
        },
    )

    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    @Test fun workItemPhoneComments() = dashboard("work-item-phone-comments", dark = false, workItem = designWorkItem(), scrollWorkItem = true)

    @Test fun workItemModal() = dashboard("work-item-modal", dark = false, workItem = designWorkItem())

    @Config(qualifiers = "+it")
    @Test fun workItemModalItalian() = dashboard("work-item-modal-it", dark = false, workItem = designWorkItem())

    @Test fun workItemModalDark() = dashboard("work-item-modal-dark", dark = true, workItem = designWorkItem())

    @Test fun workItemModalStateMenu() =
        dashboard("work-item-modal-state-menu", dark = false, workItem = designWorkItem(), workItemMenu = WiFields.STATE)

    /** The iterations as a tree: the way to the current sprint open, with the dates. */
    @Test fun workItemIterationPicker() = dashboard(
        "work-item-iteration-picker", dark = false, workItem = designWorkItem(), workItemMenu = WiFields.ITERATION,
    )

    @Config(qualifiers = "+it")
    @Test fun workItemIterationPickerItalian() = dashboard(
        "work-item-iteration-picker-it", dark = false, workItem = designWorkItem(), workItemMenu = WiFields.ITERATION,
    )

    /** A sprint that is over selected: its past sprints open, the others closed. */
    @Test fun workItemIterationPickerPast() = dashboard(
        "work-item-iteration-picker-past", dark = false, workItem = designWorkItem(iteration = "Contoso\\Release 3\\Sprint 40"),
        workItemMenu = WiFields.ITERATION,
    )

    /** Searching: the matches highlighted with the folders that lead to them. */
    @Test fun workItemIterationPickerSearch() = dashboard(
        "work-item-iteration-picker-search", dark = false, workItem = designWorkItem(),
        workItemMenu = WiFields.ITERATION, workItemMenuQuery = "Sprint 4",
    )

    @Test fun workItemAreaPicker() = dashboard(
        "work-item-area-picker", dark = false, workItem = designWorkItem(), workItemMenu = WiFields.AREA,
    )

    /** A change refused because someone else saved first: the saved value again, with the reason. */
    @Test fun workItemModalError() = dashboard(
        "work-item-modal-error", dark = false,
        workItem = designWorkItem(fieldError = FieldError(WiFields.STATE, SaveProblem.CONFLICT)).let { m ->
            m.copy(pages = listOf(m.page.copy(pending = mapOf(WiFields.PRIORITY to FieldChange.Priority(2)))))
        },
    )

    /** Changes refused for lack of permission: read-only, with the hint. */
    @Test fun workItemModalReadOnly() = dashboard(
        "work-item-modal-read-only", dark = false,
        workItem = designWorkItem(readOnly = true, fieldError = FieldError(WiFields.STATE, SaveProblem.DENIED)),
    )

    /** Opened from the row: the row's data while the rest is read. */
    @Test fun workItemModalLoading() = dashboard(
        "work-item-modal-loading", dark = false,
        workItem = WorkItemModal(listOf(WorkItemPage(4812, snapshot.workItems.first()))),
    )

    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    @Test fun workItemPhone() = dashboard("work-item-phone", dark = false, workItem = designWorkItem())

    /** A path too long for the field: the nodes before the last left out from the start. */
    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    @Test fun workItemPhoneLongPath() = dashboard(
        "work-item-phone-long-path", dark = false,
        workItem = designWorkItem().let { m ->
            val page = m.page
            val path = "Contoso\\Programme 2026\\Release 3\\Hardening\\Sprint 42"
            m.copy(pages = listOf(page.copy(saved = page.saved!!.copy(iterationPath = path))))
        },
    )

    @Config(qualifiers = "it-w411dp-h891dp-xxhdpi")
    @Test fun workItemPhoneItalian() = dashboard("work-item-phone-it", dark = false, workItem = designWorkItem())

    /** The build of the design's modal: stages, two failed tests, rerun offered. */
    private fun buildDetail(): BuildDetail {
        fun step(name: String, state: BuildState, start: Long, seconds: Long, skipped: Boolean = false) = BuildStep(
            name, state, skipped,
            if (skipped) null else now.minusSeconds(start), if (skipped) null else now.minusSeconds(start - seconds),
        )
        val names = if (italian) listOf("Checkout", "Ripristino dipendenze", "Compilazione", "Test unitari", "Pubblicazione artefatti")
        else listOf("Checkout", "Restore dependencies", "Build", "Unit tests", "Publish artifacts")
        return BuildDetail(
            "Contoso", failedBuild.id, failedBuild,
            steps = listOf(
                step(names[0], BuildState.SUCCEEDED, 1080, 8),
                step(names[1], BuildState.SUCCEEDED, 1072, 72),
                step(names[2], BuildState.SUCCEEDED, 1000, 151),
                step(names[3], BuildState.FAILED, 849, 169),
                step(names[4], BuildState.CANCELED, 0, 0, skipped = true),
            ),
            tests = TestsState.Loaded(
                listOf(
                    FailedTest("TokenRefreshTest.refreshesGuestAccount", "expected 200 but was 401"),
                    FailedTest("SessionStoreTest.keepsSessionAfterRotation", "NullPointerException at SessionStore.kt:57"),
                ),
                total = 2,
            ),
        )
    }

    @Test fun buildModal() = dashboard("build-modal", dark = false, build = buildDetail())

    @Config(qualifiers = "+it")
    @Test fun buildModalItalian() = dashboard("build-modal-it", dark = false, build = buildDetail())

    @Test fun buildModalDark() = dashboard("build-modal-dark", dark = true, build = buildDetail())

    /** A build without failed tests: the stages take the whole width, long names on two lines. */
    private fun buildDetailNoTests(): BuildDetail {
        val build = Fakes.build(982, BuildState.SUCCEEDED, ago = 1500)
        fun step(name: String, start: Long, seconds: Long) =
            BuildStep(name, BuildState.SUCCEEDED, false, now.minusSeconds(start), now.minusSeconds(start - seconds))
        val names = if (italian) listOf(
            "Checkout", "Ripristino dipendenze",
            "Analisi vulnerabilità (Grype, fail-on medium) sull'immagine del container e sulle dipendenze",
            "Compilazione", "Test unitari e di integrazione con copertura",
            "Pubblicazione dell'immagine nel registro aziendale, firma con cosign e attestazione SBOM per la catena di fornitura del software",
        ) else listOf(
            "Checkout", "Restore dependencies",
            "Scan vulnerabilities (Grype, fail-on medium) in the container image and its dependencies",
            "Build", "Unit and integration tests with coverage",
            "Publish the image to the company registry, sign it with cosign and attach the SBOM attestation for the software supply chain",
        )
        return BuildDetail(
            "Contoso", build.id, build,
            steps = listOf(
                step(names[0], 1490, 6), step(names[1], 1484, 64), step(names[2], 1420, 362),
                step(names[3], 1300, 140), step(names[4], 1160, 312), step(names[5], 848, 41),
            ),
            tests = TestsState.Loaded(emptyList(), total = 0),
        )
    }

    @Test fun buildModalNoTests() = dashboard("build-modal-no-tests", dark = false, build = buildDetailNoTests())

    @Config(qualifiers = "+it")
    @Test fun buildModalNoTestsItalian() = dashboard("build-modal-no-tests-it", dark = false, build = buildDetailNoTests())

    /** Opened from an event: the build is being read. */
    @Test fun buildModalLoading() = dashboard("build-modal-loading", dark = false, build = BuildDetail("Contoso", 981))

    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    @Test fun buildModalPhone() = dashboard("build-modal-phone", dark = false, build = buildDetail())

    /**
     * The pull request rows at the width of a dashboard column: without signals the vote sits
     * on the details line, with signals on their first line, also when they wrap.
     */
    private fun prRows(name: String, width: Int = 420, dark: Boolean = false) {
        val reviewer = listOf(Reviewer(me.id, me.name, Vote.NONE, true))
        val reviewers = listOf(
            Reviewer(anna.id, anna.name, Vote.APPROVED, true),
            Reviewer(marco.id, marco.name, Vote.WAITING, false),
            Reviewer("dddddddd-1111-2222-3333-444444444444", "Luca Rossi", Vote.APPROVED_WITH_SUGGESTIONS, false),
        )
        val rows = listOf(
            // To review, no signals
            Triple(Fakes.pr(311, anna, "Retry handling for payment gateway calls", reviewer), true, null),
            // Mine, no signals
            Triple(Fakes.pr(318, me, "Dashboard: filter by area path", reviewers, ago = 3000), false, null),
            // To review, signals and vote
            Triple(Fakes.pr(305, marco, "Refactor the authentication module", reviewer, draft = true), true,
                PrSignals(BuildSignal.SUCCEEDED, activeThreads = 2)),
            // Mine, signals and avatars
            Triple(Fakes.pr(320, me, "Update the payments SDK to 4.2", reviewers), false,
                PrSignals(BuildSignal.SUCCEEDED, activeThreads = 0, readyToComplete = true)),
            // Many signals: they wrap, the vote stays on the first line
            Triple(Fakes.pr(330, anna, "Move the session store to the shared cache", reviewer), true,
                PrSignals(BuildSignal.FAILED, activeThreads = 12, conflicts = true)),
            Triple(Fakes.pr(331, me, "Split the notification worker", reviewers), false,
                PrSignals(BuildSignal.RUNNING, activeThreads = 3, conflicts = true)),
        )
        compose.setContent {
            AdoTheme(dark = dark, dynamic = false) {
                Column(
                    Modifier.width(width.dp).background(AdoTheme.colors.surface),
                ) {
                    rows.forEach { (pr, reviewing, s) ->
                        PrRow(pr, me, reviewing, now, {}, signals = s)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("$name.png")
    }

    @Test fun prRows() = prRows("pr-rows")

    @Config(qualifiers = "+it")
    @Test fun prRowsItalian() = prRows("pr-rows-it")

    @Test fun prRowsDark() = prRows("pr-rows-dark", dark = true)

    /** Narrow: more signals wrap. */
    @Test fun prRowsNarrow() = prRows("pr-rows-narrow", width = 320)

    /** Without Build (read): no tile, the PR rows without a build. */
    @Test fun tabletNoBuilds() = dashboard(
        "dashboard-tablet-no-builds", dark = false,
        SyncState(snapshot.copy(buildsDenied = true, signals = signals.mapValues { it.value.copy(build = BuildSignal.NONE) })),
    )

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

    /** A PAT without Build (read): the hint under the account. */
    @Test fun settingsPatNoBuilds() =
        signedIn("settings-account-pat-no-builds", section = SettingsSection.ACCOUNT, pat = true, buildsDenied = true)

    @Config(qualifiers = "+it")
    @Test fun settingsPatNoBuildsItalian() =
        signedIn("settings-account-pat-no-builds-it", section = SettingsSection.ACCOUNT, pat = true, buildsDenied = true)

    /** A PAT refused a work item change: the hint to add Work Items (Read & write). */
    @Test fun settingsPatReadOnly() = signedIn(
        "settings-account-pat-read-only", section = SettingsSection.ACCOUNT, pat = true, buildsDenied = true, workItemsReadOnly = true,
    )

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

    private fun signedIn(
        name: String,
        section: SettingsSection?,
        pat: Boolean = false,
        buildsDenied: Boolean = false,
        workItemsReadOnly: Boolean = false,
    ) {
        compose.setContent {
            AdoTheme(dark = false, dynamic = false) {
                SettingsScreen(
                    Settings(
                        organization = "contoso", meId = "u1", meName = "Iacopo Sbalchiero",
                        authMethod = if (pat) AuthMethod.PAT else AuthMethod.ENTRA, pat = if (pat) "x" else "",
                    ),
                    busy = false, message = null, {}, { _, _ -> }, {}, {}, onBack = {},
                    version = "0.0.2", initialSection = section, buildsDenied = buildsDenied, workItemsReadOnly = workItemsReadOnly,
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
        val state = SyncState(
            snapshot.copy(
                shown = Shown(
                    view.queryKey, snapshot.workItems, snapshot.reviewPrs, snapshot.myPrs, pipeline = pipeline, pipelineProject = "Contoso",
                    sprint = designSprint(),
                ),
            ),
        )
        viewsDashboard("dashboard-board-view", views = views, state = state)
    }

    // --- Sprint backlog ---

    /**
     * The design's sprint: Sprint 42 of Contoso › Backend, 12 to 23 October (6 working days
     * left on Friday 16), stories, bugs, a chore and a doc, a closed bug hidden by default.
     */
    private fun designSprint(): Sprint {
        val it = italian
        val categories = mapOf(
            "New" to SprintItem.PROPOSED, "Active" to SprintItem.IN_PROGRESS, "Blocked" to SprintItem.IN_PROGRESS,
            "Resolved" to SprintItem.RESOLVED, "Closed" to SprintItem.COMPLETED,
        )
        var rank = 0.0
        fun item(
            id: Int, type: String, title: String, state: String, who: io.github.iacoposb.adodash.api.Identity?, hours: Double? = null,
        ) = SprintItem(
            Fakes.workItem(id, type, title, state).copy(assignedTo = who?.name), who?.id, hours, ++rank, categories[state],
        )
        fun group(parent: SprintItem, vararg children: SprintItem) = SprintGroup(parent, children.toList())
        val groups = listOf(
            group(
                item(4790, "User Story", if (it) "Esportazione CSV dei report mensili" else "CSV export of the monthly reports", "Active", me),
                item(4791, "Task", if (it) "Endpoint di esportazione" else "Export endpoint", "Closed", me),
                item(4792, "Task", if (it) "Formato CSV con separatore configurabile" else "CSV format with a configurable separator", "Active", me, 5.0),
                item(4793, "Task", if (it) "Test di integrazione" else "Integration tests", "New", marco, 3.0),
            ),
            group(item(4812, "Bug", if (it) "Il login con SSO fallisce dopo il rinnovo del token" else "SSO sign-in fails after the token is renewed", "Active", me, 3.0)),
            group(
                item(4777, "User Story", if (it) "Filtro per area path nella dashboard" else "Filter by area path in the dashboard", "Active", anna),
                item(4778, "Task", if (it) "Albero delle aree" else "Area tree", "Closed", anna),
                item(4779, "Task", if (it) "Ricerca nelle aree" else "Search in the areas", "Closed", anna),
                item(4780, "Task", if (it) "Filtro nelle query" else "Filter in the queries", "Active", anna, 4.0),
                item(4781, "Task", if (it) "Screenshot" else "Screenshots", "New", me, 2.0),
            ),
            group(
                item(4755, "Chore", if (it) "Aggiornare le dipendenze di build" else "Update the build dependencies", "New", me),
                item(4756, "Task", "Gradle 9", "New", me, 1.0),
                item(4757, "Task", "AGP 9", "New", anna, 1.0),
            ),
            group(item(4770, "Documentation", if (it) "Guida alla registrazione dell'app in Entra" else "Guide to registering the app in Entra", "Active", luca, 4.0)),
            group(
                item(4769, "User Story", if (it) "Aggiornamento SDK pagamenti a 4.2" else "Payments SDK update to 4.2", "Resolved", marco),
                item(4771, "Task", "SDK 4.2", "Closed", marco),
                item(4772, "Task", if (it) "Migrazione delle chiamate" else "Migrate the calls", "Closed", marco),
                item(4773, "Task", if (it) "Test end-to-end" else "End-to-end tests", "Closed", me),
            ),
            group(item(4801, "Bug", if (it) "Retry infinito se il gateway risponde 503" else "Infinite retry when the gateway answers 503", "Blocked", anna, 5.0)),
            group(item(4760, "Bug", if (it) "Crash all'avvio senza rete" else "Crash at launch without a network", "Closed", me)),
        )
        fun day(d: String) = java.time.Instant.parse("${d}T00:00:00Z")
        return Sprint(
            BoardRef("Contoso", "Backend"),
            TeamIteration("it-42", "Sprint 42", "Contoso\\Release 3\\Sprint 42", day("2026-10-12"), day("2026-10-23")),
            groups,
        )
    }

    private fun backlog(
        name: String,
        filter: BacklogFilter = BacklogFilter(),
        expanded: Set<Int> = setOf(4790),
        dark: Boolean = false,
    ) {
        val views = designViews().openBoard(BoardRef("Contoso", "Backend"))
        val shown = Shown(
            views.current.queryKey, snapshot.workItems, snapshot.reviewPrs, snapshot.myPrs, pipeline = pipeline, pipelineProject = "Contoso",
            sprint = designSprint(),
        )
        compose.setContent {
            AdoTheme(dark = dark, dynamic = false) {
                DashboardScreen(
                    "contoso", SyncState(snapshot.copy(shown = shown)), events(), {}, {}, {}, {}, {}, now = now,
                    choices = choices(views), initialBacklog = true, backlogFilter = filter, backlogExpanded = expanded,
                )
            }
        }
        compose.onRoot().captureRoboImage("$name.png")
    }

    @Test fun sprintBacklog() = backlog("sprint-backlog")

    @Config(qualifiers = "+it")
    @Test fun sprintBacklogItalian() = backlog("sprint-backlog-it")

    @Test fun sprintBacklogDark() = backlog("sprint-backlog-dark", dark = true)

    /** Mine: my items with all their children, the others with only mine. */
    @Test fun sprintBacklogMine() = backlog("sprint-backlog-mine", BacklogFilter(mine = true), expanded = setOf(4790, 4777, 4755))

    @Test fun sprintBacklogCollapsed() = backlog("sprint-backlog-collapsed", expanded = emptySet())

    @Config(qualifiers = "it-w411dp-h891dp-xxhdpi")
    @Test fun sprintPhone() = backlog("sprint-phone")

    /** A view of reviews only: the work items panel is off, the others share the width. */
    @Test fun dashboardReviewsOnly() {
        val views = designViews("reviews")
        val state = SyncState(snapshot.copy(shown = Shown(views.current.queryKey, emptyList(), snapshot.reviewPrs, emptyList())))
        viewsDashboard("dashboard-reviews-only", views = views, state = state)
    }

    /** Contoso's pipelines as in Azure DevOps' "All" view: three folders, one nested, and three at the root. */
    private val designPipelines: List<Pipeline> = run {
        fun pipeline(id: Int, name: String, folder: String, latest: io.github.iacoposb.adodash.api.Build?) =
            Pipeline(id, name, folder, "Contoso", latest?.copy(definitionId = id, definitionName = name), "https://dev.azure.com/contoso/Contoso/_build?definitionId=$id")
        listOf(
            pipeline(5, "backend-ci", "\\Backend", failedBuild),
            pipeline(6, "backend-nightly", "\\Backend", Fakes.build(960, ago = 30_000, requestedBy = anna.name)),
            pipeline(7, "backend-deploy", "\\Backend\\Release", Fakes.build(959, BuildState.RUNNING, ago = 200, requestedBy = me.name)),
            pipeline(8, "web-ci", "\\Frontend", Fakes.build(958, prId = 318, ago = 3000, requestedBy = me.name)),
            pipeline(9, "web-e2e", "\\Frontend", Fakes.build(957, BuildState.PARTIAL, ago = 9000)),
            pipeline(10, "android-ci", "\\Mobile", Fakes.build(956, BuildState.CANCELED, ago = 90_000)),
            pipeline(11, "ios-ci", "\\Mobile", Fakes.build(955, BuildState.QUEUED, ago = 60)),
            pipeline(12, "docs", "\\", Fakes.build(954, ago = 7200, requestedBy = anna.name)),
            pipeline(13, "infra-terraform", "\\", Fakes.build(953, ago = 400_000, branch = "infra/main")),
            pipeline(14, "legacy-import", "\\", null),
        )
    }

    /** backend-ci's latest ten runs. */
    private val designRuns = listOf(
        failedBuild,
        Fakes.build(980, BuildState.RUNNING, prId = 318, ago = 1200, requestedBy = me.name),
        Fakes.build(979, ago = 3600),
        Fakes.build(978, prId = 311, ago = 7000, requestedBy = anna.name),
        Fakes.build(977, BuildState.FAILED, prId = 305, ago = 9000),
        Fakes.build(976, ago = 20_000),
        Fakes.build(975, BuildState.CANCELED, prId = 311, ago = 40_000, requestedBy = anna.name),
        Fakes.build(974, ago = 90_000),
        Fakes.build(973, BuildState.PARTIAL, ago = 100_000, requestedBy = me.name),
        Fakes.build(972, ago = 200_000),
    ).map { it.copy(definitionId = 5, definitionName = "backend-ci") }

    /** On the Contoso › Backend board, its pipelines open over it: the team's folder open, backend-ci with its runs. */
    private fun pipelines(
        name: String,
        state: PipelinesState = PipelinesState("Contoso", designPipelines, runs = mapOf(5 to RunsState.Loaded(designRuns)), team = "Backend"),
        query: String = "",
        build: BuildDetail? = null,
        dark: Boolean = false,
    ) {
        val views = designViews().openBoard(BoardRef("Contoso", "Backend"))
        val shown = Shown(views.current.queryKey, snapshot.workItems, snapshot.reviewPrs, snapshot.myPrs, pipeline = pipeline, pipelineProject = "Contoso")
        compose.setContent {
            AdoTheme(dark = dark, dynamic = false) {
                DashboardScreen(
                    "contoso", SyncState(snapshot.copy(shown = shown)), events(), {}, {}, {}, {}, {}, now = now,
                    choices = choices(views), buildDetail = build, pipelines = state,
                    pipelinesOpen = setOf(5), pipelinesQuery = query,
                )
            }
        }
        compose.onRoot().captureRoboImage("$name.png")
    }

    @Test fun pipelinesModal() = pipelines("pipelines-modal")

    @Config(qualifiers = "+it")
    @Test fun pipelinesModalItalian() = pipelines("pipelines-modal-it")

    @Test fun pipelinesModalDark() = pipelines("pipelines-modal-dark", dark = true)

    /** Searching: the matches in their folders, all open. */
    @Test fun pipelinesModalSearch() = pipelines("pipelines-modal-search", query = "ci")

    /** A run tapped: its details in place of the list, with the way back. */
    @Test fun pipelinesModalRun() = pipelines("pipelines-modal-run", build = buildDetail())

    /** Opened for the first time: the pipelines being read. */
    @Test fun pipelinesModalLoading() = pipelines("pipelines-modal-loading", state = PipelinesState("Contoso", refreshing = true))

    /** A PAT without Build (read). */
    @Test fun pipelinesModalDenied() = pipelines("pipelines-modal-denied", state = PipelinesState("Contoso", denied = true))

    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    @Test fun pipelinesPhone() = pipelines("pipelines-phone")

    /** Just switched view: the panels wait for the sync of the new one. */
    @Test fun dashboardViewLoading() =
        viewsDashboard("dashboard-view-loading", views = designViews("bugs"), state = SyncState(snapshot.copy(shown = Shown("old", emptyList(), emptyList(), emptyList())), syncing = true))

    // --- Pull request page ---

    /** The design's pull request: !311 by Anna, Marco approved, Luca waiting, me still to vote. */
    private fun designPr(vote: Int = Vote.NONE) = Fakes.pr(
        311, anna,
        if (italian) "Gestione retry sulle chiamate al gateway pagamenti" else "Retry handling for payment gateway calls",
        listOf(
            Reviewer(me.id, me.name, vote, required = true),
            Reviewer(marco.id, marco.name, Vote.APPROVED, required = false),
            Reviewer(luca.id, luca.name, Vote.WAITING, required = false),
        ),
    ).copy(projectId = "p-1", mergeStatus = "succeeded")

    private fun designPrScreen(
        vote: Int = Vote.NONE,
        tab: PrTab = PrTab.OVERVIEW,
        filter: ThreadFilter = ThreadFilter.ACTIVE,
        voteState: VoteState? = null,
    ): PrScreen {
        val it = italian
        val pr = designPr(vote)
        val mention = "@<${me.id}>"
        fun c(thread: Int, id: Int, who: io.github.iacoposb.adodash.api.Identity, text: String, ago: Long) =
            io.github.iacoposb.adodash.api.PrComment(thread, id, who, text, now.minusSeconds(ago), parentId = if (id == 1) 0 else 1)
        val threads = listOf(
            io.github.iacoposb.adodash.api.PrThread(
                5, "active",
                listOf(
                    c(5, 1, anna, if (it) "$mention va bene il backoff esponenziale o preferisci un numero fisso di tentativi?" else "$mention is exponential backoff fine here, or would you rather have a fixed number of attempts?", 240),
                    c(5, 2, me, if (it) "Il backoff va bene, ma con un **tetto** di 30 s." else "Backoff is fine, but with a **cap** of 30 s.", 200),
                    c(5, 3, anna, if (it) "Ok, aggiungo `maxDelay` in `RetryPolicy`." else "Ok, I'll add `maxDelay` to `RetryPolicy`.", 120),
                ),
                filePath = "/src/payments/Gateway.kt", line = 88,
            ),
            io.github.iacoposb.adodash.api.PrThread(
                6, "active",
                listOf(c(6, 1, marco, if (it) "Il timeout massimo andrebbe letto dalla configurazione." else "The maximum timeout should come from the configuration.", 240)),
                filePath = "/src/payments/RetryPolicy.kt", line = 12,
            ),
            io.github.iacoposb.adodash.api.PrThread(
                7, "fixed",
                listOf(
                    c(7, 1, luca, if (it) "Manca il test per il caso senza tentativi rimasti." else "The case with no attempts left has no test.", 7000),
                    c(7, 2, anna, if (it) "Aggiunto, grazie." else "Added, thanks.", 6000),
                ),
            ),
        )
        val policies = listOf(
            io.github.iacoposb.adodash.api.PolicyEvaluation(
                io.github.iacoposb.adodash.api.PolicyEvaluation.BUILD_POLICY, "Build", blocking = true, enabled = true,
                status = "approved", buildId = 981, displayName = "backend-ci", completedAt = now.minusSeconds(240),
            ),
            io.github.iacoposb.adodash.api.PolicyEvaluation(
                io.github.iacoposb.adodash.api.PolicyEvaluation.MIN_REVIEWERS_POLICY, "Minimum number of reviewers",
                blocking = true, enabled = true, status = "queued", minApprovers = 2,
            ),
            io.github.iacoposb.adodash.api.PolicyEvaluation(
                io.github.iacoposb.adodash.api.PolicyEvaluation.REQUIRED_REVIEWERS_POLICY, "Required reviewers",
                blocking = true, enabled = true, status = "queued", requiredReviewerIds = listOf(me.id, marco.id),
            ),
            io.github.iacoposb.adodash.api.PolicyEvaluation(
                io.github.iacoposb.adodash.api.PolicyEvaluation.COMMENTS_POLICY, "Comment requirements", blocking = true, enabled = true, status = "rejected",
            ),
            io.github.iacoposb.adodash.api.PolicyEvaluation(
                io.github.iacoposb.adodash.api.PolicyEvaluation.WORK_ITEMS_POLICY, "Work item linking", blocking = false, enabled = true, status = "approved",
            ),
        )
        val description = if (it) {
            "Aggiunge retry con **backoff esponenziale** sulle chiamate al gateway. Il numero massimo di tentativi arriva da `RetryPolicy`.\n\n- Retry solo sugli errori 5xx\n- Log di ogni tentativo\n\nChiude #4812."
        } else {
            "Adds retry with **exponential backoff** to the gateway calls. The maximum number of attempts comes from `RetryPolicy`.\n\n- Retries only on 5xx errors\n- Each attempt is logged\n\nCloses #4812."
        }
        return PrScreen(
            id = 311,
            pr = pr,
            detail = io.github.iacoposb.adodash.api.PullRequestDetail(pr, description, labels = listOf("payments"), workItemIds = listOf(4812)),
            policies = policies,
            workItems = listOf(
                Fakes.workItem(4812, "Bug", if (it) "Il login con SSO fallisce dopo il rinnovo del token" else "SSO sign-in fails after the token is renewed", "Active", 1),
            ),
            threads = PrThreadsState(loaded = true, items = threads),
            vote = voteState ?: VoteState(vote),
            names = mapOf(me.id.lowercase() to me.name, anna.id.lowercase() to anna.name),
            tab = tab,
            filter = filter,
            me = Person(me.id, me.name, ""),
        )
    }

    private fun prPage(
        name: String,
        screen: PrScreen,
        dark: Boolean = false,
        voteMenu: Boolean = false,
        expanded: Set<Int> = emptySet(),
        capture: Boolean = true,
    ) {
        compose.setContent {
            AdoTheme(dark = dark, dynamic = false) {
                DashboardScreen(
                    "contoso", SyncState(snapshot), events(), {}, {}, {}, {}, {}, now = now,
                    pullRequest = screen, prVoteMenu = voteMenu, prExpanded = expanded,
                )
            }
        }
        if (!capture) return
        // The vote menu is a popup: the whole screen, its windows included
        if (voteMenu) captureScreenRoboImage("$name.png") else compose.onRoot().captureRoboImage("$name.png")
    }

    @Test fun prOverview() = prPage("pr-overview", designPrScreen())

    @Config(qualifiers = "+it")
    @Test fun prOverviewItalian() = prPage("pr-overview-it", designPrScreen())

    @Test fun prOverviewDark() = prPage("pr-overview-dark", designPrScreen(), dark = true)

    /** A thread opened in the overview: its replies and the reply composer. */
    @Test fun prOverviewExpanded() = prPage("pr-overview-expanded", designPrScreen(), expanded = setOf(5))

    @Test fun prThreads() = prPage("pr-threads", designPrScreen(tab = PrTab.THREADS, filter = ThreadFilter.ALL))

    @Config(qualifiers = "+it")
    @Test fun prThreadsItalian() = prPage("pr-threads-it", designPrScreen(tab = PrTab.THREADS, filter = ThreadFilter.ALL))

    /** Approved: the button filled in green, the overflow with "Reset vote". */
    @Test fun prVoteMenu() = prPage("pr-vote-menu", designPrScreen(vote = Vote.APPROVED), voteMenu = true)

    /** Refused: my vote before again, with the permission hint. */
    @Test fun prVoteError() = prPage(
        "pr-vote-error",
        designPrScreen(voteState = VoteState(Vote.NONE, error = CommentError(SaveProblem.DENIED, "HTTP 403"))),
    )

    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    @Test fun prPhone() = prPage("pr-phone", designPrScreen())

    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    @Test fun prPhoneThreads() = prPage("pr-phone-threads", designPrScreen(vote = Vote.WAITING, tab = PrTab.THREADS))

    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    @Test fun prPhoneVoteMenu() = prPage("pr-phone-vote-menu", designPrScreen(vote = Vote.WAITING), voteMenu = true)

    /** A long comment collapsed with "Show all", and a reply with a code block wider than the card. */
    private fun longCommentScreen(): PrScreen {
        val base = designPrScreen(tab = PrTab.THREADS, filter = ThreadFilter.ALL)
        val long = (1..20).joinToString("\n\n") { "Point $it: the retry loop should log the attempt number and the delay before sleeping." }
        val code = "Something like this:\n\n```kotlin\nval delay = policy.backoff(attempt).coerceAtMost(policy.maxDelay).also { log.info(\"attempt ${'$'}attempt waits ${'$'}it ms before calling the gateway again\") }\nreturn client.post(req)\n```"
        val thread = io.github.iacoposb.adodash.api.PrThread(
            9, "active",
            listOf(
                io.github.iacoposb.adodash.api.PrComment(9, 1, marco, long, now.minusSeconds(600)),
                io.github.iacoposb.adodash.api.PrComment(9, 2, anna, code, now.minusSeconds(300), parentId = 1),
            ),
        )
        return base.copy(threads = base.threads.copy(items = listOf(thread)))
    }

    @Test fun prThreadsLongComment() {
        prPage("pr-threads-long-comment", longCommentScreen())
        compose.onAllNodesWithTag(CODE_BLOCK_TAG, useUnmergedTree = true)[0].performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("pr-threads-long-comment-scrolled.png")
    }

    // --- Files and commits ---

    /** Gateway.kt as in the design: lines 84-88 of the base become 84-94, the rest unchanged. */
    private val gatewayBase: List<String> = run {
        val head = listOf(
            "package com.contoso.payments", "", "import com.contoso.http.HttpClient", "import kotlinx.coroutines.delay", "",
            "class Gateway(private val client: HttpClient, private val policy: RetryPolicy) {",
        )
        val filler = (1..19).flatMap { n ->
            listOf("", "    /** Step $n of the payment flow. */", "    private fun step$n(req: ChargeRequest) = req.copy(step = $n)")
        }
        val body = (head + filler).take(83)
        body + List(83 - body.size) { "" } + listOf(
            "    suspend fun charge(req: ChargeRequest): ChargeResult {",
            "        var attempt = 0",
            "        return client.post(req)",
            "    }",
            "}",
        )
    }

    private val gatewayNew: List<String> = gatewayBase.take(85) + listOf(
        "        while (true) {",
        "            val result = runCatching { client.post(req) }",
        "            if (result.isSuccess || attempt >= policy.maxAttempts) return result.getOrThrow()",
        "            val wait = policy.backoff(attempt)",
        "            delay(wait)",
        "            attempt++",
        "        }",
    ) + gatewayBase.drop(86)

    private fun ready(old: List<String>, new: List<String>): FileDiff.Ready {
        val lines = LineDiff.diff(old, new)
        val (added, deleted) = DiffRows.counts(lines)
        return FileDiff.Ready(lines, added, deleted)
    }

    private fun designFilesScreen(
        sideBySide: Boolean = false,
        fullThreads: Boolean = false,
        newThread: io.github.iacoposb.adodash.api.FileAnchor? = null,
        tab: PrTab = PrTab.FILES,
    ): PrScreen {
        val base = designPrScreen(tab = tab)
        val gateway = "/src/payments/Gateway.kt"
        val changes = listOf(
            io.github.iacoposb.adodash.api.PrFileChange(gateway, io.github.iacoposb.adodash.api.ChangeType.EDIT, changeTrackingId = 1),
            io.github.iacoposb.adodash.api.PrFileChange("/src/payments/RetryPolicy.kt", io.github.iacoposb.adodash.api.ChangeType.ADD, changeTrackingId = 2),
            io.github.iacoposb.adodash.api.PrFileChange("/src/test/payments/GatewayTest.kt", io.github.iacoposb.adodash.api.ChangeType.ADD, changeTrackingId = 3),
            io.github.iacoposb.adodash.api.PrFileChange("/build.gradle.kts", io.github.iacoposb.adodash.api.ChangeType.EDIT, changeTrackingId = 4),
            io.github.iacoposb.adodash.api.PrFileChange("/src/payments/LegacyClient.kt", io.github.iacoposb.adodash.api.ChangeType.DELETE, changeTrackingId = 5),
        )
        val diffs = mapOf(
            gateway to ready(gatewayBase, gatewayNew),
            "/src/payments/RetryPolicy.kt" to ready(emptyList(), List(41) { "line $it" }),
            "/src/test/payments/GatewayTest.kt" to ready(emptyList(), List(58) { "line $it" }),
            "/build.gradle.kts" to ready(listOf("a", "version = \"4.1\"", "b"), listOf("a", "version = \"4.2\"", "b")),
            "/src/payments/LegacyClient.kt" to ready(List(12) { "line $it" }, emptyList()),
        )
        // In the design the thread on line 88 has its first comment only; the full one has replies
        val threads = base.threads.items.map { t ->
            if (t.id == 5 && !fullThreads) t.copy(comments = t.comments.take(1)) else t
        } + if (fullThreads) {
            listOf(
                io.github.iacoposb.adodash.api.PrThread(
                    8, "active",
                    listOf(
                        io.github.iacoposb.adodash.api.PrComment(
                            8, 1, marco,
                            if (italian) "Perché togliere la chiamata diretta?" else "Why drop the direct call?", now.minusSeconds(900),
                        ),
                    ),
                    filePath = gateway, line = 86, leftSide = true,
                ),
            )
        } else {
            emptyList()
        }
        return base.copy(
            threads = base.threads.copy(items = threads),
            files = PrFilesState(
                loaded = true, iteration = io.github.iacoposb.adodash.api.PrIteration(3, "src", "base"),
                changes = PrFileList.ordered(changes), selected = gateway, diffs = diffs,
                viewed = setOf("/build.gradle.kts"), sideBySide = sideBySide, newThread = newThread,
            ),
            commits = PrCommitsState(
                loaded = true,
                items = listOf(
                    io.github.iacoposb.adodash.api.PrCommit("3f2a91c7d0e4b5a6", if (italian) "Limite di 30 s al backoff" else "Cap the backoff at 30 s", anna.name, now.minusSeconds(1800), "https://x/1"),
                    io.github.iacoposb.adodash.api.PrCommit("9b81e0d2c3f4a5b6", if (italian) "Test del caso senza tentativi rimasti" else "Test the case with no attempts left", anna.name, now.minusSeconds(5400), "https://x/2"),
                    io.github.iacoposb.adodash.api.PrCommit("c47d2e19a0b1c2d3", if (italian) "Retry con backoff esponenziale sulle chiamate al gateway" else "Retry with exponential backoff on the gateway calls", anna.name, now.minusSeconds(7200), "https://x/3"),
                ),
            ),
            composers = if (newThread != null) {
                mapOf(PrScreen.NEW_FILE_THREAD to ComposerState(Draft(if (italian) "Qui serve un tetto" else "This needs a cap"), 1))
            } else {
                emptyMap()
            },
        )
    }

    /** The diff scrolled to the thread on Gateway.kt. */
    private fun prFiles(name: String, screen: PrScreen, dark: Boolean = false, scrollTo: Int = 0) {
        prPage(name, screen, dark, capture = false)
        if (scrollTo > 0) {
            compose.onNodeWithTag(DIFF_TAG, useUnmergedTree = true).performScrollToIndex(scrollTo)
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("$name.png")
    }

    @Test fun prFiles() = prFiles("pr-files", designFilesScreen())

    @Config(qualifiers = "+it")
    @Test fun prFilesItalian() = prFiles("pr-files-it", designFilesScreen())

    @Test fun prFilesDark() = prFiles("pr-files-dark", designFilesScreen(), dark = true)

    @Test fun prFilesSideBySide() = prFiles("pr-files-side-by-side", designFilesScreen(sideBySide = true))

    /** The thread with its replies, and one on a deleted line. */
    @Test fun prFilesInlineThread() = prFiles("pr-files-inline-thread", designFilesScreen(fullThreads = true))

    /** Line 87 long-pressed: the composer under it. */
    @Test fun prFilesNewThread() = prFiles(
        "pr-files-new-thread", designFilesScreen(newThread = io.github.iacoposb.adodash.api.FileAnchor("/src/payments/Gateway.kt", 89)),
    )

    @Test fun prCommits() = prPage("pr-commits", designFilesScreen(tab = PrTab.COMMITS))

    @Config(qualifiers = "+it")
    @Test fun prCommitsItalian() = prPage("pr-commits-it", designFilesScreen(tab = PrTab.COMMITS))

    @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    @Test fun prPhoneFiles() = prFiles("pr-phone-files", designFilesScreen())

    @Test fun prLoading() = prPage("pr-loading", PrScreen(311, designPr(), me = Person(me.id, me.name, "")))
}
