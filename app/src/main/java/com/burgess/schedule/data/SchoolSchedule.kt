package com.burgess.schedule.data

import java.util.Calendar
import java.util.TimeZone

/**
 * The school's fixed term anchor and lesson windows shared by scheduling and UI code.
 *
 * [FIRST_WEEK_MONTH] uses Calendar's zero-based month numbering, so 7 is August.
 */
data class SchoolPeriod(
    val startMinutes: Int,
    val endMinutes: Int
)

object SchoolSchedule {
    const val DEFAULT_REMINDER_LEAD_MINUTES = 20
    const val FIRST_WEEK_YEAR = 2026
    const val FIRST_WEEK_MONTH = 7
    const val FIRST_WEEK_DAY = 31
    const val PERIOD_COUNT = 8

    val periods: List<SchoolPeriod> = listOf(
        SchoolPeriod(8 * 60, 8 * 60 + 50),
        SchoolPeriod(8 * 60 + 50, 9 * 60 + 40),
        SchoolPeriod(10 * 60, 10 * 60 + 50),
        SchoolPeriod(10 * 60 + 50, 11 * 60 + 40),
        SchoolPeriod(14 * 60, 14 * 60 + 50),
        SchoolPeriod(14 * 60 + 50, 15 * 60 + 50),
        SchoolPeriod(16 * 60, 16 * 60 + 50),
        SchoolPeriod(16 * 60 + 50, 17 * 60 + 30)
    )

    val periodStartMinutes: List<Int> = periods.map { it.startMinutes }
    val periodEndMinutes: List<Int> = periods.map { it.endMinutes }

    fun firstWeekStart(timeZone: TimeZone = TimeZone.getDefault()): Calendar {
        return Calendar.getInstance(timeZone).apply {
            set(Calendar.YEAR, FIRST_WEEK_YEAR)
            set(Calendar.MONTH, FIRST_WEEK_MONTH)
            set(Calendar.DAY_OF_MONTH, FIRST_WEEK_DAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    fun periodAt(index: Int): SchoolPeriod? = periods.getOrNull(index)

    fun isValidDayIndex(index: Int): Boolean = index in 0..6
}
