package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.data.AuthMethod
import io.github.iacoposb.adodash.data.Settings
import io.github.iacoposb.adodash.update.Release
import io.github.iacoposb.adodash.update.UpdateState
import java.io.File
import java.net.URLDecoder

data class SettingsForm(
    val organization: String,
    val pollSeconds: Int,
    val keepScreenOn: Boolean,
    /** Entra app registration, entered by the user. */
    val entraClientId: String = "",
    val entraTenantId: String = "",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    initial: Settings,
    busy: Boolean,
    message: String?,
    onMicrosoftSignIn: (SettingsForm) -> Unit,
    onPatSignIn: (SettingsForm, pat: String) -> Unit,
    onSavePreferences: (SettingsForm) -> Unit,
    onSignOut: () -> Unit,
    onBack: (() -> Unit)?,
    /** What to register in Entra, shown next to the IDs. */
    redirectUri: String = "",
    version: String = "",
    update: UpdateState = UpdateState.Idle,
    onCheckUpdates: () -> Unit = {},
    onDownloadUpdate: (Release) -> Unit = {},
    onInstallUpdate: (File) -> Unit = {},
    /** Opened from the update banner or notification: scrolls to "About". */
    showUpdate: Boolean = false,
) {
    var organization by rememberSaveable { mutableStateOf(initial.organization) }
    var pat by rememberSaveable { mutableStateOf("") }
    var poll by rememberSaveable { mutableStateOf(initial.pollSeconds.toString()) }
    var keepOn by rememberSaveable { mutableStateOf(initial.keepScreenOn) }
    var clientId by rememberSaveable { mutableStateOf(initial.entraClientId) }
    var tenantId by rememberSaveable { mutableStateOf(initial.entraTenantId) }
    var showPat by rememberSaveable { mutableStateOf(initial.authMethod == AuthMethod.PAT && initial.configured) }
    fun form() = SettingsForm(
        organization.trim(), (poll.toIntOrNull() ?: 60).coerceIn(15, 3600), keepOn, clientId.trim(), tenantId.trim(),
    )

    val about = remember { BringIntoViewRequester() }
    LaunchedEffect(showUpdate) {
        if (showUpdate) {
            withFrameNanos {} // laid out first
            about.bringIntoView()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.settings_account), style = MaterialTheme.typography.titleMedium)

                if (initial.configured) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_signed_in_as, initial.meName))
                            Text(
                                "${initial.organization} · " + stringResource(
                                    if (initial.authMethod == AuthMethod.ENTRA) R.string.settings_auth_microsoft else R.string.settings_auth_pat,
                                ),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = onSignOut, enabled = !busy) { Text(stringResource(R.string.action_sign_out)) }
                    }
                } else {
                    OutlinedTextField(
                        organization, { organization = it }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_organization)) },
                        supportingText = { Text(stringResource(R.string.settings_organization_hint)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        clientId, { clientId = it }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_client_id)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        tenantId, { tenantId = it }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_tenant_id)) },
                        supportingText = { Text(stringResource(R.string.settings_tenant_id_hint)) },
                        singleLine = true,
                    )
                    RedirectUri(redirectUri)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = { onMicrosoftSignIn(form()) },
                            enabled = clientId.isNotBlank() && !busy && organization.isNotBlank(),
                        ) { Text(stringResource(R.string.action_sign_in_microsoft)) }
                        if (busy) {
                            Spacer(Modifier.size(16.dp))
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    TextButton(onClick = { showPat = !showPat }) {
                        Text(stringResource(R.string.settings_more_options))
                        Icon(if (showPat) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
                    }
                    if (showPat) {
                        OutlinedTextField(
                            pat, { pat = it }, Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.settings_pat)) },
                            supportingText = { Text(stringResource(R.string.settings_pat_hint)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                        OutlinedButton(
                            onClick = { onPatSignIn(form(), pat.trim()) },
                            enabled = !busy && organization.isNotBlank() && pat.isNotBlank(),
                        ) { Text(stringResource(R.string.action_sign_in_pat)) }
                    }
                }

                Text(stringResource(R.string.settings_dashboard), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    poll, { poll = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_poll)) },
                    supportingText = { Text(stringResource(R.string.settings_poll_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_keep_screen_on), Modifier.weight(1f))
                    Switch(keepOn, { keepOn = it })
                }
                if (initial.configured) {
                    Button(onClick = { onSavePreferences(form()) }, enabled = !busy) { Text(stringResource(R.string.action_save)) }
                }

                AboutSection(
                    version, update, onCheckUpdates, onDownloadUpdate, onInstallUpdate,
                    Modifier.padding(top = 8.dp).bringIntoViewRequester(about),
                )
            }
        }
    }
}

/** The redirect URI to register in Entra, selectable and with a copy button. */
@Composable
private fun RedirectUri(uri: String) {
    val clipboard = LocalClipboardManager.current
    // msauth://<package>/<url-encoded hash>: Entra asks for the two parts separately
    val pkg = uri.removePrefix("msauth://").substringBefore('/')
    val hash = URLDecoder.decode(uri.substringAfterLast('/'), "UTF-8")
    Column {
        Text(stringResource(R.string.settings_redirect_uri), style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                SelectionContainer { Text(uri, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(uri)) }) { Text(stringResource(R.string.action_copy)) }
        }
        SelectionContainer {
            Text(
                stringResource(R.string.settings_redirect_uri_hint, pkg, hash),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
