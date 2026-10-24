package dev.fl1nt.ember.ui.components

import androidx.core.net.toUri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import dev.fl1nt.ember.data.db.UploadOrigin
import dev.fl1nt.ember.data.db.UploadStatus
import dev.fl1nt.ember.ui.icons.Lucide
import java.io.File

/** What to show as an upload's preview while Ember can still read the file. */
fun UploadEntity.previewModel(): Any? = when {
    stagedPath != null -> File(stagedPath)
    sourceUri != null -> sourceUri.toUri()
    else -> null
}

@Composable
fun originLabel(origin: UploadOrigin): String = stringResource(
    when (origin) {
        UploadOrigin.SHARE -> R.string.origin_share
        UploadOrigin.PICKER -> R.string.origin_picker
        UploadOrigin.PASTE -> R.string.origin_paste
        UploadOrigin.TEXT_SELECTION -> R.string.origin_selection
        UploadOrigin.CLIPBOARD -> R.string.origin_clipboard
        UploadOrigin.SYNC -> R.string.origin_sync
    },
)

@Composable
fun failureLabel(kind: FailureKind?): String = stringResource(
    when (kind) {
        FailureKind.AUTH -> R.string.failure_auth
        FailureKind.PERMISSION -> R.string.failure_permission
        FailureKind.TOO_LARGE -> R.string.failure_too_large
        FailureKind.REJECTED -> R.string.failure_rejected
        FailureKind.SOURCE_GONE -> R.string.failure_source_gone
        FailureKind.TRANSIENT -> R.string.failure_transient
        FailureKind.OTHER, null -> R.string.failure_other
    },
)

/** One upload in a history list. */
@Composable
fun UploadRow(upload: UploadEntity, onClick: () -> Unit, onCopy: (() -> Unit)?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumb(upload.previewModel(), upload.mimeType)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(upload.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (upload.status) {
                UploadStatus.RUNNING -> {
                    val total = upload.sizeBytes ?: 0
                    FlameProgress(if (total > 0) upload.bytesSent.toFloat() / total else null, Modifier.padding(top = 4.dp, end = 8.dp))
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusFor(upload)
                    val detail = listOf(
                        Formats.relative(context, upload.completedAt ?: upload.createdAt),
                        Formats.size(context, upload.sizeBytes),
                    ).filter { it.isNotEmpty() }.joinToString(" · ")
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (onCopy != null && upload.status == UploadStatus.DONE) {
            IconAction(Lucide.Copy, stringResource(R.string.action_copy_link), onCopy)
        }
    }
}

@Composable
private fun StatusFor(upload: UploadEntity) {
    when (upload.status) {
        UploadStatus.QUEUED -> StatusPill(
            stringResource(if (upload.failureKind == FailureKind.TRANSIENT) R.string.status_retrying else R.string.status_waiting),
            icon = Lucide.Clock,
        )
        UploadStatus.RUNNING -> StatusPill(stringResource(R.string.status_uploading), tone = Tone.FLAME, icon = Lucide.CloudUpload)
        UploadStatus.DONE -> StatusPill(stringResource(R.string.status_uploaded), tone = Tone.SUCCESS, icon = Lucide.CircleCheck)
        UploadStatus.FAILED -> StatusPill(failureLabel(upload.failureKind), tone = Tone.DANGER, icon = Lucide.CircleAlert)
        UploadStatus.CANCELLED -> StatusPill(stringResource(R.string.status_cancelled), icon = Lucide.CircleX)
    }
}
