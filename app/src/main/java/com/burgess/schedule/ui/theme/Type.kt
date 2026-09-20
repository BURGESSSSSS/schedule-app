package com.burgess.schedule.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.burgess.schedule.R
import androidx.compose.ui.unit.sp

object AppType {
    // Static Latin faces; Android supplies missing Chinese glyphs from system fonts.
    val inter = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold)
    )
    val title = style(34, 40, FontWeight.SemiBold, -.85f)
    val sheetTitle = style(24, 30, FontWeight.SemiBold, -.48f)
    val cardTitle = style(16, 22, FontWeight.SemiBold, -.16f)
    val body = style(15, 21, FontWeight.Normal)
    val caption = style(13, 18, FontWeight.Normal)
    val cell: TextStyle @Composable get() = if (LocalConfiguration.current.screenWidthDp <= 360) style(11, 15, FontWeight.Medium) else style(12, 16, FontWeight.Medium)
    val micro: TextStyle @Composable get() = if (LocalConfiguration.current.screenWidthDp <= 360) style(10, 13, FontWeight.Medium) else style(11, 14, FontWeight.Medium)
    private fun style(size: Int, height: Int, weight: FontWeight, spacing: Float = 0f) = TextStyle(fontFamily = inter, fontSize = size.sp, lineHeight = height.sp, fontWeight = weight, letterSpacing = spacing.sp, fontFeatureSettings = "tnum")
}
val Typography = Typography(headlineLarge = AppType.title, headlineMedium = AppType.sheetTitle, headlineSmall = AppType.sheetTitle, titleLarge = AppType.cardTitle, titleMedium = AppType.cardTitle, titleSmall = AppType.cardTitle, bodyLarge = AppType.body, bodyMedium = AppType.body, bodySmall = AppType.caption, labelLarge = AppType.caption, labelMedium = AppType.caption, labelSmall = AppType.caption)
