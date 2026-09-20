package com.burgess.schedule.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

object AppSpace {
    val page = 20.dp; val weekPage = 14.dp; val card = 16.dp; val hero = 20.dp
    val gap = 10.dp; val group = 26.dp; val touch = 48.dp; val line = 1.dp
    val cell = 12.dp; val cardRadius = 20.dp; val heroRadius = 24.dp; val sheetRadius = 28.dp
    val pillRadius = 999.dp; val button = 44.dp; val smallButton = 36.dp; val iconButton = 40.dp
    val row = 64.dp; val icon = 36.dp; val navSpace = 96.dp; val navElevation = 8.dp
    val axis = 34.dp; val gridGap = 4.dp; val gridRow = 104.dp; val timeline = 42.dp
    val date = 28.dp; val cellHorizontal = 5.dp; val cellVertical = 8.dp; val progress = 3.dp
    val glyph = 24.dp
}
object AppShape {
    val card = RoundedCornerShape(AppSpace.cardRadius)
    val hero = RoundedCornerShape(AppSpace.heroRadius)
    val sheet = RoundedCornerShape(AppSpace.sheetRadius)
    val cell = RoundedCornerShape(AppSpace.cell)
    val pill = RoundedCornerShape(AppSpace.pillRadius)
}
