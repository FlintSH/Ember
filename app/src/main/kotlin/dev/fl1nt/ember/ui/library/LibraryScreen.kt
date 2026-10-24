package dev.fl1nt.ember.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fl1nt.ember.R
import dev.fl1nt.ember.api.RemoteFile
import dev.fl1nt.ember.data.account.Account
import dev.fl1nt.ember.data.db.ShortLinkEntity
import dev.fl1nt.ember.data.db.UploadEntity
import dev.fl1nt.ember.system.Clipboard
import dev.fl1nt.ember.system.Links
import dev.fl1nt.ember.ui.components.EmberTextField
import dev.fl1nt.ember.ui.components.EmptyState
import dev.fl1nt.ember.ui.components.FileThumb
import dev.fl1nt.ember.ui.components.Formats
import dev.fl1nt.ember.ui.components.GhostButton
import dev.fl1nt.ember.ui.components.Hairline
import dev.fl1nt.ember.ui.components.LinkField
import dev.fl1nt.ember.ui.components.Notice
import dev.fl1nt.ember.ui.components.OutlineButton
import dev.fl1nt.ember.ui.components.PrimaryButton
import dev.fl1nt.ember.ui.components.ScreenHeader
import dev.fl1nt.ember.ui.components.SegmentedControl
import dev.fl1nt.ember.ui.components.Tone
import dev.fl1nt.ember.ui.components.UploadRow
import dev.fl1nt.ember.ui.components.countString
import dev.fl1nt.ember.ui.emberViewModel
import dev.fl1nt.ember.ui.icons.Lucide
import dev.fl1nt.ember.ui.uploads.UploadSheet
import java.time.Instant

@Composable
fun LibraryRoute(initialTab: LibraryTab?, onShorten: () -> Unit, onSnackbar: (String) -> Unit) {
    val viewModel = emberViewModel { LibraryViewModel(it) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedUpload by remember { mutableStateOf<UploadEntity?>(null) }
    var selectedFile by remember { mutableStateOf<RemoteFile?>(null) }
    var selectedLink by remember { mutableStateOf<ShortLinkEntity?>(null) }

    LaunchedEffect(initialTab) { initialTab?.let(viewModel::select) }

    LibraryScreen(
        state = state,
        onTab = viewModel::select,
        onSearch = viewModel::search,
        onLoadMore = viewModel::loadMore,
        onRefreshFiles = viewModel::refreshFiles,
        onRefreshLinks = viewModel::refreshLinks,
        onShorten = onShorten,
        onSelectUpload = { selectedUpload = it },
        onSelectFile = { selectedFile = it },
        onSelectLink = { selectedLink = it },
        onClearFinished = { viewModel.clearFinished() },
    )

    selectedUpload?.let { upload ->
        UploadSheet(
            upload,
            onDismiss = { selectedUpload = null },
            onRetry = { viewModel.retry(upload.id); selectedUpload = null },
            onCancel = { viewModel.cancel(upload.id); selectedUpload = null },
            onRemove = { viewModel.remove(upload.id); selectedUpload = null },
        )
    }
    selectedFile?.let { file ->
        state.account?.let { account -> RemoteFileSheet(file, account, onDismiss = { selectedFile = null }) }
    }
    selectedLink?.let { link ->
        LinkSheet(
            link,
            canDelete = state.account?.capabilities?.manageLinks?.isDenied == false,
            onDismiss = { selectedLink = null },
            onDelete = {
                viewModel.deleteLink(link.id, onSnackbar)
                selectedLink = null
            },
        )
    }
}

@Composable
fun LibraryScreen(
    state: LibraryState,
    onTab: (LibraryTab) -> Unit,
    onSearch: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRefreshFiles: () -> Unit,
    onRefreshLinks: () -> Unit,
    onShorten: () -> Unit,
    onSelectUpload: (UploadEntity) -> Unit,
    onSelectFile: (RemoteFile) -> Unit,
    onSelectLink: (ShortLinkEntity) -> Unit,
    onClearFinished: () -> Unit,
) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, state.tab, state.files.items.size) {
        if (nearEnd && state.tab == LibraryTab.FILES && state.files.items.isNotEmpty()) onLoadMore()
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ScreenHeader(
                    eyebrow = stringResource(R.string.library_eyebrow),
                    title = stringResource(R.string.library_title),
                    subtitle = stringResource(R.string.library_subtitle),
                )
                SegmentedControl(
                    options = listOf(stringResource(R.string.library_tab_recent), stringResource(R.string.library_tab_files), stringResource(R.string.library_tab_links)),
                    selected = state.tab.ordinal,
                    onSelect = { onTab(LibraryTab.entries[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        when (state.tab) {
            LibraryTab.RECENT -> recentItems(state.recent, state.loaded, onSelectUpload, onClearFinished)
            LibraryTab.FILES -> fileItems(state, onSearch, onRefreshFiles, onSelectFile)
            LibraryTab.LINKS -> linkItems(state, onShorten, onRefreshLinks, onSelectLink)
        }
    }
}

private fun LazyListScope.recentItems(recent: List<UploadEntity>, loaded: Boolean, onSelect: (UploadEntity) -> Unit, onClear: () -> Unit) {
    if (recent.isEmpty()) {
        if (loaded) {
            item {
                EmptyState(Lucide.History, stringResource(R.string.library_recent_empty_title), stringResource(R.string.library_recent_empty_body))
            }
        }
        return
    }
    items(recent, key = { it.id }) { upload ->
        val context = LocalContext.current
        UploadRow(upload, onClick = { onSelect(upload) }, onCopy = { upload.pageUrl?.let { Clipboard.copy(context, it) } })
    }
    item {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            GhostButton(stringResource(R.string.library_clear_finished), onClick = onClear, icon = Lucide.Trash2, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun LazyListScope.fileItems(state: LibraryState, onSearch: (String) -> Unit, onRefresh: () -> Unit, onSelect: (RemoteFile) -> Unit) {
    // Try unless Flare said no; an unknown answer at connect time shouldn't hide the library.
    val canRead = state.account?.capabilities?.readFiles?.isDenied == false
    if (!canRead) {
        item {
            EmptyState(Lucide.Lock, stringResource(R.string.library_files_locked_title), stringResource(R.string.library_files_locked_body))
        }
        return
    }
    item {
        EmberTextField(
            value = state.files.query,
            onValueChange = onSearch,
            label = stringResource(R.string.library_search_label),
            placeholder = stringResource(R.string.library_search_placeholder),
            leadingIcon = Lucide.Search,
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
        )
    }
    state.files.error?.let { message ->
        item {
            Notice(message, tone = Tone.DANGER, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), action = {
                GhostButton(stringResource(R.string.action_try_again), onClick = onRefresh)
            })
        }
    }
    items(state.files.items, key = { it.id }) { file ->
        RemoteFileRow(file, state.account, onClick = { onSelect(file) })
    }
    if (state.files.loading) {
        item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else if (state.files.items.isEmpty() && state.files.error == null) {
        item {
            EmptyState(
                Lucide.FolderOpen,
                stringResource(if (state.files.query.isBlank()) R.string.library_files_empty_title else R.string.library_files_no_match_title),
                stringResource(if (state.files.query.isBlank()) R.string.library_files_empty_body else R.string.library_files_no_match_body),
            )
        }
    }
}

private fun LazyListScope.linkItems(state: LibraryState, onShorten: () -> Unit, onRefresh: () -> Unit, onSelect: (ShortLinkEntity) -> Unit) {
    val capabilities = state.account?.capabilities
    item {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (capabilities?.manageLinks?.isDenied == false) {
                PrimaryButton(stringResource(R.string.links_shorten_action), onClick = onShorten, icon = Lucide.Plus, modifier = Modifier.fillMaxWidth())
            }
            if (capabilities?.readLinks?.isDenied != false) Notice(stringResource(R.string.links_local_only))
            state.linksState.error?.let { Notice(it, tone = Tone.DANGER, action = { GhostButton(stringResource(R.string.action_try_again), onClick = onRefresh) }) }
        }
    }
    if (state.links.isEmpty()) {
        if (state.loaded) item { EmptyState(Lucide.Link, stringResource(R.string.links_empty_title), stringResource(R.string.links_empty_body)) }
        return
    }
    items(state.links, key = { it.id }) { link -> LinkRow(link, onClick = { onSelect(link) }) }
}

@Composable
private fun RemoteFileRow(file: RemoteFile, account: Account, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumbnail = if (file.isOpenlyShared && file.mimeType.startsWith("image/")) "${account.origin}/api/files/${file.id}/thumbnail" else null
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FileThumb(thumbnail, file.mimeType)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val uploaded = runCatching { Instant.parse(file.uploadedAt).toEpochMilli() }.getOrNull()
                Text(
                    listOfNotNull(Formats.size(context, file.sizeBytes), uploaded?.let { Formats.relative(context, it) })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (file.isPrivate) Icon(Lucide.Lock, stringResource(R.string.visibility_private), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            if (file.hasPassword) Icon(Lucide.KeyRound, stringResource(R.string.library_password_protected), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        Hairline(Modifier.padding(start = 76.dp))
    }
}

@Composable
private fun LinkRow(link: ShortLinkEntity, onClick: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(link.shortUrl.substringAfter("://"), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(link.targetUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                countString(R.plurals.links_clicks, link.clicks.toInt()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Hairline(Modifier.padding(start = 20.dp))
    }
}

@Composable
private fun RemoteFileSheet(file: RemoteFile, account: Account, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val page = "${account.linkOrigin}${file.urlPath}"
    val raw = "$page/raw"
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (file.isPrivate) Notice(stringResource(R.string.library_private_notice), tone = Tone.WARNING)
            LinkField(page, onCopy = { Clipboard.copy(context, page) }, copyLabel = stringResource(R.string.action_copy_link))
            if (file.isOpenlyShared) GhostButton(stringResource(R.string.action_copy_raw_link), onClick = { Clipboard.copy(context, raw) }, icon = Lucide.Link2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(stringResource(R.string.action_share), onClick = { Links.share(context, page) }, icon = Lucide.Share2, modifier = Modifier.weight(1f))
                OutlineButton(stringResource(R.string.action_open), onClick = { Links.open(context, page) }, icon = Lucide.ExternalLink, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}

@Composable
private fun LinkSheet(link: ShortLinkEntity, canDelete: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.links_sheet_title), style = MaterialTheme.typography.titleMedium)
            LinkField(link.shortUrl, onCopy = { Clipboard.copy(context, link.shortUrl) }, copyLabel = stringResource(R.string.action_copy_link))
            Text(stringResource(R.string.links_points_to, link.targetUrl), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(stringResource(R.string.action_share), onClick = { Links.share(context, link.shortUrl) }, icon = Lucide.Share2, modifier = Modifier.weight(1f))
                OutlineButton(stringResource(R.string.links_open_target), onClick = { Links.open(context, link.targetUrl) }, icon = Lucide.ExternalLink, modifier = Modifier.weight(1f))
            }
            if (canDelete) {
                GhostButton(stringResource(R.string.links_delete), onClick = onDelete, icon = Lucide.Trash2, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
