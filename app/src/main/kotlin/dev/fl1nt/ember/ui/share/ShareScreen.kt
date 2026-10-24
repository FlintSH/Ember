package dev.fl1nt.ember.ui.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fl1nt.ember.R
import dev.fl1nt.ember.data.db.UploadStatus
import dev.fl1nt.ember.system.Clipboard
import dev.fl1nt.ember.system.Links
import dev.fl1nt.ember.ui.components.EmberMark
import dev.fl1nt.ember.ui.components.EmberTextField
import dev.fl1nt.ember.ui.components.ExpirationRow
import dev.fl1nt.ember.ui.components.FileThumb
import dev.fl1nt.ember.ui.components.FlameProgress
import dev.fl1nt.ember.ui.components.FlareCard
import dev.fl1nt.ember.ui.components.FolderRow
import dev.fl1nt.ember.ui.components.Formats
import dev.fl1nt.ember.ui.components.GhostButton
import dev.fl1nt.ember.ui.components.IconAction
import dev.fl1nt.ember.ui.components.LinkField
import dev.fl1nt.ember.ui.components.Notice
import dev.fl1nt.ember.ui.components.OutlineButton
import dev.fl1nt.ember.ui.components.PrimaryButton
import dev.fl1nt.ember.ui.components.SegmentedControl
import dev.fl1nt.ember.ui.components.StatusPill
import dev.fl1nt.ember.ui.components.Tone
import dev.fl1nt.ember.ui.components.VisibilityRow
import dev.fl1nt.ember.ui.components.countString
import dev.fl1nt.ember.ui.components.failureLabel
import dev.fl1nt.ember.ui.icons.Lucide

data class ShareCallbacks(
    val onDismiss: () -> Unit,
    val onOpenApp: () -> Unit,
    val onUploadFiles: () -> Unit,
    val onSubmitText: () -> Unit,
    val onTextAction: (TextAction) -> Unit,
    val onPasteName: (String) -> Unit,
    val onVisibility: (dev.fl1nt.ember.api.Visibility?) -> Unit,
    val onExpiration: (dev.fl1nt.ember.api.Expiration?) -> Unit,
    val onFolder: (dev.fl1nt.ember.data.settings.UploadPreset.Choice) -> Unit,
    val onPassword: (String) -> Unit,
    val onRetry: () -> Unit,
)

/** The bottom sheet that opens over other apps. */
@Composable
fun ShareScreen(state: ShareState, callbacks: ShareCallbacks) {
    // Closing while Ember copies shared files would cancel the copy and lose the share.
    val dismissible = state.phase != SharePhase.PREPARING
    BackHandler(enabled = !dismissible) {}
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (dismissible) callbacks.onDismiss()
                },
        )
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .heightIn(max = 760.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outline))
                Header(state, if (dismissible) callbacks.onDismiss else null)
                when (state.phase) {
                    SharePhase.LOADING -> Loading(stringResource(R.string.share_loading))
                    SharePhase.NOT_CONNECTED -> {
                        Notice(stringResource(R.string.share_not_connected))
                        PrimaryButton(stringResource(R.string.share_open_app), onClick = callbacks.onOpenApp, modifier = Modifier.fillMaxWidth())
                    }
                    SharePhase.UNSUPPORTED -> {
                        Notice(
                            if (state.rejectedFiles > 0) pluralStringResource(R.plurals.share_rejected_files, state.rejectedFiles, state.rejectedFiles) else stringResource(R.string.share_unsupported),
                            tone = Tone.WARNING,
                        )
                        OutlineButton(stringResource(R.string.action_close), onClick = callbacks.onDismiss, modifier = Modifier.fillMaxWidth())
                    }
                    SharePhase.REVIEW_FILES -> ReviewFiles(state, callbacks)
                    SharePhase.REVIEW_TEXT -> ReviewText(state, callbacks)
                    SharePhase.PREPARING -> {
                        Loading(stringResource(R.string.share_preparing))
                        Text(stringResource(R.string.share_preparing_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SharePhase.UPLOADING -> Progress(state, callbacks)
                    SharePhase.DONE -> Done(state, callbacks)
                    SharePhase.FAILED -> {
                        Notice(state.error ?: stringResource(R.string.failure_other), tone = Tone.DANGER)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (state.canRetry) PrimaryButton(stringResource(R.string.action_retry), onClick = callbacks.onRetry, icon = Lucide.RotateCcw, modifier = Modifier.weight(1f))
                            OutlineButton(stringResource(R.string.action_close), onClick = callbacks.onDismiss, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(state: ShareState, onDismiss: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EmberMark(size = 30.dp)
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    when {
                        state.phase == SharePhase.REVIEW_TEXT && state.textAction == TextAction.SHORTEN -> R.string.share_title_shorten
                        state.text.isNotEmpty() -> R.string.share_title_paste
                        else -> R.string.share_title_upload
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            state.account?.let {
                Text(it.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onDismiss != null) IconAction(Lucide.X, stringResource(R.string.action_close), onDismiss)
    }
}

@Composable
private fun Loading(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReviewFiles(state: ShareState, callbacks: ShareCallbacks) {
    val context = LocalContext.current
    FlareCard(contentPadding = PaddingValues(vertical = 6.dp)) {
        state.files.take(4).forEach { file ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FileThumb(file.uri, file.mimeType, size = 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Formats.size(context, file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (state.files.size > 4) {
            Text(
                pluralStringResource(R.plurals.share_more_files, state.files.size - 4, state.files.size - 4),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
    if (state.rejectedFiles > 0) Notice(pluralStringResource(R.plurals.share_rejected_files, state.rejectedFiles, state.rejectedFiles), tone = Tone.WARNING)
    Options(state, callbacks)
    PrimaryButton(
        pluralStringResource(R.plurals.share_upload_files, state.files.size, state.files.size),
        onClick = callbacks.onUploadFiles,
        icon = Lucide.Upload,
        enabled = !state.passwordTooLong,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ReviewText(state: ShareState, callbacks: ShareCallbacks) {
    if (state.url != null && state.canShorten) {
        SegmentedControl(
            options = listOf(stringResource(R.string.share_action_shorten), stringResource(R.string.share_action_paste)),
            selected = if (state.textAction == TextAction.SHORTEN) 0 else 1,
            onSelect = { callbacks.onTextAction(if (it == 0) TextAction.SHORTEN else TextAction.PASTE) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    FlareCard {
        Text(
            state.text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(countString(R.plurals.share_characters, state.text.length), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (state.textAction == TextAction.PASTE) {
        EmberTextField(
            value = state.pasteName,
            onValueChange = callbacks.onPasteName,
            label = stringResource(R.string.paste_file_label),
            placeholder = state.settings.pasteFileName,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
        Options(state, callbacks)
        if (state.returnsToSelection) {
            Text(stringResource(R.string.share_replaces_selection), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Text(stringResource(R.string.share_shorten_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    PrimaryButton(
        stringResource(if (state.textAction == TextAction.SHORTEN) R.string.shorten_create else R.string.paste_create),
        onClick = callbacks.onSubmitText,
        icon = if (state.textAction == TextAction.SHORTEN) Lucide.Zap else Lucide.Send,
        enabled = !state.passwordTooLong,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Options(state: ShareState, callbacks: ShareCallbacks) {
    FlareCard(contentPadding = PaddingValues(vertical = 4.dp)) {
        VisibilityRow(state.visibility, callbacks.onVisibility, state.settings.defaults)
        ExpirationRow(state.expiration, callbacks.onExpiration, state.settings.defaults)
        FolderRow(state.folder, callbacks.onFolder, state.folders, state.settings.defaults, explainUnavailableList = false)
    }
    EmberTextField(
        value = state.password,
        onValueChange = callbacks.onPassword,
        label = stringResource(R.string.field_password),
        placeholder = stringResource(R.string.field_password_placeholder),
        errorText = if (state.passwordTooLong) stringResource(R.string.field_password_too_long) else null,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        leadingIcon = Lucide.Lock,
    )
}

@Composable
private fun Progress(state: ShareState, callbacks: ShareCallbacks) {
    if (state.uploads.isEmpty()) {
        Loading(stringResource(R.string.share_shortening))
        return
    }
    FlareCard(contentPadding = PaddingValues(vertical = 6.dp)) {
        state.uploads.take(6).forEach { upload ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(upload.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    when (upload.status) {
                        UploadStatus.DONE -> StatusPill(stringResource(R.string.status_uploaded), tone = Tone.SUCCESS, icon = Lucide.CircleCheck)
                        UploadStatus.FAILED -> StatusPill(failureLabel(upload.failureKind), tone = Tone.DANGER, icon = Lucide.CircleAlert)
                        UploadStatus.QUEUED -> StatusPill(stringResource(R.string.status_waiting), icon = Lucide.Clock)
                        else -> Unit
                    }
                }
                if (upload.status == UploadStatus.RUNNING || upload.status == UploadStatus.QUEUED) {
                    val total = upload.sizeBytes ?: 0
                    FlameProgress(if (upload.status == UploadStatus.RUNNING && total > 0) upload.bytesSent.toFloat() / total else null)
                }
            }
        }
    }
    Text(stringResource(R.string.share_background_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlineButton(stringResource(R.string.share_hide), onClick = callbacks.onDismiss, icon = Lucide.ChevronDown, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun Done(state: ShareState, callbacks: ShareCallbacks) {
    val context = LocalContext.current
    val links = state.links
    Notice(
        when {
            state.failed.isNotEmpty() -> pluralStringResource(R.plurals.share_partial, state.failed.size, state.failed.size)
            state.copied && links.size > 1 -> stringResource(R.string.share_done_copied_all)
            state.copied -> stringResource(R.string.share_done_copied)
            else -> stringResource(R.string.share_done)
        },
        tone = if (state.failed.isNotEmpty()) Tone.WARNING else Tone.SUCCESS,
    )
    links.take(6).forEach { link -> LinkField(link, onCopy = { Clipboard.copy(context, link) }, copyLabel = stringResource(R.string.action_copy_link)) }
    if (links.size > 6) Text(pluralStringResource(R.plurals.share_more_links, links.size - 6, links.size - 6), style = MaterialTheme.typography.bodySmall)
    if (links.isNotEmpty()) {
        val all = links.joinToString("\n")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(stringResource(R.string.action_share), onClick = { Links.share(context, all) }, icon = Lucide.Share2, modifier = Modifier.weight(1f))
            if (links.size == 1) {
                OutlineButton(stringResource(R.string.action_open), onClick = { Links.open(context, links.first()) }, icon = Lucide.ExternalLink, modifier = Modifier.weight(1f))
            } else {
                OutlineButton(stringResource(R.string.share_copy_all), onClick = { Clipboard.copy(context, all) }, icon = Lucide.Copy, modifier = Modifier.weight(1f))
            }
        }
    }
    if (state.retryable.isNotEmpty()) GhostButton(stringResource(R.string.action_retry), onClick = callbacks.onRetry, icon = Lucide.RotateCcw)
    GhostButton(stringResource(R.string.action_done), onClick = callbacks.onDismiss, modifier = Modifier.fillMaxWidth())
}
