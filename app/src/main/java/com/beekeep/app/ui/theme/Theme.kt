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
    val Cream = Color(0xFFFAF8F5)
    val Paper = Color(0xFFFFFDF9)
    val Sand = Color(0xFFF0ECE1)
    val Clay = Color(0xFFD97706)
    val Rust = Color(0xFFB45309)
    val Moss = Color(0xFF7C7F4E)
    val Pine = Color(0xFF4A5A3C)
    val Sage = Color(0xFFADB08C)
    val Bark = Color(0xFF2A2421)
    val BarkDeep = Color(0xFF1C1917)
    val Ink = Color(0xFF2A2421)
    val DuskInk = Color(0xFFF1E9DF)
}

private val Light = lightColorScheme(
    primary = Color(0xFFD97706),
    onPrimary = Color(0xFFFFFDF9),
    primaryContainer = Color(0xFFF0ECE1),
    onPrimaryContainer = Color(0xFF2A2421),
    secondary = Color(0xFFF59E0B),
    onSecondary = Color(0xFF2A2421),
    secondaryContainer = Color(0xFFFFE8C2),
    onSecondaryContainer = Color(0xFF593208),
    tertiary = Trail.Pine,
    onTertiary = Trail.Paper,
    tertiaryContainer = Color(0xFFE4E7D4),
    onTertiaryContainer = Color(0xFF2E3A24),
    background = Color(0xFFFAF8F5),
    onBackground = Color(0xFF2A2421),
    surface = Color(0xFFFFFDF9),
    onSurface = Color(0xFF2A2421),
    surfaceVariant = Color(0xFFF0ECE1),
    onSurfaceVariant = Color(0xFF66594D),
    outline = Color(0xFFD3C9BC),
    error = Color(0xFF9F3525),
    onError = Color(0xFFFFFDF9)
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF59E0B),
    onPrimary = Color(0xFF2A2421),
    primaryContainer = Color(0xFF573719),
    onPrimaryContainer = Color(0xFFFFE4BD),
    secondary = Color(0xFFD97706),
    onSecondary = Color(0xFF211A15),
    secondaryContainer = Color(0xFF4B3322),
    onSecondaryContainer = Color(0xFFFFE3C1),
    tertiary = Color(0xFFAAB18B),
    onTertiary = Color(0xFF20291A),
    tertiaryContainer = Color(0xFF3C4630),
    onTertiaryContainer = Color(0xFFDCE3C8),
    background = Color(0xFF1C1917),
    onBackground = Color(0xFFF1E9DF),
    surface = Color(0xFF2A2421),
    onSurface = Color(0xFFF1E9DF),
    surfaceVariant = Color(0xFF403832),
    onSurfaceVariant = Color(0xFFD0C4B8),
    outline = Color(0xFF85766A),
    error = Color(0xFFFFB4A5),
    onError = Color(0xFF3B1110)
)

val NavBarLight = Color(0xFF2A2421)
val NavBarDark = Color(0xFF171311)
val OnNavBar = Color(0xFFFFF8EF)
val OnNavBarMuted = Color(0xFFD0C0B0)
val NavIndicator = Color(0xFFF59E0B)

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
