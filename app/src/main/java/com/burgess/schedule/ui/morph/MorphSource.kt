package com.burgess.schedule.ui.morph

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.burgess.schedule.ui.components.pressAction

/** Content scope used by a source to identify the one title that morphs. */
class MorphSourceContentScope internal constructor(
    private val controller: MorphController,
    private val instanceKey: String
) {
    @Composable
    fun Title(
        text: String,
        style: TextStyle,
        color: Color,
        modifier: Modifier = Modifier,
        maxLines: Int = Int.MAX_VALUE,
        softWrap: Boolean = true,
        overflow: TextOverflow = TextOverflow.Clip
    ) {
        if (LocalMorphRenderPass.current == MorphRenderPass.Overlay) {
            val snapshot = LocalMorphTitleSnapshot.current
            val density = LocalDensity.current
            if (snapshot != null) {
                val width = with(density) { snapshot.bounds.width.toDp() }
                val height = with(density) { snapshot.bounds.height.toDp() }
                if (snapshot.lineCount > 1) {
                    Text(
                        text = snapshot.text,
                        modifier = Modifier
                            .width(width)
                            .height(height)
                            .drawWithContent {
                                val content = this
                                clipRect(top = snapshot.firstLineHeight) { content.drawContent() }
                            }
                            .clearAndSetSemantics { hideFromAccessibility() },
                        style = snapshot.style,
                        color = color,
                        maxLines = snapshot.maxLines,
                        softWrap = snapshot.softWrap,
                        overflow = snapshot.overflow
                    )
                } else {
                    Box(
                        Modifier
                            .width(width)
                            .height(height)
                            .clearAndSetSemantics { hideFromAccessibility() }
                    )
                }
            } else {
                Spacer(Modifier.height(1.dp))
            }
        } else {
            var bounds by remember { mutableStateOf<Rect?>(null) }
            var firstBaseline by remember { mutableFloatStateOf(0f) }
            var firstLineHeight by remember { mutableFloatStateOf(0f) }
            var firstLineEnd by remember { mutableStateOf(0) }
            var lineCount by remember { mutableStateOf(1) }
            val density = LocalDensity.current
            fun publish() {
                val currentBounds = bounds ?: return
                val fontSize = with(density) {
                    if (style.fontSize == TextUnit.Unspecified) 16.dp.toPx() else style.fontSize.toPx()
                }
                controller.recordTitle(
                    instanceKey,
                    MorphTitleSnapshot(
                        text,
                        style,
                        currentBounds,
                        firstBaseline,
                        fontSize,
                        maxLines,
                        firstLineHeight.coerceAtLeast(currentBounds.height),
                        firstLineEnd,
                        lineCount,
                        softWrap,
                        overflow
                    )
                )
            }
            Text(
                text = text,
                modifier = modifier
                    .onGloballyPositioned { coordinates ->
                        val position = coordinates.positionInRoot()
                        bounds = Rect(
                            position.x,
                            position.y,
                            position.x + coordinates.size.width,
                            position.y + coordinates.size.height
                        )
                        publish()
                    },
                style = style,
                color = color,
                maxLines = maxLines,
                softWrap = softWrap,
                overflow = overflow,
                onTextLayout = { result ->
                    firstBaseline = result.firstBaseline
                    firstLineHeight = result.getLineBottom(0)
                    firstLineEnd = result.getLineEnd(0, visibleEnd = true)
                    lineCount = result.lineCount
                    publish()
                }
            )
        }
    }
}


/**
 * Records only a source's instance key, bounds and style. The content lambda
 * is retained only for the selected session, so ordinary cells do not render
 * off-screen bitmaps or run an animation of their own.
 */
@Composable
fun MorphSource(
    controller: MorphController,
    instanceKey: String,
    details: MorphDetails,
    style: MorphSourceStyle,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    content: @Composable MorphSourceContentScope.(Color?) -> Unit
) {
    androidx.compose.runtime.DisposableEffect(instanceKey) {
        onDispose { controller.unregisterSource(instanceKey) }
    }
    Box(
        modifier
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInRoot()
                controller.recordSource(
                    instanceKey,
                    Rect(position.x, position.y, position.x + coordinates.size.width, position.y + coordinates.size.height),
                    style,
                    coordinates.size
                )
            }
            .then(if (onOpen != null) Modifier.pressAction(onClick = {
                val source = controller.recordedSource(instanceKey)
                val title = controller.recordedTitle(instanceKey)
                if (source != null && title != null && controller.open(details, source, title, content)) {
                    onOpen()
                }
            }) else Modifier)
            .then(if (controller.isSourceHidden(instanceKey)) {
                Modifier.clearAndSetSemantics { hideFromAccessibility() }
            } else Modifier)
    ) {
        // The surface is owned here so hiding the selected source removes its
        // fill/border as well as its text while keeping the outer slot sized.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = if (controller.isSourceHidden(instanceKey)) 0f else 1f
            }
        ) {
            Box(
                Modifier.fillMaxSize()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(style.cornerRadius))
                    .background(style.fill)
                    .then(if (style.outlineWidth > 0.dp) Modifier.border(style.outlineWidth, style.outline, androidx.compose.foundation.shape.RoundedCornerShape(style.cornerRadius)) else Modifier)
            ) { content(MorphSourceContentScope(controller, instanceKey), null) }
        }
    }
}
