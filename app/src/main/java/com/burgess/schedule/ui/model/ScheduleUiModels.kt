package com.burgess.schedule.ui.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.burgess.schedule.data.CourseEntity
import com.burgess.schedule.data.CourseSourceType
import com.burgess.schedule.data.SchoolSchedule
import com.burgess.schedule.ui.theme.CoursePalette
import java.util.Locale

@Immutable
internal data class Course(
    val id: Long,
    val name: String,
    val teacher: String,
    val room: String,
    val dayIndex: Int,
    val period: Int,
    val color: Color,
    val endPeriod: Int = period,
    val weekNumber: Int = 0,
    val shortName: String? = null,
    val sourceType: String = CourseSourceType.NON_SDBI,
    val sourceSemesterKey: String? = null,
    val externalKey: String? = null
)

internal data class FocusCourse(
    val course: Course,
    val dayIndex: Int,
    val weekOffset: Int,
    val isInProgress: Boolean
)

internal data class WeekSnapshot(
    val weekOffset: Int,
    val weekStartMillis: Long,
    val weekNumber: Int,
    val courses: List<Course>
)

internal data class Period(
    val label: String,
    val startMinutes: Int,
    val endMinutes: Int
)

internal val weekDayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
internal val scheduleFirstWeekStart = SchoolSchedule.firstWeekStart()
internal val periods = SchoolSchedule.periods.mapIndexed { index, period ->
    Period("第 ${index + 1} 节", period.startMinutes, period.endMinutes)
}

internal fun courseAccentFor(id: Long): Color =
    CoursePalette[Math.floorMod(id, CoursePalette.size.toLong()).toInt()]

internal fun stableCoursePaletteIndex(course: Course): Int =
    Math.floorMod(course.color.toArgb(), CoursePalette.size)

internal fun courseAccentFor(course: Course): Color =
    CoursePalette[stableCoursePaletteIndex(course)]

internal fun CourseEntity.toCourse(): Course {
    val safePeriod = period.coerceIn(0, periods.lastIndex)
    val safeEndPeriod = endPeriod.coerceIn(safePeriod, periods.lastIndex)
    return Course(
        id = id,
        name = name,
        teacher = teacher,
        room = room,
        dayIndex = dayIndex.coerceIn(0, 6),
        period = safePeriod,
        endPeriod = safeEndPeriod,
        weekNumber = weekNumber,
        shortName = shortName,
        sourceType = sourceType,
        sourceSemesterKey = sourceSemesterKey,
        externalKey = externalKey,
        color = colorValue.takeIf { it != 0 }?.let { Color(it) } ?: courseAccentFor(id)
    )
}

internal fun Course.toEntity(): CourseEntity {
    val safePeriod = period.coerceIn(0, periods.lastIndex)
    val safeEndPeriod = endPeriod.coerceIn(safePeriod, periods.lastIndex)
    return CourseEntity(
        id = id,
        name = name,
        teacher = teacher,
        room = room,
        dayIndex = dayIndex.coerceIn(0, 6),
        period = safePeriod,
        endPeriod = safeEndPeriod,
        weekNumber = weekNumber,
        colorValue = color.toArgb(),
        shortName = shortName,
        sourceType = sourceType,
        sourceSemesterKey = sourceSemesterKey,
        externalKey = externalKey
    )
}

internal fun courseKey(course: Course): String {
    return listOf(
        course.name.trim().lowercase(Locale.ROOT),
        course.room.trim().lowercase(Locale.ROOT),
        course.dayIndex,
        course.period,
        course.endPeriod
    ).joinToString("|")
}

internal fun periodTimeRange(course: Course): String {
    val start = periods[course.period].startMinutes
    val end = periods[course.endPeriod.coerceIn(course.period, periods.lastIndex)].endMinutes
    fun format(minutes: Int): String = String.format(Locale.CHINA, "%02d:%02d", minutes / 60, minutes % 60)
    return format(start) + " - " + format(end)
}

internal fun periodRangeLabel(course: Course): String = periodRangeLabel(course.period, course.endPeriod)

internal fun compactPeriodRangeLabel(course: Course): String {
    return if (course.endPeriod <= course.period) {
        "${course.period + 1}节"
    } else {
        "${course.period + 1}\u2013${course.endPeriod + 1}节"
    }
}

internal fun periodRangeLabel(startPeriod: Int, endPeriod: Int): String {
    return if (endPeriod <= startPeriod) {
        periods[startPeriod].label
    } else {
        "第 " + (startPeriod + 1) + "-" + (endPeriod + 1) + " 节"
    }
}
