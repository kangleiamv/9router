package ai.ninerouter.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 9Router has two complete palettes. The getters below resolve against the
 * active one, so every existing `TextPrimary` / `Brand` reference follows the
 * theme without per-screen changes. [NineRouterTheme] defines the active
 * palette before content composes, and the activity recreates when it changes.
 */
object AppPalette {
    @Volatile
    var dark: Boolean = true
}

private val DarkBackground = Color(0xFF09090B)
private val DarkSurface = Color(0xFF121215)
private val DarkSurfaceHigh = Color(0xFF1A1A1F)
private val DarkSurfaceHighest = Color(0xFF222228)
private val DarkBorder = Color(0xFF27272E)
private val DarkBrand = Color(0xFFE87040)
private val DarkBrandDeep = Color(0xFFB84A20)
private val DarkBrandSoft = Color(0xFF3A1D12)
private val DarkEmerald = Color(0xFF34D399)
private val DarkEmeraldSoft = Color(0xFF0E2C22)
private val DarkAmber = Color(0xFFFBBF24)
private val DarkAmberSoft = Color(0xFF2E2410)
private val DarkRose = Color(0xFFF87171)
private val DarkRoseSoft = Color(0xFF311518)
private val DarkSky = Color(0xFF38BDF8)
private val DarkViolet = Color(0xFFA78BFA)
private val DarkTextPrimary = Color(0xFFF4F4F5)
private val DarkTextSecondary = Color(0xFFA1A1AA)
private val DarkTextMuted = Color(0xFF71717A)

private val LightBackground = Color(0xFFF7F5F2)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceHigh = Color(0xFFF1EDE8)
private val LightSurfaceHighest = Color(0xFFE7E1DA)
private val LightBorder = Color(0xFFDED7CF)
private val LightBrand = Color(0xFFC2410C)
private val LightBrandDeep = Color(0xFF9A3412)
private val LightBrandSoft = Color(0xFFFFEDD5)
private val LightEmerald = Color(0xFF047857)
private val LightEmeraldSoft = Color(0xFFD1FAE5)
private val LightAmber = Color(0xFFB45309)
private val LightAmberSoft = Color(0xFFFEF3C7)
private val LightRose = Color(0xFFDC2626)
private val LightRoseSoft = Color(0xFFFEE2E2)
private val LightSky = Color(0xFF0284C7)
private val LightViolet = Color(0xFF7C3AED)
private val LightTextPrimary = Color(0xFF1C1917)
private val LightTextSecondary = Color(0xFF57534E)
private val LightTextMuted = Color(0xFF857E76)

val Obsidian: Color get() = if (AppPalette.dark) DarkBackground else LightBackground
val AppSurface: Color get() = if (AppPalette.dark) DarkSurface else LightSurface
val AppSurfaceHigh: Color get() = if (AppPalette.dark) DarkSurfaceHigh else LightSurfaceHigh
val AppSurfaceHighest: Color get() = if (AppPalette.dark) DarkSurfaceHighest else LightSurfaceHighest
val AppBorder: Color get() = if (AppPalette.dark) DarkBorder else LightBorder
val Brand: Color get() = if (AppPalette.dark) DarkBrand else LightBrand
val BrandDeep: Color get() = if (AppPalette.dark) DarkBrandDeep else LightBrandDeep
val BrandSoft: Color get() = if (AppPalette.dark) DarkBrandSoft else LightBrandSoft
val Emerald: Color get() = if (AppPalette.dark) DarkEmerald else LightEmerald
val EmeraldSoft: Color get() = if (AppPalette.dark) DarkEmeraldSoft else LightEmeraldSoft
val Amber: Color get() = if (AppPalette.dark) DarkAmber else LightAmber
val AmberSoft: Color get() = if (AppPalette.dark) DarkAmberSoft else LightAmberSoft
val Rose: Color get() = if (AppPalette.dark) DarkRose else LightRose
val RoseSoft: Color get() = if (AppPalette.dark) DarkRoseSoft else LightRoseSoft
val Sky: Color get() = if (AppPalette.dark) DarkSky else LightSky
val Violet: Color get() = if (AppPalette.dark) DarkViolet else LightViolet
val TextPrimary: Color get() = if (AppPalette.dark) DarkTextPrimary else LightTextPrimary
val TextSecondary: Color get() = if (AppPalette.dark) DarkTextSecondary else LightTextSecondary
val TextMuted: Color get() = if (AppPalette.dark) DarkTextMuted else LightTextMuted

/** Solid colour behind a screen; also used by the status/navigation bars. */
val AppBackground: Color get() = Obsidian
/** Raised surface used by cards and sheets. */
val AppCardColor: Color get() = AppSurface

private fun darkScheme() = darkColorScheme(
    primary = DarkBrand,
    onPrimary = Color.White,
    primaryContainer = DarkBrandSoft,
    onPrimaryContainer = Color(0xFFFFD8C7),
    secondary = DarkEmerald,
    onSecondary = Color(0xFF04140E),
    secondaryContainer = DarkEmeraldSoft,
    onSecondaryContainer = Color(0xFFA7F3D0),
    tertiary = DarkAmber,
    onTertiary = Color(0xFF1F1503),
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceHigh,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorder,
    outlineVariant = Color(0xFF1F1F25),
    error = DarkRose,
    onError = Color(0xFF2A0A0B),
    errorContainer = DarkRoseSoft,
    onErrorContainer = Color(0xFFFECACA),
    inverseSurface = DarkTextPrimary,
    inverseOnSurface = DarkBackground,
)

private fun lightScheme() = lightColorScheme(
    primary = LightBrand,
    onPrimary = Color.White,
    primaryContainer = LightBrandSoft,
    onPrimaryContainer = Color(0xFF7C2D12),
    secondary = LightEmerald,
    onSecondary = Color.White,
    secondaryContainer = LightEmeraldSoft,
    onSecondaryContainer = Color(0xFF065F46),
    tertiary = LightAmber,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceHigh,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    outlineVariant = Color(0xFFEDE7E0),
    error = LightRose,
    onError = Color.White,
    errorContainer = LightRoseSoft,
    onErrorContainer = Color(0xFF991B1B),
    inverseSurface = LightTextPrimary,
    inverseOnSurface = LightSurface,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8f).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4f).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.2f).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 21.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp
    )
)

@Composable
fun NineRouterTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    AppPalette.dark = dark
    MaterialTheme(
        colorScheme = if (dark) darkScheme() else lightScheme(),
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
