package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.data.DashboardView
import io.github.iacoposb.adodash.sync.SyncFailure
import io.github.iacoposb.adodash.sync.SyncState
import java.time.Instant

/** The banner over the panels that tells what is wrong with the data, if anything. */
sealed interface StatusBanner {
    /** No connection: the data shown is from [syncedAt] (null: there is none), with Retry. */
    data class Offline(val syncedAt: Instant?) : StatusBanner

    /** The credentials are refused: the data stays, dimmed, with "Sign in". */
    data object SessionExpired : StatusBanner

    /** Any other failure, with its reason and Retry. */
    data class Error(val message: String) : StatusBanner
}

/** What the panels show. */
enum class PanelsContent {
    /** Placeholders: a sync for this view is running, or about to start, and nothing is saved. */
    LOADING,

    /** Nothing to show and nothing coming: offline or failed before any data. */
    NO_DATA,

    /** The view's data, maybe saved by an earlier run while a sync runs; empty lists show empty states. */
    DATA,
}

/**
 * What the dashboard shows of the sync's [SyncState]: pure, for the tests.
 *
 * - Offline (no network, or the sync failed for the network) wins: nothing else can be known.
 * - Refused credentials: "Session expired", the data dimmed.
 * - Any other failure: its reason.
 * - The panels show the data read (or saved) for the view; without it, placeholders while a sync
 *   runs or is about to, and "no data" once it failed or cannot run offline: never endless
 *   placeholders.
 */
data class DashboardStatus(
    val banner: StatusBanner?,
    val content: PanelsContent,
    /** The data is shown dimmed, but readable: it cannot be updated until signing in again. */
    val dimmed: Boolean,
    /** Data on screen while a sync runs: what an earlier sync read, saved or not. */
    val refreshing: Boolean,
) {
    companion object {
        fun of(state: SyncState, online: Boolean, view: DashboardView): DashboardStatus {
            val snapshot = state.snapshot
            val shown = snapshot?.shownFor(view)
            val banner = when {
                !online || state.failure == SyncFailure.NETWORK -> StatusBanner.Offline(snapshot?.syncedAt)
                state.failure == SyncFailure.AUTH -> StatusBanner.SessionExpired
                state.error != null -> StatusBanner.Error(state.error)
                else -> null
            }
            val content = when {
                shown != null -> PanelsContent.DATA
                state.syncing -> PanelsContent.LOADING
                banner != null -> PanelsContent.NO_DATA
                else -> PanelsContent.LOADING
            }
            return DashboardStatus(
                banner = banner,
                content = content,
                dimmed = banner == StatusBanner.SessionExpired && content == PanelsContent.DATA,
                refreshing = content == PanelsContent.DATA && state.syncing,
            )
        }
    }
}
