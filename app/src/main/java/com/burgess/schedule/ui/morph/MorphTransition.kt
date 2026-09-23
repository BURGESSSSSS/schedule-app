package com.burgess.schedule.ui.morph

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import com.burgess.schedule.ui.components.PillButton
import com.burgess.schedule.ui.components.PillStyle
import com.burgess.schedule.ui.components.pressAction
import com.burgess.schedule.ui.theme.AppColors
import com.burgess.schedule.ui.theme.AppShape
import com.burgess.schedule.ui.theme.AppSpace
import com.burgess.schedule.ui.theme.AppType
import com.burgess.schedule.ui.theme.LocalAppColors
import com.burgess.schedule.ui.theme.contrast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun MorphHost(
    controller: MorphController,
    modifier: Modifier = Modifier,
    onExitFinished: (MorphExitAction) -> Unit = {},
    content: @Composable () -> Unit
) {
    val colors = LocalAppColors.current
    val density = LocalDensity.current
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    controller.setExitListener(onExitFinished)
    val session = controller.session
    Box(modifier.fillMaxSize().onGloballyPositioned {
        controller.setHostOrigin(it.positionInRoot())
        rootSize = it.size
    }) {
        content()
        if (session != null && rootSize.width > 0 && rootSize.height > 0 && controller.sourceRenderer != null) {
            val navInset = androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(density)
            val side = with(density) { 10.dp.toPx() }
            val bottomGap = with(density) { 10.dp.toPx() }
            val availableHeight = (rootSize.height.toFloat() - navInset.toFloat() - bottomGap).coerceAtLeast(1f)
            val targetHeight = with(density) { session.details.targetHeight.toPx() }
                .coerceAtMost(availableHeight)
                .coerceAtLeast(1f)
            val target = Rect(
                side,
                rootSize.height - navInset - bottomGap - targetHeight,
                rootSize.width - side,
                rootSize.height - navInset - bottomGap
            )
            Box(
                Modifier.fillMaxSize().zIndex(20f).pointerInput(session.source.instanceKey) {
                    detectTapGestures { position ->
                        val current = MorphMath.interpolateRect(session.source.bounds, target, controller.progress.value)
                        if (!current.contains(position)) controller.requestDismiss()
                    }
                }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(colors.scrim.copy(alpha = colors.scrim.alpha * controller.progress.value))
                }
                key(session.details.id) {
                    MorphOverlayLayout(controller, session, target, colors)
                }
            }
        }
        PredictiveBackHandler(enabled = session != null) { progressFlow ->
            if (!controller.beginBackGesture()) return@PredictiveBackHandler
            try {
                progressFlow.collect { event -> controller.updateBackGesture(event.progress) }
                controller.commitBackGesture()
            } catch (cancelled: CancellationException) {
                controller.cancelBackGesture()
                throw cancelled
            }
        }
    }
}

@Composable
private fun MorphOverlayLayout(
    controller: MorphController,
    session: MorphSession,
    target: Rect,
    colors: AppColors
) {
    val sourceRenderer = controller.sourceRenderer ?: return
    val density = LocalDensity.current
    val detailScroll = rememberScrollState()
    val targetWidth = target.width.roundToInt().coerceAtLeast(1)
    val targetHeight = target.height.roundToInt().coerceAtLeast(1)
    val sourceWidth = session.source.contentSize.width.coerceAtLeast(1)
    val sourceHeight = session.source.contentSize.height.coerceAtLeast(1)
    Layout(
        content = {
            Box(
                Modifier.fillMaxSize().clearAndSetSemantics { hideFromAccessibility() }.graphicsLayer {
                    val p = controller.progress.value
                    val current = MorphMath.interpolateRect(session.source.bounds, target, p)
                    val fill = lerp(session.source.style.fill, colors.card, MorphMath.geometryProgress(p))
                    val useFallback = MorphMath.useSourceInkFallback(fill, session.source.style.foreground, colors.ink)
                    translationX = current.left - session.source.bounds.left
                    translationY = current.top - session.source.bounds.top
                    alpha = MorphMath.sourceBodyAlpha(p) * if (useFallback) 0f else 1f
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            ) {
                CompositionLocalProvider(
                    LocalMorphRenderPass provides MorphRenderPass.Overlay,
                    LocalMorphTitleSnapshot provides session.title
                ) {
                    sourceRenderer.invoke(MorphSourceContentScope(controller, session.source.instanceKey), null)
                }
            }
            Box(
                Modifier.fillMaxSize().clearAndSetSemantics { hideFromAccessibility() }.graphicsLayer {
                    val p = controller.progress.value
                    val current = MorphMath.interpolateRect(session.source.bounds, target, p)
                    val fill = lerp(session.source.style.fill, colors.card, MorphMath.geometryProgress(p))
                    val useFallback = MorphMath.useSourceInkFallback(fill, session.source.style.foreground, colors.ink)
                    translationX = current.left - session.source.bounds.left
                    translationY = current.top - session.source.bounds.top
                    alpha = MorphMath.sourceBodyAlpha(p) * if (useFallback) 1f else 0f
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            ) {
                CompositionLocalProvider(
                    LocalMorphRenderPass provides MorphRenderPass.Overlay,
                    LocalMorphTitleSnapshot provides session.title
                ) {
                    sourceRenderer.invoke(MorphSourceContentScope(controller, session.source.instanceKey), colors.ink)
                }
            }
            Box(
                Modifier.fillMaxSize().then(
                    if (controller.detailsInteractive) Modifier else Modifier.clearAndSetSemantics { hideFromAccessibility() }
                ).verticalScroll(detailScroll).graphicsLayer {
                    val p = controller.progress.value
                    val current = MorphMath.interpolateRect(session.source.bounds, target, p)
                    val fill = lerp(session.source.style.fill, colors.card, MorphMath.geometryProgress(p))
                    val useOnInk = contrast(fill, colors.onInk) >= contrast(fill, colors.ink)
                    translationX = current.left - target.left
                    translationY = current.top - target.top
                    alpha = MorphMath.detailBodyAlpha(p)
                    transformOrigin = TransformOrigin(0f, 0f)
                }
            ) {
                Box(Modifier.fillMaxWidth().wrapContentHeight()) {
                    Box(Modifier.fillMaxWidth().clearAndSetSemantics { hideFromAccessibility() }.graphicsLayer {
                        val p = controller.progress.value
                        val fill = lerp(session.source.style.fill, colors.card, MorphMath.geometryProgress(p))
                        val useOnInk = contrast(fill, colors.onInk) >= contrast(fill, colors.ink)
                        alpha = if (useOnInk) 1f else 0f
                    }) { MorphDetailContent(session.details, colors, colors.onInk) }
                    Box(Modifier.fillMaxWidth().clearAndSetSemantics { hideFromAccessibility() }.graphicsLayer {
                        val p = controller.progress.value
                        val fill = lerp(session.source.style.fill, colors.card, MorphMath.geometryProgress(p))
                        val useOnInk = contrast(fill, colors.onInk) >= contrast(fill, colors.ink)
                        alpha = if (useOnInk) 0f else 1f
                    }) { MorphDetailContent(session.details, colors, colors.ink) }
                    Box(Modifier.fillMaxWidth()) { MorphDetailActionFlow(controller, session.details, colors) }
                }
            }
            Box(Modifier.fillMaxSize().clearAndSetSemantics { hideFromAccessibility() }) {
                MorphTitleLayer(controller, session, target, detailScroll, colors, targetWidth)
            }
        },
        modifier = Modifier.fillMaxSize().drawBehind {
            val p = controller.progress.value
            val current = MorphMath.interpolateRect(session.source.bounds, target, p)
            val radius = MorphMath.interpolate(session.source.style.cornerRadius.toPx(), 28.dp.toPx(), p)
            // Keep the source color long enough for white-on-black text to fade
            // before the light target background arrives. This avoids a black
            // card becoming a low-contrast empty shape at the hand-off.
            val fillT = MorphMath.geometryProgress(p)
            val fill = lerp(session.source.style.fill, colors.card, fillT)
            drawRoundRect(fill, current.topLeft, current.size, CornerRadius(radius, radius))
            if (session.source.style.outlineWidth > 0.dp) {
                drawRoundRect(
                    lerp(session.source.style.outline, colors.card, p),
                    current.topLeft,
                    current.size,
                    CornerRadius(radius, radius),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(session.source.style.outlineWidth.toPx())
                )
            }
        }.drawWithContent {
            val contentScope = this
            val p = controller.progress.value
            val current = MorphMath.interpolateRect(session.source.bounds, target, p)
            val radius = MorphMath.interpolate(session.source.style.cornerRadius.toPx(), 28.dp.toPx(), p)
            val path = Path().apply {
                addRoundRect(RoundRect(current, CornerRadius(radius, radius)))
            }
            withTransform({ clipPath(path) }) { contentScope.drawContent() }
        }
    ) { measurables, constraints ->
        val source = measurables[0].measure(Constraints.fixed(sourceWidth, sourceHeight))
        val sourceInk = measurables[1].measure(Constraints.fixed(sourceWidth, sourceHeight))
        val detailGroup = measurables[2].measure(Constraints.fixed(targetWidth, targetHeight))
        val titleLayer = measurables[3].measure(Constraints.fixed(targetWidth, targetHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            // Both content layers keep their measured dimensions. The moving
            // surface below clips them; no per-axis text stretching occurs.
            source.place(session.source.bounds.left.roundToInt(), session.source.bounds.top.roundToInt())
            sourceInk.place(session.source.bounds.left.roundToInt(), session.source.bounds.top.roundToInt())
            detailGroup.place(target.left.roundToInt(), target.top.roundToInt())
            titleLayer.place(target.left.roundToInt(), target.top.roundToInt())
        }
    }
}

@Composable
private fun MorphTitleLayer(
    controller: MorphController,
    session: MorphSession,
    target: Rect,
    detailScroll: androidx.compose.foundation.ScrollState,
    colors: AppColors,
    targetWidth: Int
) {
    val density = LocalDensity.current
    val sourceTitle = session.title
    val targetTitleFont = with(density) { AppType.sheetTitle.fontSize.toPx() }
    val sourceTitleFont = sourceTitle.fontSizePx.coerceAtLeast(1f)
    val targetTitleLeft = with(density) { AppSpace.hero.toPx() }
    val targetTitleTop = with(density) { (AppSpace.hero + 4.dp + AppSpace.card).toPx() }
    val targetContentWidthPx = (targetWidth - with(density) { (AppSpace.hero * 2).roundToPx() }).coerceAtLeast(1)
    val targetContentWidthDp = with(density) { targetContentWidthPx.toDp() }
    val sourceWidthDp = with(density) { sourceTitle.bounds.width.coerceAtLeast(1f).toDp() }
    val sourceLocalX = sourceTitle.bounds.left
    val sourceLocalY = sourceTitle.bounds.top
    var targetBaseline by remember(session.details.id) { mutableFloatStateOf(0f) }
    val firstLineEnd = sourceTitle.firstLineEnd.coerceIn(0, sourceTitle.text.length)
    val sourceFirstLineText = if (firstLineEnd > 0) sourceTitle.text.substring(0, firstLineEnd) else sourceTitle.text
    val sourceOriginalColor = if (session.source.style.foreground == Color.Unspecified) colors.ink else session.source.style.foreground

    fun titleModifier(
        sourceFace: Boolean,
        scaleFrom: Float,
        scaleTo: Float,
        baseline: Float,
        primaryColor: Boolean
    ): Modifier = Modifier.graphicsLayer {
        val p = controller.progress.value
        val titleGeometry = MorphMath.titleGeometryProgress(p)
        val current = MorphMath.interpolateRect(session.source.bounds, target, p)
        val targetYWithScroll = targetTitleTop + targetBaseline - detailScroll.value.toFloat()
        val sourceBaseline = sourceLocalY + sourceTitle.firstBaseline
        val targetBaselineLocal = targetYWithScroll
        val desiredBaseline = sourceBaseline + (targetBaselineLocal - sourceBaseline) * titleGeometry
        val scale = scaleFrom + (scaleTo - scaleFrom) * titleGeometry
        val localX = sourceLocalX + (targetTitleLeft - sourceLocalX) * titleGeometry
        translationX = current.left + localX - target.left
        translationY = current.top + desiredBaseline - baseline * scale - target.top
        scaleX = scale
        scaleY = scale
        val fill = lerp(session.source.style.fill, colors.card, MorphMath.geometryProgress(p))
        val useFallback = MorphMath.useSourceInkFallback(fill, session.source.style.foreground, colors.ink)
        val useOnInk = contrast(fill, colors.onInk) >= contrast(fill, colors.ink)
        val alpha = if (sourceFace) {
            MorphMath.sourceFaceAlpha(p) * if (primaryColor != useFallback) 1f else 0f
        } else {
            MorphMath.detailAlpha(p) * if (primaryColor == useOnInk) 1f else 0f
        }
        this.alpha = alpha
        transformOrigin = TransformOrigin(0f, 0f)
    }
    Box(Modifier.fillMaxSize()) {
        Text(
            text = sourceFirstLineText,
            modifier = Modifier.width(sourceWidthDp).then(
                titleModifier(true, 1f, targetTitleFont / sourceTitleFont, sourceTitle.firstBaseline, true)
            ),
            style = sourceTitle.style,
            color = sourceOriginalColor,
            maxLines = sourceTitle.maxLines
        )
        Text(
            text = sourceFirstLineText,
            modifier = Modifier.width(sourceWidthDp).then(
                titleModifier(true, 1f, targetTitleFont / sourceTitleFont, sourceTitle.firstBaseline, false)
            ),
            style = sourceTitle.style,
            color = colors.ink,
            maxLines = sourceTitle.maxLines
        )
        Text(
            text = session.details.title,
            modifier = Modifier.width(targetContentWidthDp).then(
                titleModifier(false, sourceTitleFont / targetTitleFont, 1f, targetBaseline, true)
            ),
            style = AppType.sheetTitle,
            color = colors.onInk,
            onTextLayout = { result ->
                if (targetBaseline != result.firstBaseline) targetBaseline = result.firstBaseline
            }
        )
        Text(
            text = session.details.title,
            modifier = Modifier.width(targetContentWidthDp).then(
                titleModifier(false, sourceTitleFont / targetTitleFont, 1f, targetBaseline, false)
            ),
            style = AppType.sheetTitle,
            color = colors.ink,
            onTextLayout = { result ->
                if (targetBaseline != result.firstBaseline) targetBaseline = result.firstBaseline
            }
        )
    }
}

@Composable
private fun MorphDetailContent(item: MorphDetails, colors: AppColors, foreground: Color) {
    Column(
        Modifier.fillMaxWidth().padding(AppSpace.hero),
        verticalArrangement = Arrangement.spacedBy(AppSpace.card)
    ) {
        Box(Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally).width(36.dp).height(4.dp).background(colors.line, AppShape.pill))
        // The title is drawn by MorphTitleLayer. This fixed transparent Text
        // keeps the target layout height stable without per-frame remeasure.
        Text(item.title, style = AppType.sheetTitle, color = Color.Transparent)
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
            Box(Modifier.width(8.dp).height(8.dp).background(item.hue, AppShape.pill))
            Text(item.periods, style = AppType.body, color = foreground)
        }
        Text(item.time, style = AppType.caption, color = colors.mut)
        HorizontalDivider(color = colors.line, thickness = 1.dp)
        Text(item.room.ifBlank { "未填写" }, style = AppType.body, color = foreground)
        HorizontalDivider(color = colors.line, thickness = 1.dp)
        Spacer(Modifier.height(AppSpace.gap))
    }
}

@Composable
private fun MorphDetailActionFlow(controller: MorphController, item: MorphDetails, colors: AppColors) {
    val enabled = controller.detailsInteractive
    Column(
        Modifier.fillMaxWidth().padding(AppSpace.hero),
        verticalArrangement = Arrangement.spacedBy(AppSpace.card)
    ) {
        // These transparent placeholders keep the controls in the same measured
        // flow as the visual detail body at every font scale. There is no fixed
        // top offset and no second layout pass during the morph.
        Spacer(Modifier.width(36.dp).height(4.dp))
        Text(item.title, style = AppType.sheetTitle, color = Color.Transparent)
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
            Spacer(Modifier.width(8.dp).height(8.dp))
            Text(item.periods, style = AppType.body, color = Color.Transparent)
        }
        Text(item.time, style = AppType.caption, color = Color.Transparent)
        HorizontalDivider(color = Color.Transparent, thickness = 1.dp)
        Text(item.room.ifBlank { "未填写" }, style = AppType.body, color = Color.Transparent)
        HorizontalDivider(color = Color.Transparent, thickness = 1.dp)
        Spacer(Modifier.height(AppSpace.gap))
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
            PillButton("编辑", { if (enabled) controller.requestDismiss(MorphExitAction.Edit) })
            PillButton("删除", { if (enabled) controller.requestDismiss(MorphExitAction.Delete) }, PillStyle.Secondary, danger = true)
        }
    }
}
