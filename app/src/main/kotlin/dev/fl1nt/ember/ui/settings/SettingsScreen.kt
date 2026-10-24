package dev.fl1nt.ember.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.fl1nt.ember.AppContainer
import dev.fl1nt.ember.BuildConfig
import dev.fl1nt.ember.R
import dev.fl1nt.ember.api.FlareCredential
import dev.fl1nt.ember.api.RemoteFolder
import dev.fl1nt.ember.data.account.Account
import dev.fl1nt.ember.data.settings.EmberSettings
import dev.fl1nt.ember.data.settings.LinkFormat
import dev.fl1nt.ember.system.Links
import dev.fl1nt.ember.ui.components.DangerButton
import dev.fl1nt.ember.ui.components.EmberTextField
import dev.fl1nt.ember.ui.components.ExpirationRow
import dev.fl1nt.ember.ui.components.ExpiryActionRow
import dev.fl1nt.ember.ui.components.FlareCard
import dev.fl1nt.ember.ui.components.FolderRow
import dev.fl1nt.ember.ui.components.GhostButton
import dev.fl1nt.ember.ui.components.Hairline
import dev.fl1nt.ember.ui.components.Notice
import dev.fl1nt.ember.ui.components.Option
import dev.fl1nt.ember.ui.components.OptionRow
import dev.fl1nt.ember.ui.components.OutlineButton
import dev.fl1nt.ember.ui.components.ProfileRow
import dev.fl1nt.ember.ui.components.ScreenHeader
import dev.fl1nt.ember.ui.components.SectionTitle
import dev.fl1nt.ember.ui.components.SettingRow
import dev.fl1nt.ember.ui.components.SwitchRow
import dev.fl1nt.ember.ui.components.Tone
import dev.fl1nt.ember.ui.components.VisibilityRow
import dev.fl1nt.ember.ui.describeError
import dev.fl1nt.ember.ui.emberViewModel
import dev.fl1nt.ember.ui.icons.Lucide
import dev.fl1nt.ember.ui.onboarding.CapabilityLine
import dev.fl1nt.ember.ui.onboarding.DOCS_URL
import dev.fl1nt.ember.ui.sync.fetchFolders
import dev.fl1nt.ember.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsState(
    val account: Account? = null,
    val settings: EmberSettings = EmberSettings(),
    val folders: List<RemoteFolder>? = null,
    val checking: Boolean = false,
    val checkError: String? = null,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val folders = MutableStateFlow<List<RemoteFolder>?>(null)
    private val checking = MutableStateFlow(false)
    private val checkError = MutableStateFlow<String?>(null)

    val state: StateFlow<SettingsState> = combine(container.accounts.account, container.settings.settings, folders, checking, checkError) { account, settings, folders, checking, error ->
        SettingsState(account, settings, folders, checking, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    init {
        viewModelScope.launch { folders.value = fetchFolders(container) }
    }

    fun update(transform: (EmberSettings) -> EmberSettings) = viewModelScope.launch {
        val before = container.settings.current().sync
        container.settings.update(transform)
        if (container.settings.current().sync != before) container.sync.reschedule(force = true)
    }

    fun rename(name: String) = viewModelScope.launch { container.accounts.rename(name) }

    fun recheck() = viewModelScope.launch {
        checking.value = true
        checkError.value = null
        try {
            val client = container.connection.client() ?: return@launch
            container.accounts.updateCapabilities(client.probeCapabilities())
            folders.value = fetchFolders(container)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            checkError.value = describeError(container.application, error, container.accounts.current()?.origin?.displayName)
        } finally {
            checking.value = false
        }
    }

    fun disconnect() = viewModelScope.launch {
        container.scheduler.cancelAll()
        container.accounts.disconnect()
    }
}

@Composable
fun SettingsRoute() {
    val viewModel = emberViewModel { SettingsViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    SettingsScreen(state, viewModel::update, { viewModel.rename(it) }, { viewModel.recheck() }, { viewModel.disconnect() })
}

@Composable
fun SettingsScreen(
    state: SettingsState,
    onUpdate: ((EmberSettings) -> EmberSettings) -> Unit,
    onRename: (String) -> Unit,
    onRecheck: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val context = LocalContext.current
    val settings = state.settings
    var confirmDisconnect by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    fun defaults(transform: (dev.fl1nt.ember.data.settings.UploadPreset) -> dev.fl1nt.ember.data.settings.UploadPreset) =
        onUpdate { it.copy(defaults = transform(it.defaults)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenHeader(eyebrow = stringResource(R.string.settings_eyebrow), title = stringResource(R.string.settings_title))

        state.account?.let { account ->
            SectionTitle(stringResource(R.string.settings_connection))
            FlareCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                SettingRow(
                    title = account.displayName,
                    description = account.origin.toString(),
                    icon = Lucide.Server,
                    onClick = { renaming = true },
                ) { Text(stringResource(R.string.action_rename), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                SettingRow(
                    title = stringResource(R.string.settings_token),
                    description = stringResource(if (account.credentialKind == FlareCredential.Kind.NAMED_TOKEN) R.string.settings_token_named else R.string.settings_token_account),
                    icon = Lucide.KeyRound,
                )
                Hairline()
                CapabilityLine(R.string.capability_upload, account.capabilities.uploadFiles)
                CapabilityLine(R.string.capability_library, account.capabilities.readFiles)
                CapabilityLine(R.string.capability_links, account.capabilities.manageLinks)
                CapabilityLine(R.string.capability_folders, account.capabilities.listFolders, unavailable = R.string.access_account_token_only)
                state.checkError?.let { Notice(it, tone = Tone.DANGER, modifier = Modifier.padding(12.dp)) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    GhostButton(stringResource(if (state.checking) R.string.settings_checking else R.string.settings_check_access), onClick = onRecheck, icon = Lucide.RefreshCw, enabled = !state.checking)
                    GhostButton(stringResource(R.string.settings_token_help), onClick = { Links.open(context, "$DOCS_URL/getting-started.md#create-a-token") }, icon = Lucide.BookOpen)
                }
            }
        }

        SectionTitle(stringResource(R.string.settings_defaults))
        FlareCard(contentPadding = PaddingValues(vertical = 4.dp)) {
            VisibilityRow(settings.defaults.visibility, { value -> defaults { it.copy(visibility = value) } }, emberDefaults = null)
            ExpirationRow(settings.defaults.expiration, { value -> defaults { it.copy(expiration = value) } }, emberDefaults = null)
            ExpiryActionRow(settings.defaults.expiryAction, { value -> defaults { it.copy(expiryAction = value) } }, emberDefaults = null)
            FolderRow(settings.defaults.folder, { value -> defaults { it.copy(folder = value) } }, state.folders, emberDefaults = null)
            ProfileRow(settings.defaults.profile, { value -> defaults { it.copy(profile = value) } })
        }
        Text(stringResource(R.string.settings_defaults_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionTitle(stringResource(R.string.settings_sharing))
        FlareCard(contentPadding = PaddingValues(vertical = 4.dp)) {
            SwitchRow(stringResource(R.string.settings_copy_link), settings.copyLinkAfterUpload, { value -> onUpdate { it.copy(copyLinkAfterUpload = value) } }, description = stringResource(R.string.settings_copy_link_hint), icon = Lucide.Copy)
            OptionRow(
                title = stringResource(R.string.settings_link_format),
                icon = Lucide.Link,
                options = listOf(
                    Option(LinkFormat.SHARE_PAGE, stringResource(R.string.settings_link_page), stringResource(R.string.settings_link_page_hint)),
                    Option(LinkFormat.RAW_FILE, stringResource(R.string.settings_link_raw), stringResource(R.string.settings_link_raw_hint)),
                ),
                selected = settings.linkFormat,
                onSelect = { value -> onUpdate { it.copy(linkFormat = value) } },
            )
            SwitchRow(stringResource(R.string.settings_review), settings.reviewShares, { value -> onUpdate { it.copy(reviewShares = value) } }, description = stringResource(R.string.settings_review_hint), icon = Lucide.SlidersHorizontal)
            SwitchRow(stringResource(R.string.settings_notify), settings.notifyWhenDone, { value -> onUpdate { it.copy(notifyWhenDone = value) } }, description = stringResource(R.string.settings_notify_hint), icon = Lucide.Bell)
        }

        SectionTitle(stringResource(R.string.settings_pastes))
        FlareCard(contentPadding = PaddingValues(vertical = 4.dp)) {
            OptionRow(
                title = stringResource(R.string.settings_paste_name),
                icon = Lucide.FileText,
                options = listOf("paste.txt", "paste.md", "snippet.txt", "notes.md").map { Option(it, it) },
                selected = settings.pasteFileName,
                onSelect = { value -> onUpdate { it.copy(pasteFileName = value) } },
            )
            SwitchRow(stringResource(R.string.settings_replace_selection), settings.replaceSelection, { value -> onUpdate { it.copy(replaceSelection = value) } }, description = stringResource(R.string.settings_replace_selection_hint), icon = Lucide.TextSelect)
        }

        SectionTitle(stringResource(R.string.settings_sync))
        FlareCard(contentPadding = PaddingValues(vertical = 4.dp)) {
            OptionRow(
                title = stringResource(R.string.settings_sync_network),
                icon = Lucide.Wifi,
                options = listOf(
                    Option(true, stringResource(R.string.sync_wifi_only), stringResource(R.string.settings_sync_wifi_hint)),
                    Option(false, stringResource(R.string.sync_any_network), stringResource(R.string.settings_sync_any_hint)),
                ),
                selected = settings.sync.wifiOnly,
                onSelect = { value -> onUpdate { it.copy(sync = it.sync.copy(wifiOnly = value)) } },
            )
            SwitchRow(stringResource(R.string.settings_sync_charging), settings.sync.chargingOnly, { value -> onUpdate { it.copy(sync = it.sync.copy(chargingOnly = value)) } }, icon = Lucide.BatteryCharging)
            OptionRow(
                title = stringResource(R.string.settings_sync_interval),
                description = stringResource(R.string.settings_sync_interval_hint),
                icon = Lucide.Clock,
                options = EmberSettings.SYNC_INTERVALS.map { minutes -> Option(minutes, intervalLabel(minutes)) },
                selected = settings.sync.intervalMinutes,
                onSelect = { value -> onUpdate { it.copy(sync = it.sync.copy(intervalMinutes = value)) } },
            )
        }

        SectionTitle(stringResource(R.string.settings_appearance))
        FlareCard(contentPadding = PaddingValues(vertical = 4.dp)) {
            OptionRow(
                title = stringResource(R.string.settings_theme),
                icon = Lucide.Palette,
                options = listOf(
                    Option(ThemeMode.DARK, stringResource(R.string.theme_dark), stringResource(R.string.theme_dark_hint)),
                    Option(ThemeMode.LIGHT, stringResource(R.string.theme_light)),
                    Option(ThemeMode.SYSTEM, stringResource(R.string.theme_system)),
                ),
                selected = settings.themeMode,
                onSelect = { value -> onUpdate { it.copy(themeMode = value) } },
            )
        }

        SectionTitle(stringResource(R.string.settings_about))
        FlareCard(contentPadding = PaddingValues(vertical = 4.dp)) {
            SettingRow(
                title = stringResource(R.string.settings_version),
                description = "${BuildConfig.VERSION_NAME} · ${BuildConfig.CHANNEL}",
                icon = Lucide.Smartphone,
            )
            SettingRow(stringResource(R.string.settings_github), description = stringResource(R.string.settings_github_hint), icon = Lucide.ExternalLink, onClick = { Links.open(context, BuildConfig.REPOSITORY_URL) })
            SettingRow(stringResource(R.string.settings_docs), icon = Lucide.BookOpen, onClick = { Links.open(context, "$DOCS_URL/README.md") })
            SettingRow(stringResource(R.string.settings_report), icon = Lucide.CircleAlert, onClick = { Links.open(context, "${BuildConfig.REPOSITORY_URL}/issues/new/choose") })
            SettingRow(stringResource(R.string.settings_flare), description = stringResource(R.string.settings_flare_hint), icon = Lucide.Globe, onClick = { Links.open(context, "https://github.com/FlintSH/Flare") })
            SettingRow(stringResource(R.string.settings_licenses), icon = Lucide.FileText, onClick = { showLicenses = true })
        }

        if (state.account != null) {
            Spacer(Modifier.height(4.dp))
            DangerButton(stringResource(R.string.settings_disconnect), onClick = { confirmDisconnect = true }, icon = Lucide.LogOut, modifier = Modifier.fillMaxWidth())
        }
        Text(stringResource(R.string.settings_credit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.settings_disconnect_title)) },
            text = { Text(stringResource(R.string.settings_disconnect_body)) },
            confirmButton = { GhostButton(stringResource(R.string.settings_disconnect), onClick = { confirmDisconnect = false; onDisconnect() }, color = MaterialTheme.colorScheme.error) },
            dismissButton = { GhostButton(stringResource(R.string.action_cancel), onClick = { confirmDisconnect = false }) },
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(state.account?.displayName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.settings_rename_title)) },
            text = { EmberTextField(value = name, onValueChange = { name = it }, label = stringResource(R.string.settings_rename_label)) },
            confirmButton = { GhostButton(stringResource(R.string.action_save), onClick = { onRename(name); renaming = false }, enabled = name.isNotBlank()) },
            dismissButton = { GhostButton(stringResource(R.string.action_cancel), onClick = { renaming = false }) },
        )
    }
    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.settings_licenses)) },
            text = { Text(stringResource(R.string.licenses_body)) },
            confirmButton = { GhostButton(stringResource(R.string.licenses_full), onClick = { Links.open(context, "${BuildConfig.REPOSITORY_URL}/blob/main/THIRD_PARTY_NOTICES.md") }) },
            dismissButton = { OutlineButton(stringResource(R.string.action_close), onClick = { showLicenses = false }) },
        )
    }
}

@Composable
private fun intervalLabel(minutes: Int): String = when {
    minutes < 60 -> androidx.compose.ui.res.pluralStringResource(R.plurals.interval_minutes, minutes, minutes)
    minutes < 1440 -> androidx.compose.ui.res.pluralStringResource(R.plurals.interval_hours, minutes / 60, minutes / 60)
    else -> stringResource(R.string.interval_day)
}
