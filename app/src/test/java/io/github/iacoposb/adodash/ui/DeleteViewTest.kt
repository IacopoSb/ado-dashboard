package io.github.iacoposb.adodash.ui

import android.content.Context
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.data.DashboardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Delete view" asks first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w1280dp-h800dp-land-mdpi", application = android.app.Application::class)
class DeleteViewTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val view = DashboardView.newSaved("Mine")
    private val deleted = mutableListOf<DashboardView>()

    private fun show() = compose.setContent {
        AdoTheme(dark = false, dynamic = false) {
            ViewEditor(EditorTarget(view), EditorOptions(), {}, { deleted += it }, {})
        }
    }

    @Test fun `the view goes only once confirmed`() {
        show()
        compose.onNodeWithText(context.getString(R.string.action_delete_view)).performClick()
        assertTrue(deleted.isEmpty())
        compose.onNodeWithText(context.getString(R.string.action_delete)).performClick()
        assertEquals(listOf(view), deleted)
    }

    @Test fun `cancelled, the view stays`() {
        show()
        compose.onNodeWithText(context.getString(R.string.action_delete_view)).performClick()
        compose.onNodeWithText(context.getString(R.string.dialog_delete_view_text)).assertExists()
        // The dialog's "Cancel", not the editor's
        compose.onNode(hasText(context.getString(R.string.action_cancel)) and hasAnyAncestor(isDialog())).performClick()
        assertTrue(deleted.isEmpty())
        compose.onNodeWithText(context.getString(R.string.dialog_delete_view_text)).assertDoesNotExist()
    }
}
