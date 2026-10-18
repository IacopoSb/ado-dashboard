package io.github.iacoposb.adodash.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.iacoposb.adodash.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The system's back in the settings does what the app bar's arrow does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w1280dp-h800dp-land-mdpi", application = android.app.Application::class)
class SettingsBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var closed = 0

    private fun show(section: SettingsSection?) {
        compose.setContent {
            AdoTheme(dark = false, dynamic = false) {
                SettingsScreen(
                    Settings(organization = "contoso", meId = "u1", meName = "Iacopo Sbalchiero"),
                    busy = false, message = null, {}, { _, _ -> }, {}, {}, onBack = { closed++ }, initialSection = section,
                )
            }
        }
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test fun `back on the tablet's settings returns to the dashboard`() {
        show(SettingsSection.NOTIFICATIONS)
        back()
        assertEquals(1, closed)
    }

    @Config(qualifiers = "en-w412dp-h915dp-port-xxhdpi")
    @Test fun `back on a phone goes from a section to the list, then to the dashboard`() {
        show(SettingsSection.NOTIFICATIONS)
        back()
        assertEquals(0, closed)
        back()
        assertEquals(1, closed)
    }
}
