package com.burgess.schedule.ui.today

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
