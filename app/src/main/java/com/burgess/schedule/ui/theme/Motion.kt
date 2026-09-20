package com.burgess.schedule.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.*
import com.burgess.schedule.BuildConfig

object Motion {
    val EmphasizedDecelerate = CubicBezierEasing(.2f, 0f, 0f, 1f)
    private var debugScale by mutableFloatStateOf(1f)
    var animationsEnabled by mutableStateOf(true)
    var Scale: Float
        get() = if (BuildConfig.DEBUG) debugScale else 1f
        set(value) { if (BuildConfig.DEBUG) debugScale = value.coerceIn(.6f, 1.6f) }
    fun duration(ms: Int) = if (animationsEnabled) (ms * Scale).toInt() else 0
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
