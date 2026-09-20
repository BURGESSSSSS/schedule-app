package com.burgess.schedule.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 浅色是唯一主题基准（对齐 openai.com 的白底视觉），不跟随系统深色模式。
// 深色模式需要专门设计一整套对比关系后再开启，而不是简单反转导致不可读。
private val LightColors = lightColorScheme(
    primary = Ink10,
    onPrimary = White,
    primaryContainer = Gray200,
    onPrimaryContainer = Ink10,
    inversePrimary = Green,
    secondary = Ink60,
    onSecondary = White,
    secondaryContainer = Color(0xFFE3F4ED),
    onSecondaryContainer = GreenDeep,
    tertiary = GreenDeep,
    onTertiary = White,
    tertiaryContainer = Color(0xFFE3F4ED),
    onTertiaryContainer = GreenDeep,
    background = White,
    onBackground = Ink10,
    surface = White,
    onSurface = Ink10,
    surfaceVariant = Gray100,
    onSurfaceVariant = Ink60,
    surfaceTint = Color.Transparent,
    inverseSurface = Ink10,
    inverseOnSurface = White,
    outline = Gray200,
    outlineVariant = Gray300,
    error = Red,
    onError = White,
    errorContainer = Color(0xFFFFE7E5),
    onErrorContainer = Red,
    scrim = Color(0x52000000),
    surfaceBright = White,
    surfaceDim = Gray200,
    surfaceContainerLowest = White,
    surfaceContainerLow = Gray50,
    surfaceContainer = Gray100,
    surfaceContainerHigh = Gray200,
    surfaceContainerHighest = Gray300
)

@Composable
fun 课程表Theme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography,
        content = content
    )
}
