package io.github.iacoposb.adodash.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import io.github.iacoposb.adodash.data.Event
import io.github.iacoposb.adodash.data.Settings
import io.github.iacoposb.adodash.data.SettingsStore
import io.github.iacoposb.adodash.sync.Snapshot
import io.github.iacoposb.adodash.sync.SyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps the saved [WidgetSummary] current and the widgets on the home screen with it. */
class Widgets(
    private val context: Context,
    val store: WidgetStore,
    private val settings: SettingsStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _clock = MutableStateFlow(now())

    /**
     * The time the widgets show their ages and staleness against: moved on at every update,
     * so that an update with nothing new still says "2 h" instead of "1 h".
     */
    val clock: StateFlow<Long> = _clock.asStateFlow()

    fun tick() {
        _clock.value = now()
    }

    /** After each successful sync, foreground or background: its counters, the stored [events]' mentions. */
    suspend fun synced(s: Settings, snapshot: Snapshot, events: List<Event>) {
        store.update {
            WidgetSummaries.withMentions(WidgetSummaries.synced(it, s.organization, snapshot, s.views.current), events, s.filters)
                .copy(refreshingSince = 0)
        }
        updateAll()
    }

    /** Events stored or read, or the filters changed: the mentions again; signed out, nothing. */
    suspend fun eventsChanged(s: Settings, events: List<Event>) {
        if (!s.configured) store.clear() else store.update { WidgetSummaries.withMentions(it, events, s.filters) }
        updateAll()
    }

    /** The large widget's refresh: "Updating…" until the sync it starts ends. */
    suspend fun refresh() {
        if (!settings.current().configured) return
        store.update { it.copy(refreshingSince = now()) }
        updateAll()
        SyncWorker.syncNow(context)
    }

    /** The sync started by [refresh] ended, maybe with an error: the widgets stop saying "Updating…". */
    suspend fun refreshEnded() {
        if (store.load().refreshingSince == 0L) return
        store.update { it.copy(refreshingSince = 0) }
        updateAll()
    }

    suspend fun updateAll() {
        tick()
        // Never a reason for a sync to fail
        try {
            SmallWidget().updateAll(context)
            LargeWidget().updateAll(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AdoWidgets", "Widgets not updated", e)
        }
    }
}
