package io.github.iacoposb.adodash.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.api.Vote
import io.github.iacoposb.adodash.auth.SignInRequiredException
import io.github.iacoposb.adodash.update.UpdateException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The texts follow the device language: English by default, Italian with `it`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ResourceSyncTextsTest {
    private val texts get() = ResourceSyncTexts(ApplicationProvider.getApplicationContext<Context>())

    @Config(qualifiers = "en")
    @Test fun english() = with(texts) {
        assertEquals("Assigned to you: #7 Fix login", assigned(7, "Fix login"))
        assertEquals("Anna commented on PR !3", prComment("Anna", 3))
        assertEquals("Anna approved your PR", prVote("Anna", Vote.APPROVED))
        assertEquals("Invalid or expired credentials", error(AdoException(401, "x")))
        assertEquals("HTTP 500: boom", error(AdoException(500, "HTTP 500: boom")))
        assertEquals("can't reach GitHub, check your connection", error(UpdateException(UpdateException.Reason.UNREACHABLE)))
        assertEquals("Build failed on your PR !305", buildFailed(305))
        assertEquals("backend-ci #20261016.3 · 2 tests failed", buildFailedBody("backend-ci", "20261016.3", 2))
        assertEquals("backend-ci #20261016.3 · 1 test failed", buildFailedBody("backend-ci", "20261016.3", 1))
        assertEquals("backend-ci #20261016.3", buildFailedBody("backend-ci", "20261016.3", null))
    }

    @Config(qualifiers = "it")
    @Test fun italian() = with(texts) {
        assertEquals("Assegnato a te: #7 Fix login", assigned(7, "Fix login"))
        assertEquals("Anna ti ha menzionato nella PR !3", prMention("Anna", 3))
        assertEquals("Anna: PR in attesa dell'autore", prVote("Anna", Vote.WAITING))
        assertEquals("Stati di Bug: 403", statesWarning("Bug", "403"))
        assertEquals("Build fallita sulla tua PR !305", buildFailed(305))
        assertEquals("backend-ci #20261016.3 · 2 test non superati", buildFailedBody("backend-ci", "20261016.3", 2))
        assertEquals("Sessione Microsoft scaduta: accedi di nuovo dalle impostazioni", error(SignInRequiredException()))
        assertEquals(
            "download non riuscito (HTTP 404)",
            error(UpdateException(UpdateException.Reason.DOWNLOAD_HTTP, code = 404)),
        )
    }
}
