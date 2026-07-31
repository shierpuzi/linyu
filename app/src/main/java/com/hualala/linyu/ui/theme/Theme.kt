package com.hualala.linyu.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

enum class ThemeMode { LIGHT, DARK }

// ── Light palette ──
private val LightColors = AppColorSet(
    Background = Color(0xFFF6F8FA), Card = Color.White,
    Primary = Color(0xFF0D1117), Accent = Color(0xFF2563EB),
    TextPrimary = Color(0xFF111827), TextSecondary = Color(0xFF6B7280),
    Success = Color(0xFF22C55E), Warning = Color(0xFFF59E0B),
    Danger = Color(0xFFEF4444), ActiveBg = Color(0xFFFFF7ED),
    SurfaceVariant = Color(0xFFF0F0F0)
)

// ── Dark palette ──
private val DarkColors = AppColorSet(
    Background = Color(0xFF0D1117), Card = Color(0xFF161B22),
    Primary = Color(0xFFE6EDF3), Accent = Color(0xFF58A6FF),
    TextPrimary = Color(0xFFE6EDF3), TextSecondary = Color(0xFF8B949E),
    Success = Color(0xFF3FB950), Warning = Color(0xFFD29922),
    Danger = Color(0xFFF85149), ActiveBg = Color(0xFF1B1F23),
    SurfaceVariant = Color(0xFF21262D)
)

data class AppColorSet(
    val Background: Color, val Card: Color,
    val Primary: Color, val Accent: Color,
    val TextPrimary: Color, val TextSecondary: Color,
    val Success: Color, val Warning: Color,
    val Danger: Color, val ActiveBg: Color,
    val SurfaceVariant: Color
)

val LocalAppColors = staticCompositionLocalOf { LightColors }
val LocalThemeMode = compositionLocalOf { mutableStateOf(ThemeMode.LIGHT) }

/** Convenience: use AppColors.xxx in composable functions */
object AppColors {
    val Background: Color @Composable get() = LocalAppColors.current.Background
    val Card: Color @Composable get() = LocalAppColors.current.Card
    val Primary: Color @Composable get() = LocalAppColors.current.Primary
    val Accent: Color @Composable get() = LocalAppColors.current.Accent
    val TextPrimary: Color @Composable get() = LocalAppColors.current.TextPrimary
    val TextSecondary: Color @Composable get() = LocalAppColors.current.TextSecondary
    val Success: Color @Composable get() = LocalAppColors.current.Success
    val Warning: Color @Composable get() = LocalAppColors.current.Warning
    val Danger: Color @Composable get() = LocalAppColors.current.Danger
    val ActiveBg: Color @Composable get() = LocalAppColors.current.ActiveBg
}

@Composable
fun LinYuTheme(content: @Composable () -> Unit) {
    val themeModeState = LocalThemeMode.current
    val themeMode by themeModeState
    val dark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val set = if (dark) DarkColors else LightColors
    val scheme = if (dark) darkColorScheme(
        primary = set.Accent, background = set.Background, surface = set.Card,
        onPrimary = set.Background, secondary = set.TextSecondary,
        surfaceVariant = set.SurfaceVariant
    ) else lightColorScheme(
        primary = set.Accent, background = set.Background, surface = set.Card,
        onPrimary = Color.White, secondary = set.TextSecondary,
        surfaceVariant = set.SurfaceVariant
    )
    CompositionLocalProvider(LocalAppColors provides set) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
