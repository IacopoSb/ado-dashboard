package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.ui.components.AdoButton
import io.github.iacoposb.adodash.ui.components.AdoIcons
import io.github.iacoposb.adodash.ui.components.ButtonStyle
import io.github.iacoposb.adodash.ui.components.SectionCard
import io.github.iacoposb.adodash.update.NoteLine
import io.github.iacoposb.adodash.update.Release
import io.github.iacoposb.adodash.update.UpdateState
import io.github.iacoposb.adodash.update.parseNotes
import java.io.File

/** "About" in the settings: the installed version, the update check and the release found. */
@Composable
fun AboutSection(
    version: String,
    update: UpdateState,
    onCheck: () -> Unit,
    onDownload: (Release) -> Unit,
    onInstall: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AdoTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(stringResource(R.string.app_name)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyLarge, color = c.text)
                    val status = when (update) {
                        UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                        UpdateState.NoReleases -> stringResource(R.string.update_no_releases)
                        is UpdateState.CheckFailed -> stringResource(R.string.update_check_failed, update.message)
                        else -> null
                    }
                    if (status != null) {
                        Text(
                            status, style = MaterialTheme.typography.bodySmall,
                            color = if (update is UpdateState.CheckFailed) c.danger else c.textMuted,
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                if (update is UpdateState.Checking) {
                    CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), color = c.primary, strokeWidth = 2.dp)
                }
                AdoButton(
                    stringResource(R.string.action_check_updates), onCheck, style = ButtonStyle.Outlined, icon = Icons.Filled.Refresh,
                    enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading,
                )
            }
        }
        if (update is UpdateState.WithRelease) UpdateCard(update, onDownload, onInstall)
    }
}

@Composable
private fun UpdateCard(update: UpdateState.WithRelease, onDownload: (Release) -> Unit, onInstall: (File) -> Unit) {
    val c = AdoTheme.colors
    val r = update.release
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.primaryContainer).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AdoIcons.OpenInNew, null, Modifier.size(20.dp), tint = c.onPrimaryContainer)
            Spacer(Modifier.width(16.dp))
            Text(
                stringResource(R.string.update_version_available, r.version), Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium, color = c.onPrimaryContainer,
            )
        }
        if (parseNotes(r.notes).isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(16.dp)) {
                ReleaseNotes(r.notes)
            }
        }
        when (update) {
            is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val p = update.progress
                val track = c.surface
                if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp), color = c.primary, trackColor = track)
                else LinearProgressIndicator({ p }, Modifier.fillMaxWidth().height(6.dp), color = c.primary, trackColor = track)
                Text(
                    if (p == null) stringResource(R.string.update_downloading)
                    else stringResource(R.string.update_downloading_progress, (p * 100).toInt()),
                    style = MaterialTheme.typography.bodySmall, color = c.onPrimaryContainer,
                )
            }
            is UpdateState.Ready -> {
                Text(stringResource(R.string.update_install_hint), style = MaterialTheme.typography.bodySmall, color = c.onPrimaryContainer)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    AdoButton(stringResource(R.string.action_install), { onInstall(update.apk) })
                }
            }
            else -> {
                if (update is UpdateState.DownloadFailed) {
                    Text(stringResource(R.string.update_download_failed, update.message), style = MaterialTheme.typography.bodyMedium, color = c.danger)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    AdoButton(stringResource(R.string.action_download_install), { onDownload(r) })
                }
            }
        }
    }
}

/** The release notes' markdown, simply: headings, bullets and plain lines. Shown as GitHub has them, not translated. */
@Composable
fun ReleaseNotes(markdown: String) {
    val lines = parseNotes(markdown)
    if (lines.isEmpty()) return
    val c = AdoTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEachIndexed { i, line ->
            when (line) {
                is NoteLine.Heading -> Text(
                    line.text, style = MaterialTheme.typography.titleSmall, color = c.text,
                    modifier = if (i > 0) Modifier.padding(top = 4.dp) else Modifier,
                )
                is NoteLine.Bullet -> Row {
                    Text("•", Modifier.width(16.dp), style = MaterialTheme.typography.bodyMedium, color = c.textMuted)
                    Text(line.text, style = MaterialTheme.typography.bodyMedium, color = c.text)
                }
                is NoteLine.Text -> Text(line.text, style = MaterialTheme.typography.bodyMedium, color = c.text)
            }
        }
    }
}
