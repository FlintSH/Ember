package dev.fl1nt.ember.ui.uploads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.fl1nt.ember.R
import dev.fl1nt.ember.data.db.FailureKind
import dev.fl1nt.ember.data.db.UploadEntity
import dev.fl1nt.ember.data.db.UploadStatus
import dev.fl1nt.ember.system.Clipboard
import dev.fl1nt.ember.system.Links
import dev.fl1nt.ember.ui.components.FileThumb
import dev.fl1nt.ember.ui.components.Formats
import dev.fl1nt.ember.ui.components.GhostButton
import dev.fl1nt.ember.ui.components.LinkField
import dev.fl1nt.ember.ui.components.Notice
import dev.fl1nt.ember.ui.components.OutlineButton
import dev.fl1nt.ember.ui.components.PrimaryButton
import dev.fl1nt.ember.ui.components.Tone
import dev.fl1nt.ember.ui.components.failureLabel
import dev.fl1nt.ember.ui.components.originLabel
import dev.fl1nt.ember.ui.components.previewModel
import dev.fl1nt.ember.ui.icons.Lucide

/** Details and actions for one upload in the history. */
@Composable
fun UploadSheet(
    upload: UploadEntity,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(
            Modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                FileThumb(upload.previewModel(), upload.mimeType, size = 56.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(upload.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(
                            originLabel(upload.origin),
                            Formats.size(context, upload.sizeBytes),
                            Formats.relative(context, upload.completedAt ?: upload.createdAt),
                        ).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (upload.status) {
                UploadStatus.DONE -> {
                    val link = upload.pageUrl.orEmpty()
                    LinkField(link, onCopy = { Clipboard.copy(context, link) }, copyLabel = stringResource(R.string.action_copy_link))
                    upload.rawUrl?.let { raw ->
                        GhostButton(stringResource(R.string.action_copy_raw_link), onClick = { Clipboard.copy(context, raw) }, icon = Lucide.Link2)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton(stringResource(R.string.action_share), onClick = { Links.share(context, link) }, icon = Lucide.Share2, modifier = Modifier.weight(1f))
                        OutlineButton(stringResource(R.string.action_open), onClick = { Links.open(context, link) }, icon = Lucide.ExternalLink, modifier = Modifier.weight(1f))
                    }
                }
                UploadStatus.FAILED -> {
                    Notice(
                        upload.failureMessage?.takeIf { it.isNotBlank() } ?: failureLabel(upload.failureKind),
                        title = failureLabel(upload.failureKind),
                        tone = Tone.DANGER,
                    )
                    val canRetry = upload.failureKind != FailureKind.SOURCE_GONE && (upload.stagedPath != null || upload.sourceUri != null)
                    if (canRetry) PrimaryButton(stringResource(R.string.action_retry), onClick = onRetry, icon = Lucide.RotateCcw, modifier = Modifier.fillMaxWidth())
                }
                UploadStatus.QUEUED -> {
                    Notice(
                        stringResource(if (upload.failureKind == FailureKind.TRANSIENT) R.string.upload_waiting_retry else R.string.upload_waiting),
                        tone = Tone.NEUTRAL,
                    )
                    OutlineButton(stringResource(R.string.action_cancel_upload), onClick = onCancel, icon = Lucide.X, modifier = Modifier.fillMaxWidth())
                }
                UploadStatus.RUNNING -> Notice(stringResource(R.string.upload_in_progress))
                UploadStatus.CANCELLED -> Unit
            }
            if (upload.status != UploadStatus.RUNNING) {
                GhostButton(
                    stringResource(R.string.action_remove_from_history),
                    onClick = onRemove,
                    icon = Lucide.Trash2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
