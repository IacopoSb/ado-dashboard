package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.update.NoteLine
import io.github.iacoposb.adodash.update.Release
import io.github.iacoposb.adodash.update.UpdateState
import io.github.iacoposb.adodash.update.parseNotes
import java.io.File

/** "About" in the settings: the installed version and the update check. */
@Composable
fun AboutSection(
    version: String,
    update: UpdateState,
    onCheck: () -> Unit,
    onDownload: (Release) -> Unit,
    onInstall: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.about_version, version))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val busy = update is UpdateState.Checking || update is UpdateState.Downloading
            OutlinedButton(onClick = onCheck, enabled = !busy) { Text(stringResource(R.string.action_check_updates)) }
            Spacer(Modifier.width(16.dp))
            when (update) {
                UpdateState.Checking -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                UpdateState.UpToDate -> Text(stringResource(R.string.update_up_to_date), color = colors.onSurfaceVariant)
                UpdateState.NoReleases -> Text(stringResource(R.string.update_no_releases), color = colors.onSurfaceVariant)
                is UpdateState.CheckFailed -> Text(
                    stringResource(R.string.update_check_failed, update.message),
                    Modifier.weight(1f), color = colors.error,
                )
                else -> {}
            }
        }
        if (update is UpdateState.WithRelease) UpdateCard(update, onDownload, onInstall)
    }
}

@Composable
private fun UpdateCard(update: UpdateState.WithRelease, onDownload: (Release) -> Unit, onInstall: (File) -> Unit) {
    val r = update.release
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.update_version_available, r.version), style = MaterialTheme.typography.titleMedium)
            ReleaseNotes(r.notes)
            when (update) {
                is UpdateState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val p = update.progress
                    if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
                    Text(
                        if (p == null) stringResource(R.string.update_downloading)
                        else stringResource(R.string.update_downloading_progress, (p * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is UpdateState.Ready -> {
                    Button(onClick = { onInstall(update.apk) }) { Text(stringResource(R.string.action_install)) }
                    Text(
                        stringResource(R.string.update_install_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> {
                    Button(onClick = { onDownload(r) }) { Text(stringResource(R.string.action_download_install)) }
                    if (update is UpdateState.DownloadFailed) {
                        Text(stringResource(R.string.update_download_failed, update.message), color = MaterialTheme.colorScheme.error)
                    }
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEachIndexed { i, line ->
            when (line) {
                is NoteLine.Heading -> Text(
                    line.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = if (i > 0) Modifier.padding(top = 8.dp) else Modifier,
                )
                is NoteLine.Bullet -> Row {
                    Text("•", Modifier.width(16.dp))
                    Text(line.text, style = MaterialTheme.typography.bodyMedium)
                }
                is NoteLine.Text -> Text(line.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Dashboard banner for a newer version; tapping it opens the settings on the update. */
@Composable
fun UpdateBanner(version: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).background(colors.primaryContainer)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Info, null, tint = colors.onPrimaryContainer)
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.update_available, version), Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onOpen) { Text(stringResource(R.string.action_details)) }
        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.action_dismiss), tint = colors.onPrimaryContainer) }
    }
}
