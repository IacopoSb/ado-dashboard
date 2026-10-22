package io.github.iacoposb.adodash.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.ui.AdoTheme
import io.github.iacoposb.adodash.ui.CodeStyle

@Composable
fun AdoSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = AdoTheme.colors
    Switch(
        checked, onCheckedChange, modifier, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.onPrimary,
            checkedTrackColor = c.primary,
            checkedBorderColor = c.primary,
            uncheckedThumbColor = c.textFaint,
            uncheckedTrackColor = c.surfaceAlt,
            uncheckedBorderColor = c.textFaint,
        ),
    )
}

/** A setting that turns on and off: the whole row toggles. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    titleStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val c = AdoTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = titleStyle, color = c.text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        Spacer(Modifier.width(16.dp))
        AdoSwitch(checked, null, enabled = enabled)
    }
}

/** Checked, unchecked or partly checked (a parent whose children are mixed). */
@Composable
fun AdoCheckbox(state: ToggleableState, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = AdoTheme.colors
    TriStateCheckbox(
        state, onClick, modifier,
        colors = CheckboxDefaults.colors(
            checkedColor = c.primary,
            uncheckedColor = c.textFaint,
            checkmarkColor = c.onPrimary,
        ),
    )
}

/**
 * The design's text field: label above, 42 dp box with a 1 dp outline (primary when focused,
 * danger with [error]), hint or error below; [singleLine] false grows with the text from [minLines].
 */
@Composable
fun AdoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    error: String? = null,
    placeholder: String? = null,
    monospace: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    val c = AdoTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val style: TextStyle = (if (monospace) CodeStyle.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize) else MaterialTheme.typography.bodyMedium)
        .copy(color = if (enabled) c.text else c.textMuted)
    val shape = RoundedCornerShape(8.dp)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (error != null) c.danger else c.textMuted)
        BasicTextField(
            value, onValueChange,
            Modifier.fillMaxWidth().semantics {
                contentDescription = label
                if (error != null) error(error)
            },
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = style,
            cursorBrush = SolidColor(c.primary),
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(c.surface)
                        .border(
                            if (error != null || focused) 2.dp else 1.dp,
                            when {
                                error != null -> c.danger
                                focused -> c.primary
                                else -> c.outline
                            },
                            shape,
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = style.copy(color = c.textFaint))
                    inner()
                }
            },
        )
        when {
            error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = c.danger)
            hint != null -> Text(hint, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
    }
}

/** A value to copy elsewhere (Entra's registration): label, monospace text and "Copy". */
@Composable
fun CopyField(label: String, value: String, copyLabel: String, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    val c = AdoTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(8.dp)).background(c.surfaceAlt)
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                SelectionContainer { Text(value, style = CodeStyle, color = c.text) }
            }
            TextAction(copyLabel, onCopy)
        }
    }
}
