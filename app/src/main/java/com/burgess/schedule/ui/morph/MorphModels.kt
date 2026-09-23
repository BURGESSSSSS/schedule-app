package com.burgess.schedule.ui.morph

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

@Immutable
data class MorphDetails(
    val id: Long,
    val title: String,
    val periods: String,
    val time: String,
    val room: String,
    val hue: Color,
    val targetHeight: Dp = 360.dp
)

@Immutable
data class MorphSourceStyle(
    val fill: Color,
    val cornerRadius: Dp,
    val foreground: Color = Color.Unspecified,
    val outline: Color = Color.Transparent,
    val outlineWidth: Dp = 0.dp
)

@Immutable
data class MorphSourceSpec(
    val instanceKey: String,
    val bounds: Rect,
    val style: MorphSourceStyle,
    val contentSize: IntSize
)

@Immutable
data class MorphTitleSnapshot(
    val text: String,
    val style: TextStyle,
    val bounds: Rect,
    val firstBaseline: Float,
    val fontSizePx: Float,
    val maxLines: Int,
    val firstLineHeight: Float,
    val firstLineEnd: Int,
    val lineCount: Int,
    val softWrap: Boolean = true,
    val overflow: TextOverflow = TextOverflow.Clip
)

@Immutable
data class MorphSession(
    val details: MorphDetails,
    val source: MorphSourceSpec,
    val title: MorphTitleSnapshot
)

enum class MorphExitAction { Close, Edit, Delete }

internal enum class MorphRenderPass { Normal, Overlay }

internal val LocalMorphRenderPass = compositionLocalOf { MorphRenderPass.Normal }
internal val LocalMorphTitleSnapshot = compositionLocalOf<MorphTitleSnapshot?> { null }
