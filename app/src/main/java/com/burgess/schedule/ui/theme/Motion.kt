package com.burgess.schedule.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.*

object Motion {
    val EmphasizedDecelerate = CubicBezierEasing(.2f, 0f, 0f, 1f)
    var animationsEnabled by mutableStateOf(true)
    fun duration(ms: Int) = if (animationsEnabled) ms else 0
    val ContainerExpandMs get() = duration(400)
    val ContainerCollapseMs get() = duration(340)
    val ScrimMs get() = duration(300)
    val TabCrossfadeMs get() = duration(250)
    val ProgressBarMs get() = duration(1200)
    val PressMs get() = duration(100)
    const val PressScale = .98f
    const val ButtonPressScale = .97f
    const val StaggerMs = 40
}
