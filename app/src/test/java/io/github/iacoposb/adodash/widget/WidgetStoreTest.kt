package io.github.iacoposb.adodash.widget

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WidgetStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val file get() = File(folder.root, "widget-summary.json")

    private val summary = WidgetSummary(
        organization = "contoso", syncedAt = 1_000, toReview = 2, workItems = 5, failedBuilds = 1, unreadMentions = 2,
        mentions = listOf(
            WidgetMention("pr:311:t1:c2", "Anna Verdi", "PR !311 · is the backoff OK?", 900, "https://x/pr", "Contoso"),
            WidgetMention("wi:4812:c3", "Luca Rossi", "#4812 · can you reproduce it?", 800, "https://x/wi"),
        ),
    )

    @Test fun `what is saved reads back the same from a new store`() = runTest {
        val store = WidgetStore(file)
        store.update { summary }
        assertEquals(summary, store.summary.value)
        assertEquals(summary, WidgetStore(file).load())
    }

    @Test fun `nothing saved, or a file it cannot read, is an empty summary`() = runTest {
        assertEquals(WidgetSummary(), WidgetStore(file).load())
        file.writeText("{ not json")
        assertEquals(WidgetSummary(), WidgetStore(file).load())
    }

    @Test fun `an update changes what is saved`() = runTest {
        val store = WidgetStore(file)
        store.update { summary }
        store.update { it.copy(refreshingSince = 5) }
        assertEquals(summary.copy(refreshingSince = 5), WidgetStore(file).load())
    }

    @Test fun `clear forgets the account`() = runTest {
        val store = WidgetStore(file)
        store.update { summary }
        store.clear()
        assertFalse(file.exists())
        assertEquals(WidgetSummary(), store.summary.value)
        assertEquals(WidgetSummary(), WidgetStore(file).load())
    }
}
