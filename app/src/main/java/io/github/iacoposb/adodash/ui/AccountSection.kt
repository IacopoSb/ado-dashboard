package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.data.AuthMethod
import io.github.iacoposb.adodash.data.EntraIds
import io.github.iacoposb.adodash.data.Settings
import io.github.iacoposb.adodash.ui.components.AdoButton
import io.github.iacoposb.adodash.ui.components.AdoIcons
import io.github.iacoposb.adodash.ui.components.AdoTextField
import io.github.iacoposb.adodash.ui.components.Avatar
import io.github.iacoposb.adodash.ui.components.ButtonStyle
import io.github.iacoposb.adodash.ui.components.CopyField
import io.github.iacoposb.adodash.ui.components.SectionCard
import io.github.iacoposb.adodash.ui.components.SwitchRow
import io.github.iacoposb.adodash.ui.components.TextAction
import java.net.URLDecoder

/** What the account section edits: kept by the settings screen, which builds its form from it. */
class AccountDraft(
    val organization: String,
    val onOrganization: (String) -> Unit,
    val clientId: String,
    val onClientId: (String) -> Unit,
    val tenantId: String,
    val onTenantId: (String) -> Unit,
    val pat: String,
    val onPat: (String) -> Unit,
    val usePat: Boolean,
    val onUsePat: (Boolean) -> Unit,
)

/** What the account section does. */
class AccountActions(
    /** Microsoft's sign-in with the organization and the IDs of the draft. */
    val onMicrosoftSignIn: () -> Unit,
    /** The PAT of the draft checked against the organization, and saved. */
    val onPatSignIn: () -> Unit,
    val onSignOut: () -> Unit,
    /** Confirmed: signed out, local data cleared, the new organization kept for the next sign-in. */
    val onChangeOrganization: (String) -> Unit,
)

/**
 * Settings › Account. Signed out: the organization, the Entra registration and "Sign in with
 * Microsoft", or a PAT. Signed in: who, "Sign out", the organization (changing it signs out, after
 * a confirmation), the Entra IDs still editable ("Save and sign in again" once valid and changed)
 * and the PAT. On the right what to enter in Entra, each value to copy.
 */
@Composable
internal fun AccountSection(
    settings: Settings,
    draft: AccountDraft,
    actions: AccountActions,
    busy: Boolean,
    wide: Boolean,
    redirectUri: String,
    buildsDenied: Boolean,
    workItemsReadOnly: Boolean,
) {
    val signedIn = settings.configured
    var confirmOrganization by rememberSaveable { mutableStateOf(false) }
    val org = draft.organization.trim()
    val orgChanged = signedIn && org.isNotEmpty() && !org.equals(settings.organization, ignoreCase = true)

    val clientProblem = EntraIds.clientIdProblem(draft.clientId)
    val tenantValid = EntraIds.isValidTenant(draft.tenantId)
    val idsValid = clientProblem == null && tenantValid
    val idsChanged = draft.clientId.trim() != settings.entraClientId || draft.tenantId.trim() != settings.entraTenantId

    SettingsColumns(
        wide,
        left = {
            SectionCard(stringResource(R.string.settings_account)) {
                if (signedIn) SignedInRow(settings, busy, actions.onSignOut)
                AdoTextField(
                    draft.organization, draft.onOrganization, stringResource(R.string.settings_organization),
                    hint = stringResource(R.string.settings_organization_hint), enabled = !busy,
                )
                if (orgChanged) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextAction(stringResource(R.string.action_cancel), { draft.onOrganization(settings.organization) })
                        Spacer(Modifier.width(8.dp))
                        AdoButton(stringResource(R.string.action_change_organization), { confirmOrganization = true }, style = ButtonStyle.Tonal)
                    }
                }
                if (signedIn) PatHints(settings, buildsDenied, workItemsReadOnly)
            }
            SectionCard(stringResource(R.string.settings_entra_title)) {
                Text(
                    stringResource(if (signedIn) R.string.settings_entra_hint_signed_in else R.string.settings_entra_hint),
                    style = MaterialTheme.typography.bodySmall, color = AdoTheme.colors.textMuted,
                )
                AdoTextField(
                    draft.clientId, draft.onClientId, stringResource(R.string.settings_client_id), monospace = true,
                    error = (clientProblem as? EntraIds.ClientIdProblem.NotGuid)?.let { guidError(it) },
                )
                AdoTextField(
                    draft.tenantId, draft.onTenantId, stringResource(R.string.settings_tenant_id),
                    hint = stringResource(R.string.settings_tenant_id_hint), monospace = true,
                    placeholder = stringResource(R.string.settings_tenant_placeholder),
                    error = if (tenantValid) null else stringResource(R.string.settings_tenant_invalid),
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = AdoTheme.colors.primary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(16.dp))
                    }
                    if (signedIn) {
                        // Back to what is saved
                        if (idsChanged) {
                            TextAction(stringResource(R.string.action_cancel), {
                                draft.onClientId(settings.entraClientId)
                                draft.onTenantId(settings.entraTenantId)
                            })
                            Spacer(Modifier.width(8.dp))
                        }
                        AdoButton(
                            stringResource(R.string.action_save_and_sign_in), actions.onMicrosoftSignIn,
                            enabled = idsValid && idsChanged && !busy,
                        )
                    } else {
                        AdoButton(
                            stringResource(R.string.action_sign_in_microsoft), actions.onMicrosoftSignIn,
                            enabled = idsValid && !busy && org.isNotEmpty(),
                        )
                    }
                }
            }
        },
        right = {
            EntraValuesCard(redirectUri)
            SectionCard(stringResource(R.string.settings_more_options)) {
                SwitchRow(
                    stringResource(R.string.settings_use_pat), draft.usePat, draft.onUsePat,
                    subtitle = stringResource(R.string.settings_use_pat_hint),
                )
                if (draft.usePat) {
                    val patSignedIn = signedIn && settings.authMethod == AuthMethod.PAT
                    AdoTextField(
                        draft.pat, draft.onPat, stringResource(R.string.settings_pat),
                        hint = stringResource(R.string.settings_pat_hint),
                        // Signed in with one: a new one replaces it, the saved one is never shown
                        placeholder = if (patSignedIn) stringResource(R.string.settings_pat_saved) else null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        AdoButton(
                            stringResource(if (patSignedIn) R.string.action_save_pat else R.string.action_sign_in_pat), actions.onPatSignIn,
                            style = ButtonStyle.Tonal,
                            enabled = !busy && (if (signedIn) settings.organization else org).isNotBlank() && draft.pat.isNotBlank(),
                        )
                    }
                }
            }
        },
    )

    if (confirmOrganization) {
        val c = AdoTheme.colors
        AlertDialog(
            onDismissRequest = { confirmOrganization = false },
            containerColor = c.surface,
            title = { Text(stringResource(R.string.dialog_change_organization_title), color = c.text) },
            text = {
                Text(
                    stringResource(R.string.dialog_change_organization_text, settings.organization),
                    style = MaterialTheme.typography.bodyMedium, color = c.textMuted,
                )
            },
            confirmButton = {
                TextAction(stringResource(R.string.action_change_and_sign_out), {
                    confirmOrganization = false
                    actions.onChangeOrganization(org)
                }, color = c.danger)
            },
            dismissButton = { TextAction(stringResource(R.string.action_cancel), { confirmOrganization = false }) },
        )
    }
}

/** "Not a valid GUID: 8-4-4-4-12 hexadecimal digits (one missing)." */
@Composable
private fun guidError(problem: EntraIds.ClientIdProblem.NotGuid): String {
    val base = stringResource(R.string.settings_guid_invalid)
    val missing = problem.missing ?: 0
    val detail = when {
        missing > 0 -> pluralStringResource(R.plurals.settings_guid_missing, missing, missing)
        missing < 0 -> pluralStringResource(R.plurals.settings_guid_extra, -missing, -missing)
        else -> null
    }
    return if (detail == null) "$base." else "$base $detail."
}

@Composable
private fun SignedInRow(settings: Settings, busy: Boolean, onSignOut: () -> Unit) {
    val c = AdoTheme.colors
    val (name, detail) = accountLine(settings)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surfaceAlt)
            .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(settings.meName)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
        if (!busy) TextAction(stringResource(R.string.action_sign_out), onSignOut)
    }
}

/** With a PAT: what it lacks, as found by the sync and by a refused change. */
@Composable
private fun PatHints(settings: Settings, buildsDenied: Boolean, workItemsReadOnly: Boolean) {
    val c = AdoTheme.colors
    val hints = buildList {
        if (settings.authMethod == AuthMethod.PAT) {
            if (buildsDenied) add(R.string.settings_pat_no_builds)
            if (workItemsReadOnly) add(R.string.settings_pat_no_write)
        }
    }
    hints.forEach { hint ->
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(AdoIcons.Error, null, Modifier.size(16.dp), tint = c.warning)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = c.textMuted)
        }
    }
}

/** What to enter in Entra: package, signature hash and the redirect URI they make, each to copy. */
@Composable
private fun EntraValuesCard(uri: String) {
    val clipboard = LocalClipboardManager.current
    // msauth://<package>/<url-encoded hash>: Entra asks for the two parts separately
    val pkg = uri.removePrefix("msauth://").substringBefore('/')
    val hash = URLDecoder.decode(uri.substringAfterLast('/'), "UTF-8")
    val copy = stringResource(R.string.action_copy)
    SectionCard(stringResource(R.string.settings_entra_values)) {
        Text(stringResource(R.string.settings_entra_path), style = MaterialTheme.typography.bodySmall, color = AdoTheme.colors.textMuted)
        CopyField(stringResource(R.string.settings_package), pkg, copy, { clipboard.setText(AnnotatedString(pkg)) })
        CopyField(stringResource(R.string.settings_signature_hash), hash, copy, { clipboard.setText(AnnotatedString(hash)) })
        CopyField(stringResource(R.string.settings_redirect_uri), uri, copy, { clipboard.setText(AnnotatedString(uri)) })
        Text(stringResource(R.string.settings_entra_permissions), style = MaterialTheme.typography.bodySmall, color = AdoTheme.colors.textMuted)
    }
}
