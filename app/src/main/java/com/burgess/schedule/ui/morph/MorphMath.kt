package com.burgess.schedule.ui.morph

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.burgess.schedule.ui.theme.contrast

/** Small, side-effect free functions used by the sandbox transition. */
object MorphMath {
    /** Fast out, slow in movement without an ease-in pause or overshoot. */
    internal val movementEasing: Easing = CubicBezierEasing(0f, 0f, .2f, 1f)

    fun clampProgress(value: Float): Float = value.coerceIn(0f, 1f)

    /** The clock is linear; geometry alone uses this eased value. */
    fun geometryProgress(value: Float): Float = movementEasing.transform(clampProgress(value))

    /** Maps a linear animation segment to a visual segment while preserving
     * continuity when an in-flight morph reverses direction. */
    fun segmentGeometryProgress(
        value: Float,
        segmentStart: Float,
        segmentEnd: Float,
        visualStart: Float,
        visualEnd: Float,
        easing: Easing = movementEasing
    ): Float {
        val span = segmentEnd - segmentStart
        val fraction = if (kotlin.math.abs(span) < 0.000001f) {
            1f
        } else {
            ((value - segmentStart) / span).coerceIn(0f, 1f)
        }
        val eased = easing.transform(fraction)
        return visualStart + (visualEnd - visualStart) * eased
    }

    fun smoothStep(start: Float, end: Float, value: Float): Float {
        if (end <= start) return if (value < start) 0f else 1f
        val u = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    /** Source and detail bodies have separate windows; the title has its own
     * longer hand-off so the two fixed layouts never ghost each other. */
    fun sourceBodyAlpha(value: Float): Float = 1f - smoothStep(.05f, .14f, value)

    fun detailBodyAlpha(value: Float): Float = smoothStep(.45f, .70f, value)

    fun titleHandoff(value: Float): Float = smoothStep(.32f, .52f, value)

    /** Title geometry settles before its complementary alpha hand-off. */
    fun titleGeometryProgress(value: Float): Float =
        geometryProgress(smoothStep(.14f, .32f, value))

    fun sourceFaceAlpha(value: Float): Float = 1f - titleHandoff(value)

    /** Detail content is a complementary title hand-off, not a second clock. */
    fun detailAlpha(value: Float): Float {
        return titleHandoff(value)
    }

    /** Keep the registered source colour at its own endpoint. */
    fun useSourceInkFallback(fill: Color, sourceForeground: Color, ink: Color): Boolean {
        if (sourceForeground == Color.Unspecified) return true
        val sourceContrast = contrast(fill, sourceForeground)
        return sourceContrast < 4.5f && contrast(fill, ink) > sourceContrast
    }

    fun interpolate(start: Float, end: Float, value: Float): Float =
        start + (end - start) * clampProgress(value)

    fun interpolateRect(source: Rect, target: Rect, value: Float): Rect {
        val t = clampProgress(value)
        fun mix(a: Float, b: Float) = a + (b - a) * t
        return Rect(mix(source.left, target.left), mix(source.top, target.top),
            mix(source.right, target.right), mix(source.bottom, target.bottom))
    }

    fun cornerRadius(source: Float, target: Float, value: Float): Float =
        interpolate(source, target, value)

    /** True when the content hand-off has no near-transparent gap. */
    fun bothNearlyInvisible(value: Float, threshold: Float = .1f): Boolean =
        sourceFaceAlpha(value) < threshold && detailAlpha(value) < threshold
}

/** Pure lifecycle gate shared by the controller; it makes close idempotence
 * and delayed cleanup testable without constructing Android snapshot state. */
internal class MorphSessionGate {
    var active: Boolean = false
        private set
    var closing: Boolean = false
        private set
    private var pendingAction: MorphExitAction? = null

    fun tryStart(): Boolean {
        if (active) return false
        active = true
        closing = false
        pendingAction = null
        return true
    }

    fun beginExit(action: MorphExitAction): Boolean {
        if (!active || closing) return false
        closing = true
        pendingAction = action
        return true
    }

    fun finishExit(): MorphExitAction? {
        if (!active || !closing) return null
        val result = pendingAction ?: MorphExitAction.Close
        active = false
        closing = false
        pendingAction = null
        return result
    }
}
