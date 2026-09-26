package com.burgess.schedule.ui.morph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The only animation clock used by the sandbox. Session data stays alive until
 * the clock reaches zero, including for a zero-duration debug animation.
 */
@Stable
class MorphController internal constructor(private val scope: CoroutineScope) {
    val progress = Animatable(0f)
    private val sessionState = mutableStateOf<MorphSession?>(null)
    private val sourceRenderState = mutableStateOf<(@Composable MorphSourceContentScope.(Color?) -> Unit)?>(null)
    private val sourceMap = mutableStateMapOf<String, MorphSourceSpec>()
    private val globalSourceMap = mutableStateMapOf<String, MorphSourceSpec>()
    private val titleMap = mutableStateMapOf<String, MorphTitleSnapshot>()
    private var hostOrigin by mutableStateOf(Offset.Zero)
    private var animationJob: Job? = null
    private var phase by mutableStateOf(Phase.Idle)
    private var geometrySegmentStart = 0f
    private var geometrySegmentEnd = 1f
    private var geometryVisualStart = 0f
    private var geometryVisualEnd = 1f
    private var geometryEasing: Easing = LinearEasing
    private val sessionGate = MorphSessionGate()
    private var requestedAction: MorphExitAction? = null
    private var onExitFinished: (MorphExitAction) -> Unit = {}

    var speed by mutableFloatStateOf(1f)
        private set
    var animationsEnabled by mutableStateOf(true)
        private set
    private var debugStartProgress by mutableFloatStateOf(0f)

    val session: MorphSession? get() = sessionState.value
    val debugProgress: Float get() = if (sessionState.value == null) debugStartProgress else visualProgress
    internal val visualProgress: Float
        get() = MorphMath.segmentGeometryProgress(
            value = progress.value,
            segmentStart = geometrySegmentStart,
            segmentEnd = geometrySegmentEnd,
            visualStart = geometryVisualStart,
            visualEnd = geometryVisualEnd,
            easing = geometryEasing
        )
    internal val sourceRenderer: (@Composable MorphSourceContentScope.(Color?) -> Unit)? get() = sourceRenderState.value

    private enum class Phase { Idle, Opening, Expanded, BackGesture, Closing }
    private var backGestureStart = 0f

    /** Detail controls are enabled only after the opening clock has settled. */
    internal val detailsInteractive: Boolean
        get() = sessionState.value != null && phase == Phase.Expanded

    internal fun setExitListener(listener: (MorphExitAction) -> Unit) {
        onExitFinished = listener
    }

    internal fun recordSource(key: String, bounds: Rect, style: MorphSourceStyle, size: IntSize) {
        if (size.width > 0 && size.height > 0) {
            val global = MorphSourceSpec(key, bounds, style, size)
            globalSourceMap[key] = global
            sourceMap[key] = global.copy(bounds = Rect(bounds.left - hostOrigin.x, bounds.top - hostOrigin.y, bounds.right - hostOrigin.x, bounds.bottom - hostOrigin.y))
        }
    }

    internal fun setHostOrigin(origin: Offset) {
        hostOrigin = origin
        globalSourceMap.values.forEach { source ->
            sourceMap[source.instanceKey] = source.copy(bounds = Rect(source.bounds.left - origin.x, source.bounds.top - origin.y, source.bounds.right - origin.x, source.bounds.bottom - origin.y))
        }
    }

    internal fun recordedSource(key: String): MorphSourceSpec? = sourceMap[key]

    internal fun recordTitle(key: String, snapshot: MorphTitleSnapshot) {
        titleMap[key] = snapshot
    }

    internal fun recordedTitle(key: String): MorphTitleSnapshot? = titleMap[key]

    internal fun unregisterSource(key: String) {
        sourceMap.remove(key)
        globalSourceMap.remove(key)
        titleMap.remove(key)
    }

    internal fun isSourceHidden(key: String): Boolean = session?.source?.instanceKey == key

    fun updateSpeed(value: Float) {
        speed = value.coerceIn(.5f, 2f)
    }

    fun updateAnimationsEnabled(enabled: Boolean) {
        animationsEnabled = enabled
    }

    fun open(
        details: MorphDetails,
        source: MorphSourceSpec,
        title: MorphTitleSnapshot,
        sourceContent: @Composable MorphSourceContentScope.(Color?) -> Unit
    ): Boolean {
        if (sessionState.value != null || source.bounds.width <= 0f || source.bounds.height <= 0f || !sessionGate.tryStart()) return false
        animationJob?.cancel()
        requestedAction = null
        sourceRenderState.value = sourceContent
        sessionState.value = MorphSession(
            details,
            source,
            title.copy(
                bounds = Rect(
                    title.bounds.left - hostOrigin.x - source.bounds.left,
                    title.bounds.top - hostOrigin.y - source.bounds.top,
                    title.bounds.right - hostOrigin.x - source.bounds.left,
                    title.bounds.bottom - hostOrigin.y - source.bounds.top
                )
            )
        )
        val startProgress = debugStartProgress
        debugStartProgress = 0f
        if (startProgress > 0f) {
            configureGeometrySegment(startProgress, startProgress, startProgress, startProgress, LinearEasing)
        } else {
            configureGeometrySegment(0f, 1f, 0f, 1f, MorphMath.movementEasing)
        }
        phase = Phase.Opening
        animationJob = scope.launch {
            if (startProgress > 0f) {
                progress.snapTo(startProgress)
                phase = if (startProgress >= .999f) Phase.Expanded else Phase.Opening
            } else {
                progress.snapTo(0f)
                animateTo(1f, OPEN_DURATION_MS)
                if (sessionState.value != null && phase == Phase.Opening) phase = Phase.Expanded
            }
        }
        return true
    }

    /** Close, edit and delete all share this path and are ignored while closing. */
    fun requestDismiss(action: MorphExitAction = MorphExitAction.Close) {
        if (sessionState.value == null || !sessionGate.beginExit(action)) return
        val startProgress = progress.value
        val startVisualProgress = visualProgress
        requestedAction = action
        animationJob?.cancel()
        configureGeometrySegment(
            segmentStart = startProgress,
            segmentEnd = 0f,
            visualStart = startVisualProgress,
            visualEnd = 0f,
            easing = MorphMath.movementEasing
        )
        phase = Phase.Closing
        animationJob = scope.launch {
            animateTo(0f, CLOSE_DURATION_MS)
            val completed = sessionGate.finishExit() ?: MorphExitAction.Close
            sessionState.value = null
            sourceRenderState.value = null
            requestedAction = null
            phase = Phase.Idle
            configureGeometrySegment(0f, 1f, 0f, 1f, LinearEasing)
            animationJob = null
            onExitFinished(completed)
        }
    }

    /** Stops the current opening clock and captures its progress for system Back. */
    internal fun beginBackGesture(): Boolean {
        if (sessionState.value == null || sessionGate.closing || phase == Phase.BackGesture) return false
        animationJob?.cancel()
        animationJob = null
        backGestureStart = progress.value
        val startVisualProgress = visualProgress
        configureGeometrySegment(
            segmentStart = backGestureStart,
            segmentEnd = 0f,
            visualStart = startVisualProgress,
            visualEnd = 0f,
            easing = LinearEasing
        )
        phase = Phase.BackGesture
        return true
    }

    /** Drives the shared morph clock backwards with the platform gesture. */
    internal suspend fun updateBackGesture(gestureProgress: Float) {
        if (sessionState.value == null || phase != Phase.BackGesture) return
        progress.snapTo(backGestureStart * (1f - gestureProgress.coerceIn(0f, 1f)))
    }

    /** A completed system gesture continues through the regular close lifecycle. */
    internal fun commitBackGesture() {
        if (sessionState.value == null || phase != Phase.BackGesture) return
        requestDismiss()
    }

    /** Restores from the current seek position on the controller's composition scope. */
    internal fun cancelBackGesture() {
        if (sessionState.value == null || phase != Phase.BackGesture) return
        animationJob?.cancel()
        if (progress.value >= .999f) {
            phase = Phase.Expanded
            animationJob = null
            return
        }
        phase = Phase.Opening
        configureGeometrySegment(
            segmentStart = progress.value,
            segmentEnd = 1f,
            visualStart = visualProgress,
            visualEnd = 1f,
            easing = MorphMath.movementEasing
        )
        animationJob = scope.launch {
            animateTo(1f, OPEN_DURATION_MS)
            if (sessionState.value != null && phase == Phase.Opening) phase = Phase.Expanded
            animationJob = null
        }
    }

    /** Debug-only seek; it reuses the same clock and does not create another animation. */
    fun seekDebug(value: Float) {
        if (sessionState.value == null) {
            debugStartProgress = value.coerceIn(0f, 1f)
            return
        }
        if (phase == Phase.Closing) return
        animationJob?.cancel()
        configureGeometrySegment(0f, 1f, 0f, 1f, LinearEasing)
        phase = if (value >= .999f) Phase.Expanded else Phase.Opening
        animationJob = scope.launch {
            progress.snapTo(value.coerceIn(0f, 1f))
            animationJob = null
        }
    }

    private suspend fun animateTo(target: Float, baseDuration: Int) {
        val duration = if (!animationsEnabled) 0 else (baseDuration / speed).toInt().coerceAtLeast(0)
        if (duration == 0) progress.snapTo(target)
        else progress.animateTo(target, tween(duration, easing = LinearEasing))
    }

    private fun configureGeometrySegment(
        segmentStart: Float,
        segmentEnd: Float,
        visualStart: Float,
        visualEnd: Float,
        easing: Easing
    ) {
        geometrySegmentStart = segmentStart
        geometrySegmentEnd = segmentEnd
        geometryVisualStart = visualStart
        geometryVisualEnd = visualEnd
        geometryEasing = easing
    }

    private companion object {
        const val OPEN_DURATION_MS = 300
        const val CLOSE_DURATION_MS = 200
    }
}

@Composable
fun rememberMorphController(): MorphController {
    val scope = rememberCoroutineScope()
    return remember(scope) { MorphController(scope) }
}
