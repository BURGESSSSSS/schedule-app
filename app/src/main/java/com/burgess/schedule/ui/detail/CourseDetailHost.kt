package com.burgess.schedule.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.burgess.schedule.ui.morph.MorphController
import com.burgess.schedule.ui.morph.MorphDetails
import com.burgess.schedule.ui.morph.MorphExitAction
import com.burgess.schedule.ui.morph.MorphHost
import com.burgess.schedule.ui.morph.MorphSource
import com.burgess.schedule.ui.morph.MorphSourceContentScope
import com.burgess.schedule.ui.morph.MorphSourceStyle
import com.burgess.schedule.ui.morph.rememberMorphController

@Immutable
data class CourseDetailData(
    val id: Long,
    val title: String,
    val periods: String,
    val time: String,
    val room: String,
    val hue: Color,
    val targetHeight: Dp = 360.dp
)

@Immutable
data class CourseDetailSourceStyle(
    val fill: Color,
    val cornerRadius: Dp,
    val foreground: Color = Color.Unspecified,
    val outline: Color = Color.Transparent,
    val outlineWidth: Dp = 0.dp
)

enum class CourseDetailAction { Dismiss, Edit, Delete }

@Stable
class CourseDetailController internal constructor(
    internal val morphController: MorphController
)

@Composable
fun rememberCourseDetailController(): CourseDetailController {
    val morphController = rememberMorphController()
    return remember(morphController) { CourseDetailController(morphController) }
}

class CourseDetailSourceScope internal constructor(
    private val morphScope: MorphSourceContentScope,
    val foreground: Color?
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
        morphScope.Title(
            text = text,
            style = style,
            color = foreground ?: color,
            modifier = modifier,
            maxLines = maxLines,
            softWrap = softWrap,
            overflow = overflow
        )
    }
}

@Composable
fun CourseDetailHost(
    controller: CourseDetailController,
    modifier: Modifier = Modifier,
    onAction: (CourseDetailAction) -> Unit = {},
    content: @Composable () -> Unit
) {
    MorphHost(
        controller = controller.morphController,
        modifier = modifier,
        onExitFinished = { action -> onAction(action.toCourseDetailAction()) },
        content = content
    )
}

@Composable
fun CourseDetailSource(
    controller: CourseDetailController,
    instanceKey: String,
    details: CourseDetailData,
    style: CourseDetailSourceStyle,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    content: @Composable CourseDetailSourceScope.() -> Unit
) {
    MorphSource(
        controller = controller.morphController,
        instanceKey = instanceKey,
        details = details.toMorphDetails(),
        style = style.toMorphStyle(),
        modifier = modifier,
        onOpen = onOpen
    ) { foreground ->
        CourseDetailSourceScope(this, foreground).content()
    }
}

private fun CourseDetailData.toMorphDetails() = MorphDetails(
    id = id,
    title = title,
    periods = periods,
    time = time,
    room = room,
    hue = hue,
    targetHeight = targetHeight
)

private fun CourseDetailSourceStyle.toMorphStyle() = MorphSourceStyle(
    fill = fill,
    cornerRadius = cornerRadius,
    foreground = foreground,
    outline = outline,
    outlineWidth = outlineWidth
)

private fun MorphExitAction.toCourseDetailAction() = when (this) {
    MorphExitAction.Close -> CourseDetailAction.Dismiss
    MorphExitAction.Edit -> CourseDetailAction.Edit
    MorphExitAction.Delete -> CourseDetailAction.Delete
}
