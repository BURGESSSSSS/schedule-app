package com.burgess.schedule.ui.today

@androidx.compose.runtime.Immutable
data class TimedLesson(val id: Long, val start: Long, val end: Long)
enum class DayState { EMPTY, FREE, ACTIVE, UPCOMING, FINISHED }
data class TimelineState(val state: DayState, val today: List<TimedLesson>, val focus: TimedLesson?, val next: TimedLesson?)
fun timelineState(lessons: List<TimedLesson>, now: Long, dayStart: Long, nextDayStart: Long): TimelineState {
    val sorted = lessons.sortedWith(compareBy<TimedLesson> { it.start }.thenBy { it.id })
    val today = sorted.filter { it.start >= dayStart && it.start < nextDayStart }
    val active = today.firstOrNull { now >= it.start && now < it.end }
    val upcoming = today.firstOrNull { it.start > now }
    val next = sorted.firstOrNull { it.start >= nextDayStart && it.start > now }
    val state = when { sorted.isEmpty() -> DayState.EMPTY; today.isEmpty() -> DayState.FREE; active != null -> DayState.ACTIVE; upcoming != null -> DayState.UPCOMING; else -> DayState.FINISHED }
    return TimelineState(state, today, active ?: upcoming, next)
}
fun countdown(milliseconds: Long, remaining: Boolean = false): String {
    val minutes = ((milliseconds.coerceAtLeast(0) + 59_999) / 60_000)
    val prefix = if (remaining) "还剩" else "还有"
    return when {
        minutes < 60 -> "$prefix $minutes 分钟"
        minutes < 1440 -> "$prefix ${minutes / 60} 小时 ${minutes % 60} 分"
        else -> "$prefix ${minutes / 1440} 天"
    }
}

/** The highlighted next lesson must never be repeated in the preview below it. */
fun remainingPreviewLessons(lessons: List<TimedLesson>, next: TimedLesson, nextDayEnd: Long) =
    lessons.filter { it.start >= next.start && it.start < nextDayEnd && it.id != next.id }
        .sortedWith(compareBy<TimedLesson> { it.start }.thenBy { it.id })

fun lessonProgress(lesson: TimedLesson, now: Long): Float =
    if (lesson.end <= lesson.start) 0f else ((now - lesson.start).toFloat() / (lesson.end - lesson.start)).coerceIn(0f, 1f)
