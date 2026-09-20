package com.burgess.schedule.notifications

import com.burgess.schedule.data.SchoolSchedule
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderSchedulingTest {
    private val schoolTimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun schoolScheduleKeepsThePublishedAnchorAndTwentyMinuteDefault() {
        val firstWeek = SchoolSchedule.firstWeekStart(schoolTimeZone)

        assertEquals(2026, firstWeek.get(Calendar.YEAR))
        assertEquals(Calendar.AUGUST, firstWeek.get(Calendar.MONTH))
        assertEquals(31, firstWeek.get(Calendar.DAY_OF_MONTH))
        assertEquals(20, SchoolSchedule.DEFAULT_REMINDER_LEAD_MINUTES)
        assertEquals(8 * 60, SchoolSchedule.periodAt(0)?.startMinutes)
        assertEquals(17 * 60 + 30, SchoolSchedule.periodAt(7)?.endMinutes)
    }

    @Test
    fun recurringCourseUsesTheNextOccurrenceAfterTheLeadWindow() {
        val now = calendar(2026, Calendar.SEPTEMBER, 7, 7, 0)

        val trigger = ReminderScheduler.nextTriggerTime(
            now = now,
            dayIndex = 0,
            period = 0,
            weekNumber = 0,
            leadMinutes = SchoolSchedule.DEFAULT_REMINDER_LEAD_MINUTES
        )

        assertEquals(calendar(2026, Calendar.SEPTEMBER, 7, 7, 40).timeInMillis, trigger)
    }

    @Test
    fun courseStillAheadGetsAnImmediateCatchUpWhenTheLeadWindowHasPassed() {
        val now = calendar(2026, Calendar.SEPTEMBER, 7, 7, 50)

        val trigger = ReminderScheduler.nextTriggerTime(
            now = now,
            dayIndex = 0,
            period = 0,
            weekNumber = 0,
            leadMinutes = SchoolSchedule.DEFAULT_REMINDER_LEAD_MINUTES
        )

        assertEquals(now.timeInMillis + 1_000L, trigger)
    }

    @Test
    fun oneOffCourseStillAheadAlsoGetsTheImmediateCatchUp() {
        val now = calendar(2026, Calendar.AUGUST, 31, 7, 50)

        val trigger = ReminderScheduler.nextTriggerTime(
            now = now,
            dayIndex = 0,
            period = 0,
            weekNumber = 1,
            leadMinutes = SchoolSchedule.DEFAULT_REMINDER_LEAD_MINUTES
        )

        assertEquals(now.timeInMillis + 1_000L, trigger)
    }

    @Test
    fun courseAlreadyStartedDoesNotGetALateCatchUp() {
        val now = calendar(2026, Calendar.SEPTEMBER, 7, 8, 0)

        val trigger = ReminderScheduler.nextTriggerTime(
            now = now,
            dayIndex = 0,
            period = 0,
            weekNumber = 1,
            leadMinutes = SchoolSchedule.DEFAULT_REMINDER_LEAD_MINUTES
        )

        assertNull(trigger)
    }

    @Test
    fun recurringCatchUpAdvancesByOccurrenceAfterDelivery() {
        val now = calendar(2026, Calendar.SEPTEMBER, 7, 7, 50)
        val currentOccurrence = calendar(2026, Calendar.SEPTEMBER, 7, 8, 0).timeInMillis

        val nextTrigger = ReminderScheduler.nextRecurringTriggerTime(
            now = now,
            occurrenceAt = currentOccurrence,
            leadMinutes = SchoolSchedule.DEFAULT_REMINDER_LEAD_MINUTES
        )

        assertEquals(calendar(2026, Calendar.SEPTEMBER, 14, 7, 40).timeInMillis, nextTrigger)
    }

    @Test
    fun oneOffCourseWithAnExpiredReminderIsNotScheduled() {
        val now = calendar(2026, Calendar.SEPTEMBER, 1, 8, 0)

        val trigger = ReminderScheduler.nextTriggerTime(
            now = now,
            dayIndex = 0,
            period = 0,
            weekNumber = 1,
            leadMinutes = SchoolSchedule.DEFAULT_REMINDER_LEAD_MINUTES
        )

        assertNull(trigger)
    }

    @Test
    fun invalidScheduleCoordinatesAreRejected() {
        val now = calendar(2026, Calendar.SEPTEMBER, 7, 7, 0)

        assertNull(ReminderScheduler.nextTriggerTime(now, -1, 0, 0, 20))
        assertNull(ReminderScheduler.nextTriggerTime(now, 0, SchoolSchedule.PERIOD_COUNT, 0, 20))
        assertTrue(SchoolSchedule.isValidDayIndex(6))
    }

    private fun calendar(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): Calendar {
        return Calendar.getInstance(schoolTimeZone).apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
}
