package com.beekeep.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Trail {
    val Cream = Color(0xFFF5EFE0)
    val Paper = Color(0xFFFBF7EC)
    val Sand = Color(0xFFEDE1C8)
    val Clay = Color(0xFF9C6B3F)
    val Rust = Color(0xFF7C4A26)
    val Moss = Color(0xFF7C7F4E)
    val Pine = Color(0xFF4A5A3C)
    val Sage = Color(0xFFADB08C)
    val Bark = Color(0xFF3A2B1C)
    val BarkDeep = Color(0xFF221811)
    val Ink = Color(0xFF33261A)
    val DuskInk = Color(0xFFEDE2CB)
}

private val Light = lightColorScheme(
    primary = Trail.Clay,
    onPrimary = Trail.Paper,
    primaryContainer = Trail.Sand,
    onPrimaryContainer = Trail.Bark,
    secondary = Trail.Moss,
    onSecondary = Trail.Paper,
    secondaryContainer = Color(0xFFE1DEC2),
    onSecondaryContainer = Color(0xFF38391F),
    tertiary = Trail.Pine,
    onTertiary = Trail.Paper,
    tertiaryContainer = Color(0xFFDBE0C6),
    onTertiaryContainer = Color(0xFF2E3A24),
    background = Trail.Cream,
    onBackground = Trail.Ink,
    surface = Trail.Paper,
    onSurface = Trail.Ink,
    surfaceVariant = Color(0xFFE7DFCB),
    onSurfaceVariant = Color(0xFF6E5C44),
    outline = Color(0xFFAC9A7A),
    error = Color(0xFFA3402A),
    onError = Trail.Paper
)

private val Dark = darkColorScheme(
    primary = Color(0xFFD9A56C),
    onPrimary = Color(0xFF3A2410),
    primaryContainer = Color(0xFF5C4123),
    onPrimaryContainer = Color(0xFFF1DFC2),
    secondary = Color(0xFFB7BA83),
    onSecondary = Color(0xFF2C2E15),
    secondaryContainer = Color(0xFF484A2C),
    onSecondaryContainer = Color(0xFFE0E2C2),
    tertiary = Color(0xFFAAB18B),
    onTertiary = Color(0xFF20291A),
    tertiaryContainer = Color(0xFF3C4630),
    onTertiaryContainer = Color(0xFFDCE3C8),
    background = Trail.BarkDeep,
    onBackground = Trail.DuskInk,
    surface = Color(0xFF2C2015),
    onSurface = Trail.DuskInk,
    surfaceVariant = Color(0xFF473722),
    onSurfaceVariant = Color(0xFFC4B297),
    outline = Color(0xFF8C7A5E),
    error = Color(0xFFE08B72),
    onError = Color(0xFF3A1408)
)

val NavBarLight = Trail.Bark
val NavBarDark = Color(0xFF1B1109)
val OnNavBar = Color(0xFFF1E6CF)
val OnNavBarMuted = Color(0xFFBCA886)
val NavIndicator = Color(0xFFD9A56C)

class LandscapePalette(
    val sky: Color,
    val sun: Color,
    val hillBack: Color,
    val hillMid: Color,
    val hillFront: Color,
    val tree: Color,
    val ink: Color
)

val LandscapeDay = LandscapePalette(
    sky = Color(0xFFF0E6CF),
    sun = Color(0xFFC8894E),
    hillBack = Color(0xFFCBBE97),
    hillMid = Color(0xFF8A8757),
    hillFront = Color(0xFF5A6A45),
    tree = Color(0xFF3E5233),
    ink = Trail.Ink
)

val LandscapeDusk = LandscapePalette(
    sky = Color(0xFF463322),
    sun = Color(0xFFD9C9A6),
    hillBack = Color(0xFF5A4630),
    hillMid = Color(0xFF4A4226),
    hillFront = Color(0xFF2E3823),
    tree = Color(0xFF1F2A19),
    ink = Trail.DuskInk
)

private val BaseType = Typography()
private val BeeTypography = Typography(
    headlineLarge = BaseType.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.5.sp),
    headlineMedium = BaseType.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.4.sp),
    headlineSmall = BaseType.headlineSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.3.sp),
    titleMedium = BaseType.titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
    labelLarge = BaseType.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
)

private val BeeShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

@Composable
fun BeeKeepTheme(darkMode: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkMode) Dark else Light,
        typography = BeeTypography,
        shapes = BeeShapes,
        content = content
    )
}
