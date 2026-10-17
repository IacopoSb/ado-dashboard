package io.github.iacoposb.adodash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.ui.AdoTheme

/**
 * The design's dropdown field: a 42 dp outlined box with the value and a chevron, opening
 * [menu] below it. [menu] gets a function that closes it; multiple choice menus stay open.
 */
@Composable
fun DropdownField(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Open from the start, for the screenshots. */
    initiallyOpen: Boolean = false,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val c = AdoTheme.colors
    var open by remember { mutableStateOf(initiallyOpen) }
    val shape = RoundedCornerShape(8.dp)
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 42.dp).clip(shape).background(c.surface).border(1.dp, c.outline, shape)
                .clickable(enabled = enabled, role = Role.DropdownList) { open = true }
                .semantics {
                    contentDescription = label
                    stateDescription = value
                }
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) c.text else c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(20.dp), tint = c.textMuted)
        }
        DropdownMenu(open, { open = false }, containerColor = c.surface) { menu { open = false } }
    }
}

/** A choice in a [DropdownField]'s menu, ticked when [selected]. */
@Composable
fun DropdownOption(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = AdoTheme.colors
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = c.text) },
        onClick = onClick,
        leadingIcon = {
            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp), tint = c.primary)
            else Spacer(Modifier.size(18.dp))
        },
        modifier = Modifier.semantics { this.selected = selected },
    )
}
