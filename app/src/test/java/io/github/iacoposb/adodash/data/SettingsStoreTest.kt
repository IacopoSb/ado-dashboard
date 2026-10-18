package io.github.iacoposb.adodash.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store by lazy {
        SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "settings.preferences_pb") })
    }

    @After fun tearDown() = scope.cancel()

    @Test fun `two quick changes of the filters both stay`() = runBlocking {
        listOf(
            async { store.editFilters { it.copy(draftsToReview = false) } },
            async { store.editFilters { it.copy(hideApprovedByMe = true) } },
            async { store.editFilters { it.copy(hiddenStates = mapOf("Bug" to setOf("Resolved"))) } },
        ).awaitAll()
        val filters = store.current().filters
        assertFalse(filters.draftsToReview)
        assertTrue(filters.hideApprovedByMe)
        assertEquals(mapOf("Bug" to setOf("Resolved")), filters.hiddenStates)
        assertTrue(store.current().filtersChangedAt > 0)
    }

    @Test fun `two quick changes of the notifications both stay`() = runBlocking {
        listOf(
            async { store.editNotifications { it.switching(NotifyKind.VOTES, false) } },
            async { store.editNotifications { it.copy(quiet = it.quiet.copy(enabled = true)) } },
        ).awaitAll()
        val prefs = store.current().notifications
        assertFalse(prefs.isOn(NotifyKind.VOTES))
        assertTrue(prefs.quiet.enabled)
    }

    @Test fun `the same organization typed with other capitals keeps its views, filters and baseline`() = runBlocking {
        store.saveAccount("Contoso", AuthMethod.ENTRA, "", "u1", "Iacopo")
        store.editFilters { it.copy(draftsToReview = false) }
        store.editViews { it.put(DashboardView.newSaved("Mine")) }
        store.markSynced(1000)
        store.saveAccount("contoso", AuthMethod.ENTRA, "", "U1", "Iacopo")
        val s = store.current()
        assertFalse(s.filters.draftsToReview)
        assertTrue(s.views.saved.any { it.name == "Mine" })
        assertEquals(1000L, s.baselineAt)
        assertEquals("contoso", s.organization)

        store.saveAccount("fabrikam", AuthMethod.ENTRA, "", "U1", "Iacopo")
        assertEquals(Filters(), store.current().filters)
        assertEquals(0L, store.current().baselineAt)
    }
}
