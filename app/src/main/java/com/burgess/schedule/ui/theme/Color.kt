package com.burgess.schedule.ui.theme

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

data class AppColors(val bg: Color, val card: Color, val ink: Color, val onInk: Color, val mut: Color, val line: Color, val live: Color, val danger: Color, val scrim: Color)
val LightAppColors = AppColors(Color(0xFFF7F7F8), Color(0xFFFFFFFF), Color(0xFF0D0D0D), Color(0xFFFFFFFF), Color(0xFF6E6E76), Color(0xFFE8E8EC), Color(0xFF5FD3A0), Color(0xFFE5484D), Color.Black.copy(alpha = .4f))
val DarkAppColors = AppColors(Color(0xFF0E0E10), Color(0xFF1A1A1D), Color(0xFFF2F2F3), Color(0xFF0D0D0D), Color(0xFF9A9AA3), Color(0xFF2A2A2F), Color(0xFF5FD3A0), Color(0xFFF2696D), Color.Black.copy(alpha = .55f))
val LocalAppColors = staticCompositionLocalOf { LightAppColors }
val CoursePalette = listOf(Color(0xFF3E9B74), Color(0xFF4B7FD1), Color(0xFF8467D6), Color(0xFFC08A2B), Color(0xFFD0526B))
fun contrast(a: Color, b: Color): Float = (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)
fun courseTint(hue: Color, colors: AppColors): Pair<Color, Color> {
    val background = lerp(colors.card, hue, .15f)
    var ratio = .32f
    var foreground = lerp(hue, colors.ink, ratio)
    while (contrast(foreground, background) < 4.5f && ratio < 1f) {
        ratio = (ratio + .04f).coerceAtMost(1f)
        foreground = lerp(hue, colors.ink, ratio)
    }
    return background to foreground
}
@Composable fun courseTint(hue: Color) = courseTint(hue, LocalAppColors.current)
