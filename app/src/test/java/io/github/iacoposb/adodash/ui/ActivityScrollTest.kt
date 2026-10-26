package io.github.iacoposb.adodash.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.EventKind
import io.github.iacoposb.adodash.sync.Snapshot
import io.github.iacoposb.adodash.sync.SyncState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A new event at the top of a long activity list comes into view, instead of landing above it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w1280dp-h800dp-land-mdpi", application = android.app.Application::class)
class ActivityScrollTest {
    @get:Rule val compose = createComposeRule()

    private fun event(n: Int) =
        Event("$n", EventKind.ASSIGNED, "Event $n", "", "", Fakes.now.minusSeconds(n * 60L).toEpochMilli(), read = true)

    @Test fun `a new event at the top shows`() {
        val events = mutableStateOf((1..30).map(::event))
        val snapshot = Snapshot(Fakes.me, emptyList(), emptyList(), emptyList(), Fakes.now)
        compose.setContent {
            AdoTheme(dark = false, dynamic = false) {
                DashboardScreen("contoso", SyncState(snapshot), events.value, {}, {}, {}, {}, {}, now = Fakes.now)
            }
        }
        compose.onNodeWithText("Event 1").assertIsDisplayed()
        events.value = listOf(event(0)) + events.value
        compose.onNodeWithText("Event 0").assertIsDisplayed()
    }
}
