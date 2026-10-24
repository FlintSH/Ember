package dev.fl1nt.ember.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fl1nt.ember.R
import dev.fl1nt.ember.api.Access
import dev.fl1nt.ember.api.Capabilities
import dev.fl1nt.ember.system.Clipboard
import dev.fl1nt.ember.system.Links
import dev.fl1nt.ember.system.LocalNetworkAccess
import dev.fl1nt.ember.ui.components.EmberMark
import dev.fl1nt.ember.ui.components.EmberTextField
import dev.fl1nt.ember.ui.components.Eyebrow
import dev.fl1nt.ember.ui.components.FlareCard
import dev.fl1nt.ember.ui.components.GhostButton
import dev.fl1nt.ember.ui.components.GlowBackground
import dev.fl1nt.ember.ui.components.IconAction
import dev.fl1nt.ember.ui.components.IconTile
import dev.fl1nt.ember.ui.components.Notice
import dev.fl1nt.ember.ui.components.PrimaryButton
import dev.fl1nt.ember.ui.components.Tone
import dev.fl1nt.ember.ui.emberViewModel
import dev.fl1nt.ember.ui.icons.Lucide

const val DOCS_URL = "https://github.com/FlintSH/Ember/blob/main/docs"

@Composable
private fun OnboardingFrame(content: @Composable () -> Unit) {
    GlowBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) { content() }
        }
    }
}

@Composable
fun WelcomeScreen(onConnect: () -> Unit) {
    OnboardingFrame {
        Spacer(Modifier.height(36.dp))
        EmberMark(size = 72.dp, glow = true)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Feature(Lucide.Share2, R.string.welcome_feature_share_title, R.string.welcome_feature_share_body)
            Feature(Lucide.FileText, R.string.welcome_feature_paste_title, R.string.welcome_feature_paste_body)
            Feature(Lucide.FolderSync, R.string.welcome_feature_sync_title, R.string.welcome_feature_sync_body)
        }
        Spacer(Modifier.height(32.dp))
        PrimaryButton(stringResource(R.string.welcome_connect), onClick = onConnect, icon = Lucide.ArrowUpRight, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.welcome_footnote), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, body: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        IconTile(icon)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ConnectRoute(onBack: () -> Unit) {
    val viewModel = emberViewModel { ConnectViewModel(it, it.application) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val localNetworkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(), viewModel::onLocalNetworkResult)

    ConnectScreen(
        state = state,
        onBack = onBack,
        onAddressChange = viewModel::onAddressChange,
        onTokenChange = viewModel::onTokenChange,
        onPasteToken = { Clipboard.readText(context)?.let(viewModel::onTokenChange) },
        onToggleToken = viewModel::toggleTokenVisible,
        onConnect = viewModel::connect,
        onHelp = { Links.open(context, "$DOCS_URL/getting-started.md") },
    )

    when (val step = state.step) {
        is ConnectStep.ConfirmHttp -> AlertDialog(
            onDismissRequest = viewModel::dismissPrompt,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            icon = { Icon(Lucide.TriangleAlert, contentDescription = null) },
            title = { Text(stringResource(R.string.connect_http_title)) },
            text = { Text(stringResource(R.string.connect_http_body, step.host)) },
            confirmButton = { GhostButton(stringResource(R.string.connect_http_confirm), onClick = viewModel::confirmHttp, color = MaterialTheme.colorScheme.error) },
            dismissButton = { GhostButton(stringResource(R.string.action_cancel), onClick = viewModel::dismissPrompt) },
        )
        is ConnectStep.NeedsLocalNetwork -> AlertDialog(
            onDismissRequest = viewModel::dismissPrompt,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            icon = { Icon(Lucide.Network, contentDescription = null) },
            title = { Text(stringResource(R.string.connect_local_title)) },
            text = { Text(stringResource(R.string.connect_local_body, step.host)) },
            confirmButton = {
                GhostButton(stringResource(R.string.action_continue), onClick = {
                    LocalNetworkAccess.PERMISSION?.let(localNetworkLauncher::launch) ?: viewModel.onLocalNetworkResult(true)
                })
            },
            dismissButton = { GhostButton(stringResource(R.string.action_cancel), onClick = viewModel::dismissPrompt) },
        )
        else -> Unit
    }
}

@Composable
fun ConnectScreen(
    state: ConnectState,
    onBack: () -> Unit,
    onAddressChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onPasteToken: () -> Unit,
    onToggleToken: () -> Unit,
    onConnect: () -> Unit,
    onHelp: () -> Unit,
) {
    val checking = state.step == ConnectStep.Checking
    OnboardingFrame {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconAction(Lucide.ArrowLeft, stringResource(R.string.action_back), onBack, tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(8.dp))
        Eyebrow(stringResource(R.string.connect_eyebrow))
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.connect_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        EmberTextField(
            value = state.address,
            onValueChange = onAddressChange,
            label = stringResource(R.string.connect_address_label),
            placeholder = stringResource(R.string.connect_address_placeholder),
            supportingText = stringResource(R.string.connect_address_help),
            errorText = state.addressError,
            enabled = !checking,
            leadingIcon = Lucide.Globe,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        )
        Spacer(Modifier.height(18.dp))
        EmberTextField(
            value = state.token,
            onValueChange = onTokenChange,
            label = stringResource(R.string.connect_token_label),
            placeholder = "flr_…",
            supportingText = stringResource(R.string.connect_token_help),
            errorText = state.tokenError,
            enabled = !checking,
            leadingIcon = Lucide.KeyRound,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            visualTransformation = if (state.tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { onConnect() }),
            trailing = {
                Row {
                    IconAction(Lucide.ClipboardPaste, stringResource(R.string.connect_paste_token), onPasteToken)
                    IconAction(
                        if (state.tokenVisible) Lucide.EyeOff else Lucide.Eye,
                        stringResource(if (state.tokenVisible) R.string.connect_hide_token else R.string.connect_show_token),
                        onToggleToken,
                    )
                }
            },
        )
        state.error?.let {
            Spacer(Modifier.height(16.dp))
            Notice(it, tone = Tone.DANGER)
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            stringResource(if (checking) R.string.connect_checking else R.string.connect_action),
            onClick = onConnect,
            loading = checking,
            enabled = state.address.isNotBlank() && state.token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        FlareCard {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(Lucide.KeyRound, size = 36.dp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.connect_guide_title), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.connect_guide_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GhostButton(stringResource(R.string.connect_guide_link), onClick = onHelp, icon = Lucide.BookOpen, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
fun ReadyScreen(capabilities: Capabilities, host: String, onDone: () -> Unit) {
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }
    OnboardingFrame {
        Spacer(Modifier.height(36.dp))
        IconTile(Lucide.CircleCheck, size = 56.dp, tint = dev.fl1nt.ember.ui.theme.EmberTheme.colors.success)
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.ready_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.ready_subtitle, host), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        FlareCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
            CapabilityLine(R.string.capability_upload, capabilities.uploadFiles)
            CapabilityLine(R.string.capability_library, capabilities.readFiles)
            CapabilityLine(R.string.capability_links, if (capabilities.manageLinks.isGranted) Access.GRANTED else capabilities.manageLinks)
            CapabilityLine(R.string.capability_folders, capabilities.listFolders, unavailable = R.string.access_account_token_only)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.ready_capabilities_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PrimaryButton(
                stringResource(R.string.ready_allow_notifications),
                onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                icon = Lucide.Bell,
                modifier = Modifier.fillMaxWidth(),
            )
            GhostButton(stringResource(R.string.ready_skip_notifications), onClick = onDone, modifier = Modifier.fillMaxWidth())
        } else {
            PrimaryButton(stringResource(R.string.ready_start), onClick = onDone, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun CapabilityLine(label: Int, access: Access, unavailable: Int? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val (icon, tint) = when (access) {
            Access.GRANTED -> Lucide.CircleCheck to dev.fl1nt.ember.ui.theme.EmberTheme.colors.success
            Access.DENIED_BY_ROLE -> Lucide.CircleX to MaterialTheme.colorScheme.error
            Access.NOT_IN_TOKEN -> Lucide.CircleX to MaterialTheme.colorScheme.onSurfaceVariant
            Access.UNKNOWN -> Lucide.CircleHelp to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            stringResource(
                when (access) {
                    Access.GRANTED -> R.string.access_granted
                    Access.NOT_IN_TOKEN -> unavailable ?: R.string.access_not_in_token
                    Access.DENIED_BY_ROLE -> R.string.access_role
                    Access.UNKNOWN -> R.string.access_unknown
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
