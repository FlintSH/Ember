package dev.fl1nt.ember.ui.components

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.fl1nt.ember.R
import dev.fl1nt.ember.ui.icons.Lucide

/** The Lucide icon Flare's library uses for a file type. */
fun fileIcon(mimeType: String?): ImageVector {
    val type = mimeType?.lowercase().orEmpty()
    return when {
        type.startsWith("image/") -> Lucide.Image
        type.startsWith("video/") -> Lucide.Video
        type.startsWith("audio/") -> Lucide.Music
        type.startsWith("text/") || type in TEXT_TYPES -> Lucide.FileText
        ARCHIVE_HINTS.any { type.contains(it) } -> Lucide.Archive
        else -> Lucide.File
    }
}

private val TEXT_TYPES = setOf("application/json", "application/xml", "application/yaml", "application/toml", "application/x-sh")
private val ARCHIVE_HINTS = listOf("zip", "tar", "gzip", "7z", "rar", "bzip", "xz")

/** A rounded thumbnail that falls back to the file type icon. */
@Composable
fun FileThumb(model: Any?, mimeType: String?, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(fileIcon(mimeType), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.42f))
        val previewable = mimeType?.startsWith("image/") == true
        if (model != null && previewable) {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}

object Formats {
    fun size(context: Context, bytes: Long?): String = if (bytes == null || bytes < 0) "" else Formatter.formatShortFileSize(context, bytes)

    fun relative(context: Context, millis: Long, now: Long = System.currentTimeMillis()): String =
        if (now - millis in 0 until DateUtils.MINUTE_IN_MILLIS) {
            context.getString(R.string.time_just_now)
        } else {
            DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
        }
}
