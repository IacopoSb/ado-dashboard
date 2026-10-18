package io.github.iacoposb.adodash.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.iacoposb.adodash.AdoApp
import io.github.iacoposb.adodash.MainActivity
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.sync.EventIntents
import io.github.iacoposb.adodash.ui.DarkAdoColors
import io.github.iacoposb.adodash.ui.LightAdoColors
import io.github.iacoposb.adodash.ui.toColorScheme
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** What a widget shows. */
data class WidgetUi(val signedIn: Boolean, val summary: WidgetSummary, val now: Long = System.currentTimeMillis())

/** 3×1: the app, the time of the last sync and three counters. */
class SmallWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) = provideWidget(context) { SmallWidgetContent(it) }
}

/** 4×2, resizable: counters, refresh and the latest mentions, as many as fit. */
class LargeWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) = provideWidget(context) { LargeWidgetContent(it) }
}

class SmallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SmallWidget()
}

class LargeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LargeWidget()
}

/** The refresh icon: a sync now, in the background. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        (context.applicationContext as AdoApp).widgets.refresh()
    }
}

private suspend fun GlanceAppWidget.provideWidget(context: Context, content: @Composable (WidgetUi) -> Unit) {
    val app = context.applicationContext as AdoApp
    val widgets = app.widgets
    widgets.store.load()
    // Also the system's half-hourly update: a sync over an hour old shows as such, offline too
    widgets.tick()
    val signedInAtStart = app.settings.current().configured
    provideContent {
        val summary by widgets.store.summary.collectAsState()
        val now by widgets.clock.collectAsState()
        val signedInFlow = remember { app.settings.settings.map { it.configured } }
        val signedIn by signedInFlow.collectAsState(signedInAtStart)
        WidgetTheme { content(WidgetUi(signedIn, summary, now)) }
    }
}

/** The design tokens (docs/design/tokens.css), light or dark as the system is. */
@Composable
fun WidgetTheme(content: @Composable () -> Unit) {
    GlanceTheme(colors = WidgetColors, content = content)
}

private val WidgetColors = ColorProviders(light = LightAdoColors.toColorScheme(false), dark = DarkAdoColors.toColorScheme(true))

@Composable
fun SmallWidgetContent(ui: WidgetUi) {
    val context = LocalContext.current
    val s = ui.summary
    WidgetFrame(openApp()) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(context.getString(R.string.widget_title), GlanceModifier.defaultWeight(), style = small(GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
            if (ui.signedIn && s.hasData) Text(syncTime(context, s, ui.now), style = small(timeColor(s, ui.now)), maxLines = 1)
        }
        when {
            !ui.signedIn -> Message(context.getString(R.string.widget_sign_in))
            !s.hasData -> Message(context.getString(R.string.widget_waiting))
            else -> {
                Spacer(GlanceModifier.height(6.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    val stale = s.stale(ui.now)
                    Counter(s.toReview, context.getString(R.string.widget_to_review), countColor(stale, s.toReview, GlanceTheme.colors.primary))
                    Counter(s.workItems, context.getString(R.string.widget_work_items), countColor(stale, 0, GlanceTheme.colors.onSurface))
                    Counter(s.unreadMentions, context.getString(R.string.widget_mentions), countColor(stale, s.unreadMentions, GlanceTheme.colors.error))
                }
            }
        }
    }
}

@Composable
fun LargeWidgetContent(ui: WidgetUi) {
    val context = LocalContext.current
    val s = ui.summary
    WidgetFrame(openApp()) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val title = s.organization.takeIf { ui.signedIn && it.isNotEmpty() }
                ?.let { context.getString(R.string.widget_title_organization, it) } ?: context.getString(R.string.widget_title)
            Text(
                title, GlanceModifier.defaultWeight(), maxLines = 1,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
            if (ui.signedIn) {
                val status = when {
                    s.refreshing(ui.now) -> context.getString(R.string.widget_updating)
                    s.stale(ui.now) -> syncTime(context, s, ui.now)
                    else -> null
                }
                status?.let { Text(it, GlanceModifier.padding(start = 8.dp), style = small(GlanceTheme.colors.onSurfaceVariant), maxLines = 1) }
                Image(
                    ImageProvider(R.drawable.ic_widget_refresh),
                    contentDescription = context.getString(R.string.widget_refresh),
                    modifier = GlanceModifier.padding(start = 4.dp).size(28.dp).padding(4.dp).clickable(actionRunCallback<RefreshAction>()),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                )
            }
        }
        when {
            !ui.signedIn -> Message(context.getString(R.string.widget_sign_in))
            !s.hasData -> Message(context.getString(R.string.widget_waiting))
            else -> {
                Spacer(GlanceModifier.height(4.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    val stale = s.stale(ui.now)
                    Counter(s.toReview, context.getString(R.string.widget_to_review), countColor(stale, s.toReview, GlanceTheme.colors.primary))
                    Counter(s.workItems, context.getString(R.string.widget_work_items), countColor(stale, 0, GlanceTheme.colors.onSurface))
                    Counter(
                        s.failedBuilds, context.resources.getQuantityString(R.plurals.widget_builds_failed, s.failedBuilds),
                        countColor(stale, s.failedBuilds, GlanceTheme.colors.error),
                    )
                }
                val rows = WidgetSummaries.mentionRows(LocalSize.current.height.value)
                if (rows > 0) {
                    Spacer(GlanceModifier.height(8.dp))
                    Text(
                        context.getString(R.string.widget_latest_mentions).uppercase(locale(context)),
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.height(6.dp))
                    if (s.mentions.isEmpty()) {
                        Text(context.getString(R.string.widget_no_mentions), style = small(GlanceTheme.colors.onSurfaceVariant))
                    }
                    s.mentions.take(rows).forEachIndexed { i, m ->
                        if (i > 0) Spacer(GlanceModifier.height(8.dp))
                        MentionRow(m, ui.now)
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetFrame(onClick: Action, content: @Composable () -> Unit) {
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(16.dp).background(GlanceTheme.colors.surface)
            .clickable(onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    ) { content() }
}

@Composable
private fun Message(text: String) {
    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun RowScope.Counter(count: Int, label: String, color: ColorProvider) {
    Column(GlanceModifier.defaultWeight()) {
        Text(count.toString(), style = TextStyle(color = color, fontSize = 26.sp), maxLines = 1)
        Text(label, style = small(GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
    }
}

@Composable
private fun MentionRow(m: WidgetMention, now: Long) {
    val context = LocalContext.current
    Row(
        GlanceModifier.fillMaxWidth().cornerRadius(12.dp).background(GlanceTheme.colors.surfaceVariant)
            .clickable(actionStartActivity(openMention(context, m))).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            ImageProvider(R.drawable.ic_widget_mention), contentDescription = null,
            modifier = GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(GlanceTheme.colors.error),
        )
        Spacer(GlanceModifier.width(10.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(m.who, style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            Text(m.context, style = small(GlanceTheme.colors.onSurfaceVariant), maxLines = 1)
        }
        Spacer(GlanceModifier.width(8.dp))
        Text(age(context, m.at, now), style = small(GlanceTheme.colors.outline), maxLines = 1)
    }
}

/** The dashboard, or the sign-in when signed out. */
@Composable
private fun openApp(): Action = actionStartActivity<MainActivity>()

/** The work item or pull request at the comment, as the mention's notification opens it. */
private fun openMention(context: Context, m: WidgetMention): Intent = EventIntents.of(context, m.toEvent()).apply {
    // Pending intents differing only in their extras would be the same one
    if (component != null) data = Uri.parse("adodash-widget://mention/${Uri.encode(m.key)}")
}

private fun small(color: ColorProvider) = TextStyle(color = color, fontSize = 12.sp)

/** Muted when the last sync is over an hour old, as the counters may be. */
@Composable
private fun countColor(stale: Boolean, count: Int, color: ColorProvider): ColorProvider = when {
    stale -> GlanceTheme.colors.outline
    count > 0 -> color
    else -> GlanceTheme.colors.onSurface
}

@Composable
private fun timeColor(s: WidgetSummary, now: Long): ColorProvider =
    if (s.stale(now)) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.outline

private fun locale(context: Context): Locale = context.resources.configuration.locales[0]

/** "12:00" today, with the date before. */
internal fun syncTime(context: Context, s: WidgetSummary, now: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(s.syncedAt).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val style = if (at.toLocalDate() == today) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    return style.withLocale(locale(context)).format(at)
}

/** "4 min", "1 h", "2 d". */
internal fun age(context: Context, at: Long, now: Long): String {
    val d = Duration.ofMillis((now - at).coerceAtLeast(0))
    return when {
        d.toMinutes() < 1 -> context.getString(R.string.widget_age_now)
        d.toMinutes() < 60 -> context.getString(R.string.widget_age_minutes, d.toMinutes().toInt())
        d.toHours() < 24 -> context.getString(R.string.widget_age_hours, d.toHours().toInt())
        else -> context.getString(R.string.widget_age_days, d.toDays().toInt())
    }
}
