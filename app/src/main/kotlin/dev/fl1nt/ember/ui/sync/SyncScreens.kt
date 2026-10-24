package dev.fl1nt.ember.ui.sync

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fl1nt.ember.R
import dev.fl1nt.ember.data.db.SourceType
import dev.fl1nt.ember.data.db.SyncSourceEntity
import dev.fl1nt.ember.data.settings.UploadPreset
import dev.fl1nt.ember.data.sync.MediaAlbum
import dev.fl1nt.ember.data.sync.NewSource
import dev.fl1nt.ember.data.sync.SyncEngine
import dev.fl1nt.ember.system.MediaAccess
import dev.fl1nt.ember.system.MediaPermissions
import dev.fl1nt.ember.ui.components.DangerButton
import dev.fl1nt.ember.ui.components.EmptyState
import dev.fl1nt.ember.ui.components.ExpirationRow
import dev.fl1nt.ember.ui.components.FileThumb
import dev.fl1nt.ember.ui.components.FlameProgress
import dev.fl1nt.ember.ui.components.FlareCard
import dev.fl1nt.ember.ui.components.FolderRow
import dev.fl1nt.ember.ui.components.Formats
import dev.fl1nt.ember.ui.components.GhostButton
import dev.fl1nt.ember.ui.components.Hairline
import dev.fl1nt.ember.ui.components.IconAction
import dev.fl1nt.ember.ui.components.IconTile
import dev.fl1nt.ember.ui.components.Notice
import dev.fl1nt.ember.ui.components.OutlineButton
import dev.fl1nt.ember.ui.components.PrimaryButton
import dev.fl1nt.ember.ui.components.ScreenHeader
import dev.fl1nt.ember.ui.components.SectionTitle
import dev.fl1nt.ember.ui.components.SegmentedControl
import dev.fl1nt.ember.ui.components.StatusPill
import dev.fl1nt.ember.ui.components.SwitchRow
import dev.fl1nt.ember.ui.components.Tone
import dev.fl1nt.ember.ui.components.UploadRow
import dev.fl1nt.ember.ui.components.VisibilityRow
import dev.fl1nt.ember.ui.emberViewModel
import dev.fl1nt.ember.ui.components.countString
import dev.fl1nt.ember.ui.icons.Lucide

fun sourceIcon(source: SyncSourceEntity): ImageVector = when {
    source.type == SourceType.FOLDER -> Lucide.Folder
    source.name.equals("Camera", true) || source.location?.startsWith("DCIM") == true -> Lucide.Camera
    source.name.contains("Screenshot", true) -> Lucide.ScanLine
    else -> Lucide.Images
}

@Composable
fun SyncRoute(onAdd: () -> Unit, onOpen: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val viewModel = emberViewModel { SyncViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissions()
        onPauseOrDispose {}
    }
    SyncScreen(
        state = state,
        onToggleSync = { viewModel.setSyncEnabled(it) },
        onToggleSource = { id, enabled -> viewModel.setSourceEnabled(id, enabled) },
        onSyncNow = { viewModel.syncNow() },
        onAdd = onAdd,
        onOpen = onOpen,
        onOpenSettings = onOpenSettings,
    )
}

@Composable
fun SyncScreen(
    state: SyncState,
    onToggleSync: (Boolean) -> Unit,
    onToggleSource: (Long, Boolean) -> Unit,
    onSyncNow: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(
            eyebrow = stringResource(R.string.sync_eyebrow),
            title = stringResource(R.string.sync_title),
            subtitle = stringResource(R.string.sync_subtitle),
        )
        FlareCard(contentPadding = PaddingValues(0.dp)) {
            SwitchRow(
                title = stringResource(R.string.sync_master_title),
                description = syncStatus(state, context),
                checked = state.prefs.enabled,
                onCheckedChange = onToggleSync,
                icon = Lucide.FolderSync,
            )
            state.progress?.let {
                FlameProgress(if (it.total > 0 && it.done > 0) it.done.toFloat() / it.total else null, Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
            }
            Hairline()
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    GhostButton(constraintsSummary(state), onClick = onOpenSettings, icon = if (state.prefs.wifiOnly) Lucide.Wifi else Lucide.Globe, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GhostButton(stringResource(R.string.sync_now), onClick = onSyncNow, icon = Lucide.RefreshCw, enabled = state.sources.isNotEmpty() && !state.running)
            }
        }

        if (state.sources.any { it.source.type == SourceType.MEDIA_ALBUM } && state.mediaAccess != MediaAccess.FULL) {
            MediaAccessNotice(state.mediaAccess)
        }

        if (state.loaded && state.sources.isEmpty()) {
            FlareCard {
                EmptyState(
                    Lucide.FolderSync,
                    stringResource(R.string.sync_empty_title),
                    stringResource(R.string.sync_empty_body),
                    action = { PrimaryButton(stringResource(R.string.sync_add), onClick = onAdd, icon = Lucide.Plus) },
                )
            }
        } else {
            SectionTitle(stringResource(R.string.sync_sources_title)) {
                GhostButton(stringResource(R.string.sync_add_short), onClick = onAdd, icon = Lucide.Plus)
            }
            state.sources.forEach { summary -> SourceCard(summary, onToggle = { onToggleSource(summary.source.id, it) }, onClick = { onOpen(summary.source.id) }) }
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun syncStatus(state: SyncState, context: android.content.Context): String {
    val pending = state.sources.filter { it.source.enabled }.sumOf { it.source.pendingCount }
    val last = state.sources.mapNotNull { it.source.lastScanAt }.maxOrNull()
    return when {
        !state.prefs.enabled -> context.getString(R.string.sync_status_off)
        state.progress != null -> context.getString(R.string.sync_status_uploading, state.progress.done + 1, state.progress.total)
        state.running -> context.getString(R.string.sync_status_checking)
        pending > 0 -> context.resources.getQuantityString(R.plurals.sync_status_waiting, pending, pending)
        last != null -> context.getString(R.string.sync_status_up_to_date, Formats.relative(context, last))
        else -> context.getString(R.string.sync_status_scheduled)
    }
}

@Composable
private fun constraintsSummary(state: SyncState): String {
    val network = stringResource(if (state.prefs.wifiOnly) R.string.sync_wifi_only else R.string.sync_any_network)
    return if (state.prefs.chargingOnly) stringResource(R.string.sync_constraints_charging, network) else network
}

@Composable
private fun MediaAccessNotice(access: MediaAccess) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    Notice(
        text = stringResource(if (access == MediaAccess.PARTIAL) R.string.sync_media_partial else R.string.sync_media_missing),
        tone = Tone.WARNING,
        action = {
            Row {
                GhostButton(stringResource(R.string.sync_media_allow), onClick = { launcher.launch(MediaPermissions.requested()) })
                GhostButton(stringResource(R.string.action_open_settings), onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                })
            }
        },
    )
}

@Composable
private fun SourceCard(summary: SourceSummary, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    val source = summary.source
    FlareCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(sourceIcon(source), size = 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(source.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    source.location ?: stringResource(if (source.type == SourceType.FOLDER) R.string.sync_type_folder else R.string.sync_type_album),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = source.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = source.name },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.secondaryContainer,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(countString(R.plurals.sync_uploaded_count, summary.counts.uploaded), icon = Lucide.CircleCheck, tone = Tone.SUCCESS)
            if (source.pendingCount > 0 && source.enabled) {
                StatusPill(countString(R.plurals.sync_waiting_count, source.pendingCount), icon = Lucide.Clock)
            }
            if (summary.counts.failed > 0) {
                StatusPill(countString(R.plurals.sync_failed_count, summary.counts.failed), icon = Lucide.CircleAlert, tone = Tone.DANGER)
            }
        }
        source.lastError?.let { error ->
            Spacer(Modifier.height(10.dp))
            Notice(
                stringResource(if (error == SyncEngine.MEDIA_ACCESS_ERROR) R.string.sync_error_media else R.string.sync_error_folder),
                tone = Tone.WARNING,
            )
        }
    }
}

@Composable
fun AddSourceRoute(onBack: () -> Unit, onSaved: (Long) -> Unit) {
    val viewModel = emberViewModel { AddSourceViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.refresh() }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(viewModel::pickFolder) }
    LifecycleResumeEffect(Unit) {
        if (state.mediaAccess != MediaAccess.FULL) viewModel.refresh()
        onPauseOrDispose {}
    }
    AddSourceScreen(
        state = state,
        onBack = { if (state.selected != null) viewModel.clearSelection() else onBack() },
        onAllowMedia = { permission.launch(MediaPermissions.requested()) },
        onPickAlbum = viewModel::pickAlbum,
        onPickFolder = { folderPicker.launch(null) },
        onOptions = viewModel::updateOptions,
        onSave = { viewModel.save(onSaved) },
    )
}

@Composable
fun AddSourceScreen(
    state: AddSourceState,
    onBack: () -> Unit,
    onAllowMedia: () -> Unit,
    onPickAlbum: (MediaAlbum) -> Unit,
    onPickFolder: () -> Unit,
    onOptions: ((SourceOptions) -> SourceOptions) -> Unit,
    onSave: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconAction(Lucide.ArrowLeft, stringResource(R.string.action_back), onBack, tint = MaterialTheme.colorScheme.onSurface)
        val selected = state.selected
        if (selected == null) {
            ScreenHeader(title = stringResource(R.string.add_source_title), subtitle = stringResource(R.string.add_source_subtitle))
            SectionTitle(stringResource(R.string.add_source_albums))
            when {
                state.mediaAccess == MediaAccess.NONE -> Notice(
                    stringResource(R.string.add_source_media_needed),
                    action = { PrimaryButton(stringResource(R.string.sync_media_allow), onClick = onAllowMedia, icon = Lucide.Images) },
                )
                state.albumsLoading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                state.albums.isEmpty() -> Text(stringResource(R.string.add_source_no_albums), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> FlareCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                    if (state.mediaAccess == MediaAccess.PARTIAL) Notice(stringResource(R.string.sync_media_partial), tone = Tone.WARNING, modifier = Modifier.padding(12.dp))
                    state.albums.forEach { album -> AlbumRow(album, onClick = { onPickAlbum(album) }) }
                }
            }
            SectionTitle(stringResource(R.string.add_source_other))
            FlareCard(onClick = onPickFolder) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(Lucide.FolderPlus)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.add_source_choose_folder), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.add_source_choose_folder_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Lucide.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            state.error?.let { Notice(it, tone = Tone.DANGER) }
        } else {
            ScreenHeader(
                eyebrow = stringResource(if (selected is NewSource.Album) R.string.sync_type_album else R.string.sync_type_folder),
                title = selected.name,
                subtitle = when (selected) {
                    is NewSource.Album -> selected.album.location
                    is NewSource.Folder -> selected.location
                },
            )
            SectionTitle(stringResource(R.string.add_source_start_with))
            SegmentedControl(
                options = listOf(
                    stringResource(R.string.add_source_new_only),
                    if (selected is NewSource.Album) countString(R.plurals.add_source_everything_count, selected.album.count) else stringResource(R.string.add_source_everything),
                ),
                selected = if (state.options.uploadExisting) 1 else 0,
                onSelect = { index -> onOptions { it.copy(uploadExisting = index == 1) } },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(if (state.options.uploadExisting) R.string.add_source_everything_hint else R.string.add_source_new_only_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SourceOptionsCard(
                isAlbum = selected is NewSource.Album,
                options = state.options,
                folders = state.folders,
                emberDefaults = state.defaults,
                onOptions = onOptions,
            )
            state.error?.let { Notice(it, tone = Tone.DANGER) }
            PrimaryButton(stringResource(R.string.add_source_save), onClick = onSave, loading = state.saving, icon = Lucide.Check, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AlbumRow(album: MediaAlbum, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FileThumb(album.coverUri, "image/*", size = 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(album.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(album.location, countString(R.plurals.album_items, album.count)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Lucide.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** Where a sync folder's uploads go, and how Ember tells you about them. */
@Composable
fun SourceOptionsCard(
    isAlbum: Boolean,
    options: SourceOptions,
    folders: List<dev.fl1nt.ember.api.RemoteFolder>?,
    emberDefaults: UploadPreset,
    onOptions: ((SourceOptions) -> SourceOptions) -> Unit,
) {
    fun preset(transform: (UploadPreset) -> UploadPreset) = onOptions { it.copy(preset = transform(it.preset)) }
    SectionTitle(stringResource(R.string.source_options_destination))
    FlareCard(contentPadding = PaddingValues(vertical = 4.dp)) {
        VisibilityRow(options.preset.visibility, { value -> preset { it.copy(visibility = value) } }, emberDefaults)
        ExpirationRow(options.preset.expiration, { value -> preset { it.copy(expiration = value) } }, emberDefaults)
        FolderRow(options.preset.folder, { value -> preset { it.copy(folder = value) } }, folders, emberDefaults)
    }
    SectionTitle(stringResource(R.string.source_options_behavior))
    FlareCard(contentPadding = PaddingValues(vertical = 4.dp)) {
        SwitchRow(
            title = stringResource(R.string.source_copy_link),
            description = stringResource(R.string.source_copy_link_hint),
            checked = options.copyLink,
            onCheckedChange = { value -> onOptions { it.copy(copyLink = value) } },
            icon = Lucide.Copy,
        )
        SwitchRow(
            title = stringResource(R.string.source_notify_each),
            description = stringResource(R.string.source_notify_each_hint),
            checked = options.notifyEach,
            onCheckedChange = { value -> onOptions { it.copy(notifyEach = value) } },
            icon = Lucide.Bell,
        )
        if (isAlbum) {
            SwitchRow(stringResource(R.string.source_include_photos), checked = options.includeImages, onCheckedChange = { value -> onOptions { it.copy(includeImages = value || !it.includeVideos) } }, icon = Lucide.Image)
            SwitchRow(stringResource(R.string.source_include_videos), checked = options.includeVideos, onCheckedChange = { value -> onOptions { it.copy(includeVideos = value || !it.includeImages) } }, icon = Lucide.Video)
        } else {
            SwitchRow(stringResource(R.string.source_include_subfolders), checked = options.includeSubfolders, onCheckedChange = { value -> onOptions { it.copy(includeSubfolders = value) } }, icon = Lucide.Layers)
        }
    }
}

@Composable
fun SourceDetailRoute(id: Long, onBack: () -> Unit, onSelectUpload: (dev.fl1nt.ember.data.db.UploadEntity) -> Unit) {
    val viewModel = emberViewModel(key = "source-$id") { SourceDetailViewModel(it, id) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(state.removed) { if (state.removed) onBack() }
    val source = state.source ?: return
    val options = SourceOptions(
        preset = UploadPreset.decode(source.presetJson),
        copyLink = source.copyLink,
        notifyEach = source.notifyEach,
        includeImages = source.includeImages,
        includeVideos = source.includeVideos,
        includeSubfolders = source.includeSubfolders,
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconAction(Lucide.ArrowLeft, stringResource(R.string.action_back), onBack, tint = MaterialTheme.colorScheme.onSurface)
        ScreenHeader(
            eyebrow = stringResource(if (source.type == SourceType.FOLDER) R.string.sync_type_folder else R.string.sync_type_album),
            title = source.name,
            subtitle = source.location,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill(countString(R.plurals.sync_uploaded_count, state.counts.uploaded), icon = Lucide.CircleCheck, tone = Tone.SUCCESS)
            if (state.counts.failed > 0) StatusPill(countString(R.plurals.sync_failed_count, state.counts.failed), icon = Lucide.CircleAlert, tone = Tone.DANGER)
        }
        if (state.counts.failed > 0) {
            OutlineButton(stringResource(R.string.source_retry_failed), onClick = { viewModel.retryFailures() }, icon = Lucide.RotateCcw, modifier = Modifier.fillMaxWidth())
        }
        SourceOptionsCard(
            isAlbum = source.type == SourceType.MEDIA_ALBUM,
            options = options,
            folders = state.folders,
            emberDefaults = state.defaults,
            onOptions = { transform ->
                val next = transform(options)
                viewModel.update {
                    it.copy(
                        presetJson = next.preset.encode(),
                        copyLink = next.copyLink,
                        notifyEach = next.notifyEach,
                        includeImages = next.includeImages,
                        includeVideos = next.includeVideos,
                        includeSubfolders = next.includeSubfolders,
                    )
                }
            },
        )
        if (state.recent.isNotEmpty()) {
            SectionTitle(stringResource(R.string.source_recent))
            FlareCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                state.recent.take(10).forEach { upload -> UploadRow(upload, onClick = { onSelectUpload(upload) }, onCopy = null) }
            }
        }
        DangerButton(stringResource(R.string.source_remove), onClick = { confirmRemove = true }, icon = Lucide.Trash2, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.source_remove_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            title = { Text(stringResource(R.string.source_remove_title, source.name)) },
            text = { Text(stringResource(R.string.source_remove_hint)) },
            confirmButton = { GhostButton(stringResource(R.string.source_remove), onClick = { confirmRemove = false; viewModel.remove() }, color = MaterialTheme.colorScheme.error) },
            dismissButton = { GhostButton(stringResource(R.string.action_cancel), onClick = { confirmRemove = false }) },
        )
    }
}
