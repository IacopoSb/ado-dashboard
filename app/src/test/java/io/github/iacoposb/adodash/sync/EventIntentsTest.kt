package io.github.iacoposb.adodash.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.iacoposb.adodash.MainActivity
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.EventKind
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class EventIntentsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `an assignment opens its page through the app, which marks it read`() {
        val url = "https://dev.azure.com/org/Contoso/_workitems/edit/4812"
        val event = Event("wi:4812:assigned", EventKind.ASSIGNED, "Assigned", "Bug", url, at = 0)
        val intent = EventIntents.of(context, event)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("wi:4812:assigned", intent.getStringExtra(MainActivity.EXTRA_EVENT_KEY))
        assertEquals(url, intent.getStringExtra(MainActivity.EXTRA_URL))
    }

    @Test fun `a pull request's event opens its page, not the browser`() {
        val event = Event("pr:311:review", EventKind.PR_REVIEW, "Review", "", "https://x/pr/311", at = 0)
        val intent = EventIntents.of(context, event)
        assertEquals(311, intent.getIntExtra(MainActivity.EXTRA_PR_ID, -1))
        assertEquals(null, intent.getStringExtra(MainActivity.EXTRA_URL))
    }
}
