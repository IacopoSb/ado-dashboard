package io.github.iacoposb.adodash.ui

import android.content.Context
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.data.DashboardView
import io.github.iacoposb.adodash.data.ViewSet
import io.github.iacoposb.adodash.sync.Snapshot
import io.github.iacoposb.adodash.sync.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What is open or being edited over the dashboard survives a rotation (the activity made again). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w1280dp-h800dp-land-mdpi", application = android.app.Application::class)
class RotationStateTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `the editor's target goes through the saved state whole`() {
        val scope = SaverScope { true }
        val view = DashboardView.newSaved("Mine").let { it.copy(pullRequests = it.pullRequests.copy(enabled = false)) }
        val target = EditorTarget(view, isNew = true, focus = ViewPanel.ACTIVITY)
        val saved = with(editorTargetSaver) { scope.save(target) }!!
        assertEquals(target, editorTargetSaver.restore(saved))
        assertNull(with(editorTargetSaver) { scope.save(null) })
    }

    @Test fun `a change in the editor survives a rotation`() {
        val tester = StateRestorationTester(compose)
        tester.setContent {
            AdoTheme(dark = false, dynamic = false) {
                ViewEditor(EditorTarget(DashboardView.newSaved("Mine")), EditorOptions(), {}, {}, {})
            }
        }
        val pullRequests = hasText(context.getString(R.string.panel_pull_requests)) and isToggleable()
        compose.onNode(pullRequests).assertIsOn().performClick()
        compose.onNode(pullRequests).assertIsOff()
        tester.emulateSavedInstanceStateRestore()
        compose.onNode(pullRequests).assertIsOff()
    }

    @Test fun `the view switcher stays open after a rotation`() {
        val tester = StateRestorationTester(compose)
        val snapshot = Snapshot(Fakes.me, emptyList(), emptyList(), emptyList(), Fakes.now)
        tester.setContent {
            AdoTheme(dark = false, dynamic = false) {
                DashboardScreen(
                    "contoso", SyncState(snapshot), emptyList(), {}, {}, {}, {}, {}, now = Fakes.now,
                    choices = viewChoices(ViewSet(), io.github.iacoposb.adodash.sync.CatalogState(), io.github.iacoposb.adodash.data.Filters(), snapshot),
                )
            }
        }
        compose.onAllNodesWithContentDescription(context.getString(R.string.action_choose_view)).onFirst().performClick()
        compose.onNodeWithText(context.getString(R.string.switcher_views), ignoreCase = true).assertExists()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(context.getString(R.string.switcher_views), ignoreCase = true).assertExists()
    }
}
