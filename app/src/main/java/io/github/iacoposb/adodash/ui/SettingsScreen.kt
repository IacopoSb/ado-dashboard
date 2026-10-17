package io.github.iacoposb.adodash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iacoposb.adodash.R
import io.github.iacoposb.adodash.data.AuthMethod
import io.github.iacoposb.adodash.data.Filters
import io.github.iacoposb.adodash.data.Settings
import io.github.iacoposb.adodash.ui.components.AdoButton
import io.github.iacoposb.adodash.ui.components.AdoIcons
import io.github.iacoposb.adodash.ui.components.AdoTextField
import io.github.iacoposb.adodash.ui.components.Avatar
import io.github.iacoposb.adodash.ui.components.Banner
import io.github.iacoposb.adodash.ui.components.BannerTone
import io.github.iacoposb.adodash.ui.components.ButtonStyle
import io.github.iacoposb.adodash.ui.components.CopyField
import io.github.iacoposb.adodash.ui.components.SectionCard
import io.github.iacoposb.adodash.ui.components.SwitchRow
import io.github.iacoposb.adodash.ui.components.TextAction
import io.github.iacoposb.adodash.sync.CatalogState
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

/** The settings' sections, in the order of the navigation. */
enum class SettingsSection { ACCOUNT, FILTERS, SCREEN, ABOUT }

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
    /** Opened from the update banner or notification: shows "About". */
    showUpdate: Boolean = false,
    /** The section shown first, if not the account (or "About" with [showUpdate]). */
    initialSection: SettingsSection? = null,
    catalog: CatalogState = CatalogState(),
    /** The filters section opened: time to (re)read projects, areas and types. */
    onLoadCatalog: () -> Unit = {},
    onFiltersChange: (Filters) -> Unit = {},
    /** For the screenshots: the filters' tree as in the design. */
    filtersExpanded: Set<String>? = null,
    filtersQuery: String = "",
    /** The last sync could not read builds: with a PAT, a hint to add Build (read). */
    buildsDenied: Boolean = false,
    /** A change of a work item was refused: with a PAT, a hint to add Work Items (Read & write). */
    workItemsReadOnly: Boolean = false,
    /** "What do you want to see?" at launch; applied at once. */
    askViewAtLaunch: Boolean = true,
    onAskViewAtLaunchChange: (Boolean) -> Unit = {},
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

    var section by rememberSaveable { mutableStateOf(if (showUpdate) SettingsSection.ABOUT else initialSection ?: SettingsSection.ACCOUNT) }
    // Narrow screens: the navigation is a list and a section opens on its own
    var sectionOpen by rememberSaveable { mutableStateOf(showUpdate || initialSection != null || !initial.configured) }
    LaunchedEffect(showUpdate) {
        if (showUpdate) {
            section = SettingsSection.ABOUT
            sectionOpen = true
        }
    }

    val content: @Composable ColumnScope.(wide: Boolean) -> Unit = { wide ->
        message?.let { Banner(it, BannerTone.Error) }
        when (section) {
            SettingsSection.FILTERS -> if (initial.configured) {
                FiltersSection(
                    initial.filters, catalog, onFiltersChange, onLoadCatalog, wide,
                    if (wide) Modifier.weight(1f) else Modifier,
                    initiallyExpanded = filtersExpanded, initialQuery = filtersQuery,
                )
            } else {
                Columns(wide, left = {
                    SectionCard(stringResource(R.string.settings_section_filters)) {
                        Text(
                            stringResource(R.string.filters_sign_in_first),
                            style = MaterialTheme.typography.bodyMedium, color = AdoTheme.colors.textMuted,
                        )
                    }
                })
            }
            SettingsSection.ACCOUNT -> if (initial.configured) {
                Columns(wide, left = {
                    SignedInCard(initial, busy, onSignOut, buildsDenied, workItemsReadOnly)
                })
            } else {
                Columns(
                    wide,
                    left = {
                        SectionCard(stringResource(R.string.settings_account)) {
                            AdoTextField(
                                organization, { organization = it }, stringResource(R.string.settings_organization),
                                hint = stringResource(R.string.settings_organization_hint),
                            )
                        }
                        SectionCard(stringResource(R.string.settings_entra_title)) {
                            Text(
                                stringResource(R.string.settings_entra_hint),
                                style = MaterialTheme.typography.bodySmall, color = AdoTheme.colors.textMuted,
                            )
                            AdoTextField(clientId, { clientId = it }, stringResource(R.string.settings_client_id), monospace = true)
                            AdoTextField(
                                tenantId, { tenantId = it }, stringResource(R.string.settings_tenant_id),
                                hint = stringResource(R.string.settings_tenant_id_hint), monospace = true,
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(Modifier.size(24.dp), color = AdoTheme.colors.primary, strokeWidth = 2.dp)
                                    Spacer(Modifier.width(16.dp))
                                }
                                AdoButton(
                                    stringResource(R.string.action_sign_in_microsoft), { onMicrosoftSignIn(form()) },
                                    enabled = clientId.isNotBlank() && !busy && organization.isNotBlank(),
                                )
                            }
                        }
                    },
                    right = {
                        EntraValuesCard(redirectUri)
                        SectionCard(stringResource(R.string.settings_more_options)) {
                            SwitchRow(
                                stringResource(R.string.settings_use_pat), showPat, { showPat = it },
                                subtitle = stringResource(R.string.settings_use_pat_hint),
                            )
                            if (showPat) {
                                AdoTextField(
                                    pat, { pat = it }, stringResource(R.string.settings_pat),
                                    hint = stringResource(R.string.settings_pat_hint),
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    AdoButton(
                                        stringResource(R.string.action_sign_in_pat), { onPatSignIn(form(), pat.trim()) },
                                        style = ButtonStyle.Tonal,
                                        enabled = !busy && organization.isNotBlank() && pat.isNotBlank(),
                                    )
                                }
                            }
                        }
                    },
                )
            }
            SettingsSection.SCREEN -> Columns(wide, left = {
                SectionCard(stringResource(R.string.settings_dashboard)) {
                    AdoTextField(
                        poll, { poll = it.filter(Char::isDigit) }, stringResource(R.string.settings_poll),
                        hint = stringResource(R.string.settings_poll_hint),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    SwitchRow(stringResource(R.string.settings_keep_screen_on), keepOn, { keepOn = it })
                    if (initial.configured) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            AdoButton(stringResource(R.string.action_save), { onSavePreferences(form()) }, enabled = !busy)
                        }
                    }
                }
                if (initial.configured) {
                    SectionCard(stringResource(R.string.switcher_views)) {
                        SwitchRow(
                            stringResource(R.string.settings_ask_view), askViewAtLaunch, onAskViewAtLaunchChange,
                            subtitle = stringResource(R.string.settings_ask_view_hint),
                        )
                    }
                }
            })
            SettingsSection.ABOUT -> Columns(wide, left = {
                AboutSection(version, update, onCheckUpdates, onDownloadUpdate, onInstallUpdate)
            })
        }
    }

    val c = AdoTheme.colors
    BoxWithConstraints(
        Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val wide = maxWidth >= 840.dp
        val inSection = !wide && sectionOpen
        BackHandler(enabled = inSection) { sectionOpen = false }
        val filtersShown = section == SettingsSection.FILTERS && (wide || inSection) && initial.configured
        LaunchedEffect(filtersShown) { if (filtersShown) onLoadCatalog() }
        Column(Modifier.fillMaxSize()) {
            SettingsBar(
                title = if (inSection) stringResource(section.title) else stringResource(R.string.settings_title),
                onBack = if (inSection) ({ sectionOpen = false }) else onBack,
                subtitle = if (filtersShown) stringResource(R.string.settings_applied_now) else null,
            )
            if (wide) {
                Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Navigation(initial, section, { section = it }, Modifier.width(300.dp).fillMaxHeight(), arrows = false)
                    // The filters' tree scrolls inside its card, the other sections as a whole
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .then(if (filtersShown) Modifier else Modifier.verticalScroll(rememberScrollState())),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { content(true) }
                }
            } else if (inSection) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { content(false) }
            } else {
                Navigation(
                    initial, null, { section = it; sectionOpen = true },
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), arrows = true,
                )
            }
        }
    }
}

private val SettingsSection.title
    get() = when (this) {
        SettingsSection.ACCOUNT -> R.string.settings_section_account
        SettingsSection.FILTERS -> R.string.settings_section_filters
        SettingsSection.SCREEN -> R.string.settings_section_screen
        SettingsSection.ABOUT -> R.string.about_title
    }

private val SettingsSection.icon: ImageVector
    get() = when (this) {
        SettingsSection.ACCOUNT -> Icons.Filled.Person
        SettingsSection.FILTERS -> AdoIcons.FilterList
        SettingsSection.SCREEN -> Icons.Filled.Refresh
        SettingsSection.ABOUT -> AdoIcons.OpenInNew
    }

@Composable
private fun SettingsBar(title: String, onBack: (() -> Unit)?, subtitle: String? = null) {
    val c = AdoTheme.colors
    Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = c.text)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
        Column {
            Text(
                title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = c.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Two columns of cards on wide screens (the right one narrower), one below the other otherwise. */
@Composable
private fun Columns(wide: Boolean, left: @Composable ColumnScope.() -> Unit, right: @Composable ColumnScope.() -> Unit = {}) {
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1.45f), verticalArrangement = Arrangement.spacedBy(12.dp), content = left)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), content = right)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            left()
            right()
        }
    }
}

/** Who is signed in, on top of the navigation and in the account section. */
@Composable
private fun accountLine(settings: Settings): Pair<String, String> {
    if (!settings.configured) return stringResource(R.string.settings_not_signed_in) to settings.organization
    val method = stringResource(if (settings.authMethod == AuthMethod.ENTRA) R.string.settings_auth_microsoft else R.string.settings_auth_pat)
    return settings.meName to "$method · ${settings.organization}"
}

@Composable
private fun Navigation(
    settings: Settings,
    selected: SettingsSection?,
    onSelect: (SettingsSection) -> Unit,
    modifier: Modifier,
    arrows: Boolean,
) {
    val c = AdoTheme.colors
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(c.surface).padding(12.dp)) {
        val (name, detail) = accountLine(settings)
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (settings.configured) {
                Avatar(settings.meName)
            } else {
                Icon(Icons.Filled.Person, null, Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(c.surfaceAlt).padding(6.dp), tint = c.textMuted)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsSection.entries.forEach { s ->
                val on = s == selected
                Row(
                    Modifier.fillMaxWidth().height(if (arrows) 52.dp else 44.dp).clip(RoundedCornerShape(22.dp))
                        .then(if (on) Modifier.background(c.primaryContainer) else Modifier)
                        .selectable(on, onClick = { onSelect(s) }, role = Role.Tab)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(s.icon, null, Modifier.size(20.dp), tint = if (on) c.onPrimaryContainer else c.textMuted)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(s.title), Modifier.weight(1f),
                        style = if (on) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        color = if (on) c.onPrimaryContainer else c.text,
                    )
                    if (arrows) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.textFaint)
                }
            }
        }
    }
}

@Composable
private fun SignedInCard(settings: Settings, busy: Boolean, onSignOut: () -> Unit, buildsDenied: Boolean, workItemsReadOnly: Boolean) {
    val c = AdoTheme.colors
    SectionCard(stringResource(R.string.settings_account)) {
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
    }
}
