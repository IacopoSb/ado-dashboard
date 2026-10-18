package io.github.iacoposb.adodash.widget

import android.content.Context
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.testing.unit.hasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import io.github.iacoposb.adodash.Fakes.now
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en", application = android.app.Application::class)
class WidgetContentTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val t = now.toEpochMilli()

    private val summary = WidgetSummary(
        organization = "contoso", syncedAt = t - 60_000, toReview = 2, workItems = 5, failedBuilds = 1, unreadMentions = 7,
        mentions = listOf(
            WidgetMention("pr:311:t1:c2", "Anna Verdi", "PR !311 · is the backoff OK?", t - 4 * 60_000, "https://x/pr"),
            WidgetMention("wi:4812:c3", "Luca Rossi", "#4812 · can you reproduce it?", t - 3_600_000, "https://x/wi"),
            WidgetMention("wi:4813:c4", "Marco Bianchi", "#4813 · ping", t - 7_200_000, "https://x/wi2"),
        ),
    )

    private val small = DpSize(300.dp, 110.dp)
    private val large = DpSize(420.dp, 260.dp)

    @Test fun `the small widget shows its three counters`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(small)
        provideComposable { WidgetTheme { SmallWidgetContent(WidgetUi(true, summary, t)) } }
        onNode(hasTextEqualTo("2")).assertExists()
        onNode(hasText("to review")).assertExists()
        onNode(hasTextEqualTo("5")).assertExists()
        onNode(hasTextEqualTo("7")).assertExists()
        onNode(hasText("mentions")).assertExists()
    }

    @Test fun `the large widget shows the counters, the refresh and the mentions that fit`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { WidgetTheme { LargeWidgetContent(WidgetUi(true, summary, t)) } }
        onNode(hasText("Azure DevOps · contoso")).assertExists()
        onNode(hasText("build failed")).assertExists()
        onNode(hasText("LATEST MENTIONS")).assertExists()
        onNode(hasText("Anna Verdi")).assertExists()
        onNode(hasText("4 min")).assertExists()
        onNode(hasText("Luca Rossi")).assertExists()
        onNode(hasText("1 h")).assertExists()
        // Two rows fit in 4×2
        onAllNodes(hasText("Marco Bianchi")).assertCountEquals(0)
        onNode(hasRunCallbackClickAction<RefreshAction>()).assertExists()
    }

    @Test fun `signed out, the widgets ask to sign in`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { WidgetTheme { LargeWidgetContent(WidgetUi(false, summary, t)) } }
        onNode(hasText("Sign in to ADO Dashboard")).assertExists()
        onAllNodes(hasText("Anna Verdi")).assertCountEquals(0)
        onAllNodes(hasRunCallbackClickAction<RefreshAction>()).assertCountEquals(0)
    }

    @Test fun `before the first sync, the widgets wait for it`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(small)
        provideComposable { WidgetTheme { SmallWidgetContent(WidgetUi(true, WidgetSummary(), t)) } }
        onNode(hasText("Waiting for the first sync")).assertExists()
    }

    @Test fun `a refresh from the widget says it is updating`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        setAppWidgetSize(large)
        provideComposable { WidgetTheme { LargeWidgetContent(WidgetUi(true, summary.copy(refreshingSince = t - 1000), t)) } }
        onNode(hasText("Updating…")).assertExists()
    }
}
