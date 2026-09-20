package com.burgess.schedule.ui
import com.burgess.schedule.ui.today.*
import org.junit.Assert.*
import org.junit.Test
class TimelineStateTest {
    private val day = 86_400_000L
    private val lessons = listOf(TimedLesson(1, 100, 200), TimedLesson(2, 300, 400), TimedLesson(3, day + 100, day + 200))
    @Test fun activeAppearsOnlyOnce() { val s = timelineState(lessons, 150, 0, day); assertEquals(DayState.ACTIVE, s.state); assertEquals(1L, s.focus?.id); assertEquals(2, s.today.size) }
    @Test fun upcoming() { assertEquals(2L, timelineState(lessons, 200, 0, day).focus?.id) }
    @Test fun finished() { assertEquals(DayState.FINISHED, timelineState(lessons, 500, 0, day).state) }
    @Test fun freeDay() { assertEquals(DayState.FREE, timelineState(lessons.drop(2), 500, 0, day).state) }
    @Test fun acrossMidnight() { assertEquals(3L, timelineState(lessons, day + 110, day, day * 2).focus?.id) }
    @Test fun empty() { assertEquals(DayState.EMPTY, timelineState(emptyList(), 0, 0, day).state) }
    @Test fun countdownBoundaries() { assertEquals("还有 25 分钟", countdown(25 * 60_000)); assertEquals("还有 3 小时 10 分", countdown(190 * 60_000)); assertEquals("还有 2 天", countdown(day * 2)) }
}
