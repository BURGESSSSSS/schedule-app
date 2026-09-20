@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class, androidx.compose.animation.core.ExperimentalTransitionApi::class)
package com.burgess.schedule.ui.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.*
import com.burgess.schedule.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class CourseDetail(val id: Long, val title: String, val time: String, val periods: String, val room: String, val hue: Color)
data class CourseTransitionContext(val shared: SharedTransitionScope, val transition: Transition<Boolean>, val selectedId: Long?)
val LocalCourseTransition = staticCompositionLocalOf<CourseTransitionContext?> { null }
val LocalCourseVisibility = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable fun CourseTransitionHost(detail: CourseDetail?, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, content: @Composable () -> Unit) {
    var retained by remember { mutableStateOf(detail) }
    if (detail != null) retained = detail
    val state = remember { SeekableTransitionState(false) }
    val transition = rememberTransition(state, label = "课程容器")
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(detail != null) {
        runCatching { haptic.performHapticFeedback(HapticFeedbackType.ContextClick) }
        state.animateTo(detail != null, tween(if (detail != null) Motion.ContainerExpandMs else Motion.ContainerCollapseMs, easing = Motion.EmphasizedDecelerate))
    }
    PredictiveBackHandler(enabled = detail != null) { events ->
        try {
            events.collect { event -> state.seekTo(event.progress, false) }
            onDismiss()
        } catch (_: CancellationException) { state.animateTo(true, tween(Motion.ContainerExpandMs)) }
    }
    SharedTransitionLayout {
        val shared = this
        CompositionLocalProvider(LocalCourseTransition provides CourseTransitionContext(shared, transition, retained?.id)) {
            content()
            transition.AnimatedVisibility(visible = { it }, enter = EnterTransition.None, exit = ExitTransition.None) {
                val visibility = this
                val p by transition.animateFloat(label = "统一进度") { if (it) 1f else 0f }
                val c = LocalAppColors.current
                val density = LocalDensity.current
                var sheetHeight by remember { mutableIntStateOf(1) }
                var drag by remember { mutableFloatStateOf(0f) }
                Box(Modifier.fillMaxSize().renderInSharedTransitionScopeOverlay(zIndexInOverlay = 1f, renderInOverlay = { true })) {
                    Box(Modifier.fillMaxSize().background(c.scrim.copy(alpha = c.scrim.alpha * p)).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = onDismiss))
                    retained?.let { item ->
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(AppSpace.weekPage)
                            .sharedBounds(rememberSharedContentState("course-${item.id}"), visibility, enter = fadeIn(tween(Motion.ContainerExpandMs)), exit = fadeOut(tween(Motion.ContainerCollapseMs)), boundsTransform = { _, _ -> tween(if (detail != null) Motion.ContainerExpandMs else Motion.ContainerCollapseMs, easing = Motion.EmphasizedDecelerate) }, placeholderSize = SharedTransitionScope.PlaceholderSize.ContentSize, zIndexInOverlay = 2f)
                            .clip(AppShape.sheet).background(c.card).onSizeChanged { sheetHeight = it.height }
                            .draggable(rememberDraggableState { delta -> drag = (drag + delta).coerceAtLeast(0f); scope.launch { state.seekTo((drag / sheetHeight).coerceIn(0f, 1f), false) } }, Orientation.Vertical,
                                onDragStarted = { drag = 0f }, onDragStopped = { velocity -> if (drag > with(density) { 90.dp.toPx() } || velocity > with(density) { 1200.dp.toPx() }) onDismiss() else state.animateTo(true, spring(dampingRatio = .8f, stiffness = 400f)) })
                            .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = {})
                            .verticalScroll(rememberScrollState()).padding(AppSpace.hero), verticalArrangement = Arrangement.spacedBy(AppSpace.card)) {
                            Box(Modifier.align(Alignment.CenterHorizontally).width(AppSpace.icon).height(AppSpace.gridGap).background(c.line, AppShape.pill))
                            CompositionLocalProvider(LocalCourseVisibility provides visibility) { Text(item.title, Modifier.courseTitle(item.id), style = AppType.sheetTitle, color = c.ink) }
                            Column(Modifier.graphicsLayer { alpha = smoothStep(.3f, .8f, p) }) {
                                Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gap), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(AppSpace.cellVertical).background(item.hue, AppShape.pill))
                                    Text(item.periods, style = AppType.body, color = c.ink)
                                }
                                SectionLabel(item.time)
                                HorizontalDivider(Modifier.padding(vertical = AppSpace.card), color = c.line)
                                Text(item.room.ifBlank { "未填写" }, style = AppType.body, color = c.ink)
                                HorizontalDivider(Modifier.padding(vertical = AppSpace.card), color = c.line)
                                Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                                    PillButton("编辑", onEdit)
                                    PillButton("删除", onDelete, PillStyle.Secondary, danger = true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable fun CourseSource(id: Long, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalCourseTransition.current
    if (context == null) { Box(modifier) { content() }; return }
    var height by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    Box(modifier.heightIn(min = height)) {
        context.transition.AnimatedVisibility(visible = { !it || context.selectedId != id }, enter = EnterTransition.None, exit = ExitTransition.None) {
            val visibility = this
            with(context.shared) {
                Box(Modifier.onSizeChanged { height = with(density) { it.height.toDp() } }.sharedBounds(rememberSharedContentState("course-$id"), visibility, enter = fadeIn(tween(Motion.ContainerCollapseMs)), exit = fadeOut(tween(Motion.ContainerExpandMs)), boundsTransform = { _, _ -> tween(if (context.transition.targetState) Motion.ContainerExpandMs else Motion.ContainerCollapseMs, easing = Motion.EmphasizedDecelerate) }, placeholderSize = SharedTransitionScope.PlaceholderSize.ContentSize, zIndexInOverlay = 2f)) {
                    CompositionLocalProvider(LocalCourseVisibility provides visibility) { content() }
                }
            }
        }
    }
}
@Composable fun Modifier.courseTitle(id: Long): Modifier {
    val context = LocalCourseTransition.current ?: return this
    val visibility = LocalCourseVisibility.current ?: return this
    return with(context.shared) { sharedBounds(rememberSharedContentState("title-$id"), visibility, enter = fadeIn(tween(Motion.ContainerCollapseMs)), exit = fadeOut(tween(Motion.ContainerExpandMs)), boundsTransform = { _, _ -> tween(if (context.transition.targetState) Motion.ContainerExpandMs else Motion.ContainerCollapseMs, easing = Motion.EmphasizedDecelerate) }, zIndexInOverlay = 3f) }
}
fun smoothStep(start: Float, end: Float, value: Float): Float { val t = ((value - start) / (end - start)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
@Preview @Composable private fun DetailLight() { 课程表Theme(false) { CourseTransitionHost(null, {}, {}, {}) { CourseSource(1) { AppCard { Text("高等数学") } } } } }
@Preview @Composable private fun DetailDark() { 课程表Theme(true) { CourseTransitionHost(null, {}, {}, {}) { CourseSource(1) { AppCard { Text("高等数学") } } } } }
