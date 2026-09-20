package com.burgess.schedule.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import android.provider.Settings
import androidx.core.view.WindowCompat
import androidx.compose.material.ripple.RippleAlpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun 课程表Theme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val savedTheme by remember(context) { Appearance.theme(context) }.collectAsState(initial = "跟随系统")
    val savedScale by remember(context) { Appearance.scale(context) }.collectAsState(initial = 1f)
    val effectiveDark = when (savedTheme) { "深色" -> true; "浅色" -> false; else -> darkTheme }
    SideEffect {
        Motion.Scale = savedScale
        Motion.animationsEnabled = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    val c = if (effectiveDark) DarkAppColors else LightAppColors
    val scheme = if (effectiveDark) darkColorScheme() else lightColorScheme()
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !effectiveDark
                isAppearanceLightNavigationBars = !effectiveDark
            }
        }
    }
    CompositionLocalProvider(
        LocalAppColors provides c,
        LocalRippleConfiguration provides RippleConfiguration(
            color = c.ink,
            rippleAlpha = RippleAlpha(draggedAlpha = .08f, focusedAlpha = .08f, hoveredAlpha = .08f, pressedAlpha = .08f)
        )
    ) {
        MaterialTheme(colorScheme = scheme.copy(
            primary = c.ink, onPrimary = c.onInk, primaryContainer = c.line, onPrimaryContainer = c.ink,
            secondary = c.ink, onSecondary = c.onInk, secondaryContainer = c.line, onSecondaryContainer = c.ink,
            tertiary = c.ink, onTertiary = c.onInk, tertiaryContainer = c.line, onTertiaryContainer = c.ink,
            background = c.bg, onBackground = c.ink, surface = c.card, onSurface = c.ink,
            surfaceVariant = c.bg, onSurfaceVariant = c.mut, outline = c.line, outlineVariant = c.line,
            surfaceTint = Color.Transparent, inverseSurface = c.ink, inverseOnSurface = c.onInk,
            error = c.danger, onError = c.card, errorContainer = c.card, onErrorContainer = c.danger,
            scrim = c.scrim, surfaceBright = c.card, surfaceDim = c.bg,
            surfaceContainerLowest = c.bg, surfaceContainerLow = c.card, surfaceContainer = c.card,
            surfaceContainerHigh = c.card, surfaceContainerHighest = c.card
        ), typography = Typography, content = content)
    }
}
