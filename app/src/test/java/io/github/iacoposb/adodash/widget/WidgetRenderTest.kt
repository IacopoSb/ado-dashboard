package io.github.iacoposb.adodash.widget

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.ViewGroup
import android.graphics.Color
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.iacoposb.adodash.Fakes.now
import io.github.iacoposb.adodash.R
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The widgets as the launcher draws them (Glance's RemoteViews applied to views) in
 * app/build/outputs/roborazzi, on the home screen's dark background of the design.
 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en-w800dp-h600dp-mdpi", application = android.app.Application::class)
class WidgetRenderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val t = now.toEpochMilli()

    private val summary = WidgetSummary(
        organization = "contoso", syncedAt = t - 60_000, toReview = 2, workItems = 5, failedBuilds = 1, unreadMentions = 2,
        mentions = listOf(
            WidgetMention("pr:311:t1:c2", "Anna Verdi", "PR !311 · is the exponential backoff OK?", t - 4 * 60_000, "https://x/pr"),
            WidgetMention("wi:4812:c3", "Luca Rossi", "#4812 · can you reproduce it with guest01?", t - 3_600_000, "https://x/wi"),
        ),
    )

    /** The design's sample, in the device's language. */
    private val preview get() = summary.copy(
        mentions = listOf(
            summary.mentions[0].copy(context = context.getString(R.string.widget_preview_mention_context)),
            summary.mentions[1].copy(context = context.getString(R.string.widget_preview_mention_context_2)),
        ),
    )

    private val small = DpSize(300.dp, 100.dp)
    private val large = DpSize(420.dp, 254.dp)

    /** [preview]: only the widget, as the picker's preview images in res/drawable*-nodpi are. */
    private fun render(
        name: String, size: DpSize, preview: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit,
    ) = runBlocking {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val result = GlanceRemoteViews().compose(activity, size) { WidgetTheme(content) }
        val density = activity.resources.displayMetrics.density
        val w = (size.width.value * density).toInt()
        val h = (size.height.value * density).toInt()
        val margin = if (preview) 0 else (16 * density).toInt()
        val frame = FrameLayout(activity).apply { if (!preview) setBackgroundColor(Color.parseColor("#2E3E55")) }
        val widget = result.remoteViews.apply(activity, frame)
        frame.addView(widget, FrameLayout.LayoutParams(w, h).apply { setMargins(margin, margin, margin, margin) })
        activity.setContentView(frame, ViewGroup.LayoutParams(w + 2 * margin, h + 2 * margin))
        shadowOf(Looper.getMainLooper()).idle()
        frame.captureRoboImage("widget/$name.png")
    }

    @Test fun small() = render("small", small) { SmallWidgetContent(WidgetUi(true, summary, t)) }

    @Test fun large() = render("large", large) { LargeWidgetContent(WidgetUi(true, summary, t)) }

    @Test fun largeStale() = render("large-stale", large) { LargeWidgetContent(WidgetUi(true, summary, t + 2 * 3_600_000)) }

    @Test fun largeUpdating() = render("large-updating", large) {
        LargeWidgetContent(WidgetUi(true, summary.copy(refreshingSince = t - 1000), t))
    }

    @Test fun smallStale() = render("small-stale", small) { SmallWidgetContent(WidgetUi(true, summary, t + 2 * 3_600_000)) }

    @Test fun signedOut() = render("small-signed-out", small) { SmallWidgetContent(WidgetUi(false, summary, t)) }

    @Test fun waiting() = render("large-waiting", large) { LargeWidgetContent(WidgetUi(true, WidgetSummary(), t)) }

    @Test @Config(qualifiers = "+night") fun largeDark() = render("large-dark", large) { LargeWidgetContent(WidgetUi(true, summary, t)) }

    @Test @Config(qualifiers = "+it") fun largeItalian() = render("large-it", large) { LargeWidgetContent(WidgetUi(true, summary, t)) }

    // The picker's preview images (before Android 12), copied by hand into res/drawable-nodpi
    // and res/drawable-it-nodpi when the widgets change
    @Test @Config(qualifiers = "en-w1000dp-h800dp-xhdpi") fun previewSmall() =
        render("preview/small", small, preview = true) { SmallWidgetContent(WidgetUi(true, preview, t)) }

    @Test @Config(qualifiers = "en-w1000dp-h800dp-xhdpi") fun previewLarge() =
        render("preview/large", large, preview = true) { LargeWidgetContent(WidgetUi(true, preview, t)) }

    @Test @Config(qualifiers = "it-w1000dp-h800dp-xhdpi") fun previewSmallItalian() =
        render("preview/small-it", small, preview = true) { SmallWidgetContent(WidgetUi(true, preview, t)) }

    @Test @Config(qualifiers = "it-w1000dp-h800dp-xhdpi") fun previewLargeItalian() =
        render("preview/large-it", large, preview = true) { LargeWidgetContent(WidgetUi(true, preview, t)) }

    /** The picker's preview layouts (Android 12 and later), to compare with the widgets. */
    private fun renderLayout(name: String, layout: Int, size: DpSize) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = FrameLayout(activity).apply { setBackgroundColor(Color.parseColor("#2E3E55")) }
        val view = activity.layoutInflater.inflate(layout, frame, false)
        frame.addView(view, FrameLayout.LayoutParams(size.width.value.toInt(), size.height.value.toInt()).apply { setMargins(16, 16, 16, 16) })
        activity.setContentView(frame, ViewGroup.LayoutParams(size.width.value.toInt() + 32, size.height.value.toInt() + 32))
        shadowOf(Looper.getMainLooper()).idle()
        frame.captureRoboImage("widget/$name.png")
    }

    @Test fun previewLayouts() {
        renderLayout("preview-layout-small", R.layout.widget_small_preview, small)
        renderLayout("preview-layout-large", R.layout.widget_large_preview, large)
    }

    @Test @Config(qualifiers = "+it") fun smallItalian() = render("small-it", small) { SmallWidgetContent(WidgetUi(true, summary, t)) }
}
