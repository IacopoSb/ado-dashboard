package io.github.iacoposb.adodash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.ui.AdoTheme

enum class BannerTone { Error, Offline, Warning, Info }

/** A message across the top of a screen, with optional text actions on the right; up to four lines on phones. */
@Composable
fun Banner(
    text: String,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = AdoTheme.colors
    val (bg, iconTint, textColor) = when (tone) {
        BannerTone.Error -> Triple(c.dangerContainer, c.danger, c.text)
        BannerTone.Offline -> Triple(c.surfaceAlt, c.textMuted, c.text)
        BannerTone.Warning -> Triple(c.warningContainer, c.warning, c.text)
        BannerTone.Info -> Triple(c.primaryContainer, c.onPrimaryContainer, c.onPrimaryContainer)
    }
    val defaultIcon = when (tone) {
        BannerTone.Error -> AdoIcons.Error
        BannerTone.Offline -> AdoIcons.CloudOff
        BannerTone.Warning -> Icons.Filled.Warning
        BannerTone.Info -> Icons.Filled.Info
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon ?: defaultIcon, null, Modifier.size(20.dp), tint = iconTint)
        Spacer(Modifier.width(12.dp))
        Text(
            text, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
            color = textColor, maxLines = 4, overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/** A text action for [Banner] and panel headers. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = AdoTheme.colors.primary) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = color, maxLines = 1)
    }
}
