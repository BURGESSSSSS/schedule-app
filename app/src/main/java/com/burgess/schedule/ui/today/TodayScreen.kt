package com.burgess.schedule.ui.today

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.burgess.schedule.Ink
import com.burgess.schedule.MutedInk
import com.burgess.schedule.PickupBriefCard
import com.burgess.schedule.cloneCalendar
import com.burgess.schedule.currentDayIndex
import com.burgess.schedule.mondayOfCurrentWeek
import com.burgess.schedule.scheduleWeekNumber
import com.burgess.schedule.data.PickupCodeEntity
import com.burgess.schedule.ui.detail.CourseDetailController
import com.burgess.schedule.ui.detail.CourseDetailData
import com.burgess.schedule.ui.detail.CourseDetailSource
import com.burgess.schedule.ui.detail.CourseDetailSourceStyle
import com.burgess.schedule.ui.model.Course
import com.burgess.schedule.ui.model.FocusCourse
import com.burgess.schedule.ui.model.courseAccentFor
import com.burgess.schedule.ui.model.periodRangeLabel
import com.burgess.schedule.ui.model.periodTimeRange
import com.burgess.schedule.ui.model.periods
import com.burgess.schedule.ui.model.weekDayLabels
import com.burgess.schedule.ui.theme.AppShape
import com.burgess.schedule.ui.theme.AppSpace
import com.burgess.schedule.ui.theme.AppType
import com.burgess.schedule.ui.theme.LocalAppColors
import com.burgess.schedule.ui.theme.Motion
import com.burgess.schedule.ui.theme.课程表Theme
import com.burgess.schedule.ui.components.AppChip
import com.burgess.schedule.ui.components.InkCard
import com.burgess.schedule.ui.components.SectionLabel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.burgess.schedule.data.SchoolSchedule

private fun Course.timedLesson(): TimedLesson {
    val date = SchoolSchedule.firstWeekStart().apply { add(Calendar.WEEK_OF_YEAR, weekNumber - 1); add(Calendar.DAY_OF_YEAR, dayIndex) }
    val start = date.cloneCalendar().apply { add(Calendar.MINUTE, periods[period].startMinutes) }.timeInMillis
    val end = date.cloneCalendar().apply { add(Calendar.MINUTE, periods[endPeriod].endMinutes) }.timeInMillis
    return TimedLesson(id, start, end)
}

private fun displayLocation(raw: String): String {
    val compact = raw
        .replace('\r', ' ')
        .replace('\n', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
    if (compact.isBlank()) return ""

    return buildString(compact.length) {
        compact.forEachIndexed { index, character ->
            val previous = compact.getOrNull(index - 1)
            val next = compact.getOrNull(index + 1)
            val isOcrGap = character == ' ' && previous != null && next != null &&
                ((previous.isLocationCjkOrDigit() && next.isLetterOrDigit()) ||
                    (next.isLocationCjkOrDigit() && previous.isLetterOrDigit()))
            if (!isOcrGap) append(character)
        }
    }.trim()
}

private fun Char.isLocationCjkOrDigit(): Boolean =
    isDigit() || this in '\u4E00'..'\u9FFF'

@Composable
internal fun TodayScreen(
    today: Calendar, courses: List<Course>, focus: FocusCourse?,
    pendingPickups: List<PickupCodeEntity>, onPickupOpen: (PickupCodeEntity) -> Unit,
    onPickupCopy: (String) -> Unit, onCourseClick: (Course) -> Unit,
    onImport: () -> Unit, onAdd: () -> Unit,
    detailController: CourseDetailController? = null
) {
    val start = today.cloneCalendar().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val tomorrow = start.cloneCalendar().apply { add(Calendar.DAY_OF_YEAR, 1) }
    val timed = remember(courses) { courses.map { it.timedLesson() } }
    val state = timelineState(timed, today.timeInMillis, start.timeInMillis, tomorrow.timeInMillis)
    val byId = remember(courses) { courses.associateBy { it.id } }
    val timelineWidth = AppSpace.timeline * LocalDensity.current.fontScale.coerceAtLeast(1f)
    val header: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("周" + weekDayLabels[currentDayIndex(today)], style = AppType.title, color = Ink)
                SectionLabel("${today.get(Calendar.MONTH) + 1}月${today.get(Calendar.DAY_OF_MONTH)}日 · 第 ${scheduleWeekNumber(mondayOfCurrentWeek(today))} 周")
            }
            AppChip(if (state.today.isEmpty()) "今天没课" else "${state.today.size} 节课")
        }
    }
    if (state.state == DayState.EMPTY) {
        Column(Modifier.fillMaxSize().padding(AppSpace.page)) {
            header()
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                    Text("还没有课程", style = AppType.sheetTitle, color = Ink)
                    SectionLabel("导入课表，或手动添加第一门课程。")
                    com.burgess.schedule.ui.components.PillButton("导入课表", onImport)
                    com.burgess.schedule.ui.components.PillButton("手动添加", onAdd, com.burgess.schedule.ui.components.PillStyle.Secondary)
                }
            }
            if (pendingPickups.isNotEmpty()) PickupBriefCard(pendingPickups, onPickupOpen, onPickupCopy)
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AppSpace.page), verticalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
        header()
        state.today.forEach { lesson ->
            key(lesson.id) {
                byId[lesson.id]?.let { course ->
                    Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gap)) {
                        Column(Modifier.width(timelineWidth)) {
                            Text(SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(lesson.start)), style = AppType.caption, fontWeight = FontWeight.Medium, color = Ink)
                            Text(SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(lesson.end)), style = AppType.caption, color = MutedInk)
                        }
                        TimelineCourseCard(
                            course, lesson, today.timeInMillis, state.focus?.id == lesson.id,
                            Modifier.weight(1f), onCourseClick, detailController = detailController,
                            sourceRole = "timeline"
                        )
                    }
                }
            }
        }
        if (state.state == DayState.FINISHED) SectionLabel("今天的课都上完了")
        if (state.state == DayState.FREE || state.state == DayState.FINISHED) {
            state.next?.let { next ->
                val nextDate = Calendar.getInstance().apply { timeInMillis = next.start }
                val dayLabel = if (next.start < tomorrow.cloneCalendar().apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis) "明天" else "${nextDate.get(Calendar.MONTH) + 1}月${nextDate.get(Calendar.DAY_OF_MONTH)}日"
                if (state.state == DayState.FINISHED) SectionLabel(dayLabel)
                byId[next.id]?.let { course ->
                    TimelineCourseCard(
                        course, next, today.timeInMillis, true, Modifier.fillMaxWidth(), onCourseClick,
                        "$dayLabel ${SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(next.start))}", true,
                        detailController, "next"
                    )
                }
                val nextDayEnd = nextDate.cloneCalendar().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
                val rest = remainingPreviewLessons(timed, next, nextDayEnd)
                if (rest.isNotEmpty()) SectionLabel("${dayLabel}还有 ${rest.size} 节课")
                rest.forEach { lesson -> byId[lesson.id]?.let {
                    TimelineCourseCard(
                        it, lesson, today.timeInMillis, false, Modifier.fillMaxWidth(), onCourseClick,
                        detailController = detailController, sourceRole = "preview"
                    )
                } }
            } ?: if (state.state != DayState.EMPTY) SectionLabel("暂时没有后续课程，可到周视图添加或导入。") else Unit
        }
        if (pendingPickups.isNotEmpty()) PickupBriefCard(pendingPickups, onPickupOpen, onPickupCopy)
    }
}

@Composable
private fun TimelineCourseCard(
    course: Course,
    lesson: TimedLesson,
    now: Long,
    highlighted: Boolean,
    modifier: Modifier,
    onClick: (Course) -> Unit,
    dateLabel: String = "",
    large: Boolean = false,
    detailController: CourseDetailController? = null,
    sourceRole: String = "today"
) {
    val active = now >= lesson.start && now < lesson.end
    val ended = now >= lesson.end
    val c = LocalAppColors.current
    val entry = if (active && highlighted) {
        remember(course.id, lesson.id) { Animatable(0f) }
    } else {
        null
    }
    if (entry != null) {
        LaunchedEffect(course.id, lesson.id) {
            entry.animateTo(1f, tween(Motion.ProgressBarMs, easing = Motion.EmphasizedDecelerate))
        }
    }
    val body: @Composable ColumnScope.(Color?, @Composable (String, TextStyle, Color) -> Unit) -> Unit = { foregroundOverride, renderTitle ->
        TimelineCourseBody(
            course = course,
            lesson = lesson,
            now = now,
            highlighted = highlighted,
            dateLabel = dateLabel,
            large = large,
            entryProgress = { entry?.value ?: 0f },
            foregroundOverride = foregroundOverride,
            renderTitle = renderTitle
        )
    }
    if (detailController == null) {
        if (highlighted) {
            InkCard(Modifier.fillMaxWidth(), onClick = { onClick(course) }) {
                body(null) { text, style, color ->
                    Text(text, style = style, color = color)
                }
            }
        } else {
            com.burgess.schedule.ui.components.AppCard(Modifier.fillMaxWidth(), onClick = { onClick(course) }) {
                body(null) { text, style, color ->
                    Text(text, style = style, color = color)
                }
            }
        }
    } else {
        val sourceForeground = if (highlighted) c.onInk else if (ended) c.mut else c.ink
        val sourceStyle = CourseDetailSourceStyle(
            fill = if (highlighted) c.ink else c.card,
            cornerRadius = if (highlighted) AppSpace.heroRadius else AppSpace.cardRadius,
            foreground = sourceForeground,
            outline = if (highlighted) Color.Transparent else c.line,
            outlineWidth = if (highlighted) 0.dp else AppSpace.line
        )
        val sourceKey = "today-$sourceRole-${course.id}-" + SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date(lesson.start))
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
            style = sourceStyle,
            modifier = modifier,
            onOpen = { onClick(course) }
        ) {
            Column(
                Modifier.fillMaxSize().padding(if (highlighted) AppSpace.hero else AppSpace.card)
            ) {
                body(foreground) { text, style, color ->
                    Title(text, style, color)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.TimelineCourseBody(
    course: Course,
    lesson: TimedLesson,
    now: Long,
    highlighted: Boolean,
    dateLabel: String,
    large: Boolean,
    entryProgress: () -> Float,
    foregroundOverride: Color?,
    renderTitle: @Composable (String, TextStyle, Color) -> Unit
) {
    val active = now >= lesson.start && now < lesson.end
    val ended = now >= lesson.end
    val c = LocalAppColors.current
    val foreground = foregroundOverride ?: if (highlighted) c.onInk else if (ended) c.mut else c.ink
    val metadata = foregroundOverride?.copy(alpha = .78f) ?: if (highlighted) c.onInk else c.mut
    if (highlighted) {
        Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gridGap), verticalAlignment = Alignment.CenterVertically) {
            if (active) Box(Modifier.size(AppSpace.cellVertical).background(c.live, AppShape.pill))
            Text((if (active) "进行中" else "下一节") + " · " +
                if (dateLabel.isEmpty()) countdown(if (active) lesson.end - now else lesson.start - now, active) else dateLabel,
                style = AppType.caption, color = foreground)
        }
        if (dateLabel.isNotEmpty()) {
            Text(countdown(lesson.start - now), style = AppType.caption, color = foreground)
        }
        Spacer(Modifier.height(AppSpace.gap))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpace.gridGap), verticalAlignment = Alignment.CenterVertically) {
        if (!highlighted) Box(Modifier.size(AppSpace.cellVertical).background(courseAccentFor(course).copy(alpha = if (ended) .5f else 1f), AppShape.pill))
        renderTitle(course.name, if (large) AppType.sheetTitle else AppType.cardTitle, foreground)
    }
    Text(periodRangeLabel(course) + " · " + displayLocation(course.room).ifBlank { "未填写" }, style = AppType.caption, color = metadata)
    if (active && highlighted) {
        Spacer(Modifier.height(AppSpace.gap))
        LinearProgressIndicator(
            progress = { lessonProgress(lesson, now) * entryProgress() },
            modifier = Modifier.fillMaxWidth().height(AppSpace.progress), color = foreground, trackColor = foreground.copy(alpha = .15f)
        )
    }
}

@Preview @Composable private fun TodayLightPreview() { 课程表Theme(false) { TodayScreen(Calendar.getInstance(), emptyList(), null, emptyList(), {}, {}, {}, {}, {}) } }
@Preview @Composable private fun TodayDarkPreview() { 课程表Theme(true) { TodayScreen(Calendar.getInstance(), emptyList(), null, emptyList(), {}, {}, {}, {}, {}) } }
