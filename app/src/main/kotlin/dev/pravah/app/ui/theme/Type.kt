package dev.pravah.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val base = Typography()

/** Tabular figures so live numbers never jitter horizontally. */
private const val TNUM = "tnum"

val PravahTypography = Typography(
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium, fontSize = 26.sp, letterSpacing = (-0.3).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    bodyLarge = base.bodyLarge.copy(fontFeatureSettings = TNUM),
    bodyMedium = base.bodyMedium.copy(fontFeatureSettings = TNUM),
    labelLarge = base.labelLarge.copy(fontFeatureSettings = TNUM),
    labelMedium = base.labelMedium.copy(letterSpacing = 0.6.sp),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.6.sp),
)

/** The single huge battery-level number. */
val HeroNumber = TextStyle(
    fontWeight = FontWeight.Thin,
    fontSize = 112.sp,
    lineHeight = 112.sp,
    letterSpacing = (-4).sp,
    fontFeatureSettings = TNUM,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

val HeroUnit = TextStyle(
    fontWeight = FontWeight.Light,
    fontSize = 36.sp,
    lineHeight = 36.sp,
    fontFeatureSettings = TNUM,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

val BigLine = TextStyle(
    fontWeight = FontWeight.Normal,
    fontSize = 28.sp,
    lineHeight = 34.sp,
    letterSpacing = (-0.4).sp,
    fontFeatureSettings = TNUM,
)

val Figure = TextStyle(
    fontWeight = FontWeight.Light,
    fontSize = 44.sp,
    lineHeight = 48.sp,
    letterSpacing = (-1).sp,
    fontFeatureSettings = TNUM,
)
