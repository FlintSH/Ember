package dev.fl1nt.ember.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.fl1nt.ember.ui.icons.Lucide
import dev.fl1nt.ember.ui.theme.EmberTheme
import dev.fl1nt.ember.ui.theme.Flame

enum class Tone { NEUTRAL, SUCCESS, WARNING, DANGER, FLAME }

@Composable
private fun Tone.color(): Color = when (this) {
    Tone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.SUCCESS -> EmberTheme.colors.success
    Tone.WARNING -> EmberTheme.colors.warning
    Tone.DANGER -> MaterialTheme.colorScheme.error
    Tone.FLAME -> MaterialTheme.colorScheme.tertiary
}

/** A small rounded label, like Flare's badges. */
@Composable
fun StatusPill(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.NEUTRAL, icon: ImageVector? = null) {
    val color = tone.color()
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.onSurface else color, maxLines = 1)
    }
}

/** A dot that shows whether something is live. */
@Composable
fun StatusDot(tone: Tone, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(tone.color()))
}

/** Explains an empty list and offers the next step. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconTile(icon, size = 48.dp)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) Box(Modifier.padding(top = 6.dp)) { action() }
    }
}

/** A progress bar in Flare's flame gradient. `null` progress animates. */
@Composable
fun FlameProgress(progress: Float?, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.secondaryContainer
    val sweep = if (progress == null) {
        val transition = rememberInfiniteTransition(label = "progress")
        val value by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
        value
    } else {
        0f
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .semantics { if (progress != null) progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }
            .drawBehind {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = radius)
                if (progress != null) {
                    val width = size.width * progress.coerceIn(0f, 1f)
                    if (width > 0f) drawRoundRect(Flame.horizontal, size = Size(width, size.height), cornerRadius = radius)
                } else {
                    val segment = size.width * 0.35f
                    val start = (size.width + segment) * sweep - segment
                    drawRoundRect(Flame.horizontal, topLeft = Offset(start.coerceAtLeast(0f), 0f), size = Size((segment + minOf(0f, start)).coerceAtLeast(0f), size.height), cornerRadius = radius)
                }
            },
    )
}

/** An inline notice, for example a warning above a form. */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.NEUTRAL,
    icon: ImageVector = when (tone) {
        Tone.DANGER -> Lucide.CircleAlert
        Tone.WARNING -> Lucide.TriangleAlert
        Tone.SUCCESS -> Lucide.CircleCheck
        else -> Lucide.Info
    },
    title: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    val color = tone.color()
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .border(1.dp, if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.outline else color.copy(alpha = 0.45f), MaterialTheme.shapes.medium)
            .background(if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.background else color.copy(alpha = 0.08f))
            .padding(12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp).padding(top = 1.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (action != null) Box(Modifier.padding(start = 28.dp)) { action() }
    }
}
