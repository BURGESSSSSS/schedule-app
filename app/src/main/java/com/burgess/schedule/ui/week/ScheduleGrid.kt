package com.burgess.schedule.ui.week

import java.util.Calendar
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.burgess.schedule.BuildConfig
import com.burgess.schedule.Ink
import com.burgess.schedule.MutedInk
import com.burgess.schedule.cloneCalendar
import com.burgess.schedule.currentDayIndex
import com.burgess.schedule.mondayOfCurrentWeek
import com.burgess.schedule.ui.components.*
import com.burgess.schedule.ui.detail.*
import com.burgess.schedule.ui.model.*
import com.burgess.schedule.ui.theme.*
import kotlinx.coroutines.launch

@Composable
internal fun ScheduleGrid(today: Calendar, courses: List<Course>, focus: FocusCourse?, selectedWeekOffset: Int, editMode: Boolean, rowHeight: Dp, onAddCourse: (Int, Int) -> Unit, onEditCourse: (Course) -> Unit, onCourseClick: (Course) -> Unit, detailController: CourseDetailController? = null) {
    val hasWeekend = courses.any { it.dayIndex >= 5 }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val fontScale = LocalConfiguration.current.fontScale.coerceAtLeast(1f)
    val height = rowHeight * fontScale
    val clusters = remember(courses) { (0..6).associateWith { day -> weekClusters(courses.filter { it.dayIndex == day }.map { WeekBlockCourse(it.id, it.period, it.endPeriod) }) } }
    val byId = remember(courses) { courses.associateBy { it.id } }
    val blockCounts = remember(courses) { courses.groupingBy { it.dayIndex to it.period / 2 }.eachCount() }
    fun blockHeight(block: Int): Dp = height * (0..6).maxOf { blockCounts[it to block] ?: 1 }
    val weekStart = mondayOfCurrentWeek(today).apply { add(Calendar.WEEK_OF_YEAR, selectedWeekOffset) }
    LaunchedEffect(selectedWeekOffset, hasWeekend, scroll.maxValue) {
        if (scroll.maxValue != Int.MAX_VALUE) scroll.scrollTo(if (hasWeekend && selectedWeekOffset == 0 && currentDayIndex(today) >= 5) scroll.maxValue else 0)
    }
    LaunchedEffect(scroll.isScrollInProgress) {
        if (!scroll.isScrollInProgress && scroll.maxValue > 0 && scroll.maxValue != Int.MAX_VALUE) {
            val target = if (scroll.value > scroll.maxValue / 2) scroll.maxValue else 0
            if (scroll.value != target) scroll.animateScrollTo(target, tween(Motion.TabCrossfadeMs))
        }
    }
    if (hasWeekend) AppChip(if (scroll.value > scroll.maxValue / 2) "‹ 工作日" else "周末 ›") { scope.launch { scroll.animateScrollTo(if (scroll.value > scroll.maxValue / 2) 0 else scroll.maxValue, tween(Motion.TabCrossfadeMs)) } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = (maxWidth - AppSpace.axis - AppSpace.gridGap * 5) / 5
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gridGap)) {
            Column(Modifier.width(AppSpace.axis), verticalArrangement = Arrangement.spacedBy(AppSpace.gridGap)) {
                Spacer(Modifier.height(AppSpace.row))
                periods.indices.step(2).forEach { period ->
                    Box(Modifier.height(blockHeight(period / 2))) { FittedWeekText("%02d:%02d".format(periods[period].startMinutes / 60, periods[period].startMinutes % 60), AppType.micro, AppSpace.axis, MutedInk) }
                }
            }
            Row(Modifier.horizontalScroll(scroll, enabled = hasWeekend), horizontalArrangement = Arrangement.spacedBy(AppSpace.gridGap)) {
                (0 until if (hasWeekend) 7 else 5).forEach { day ->
                    val date = weekStart.cloneCalendar().apply { add(Calendar.DAY_OF_YEAR, day) }
                    val isToday = selectedWeekOffset == 0 && currentDayIndex(today) == day
                    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(AppSpace.gridGap)) {
                        Column(Modifier.height(AppSpace.row).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("周${weekDayLabels[day]}", style = AppType.caption, color = MutedInk)
                            Box(Modifier.size(AppSpace.date).background(if (isToday) Ink else Color.Transparent, AppShape.pill), contentAlignment = Alignment.Center) { Text(date.get(Calendar.DAY_OF_MONTH).toString(), style = AppType.caption, color = if (isToday) LocalAppColors.current.onInk else Ink) }
                        }
                        var period = 0
                        while (period < periods.size) {
                            val current = period
                            val cluster = clusters[day]?.firstOrNull { it.firstBlock == current / 2 }
                            val lastBlock = cluster?.lastBlock ?: current / 2
                            val blocks = current / 2..lastBlock
                            val slotHeight = blocks.fold(0.dp) { sum, block -> sum + blockHeight(block) } + AppSpace.gridGap * (blocks.count() - 1)
                            val slotCourses = cluster?.ids?.mapNotNull { byId[it] }.orEmpty()
                            if (slotCourses.isEmpty()) {
                                CourseSlot(null, false, false, editMode, width, slotHeight, detailController = detailController) { if (editMode) onAddCourse(day, current) }
                            } else {
                                Column(Modifier.width(width).height(slotHeight), verticalArrangement = Arrangement.spacedBy(AppSpace.gridGap)) {
                                    val courseHeight = (slotHeight - AppSpace.gridGap * (slotCourses.size - 1)) / slotCourses.size
                                    slotCourses.forEach { course -> key(course.id) {
                                        CourseSlot(course, focus?.course?.id == course.id, focus?.isInProgress == true && focus.course.id == course.id, editMode, width, courseHeight, showPeriodRange = slotCourses.size > 1, detailController = detailController) {
                                            if (editMode) onEditCourse(course) else onCourseClick(course)
                                        }
                                    } }
                                }
                            }
                            period = (lastBlock + 1) * 2
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseSlot(course: Course?, isFocus: Boolean, isInProgress: Boolean, editMode: Boolean, width: Dp, height: Dp, showPeriodRange: Boolean = false, detailController: CourseDetailController? = null, onClick: () -> Unit) {
    val c = LocalAppColors.current
    val tint = course?.let { courseTint(courseAccentFor(it)) }
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction, Motion.PressScale)
    val description = course?.let { "${it.name}，周${weekDayLabels[it.dayIndex]}，${periodTimeRange(it)}，${it.room.ifBlank { "未填写地点" }}" }
        ?: if (editMode) "添加课程" else "空闲时段"

    @Composable
    fun SlotBody(
        foregroundOverride: Color?,
        clickable: Boolean,
        titleRenderer: (@Composable (String, TextStyle, Color, Modifier, Int, Boolean, TextOverflow) -> Unit)? = null,
        exposeSemantics: Boolean = clickable
    ) {
        val foreground = foregroundOverride ?: tint?.second ?: c.mut
        Column(
            Modifier
                .fillMaxSize()
                .then(if (clickable) Modifier.clickable(interaction, null, enabled = course != null || editMode, onClick = onClick) else Modifier)
                .then(if (exposeSemantics) Modifier.semantics { contentDescription = description } else Modifier)
                .padding(horizontal = AppSpace.cellHorizontal, vertical = AppSpace.cellVertical),
            verticalArrangement = Arrangement.spacedBy(AppSpace.gridGap)
        ) {
            if (course != null) {
                val label = course.shortName?.takeIf { it.isNotBlank() } ?: course.name
                if (titleRenderer == null) {
                    Text(
                        text = label,
                        style = AppType.cell,
                        color = foreground,
                        maxLines = 2,
                        softWrap = true,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    titleRenderer.invoke(label, AppType.cell, foreground, Modifier, 2, true, TextOverflow.Ellipsis)
                }
                if (showPeriodRange) FittedWeekText(compactPeriodRangeLabel(course), AppType.micro, width - AppSpace.cellHorizontal * 2, foreground)
                Text(
                    text = weekLocation(course.room).ifBlank { "未填写" },
                    style = AppType.micro,
                    color = foreground,
                    maxLines = 2,
                    softWrap = true,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (editMode) {
                Text("+", style = AppType.cardTitle, color = c.mut)
            }
        }
    }

    if (course == null || detailController == null || editMode) {
        Box(
            Modifier
                .width(width)
                .height(height)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(AppShape.cell)
                .background(tint?.first ?: c.ink.copy(alpha = .035f))
                .then(if (isInProgress) Modifier.border(AppSpace.line * 1.5f, c.ink, AppShape.cell) else Modifier)
        ) { SlotBody(null, clickable = true) }
    } else {
        val sourceKey = "week-${course.weekNumber}-${course.id}"
        CourseDetailSource(
            controller = detailController,
            instanceKey = sourceKey,
            details = CourseDetailData(
                id = course.id,
                title = course.name,
                periods = "周${weekDayLabels[course.dayIndex]} · ${periodRangeLabel(course)}",
                time = periodTimeRange(course),
                room = course.room,
                hue = courseAccentFor(course)
            ),
            style = CourseDetailSourceStyle(
                fill = tint!!.first,
                cornerRadius = AppSpace.cell,
                foreground = tint.second,
                outline = if (isInProgress) c.ink else Color.Transparent,
                outlineWidth = if (isInProgress) AppSpace.line * 1.5f else 0.dp
            ),
            modifier = Modifier
                .width(width)
                .height(height)
                .semantics { contentDescription = description },
            onOpen = onClick
        ) {
            SlotBody(
                foreground,
                clickable = false,
                titleRenderer = { text, style, color, modifier, maxLines, softWrap, overflow ->
                    Title(text, style, color, modifier, maxLines, softWrap, overflow)
                },
                exposeSemantics = false
            )
        }
    }
}

@Composable
private fun FittedWeekText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    width: Dp,
    color: Color,
    modifier: Modifier = Modifier,
    renderer: (@Composable (String, androidx.compose.ui.text.TextStyle, Color, Modifier) -> Unit)? = null
) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val available = with(density) { width.toPx() }
    val fitted = remember(text, style, available, density.fontScale) {
        val natural = measurer.measure(text, style, softWrap = false).size.width.toFloat().coerceAtLeast(1f)
        val scale = (available / natural * .97f).coerceIn(8f / style.fontSize.value, 1f)
        style.copy(fontSize = style.fontSize * scale)
    }
    if (renderer == null) {
        Text(text, modifier, style = fitted, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    } else {
        renderer(text, fitted, color, modifier)
    }
}

@Preview(widthDp = 360) @Composable private fun WeekLightPreview() { 课程表Theme(false) { ScheduleGrid(Calendar.getInstance(), emptyList(), null, 0, false, AppSpace.gridRow, { _, _ -> }, {}, {}) } }
@Preview(widthDp = 360, fontScale = 1.3f) @Composable private fun WeekDarkPreview() { 课程表Theme(true) { ScheduleGrid(Calendar.getInstance(), emptyList(), null, 0, true, AppSpace.gridRow, { _, _ -> }, {}, {}) } }

@Composable internal fun WeekGridSandbox() {
    if (!BuildConfig.DEBUG) return
    val today = remember { mondayOfCurrentWeek(Calendar.getInstance()) }
    var offset by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    val samples = remember { listOf(
        Course(-101, "Node.js应用开发", "", "第二教学楼 E209", 0, 0, CoursePalette[0], endPeriod = 3),
        Course(-102, "高等数学（下）", "", "AB-302", 0, 2, CoursePalette[1], endPeriod = 3),
        Course(-103, "区块链基础原理", "", "实训中心E区[数智基地32-实训中心E303]", 2, 0, CoursePalette[2], endPeriod = 1),
        Course(-104, "IntroductionToComputing", "", "C208", 4, 2, CoursePalette[3], endPeriod = 3),
        Course(-105, "交互设计", "", "实训楼 E209", 5, 0, CoursePalette[4], endPeriod = 1),
        Course(-106, "长课示例", "", "A101", 6, 0, CoursePalette[0], endPeriod = 5),
        Course(-107, "嵌套课示例", "", "B202", 6, 2, CoursePalette[1], endPeriod = 3)
    ) }
    val detailController = rememberCourseDetailController()
    CourseDetailHost(controller = detailController, onAction = {}) {
        WeekScreen(today, today.cloneCalendar().apply { add(Calendar.WEEK_OF_YEAR, offset) }, offset, samples,
            null, {}, {}, { offset-- }, { offset = 0 }, { offset++ }, editing, { editing = !editing }, { _, _, _ -> }, {}, { _, _ -> }, {}, detailController)
    }
}
