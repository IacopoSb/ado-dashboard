package io.github.iacoposb.adodash.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.data.Filters
import io.github.iacoposb.adodash.data.NotificationPrefs
import io.github.iacoposb.adodash.data.NotifyKind
import io.github.iacoposb.adodash.sync.CatalogState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two taps before the screen shows the first one's result: each change applies to the settings
 * as stored, so the second never undoes the first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w1280dp-h800dp-land-mdpi", application = android.app.Application::class)
class QuickChangesTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun tap(text: Int) {
        compose.onNode(hasText(context.getString(text)) and isToggleable()).performScrollTo().performClick()
    }

    @Test fun `two quick changes of the filters both apply`() {
        val changes = mutableListOf<(Filters) -> Filters>()
        // The filters on screen stay as they were: the store has not answered yet
        compose.setContent {
            AdoTheme(dark = false, dynamic = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FiltersSection(Filters(), CatalogState(Fakes.catalog), { changes += it }, {}, wide = false)
                }
            }
        }
        tap(R.string.filters_show_drafts)
        tap(R.string.filters_hide_approved)
        val stored = changes.fold(Filters()) { f, change -> change(f) }
        assertFalse(stored.draftsToReview)
        assertTrue(stored.hideApprovedByMe)
        assertEquals(2, changes.size)
    }

    @Test fun `two quick changes of the notifications both apply`() {
        val changes = mutableListOf<(NotificationPrefs) -> NotificationPrefs>()
        compose.setContent {
            AdoTheme(dark = false, dynamic = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    NotificationsSection(NotificationPrefs(), true, NotificationActions(onChange = { changes += it }), wide = false)
                }
            }
        }
        tap(R.string.notif_votes)
        tap(R.string.notif_builds)
        val stored = changes.fold(NotificationPrefs()) { p, change -> change(p) }
        assertFalse(stored.isOn(NotifyKind.VOTES))
        assertFalse(stored.isOn(NotifyKind.BUILDS))
    }
}
