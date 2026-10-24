package dev.fl1nt.ember.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fl1nt.ember.R
import dev.fl1nt.ember.data.db.UploadEntity
import dev.fl1nt.ember.system.Clipboard
import dev.fl1nt.ember.ui.LocalAppContainer
import dev.fl1nt.ember.ui.components.AccountProblemNotice
import dev.fl1nt.ember.ui.components.DashedCard
import dev.fl1nt.ember.ui.components.EmberWordmark
import dev.fl1nt.ember.ui.components.EmptyState
import dev.fl1nt.ember.ui.components.Eyebrow
import dev.fl1nt.ember.ui.components.FlameProgress
import dev.fl1nt.ember.ui.components.FlareCard
import dev.fl1nt.ember.ui.components.Formats
import dev.fl1nt.ember.ui.components.GhostButton
import dev.fl1nt.ember.ui.components.GlowBackground
import dev.fl1nt.ember.ui.components.IconTile
import dev.fl1nt.ember.ui.components.LocalNetworkNotice
import dev.fl1nt.ember.ui.components.SectionTitle
import dev.fl1nt.ember.ui.components.SettingRow
import dev.fl1nt.ember.ui.components.StatusDot
import dev.fl1nt.ember.ui.components.StatusPill
import dev.fl1nt.ember.ui.components.Tone
import dev.fl1nt.ember.ui.components.UploadRow
import dev.fl1nt.ember.ui.components.rememberLocalNetworkRequest
import dev.fl1nt.ember.ui.emberViewModel
import dev.fl1nt.ember.ui.icons.Lucide
import dev.fl1nt.ember.ui.uploads.UploadSheet
import kotlinx.coroutines.launch

@Composable
fun HomeRoute(
    onPaste: () -> Unit,
    onShorten: () -> Unit,
    onOpenSync: () -> Unit,
    onAddSource: () -> Unit,
    onSeeAll: () -> Unit,
    onSnackbar: (String) -> Unit,
    openUploadPicker: Boolean,
    onUploadPickerHandled: () -> Unit,
    onReconnect: () -> Unit = {},
) {
    val viewModel = emberViewModel { HomeViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<UploadEntity?>(null) }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { viewModel.upload(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { viewModel.upload(it) }

    val requestLocalNetwork = rememberLocalNetworkRequest { granted -> if (granted) viewModel.onLocalNetworkGranted() else viewModel.refreshAccess() }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshAccess()
        onPauseOrDispose {}
    }

    LaunchedEffect(openUploadPicker) {
        if (openUploadPicker) {
            choosing = true
            onUploadPickerHandled()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            onSnackbar(
                when (event) {
                    is HomeEvent.Queued -> resources.getQuantityString(R.plurals.home_queued, event.count, event.count)
                    HomeEvent.SyncStarted -> resources.getString(R.string.sync_started)
                },
            )
        }
    }

    HomeScreen(
        state = state,
        onUpload = { choosing = true },
        onPaste = onPaste,
        onShorten = onShorten,
        onSyncNow = { if (state.sources.isEmpty()) onAddSource() else viewModel.syncNow() },
        onOpenSync = onOpenSync,
        onSeeAll = onSeeAll,
        onSelect = { selected = it },
        onCopy = { upload -> upload.pageUrl?.let { Clipboard.copy(context, it) } },
        onReconnect = onReconnect,
        onAllowLocalNetwork = requestLocalNetwork,
    )

    if (choosing) {
        UploadChooser(
            onDismiss = { choosing = false },
            onMedia = {
                choosing = false
                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onFiles = {
                choosing = false
                pickFiles.launch(arrayOf("*/*"))
            },
        )
    }
    selected?.let { upload ->
        UploadSheet(
            upload = upload,
            onDismiss = { selected = null },
            onRetry = { scope.launch { container.uploads.retry(upload.id) }; selected = null },
            onCancel = { scope.launch { container.uploads.cancel(upload.id) }; selected = null },
            onRemove = { scope.launch { container.uploads.remove(upload.id) }; selected = null },
        )
    }
}

private const val MAX_PICK = 50

@Composable
fun HomeScreen(
    state: HomeState,
    onUpload: () -> Unit,
    onPaste: () -> Unit,
    onShorten: () -> Unit,
    onSyncNow: () -> Unit,
    onOpenSync: () -> Unit,
    onSeeAll: () -> Unit,
    onSelect: (UploadEntity) -> Unit,
    onCopy: (UploadEntity) -> Unit,
    onReconnect: () -> Unit = {},
    onAllowLocalNetwork: () -> Unit = {},
) {
    val account = state.account
    val canShorten = account?.capabilities?.manageLinks?.isDenied != true
    GlowBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmberWordmark(Modifier.weight(1f))
                if (account != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusDot(if (account.problem != null || state.needsLocalNetwork) Tone.DANGER else Tone.SUCCESS)
                        Text(account.origin.displayName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow(stringResource(R.string.home_eyebrow))
                Text(account?.displayName.orEmpty(), style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            account?.problem?.let { AccountProblemNotice(it, onReconnect) }
            if (state.needsLocalNetwork) LocalNetworkNotice(onAllowLocalNetwork)

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionCard(Lucide.Upload, stringResource(R.string.home_action_upload), stringResource(R.string.home_action_upload_hint), onUpload, Modifier.weight(1f))
                    ActionCard(Lucide.FileText, stringResource(R.string.home_action_paste), stringResource(R.string.home_action_paste_hint), onPaste, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionCard(
                        Lucide.Link,
                        stringResource(R.string.home_action_shorten),
                        stringResource(if (canShorten) R.string.home_action_shorten_hint else R.string.home_action_shorten_unavailable),
                        onShorten,
                        Modifier.weight(1f),
                        enabled = canShorten,
                    )
                    ActionCard(
                        Lucide.FolderSync,
                        stringResource(if (state.sources.isEmpty()) R.string.home_action_setup_sync else R.string.home_action_sync_now),
                        if (state.sources.isEmpty()) {
                            stringResource(R.string.home_action_setup_sync_hint)
                        } else {
                            pluralStringResource(R.plurals.home_folders, state.enabledSources, state.enabledSources)
                        },
                        onSyncNow,
                        Modifier.weight(1f),
                    )
                }
            }

            if (state.sources.isNotEmpty()) SyncSummaryCard(state, onOpenSync)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.home_recent)) {
                    if (state.recent.isNotEmpty()) GhostButton(stringResource(R.string.action_see_all), onClick = onSeeAll)
                }
                FlareCard(contentPadding = PaddingValues(vertical = 6.dp)) {
                    if (state.recent.isEmpty()) {
                        EmptyState(Lucide.CloudUpload, stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_body))
                    } else {
                        state.recent.forEach { upload -> UploadRow(upload, onClick = { onSelect(upload) }, onCopy = { onCopy(upload) }) }
                    }
                }
            }

            DashedCard {
                Text(stringResource(R.string.home_tip_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.home_tip_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, hint: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    FlareCard(modifier = modifier, onClick = if (enabled) onClick else null, contentPadding = PaddingValues(14.dp)) {
        IconTile(icon, size = 36.dp, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SyncSummaryCard(state: HomeState, onOpen: () -> Unit) {
    val context = LocalContext.current
    FlareCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Lucide.FolderSync, size = 36.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.home_sync_title), style = MaterialTheme.typography.titleSmall)
                val status = when {
                    !state.syncEnabled -> stringResource(R.string.sync_status_off)
                    state.syncNeedsAttention && state.syncProgress == null -> stringResource(R.string.sync_status_attention)
                    state.syncProgress != null -> stringResource(R.string.sync_status_uploading, state.syncProgress.done + 1, state.syncProgress.total)
                    state.syncRunning -> stringResource(R.string.sync_status_checking)
                    state.pending > 0 -> pluralStringResource(R.plurals.sync_status_waiting, state.pending, state.pending)
                    state.lastSyncAt != null -> stringResource(R.string.sync_status_up_to_date, Formats.relative(context, state.lastSyncAt!!))
                    else -> stringResource(R.string.sync_status_scheduled)
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Lucide.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        state.syncProgress?.let {
            Spacer(Modifier.height(12.dp))
            FlameProgress(if (it.total > 0 && it.done > 0) it.done.toFloat() / it.total else null)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.sources.take(3).forEach { source ->
                StatusPill(source.name, tone = if (source.enabled) Tone.NEUTRAL else Tone.NEUTRAL, icon = if (source.enabled) Lucide.Folder else Lucide.Pause)
            }
            if (state.sources.size > 3) StatusPill("+${state.sources.size - 3}")
        }
    }
}

@Composable
private fun UploadChooser(onDismiss: () -> Unit, onMedia: () -> Unit, onFiles: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(stringResource(R.string.home_choose_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            SettingRow(
                title = stringResource(R.string.home_choose_media),
                description = stringResource(R.string.home_choose_media_hint),
                icon = Lucide.Images,
                onClick = onMedia,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
            )
            SettingRow(
                title = stringResource(R.string.home_choose_files),
                description = stringResource(R.string.home_choose_files_hint),
                icon = Lucide.Files,
                onClick = onFiles,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
            )
        }
    }
}
