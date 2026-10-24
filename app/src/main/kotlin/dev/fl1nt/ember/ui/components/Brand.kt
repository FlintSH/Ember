package dev.fl1nt.ember.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.fl1nt.ember.ui.theme.Flame
import dev.fl1nt.ember.ui.theme.Wordmark

// The mark on a 100 unit grid: an outlined flame holding a glowing ember.
private val FlamePath = Path().apply {
    moveTo(58f, 10f)
    cubicTo(60f, 27f, 79f, 39f, 79f, 60f)
    cubicTo(79f, 78f, 66f, 90f, 50f, 90f)
    cubicTo(34f, 90f, 21f, 78f, 21f, 60f)
    cubicTo(21f, 42f, 39f, 31f, 51f, 20f)
    cubicTo(54f, 17f, 56.5f, 13.5f, 58f, 10f)
    close()
}

private val FlameStroke = Brush.linearGradient(listOf(Flame.Amber, Flame.Red), start = Offset(21f, 90f), end = Offset(79f, 10f))
private val EmberFill = Brush.radialGradient(listOf(Flame.Glow, Flame.Orange, Color(0xFFFF3D00)), center = Offset(50f, 62f), radius = 13f)

/** Ember's mark. Pass [tint] for a single-color version. */
@Composable
fun EmberMark(modifier: Modifier = Modifier, size: Dp = 32.dp, tint: Color? = null, glow: Boolean = false) {
    Canvas(modifier.size(size)) {
        if (glow) {
            drawCircle(
                brush = Brush.radialGradient(listOf(Flame.Orange.copy(alpha = 0.28f), Color.Transparent), center = center, radius = this.size.minDimension * 0.75f),
                radius = this.size.minDimension * 0.75f,
            )
        }
        scale(this.size.minDimension / 100f, pivot = Offset.Zero) {
            drawPath(
                FlamePath,
                brush = tint?.let(::SolidColor) ?: FlameStroke,
                style = Stroke(width = 8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawCircle(brush = tint?.let(::SolidColor) ?: EmberFill, radius = 11.5f, center = Offset(50f, 65f))
        }
    }
}

/** The mark with the "Ember" wordmark, in Flare's Inter ExtraBold. */
@Composable
fun EmberWordmark(modifier: Modifier = Modifier, markSize: Dp = 28.dp) {
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Ember" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EmberMark(size = markSize)
        Text("Ember", style = Wordmark, color = MaterialTheme.colorScheme.onBackground)
    }
}
