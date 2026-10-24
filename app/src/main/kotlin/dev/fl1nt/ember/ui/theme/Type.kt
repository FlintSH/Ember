package dev.fl1nt.ember.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.fl1nt.ember.R

/** Inter, Flare's typeface, from one variable font file. */
val Inter = FontFamily(
    Font(R.font.inter_variable, FontWeight.Normal),
    Font(R.font.inter_variable, FontWeight.Medium),
    Font(R.font.inter_variable, FontWeight.SemiBold),
    Font(R.font.inter_variable, FontWeight.Bold),
    Font(R.font.inter_variable, FontWeight.ExtraBold),
)

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

val EmberTypography = Typography(
    displaySmall = style(34, 40, FontWeight.ExtraBold, -0.025),
    headlineLarge = style(30, 36, FontWeight.Bold, -0.022),
    headlineMedium = style(26, 32, FontWeight.Bold, -0.02),
    headlineSmall = style(22, 28, FontWeight.Bold, -0.015),
    titleLarge = style(19, 26, FontWeight.SemiBold, -0.012),
    titleMedium = style(16, 22, FontWeight.SemiBold, -0.006),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(13, 18, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(13, 16, FontWeight.Medium),
    // Flare's uppercase section eyebrows, such as "YOUR SPACE".
    labelSmall = style(11, 16, FontWeight.SemiBold, 0.14),
)

/** Flare's wordmark style: Inter ExtraBold. */
val Wordmark = style(22, 26, FontWeight.ExtraBold, -0.02)
