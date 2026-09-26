package com.burgess.schedule.backup

import com.burgess.schedule.data.CourseEntity
import com.burgess.schedule.data.CourseSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleBackupPlannerTest {
    @Test
    fun emptyDatabasePlansAllCoursesAsInserts() {
        val plan = ScheduleBackupPlanner.create(emptyList(), listOf(course()))
        assertEquals(1, plan.toInsert.size)
        assertTrue(plan.toUpdate.isEmpty())
        assertEquals(0, plan.unchanged)
    }

    @Test
    fun sameSchoolSourceKeyUpdatesContentWithoutReplacingDatabaseId() {
        val existing = course().toEntity(72L)
        val changed = course(room = "E303", teacher = "教师乙")
        val plan = ScheduleBackupPlanner.create(listOf(existing), listOf(changed))

        assertTrue(plan.toInsert.isEmpty())
        assertEquals(1, plan.toUpdate.size)
        assertEquals(72L, plan.toUpdate.single().id)
        assertEquals("E303", plan.toUpdate.single().room)
    }

    @Test
    fun identicalSchoolSourceKeyIsDeduplicated() {
        val existing = course().toEntity(72L)
        val plan = ScheduleBackupPlanner.create(listOf(existing), listOf(course()))

        assertTrue(plan.toInsert.isEmpty())
        assertTrue(plan.toUpdate.isEmpty())
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun nonSchoolFingerprintUsesNameTeacherLocationDaySectionAndWeek() {
        val base = course(sourceType = CourseSourceType.NON_SDBI, semester = null, key = null)
        val existing = base.toEntity(6L)
        val same = base.copy(colorValue = 999, shortName = "简称")
        val plan = ScheduleBackupPlanner.create(listOf(existing), listOf(same))
        assertEquals(0, plan.toInsert.size)
        assertEquals(1, plan.toUpdate.size)

        val changedWeek = ScheduleBackupPlanner.create(listOf(existing), listOf(base.copy(weekNumber = 2)))
        val changedDay = ScheduleBackupPlanner.create(listOf(existing), listOf(base.copy(dayIndex = 1)))
        val changedSection = ScheduleBackupPlanner.create(listOf(existing), listOf(base.copy(endPeriod = 2)))
        val changedRoom = ScheduleBackupPlanner.create(listOf(existing), listOf(base.copy(room = "E311")))
        val changedTeacher = ScheduleBackupPlanner.create(listOf(existing), listOf(base.copy(teacher = "教师丙")))
        listOf(changedWeek, changedDay, changedSection, changedRoom, changedTeacher).forEach {
            assertEquals(1, it.toInsert.size)
        }
    }

    @Test
    fun duplicateExistingSchoolIdentitiesAreNotSilentlyChosen() {
        val duplicateRows = listOf(course().toEntity(1L), course().toEntity(2L))
        assertThrows(IllegalStateException::class.java) {
            ScheduleBackupPlanner.create(duplicateRows, listOf(course()))
        }
    }

    private fun course(
        room: String = "E209",
        teacher: String = "教师甲",
        sourceType: String = CourseSourceType.SDBI,
        semester: String? = "2026-fall",
        key: String? = "occurrence-1"
    ) = ScheduleBackupCourse(
        name = "人工智能应用基础",
        teacher = teacher,
        room = room,
        dayIndex = 4,
        period = 4,
        endPeriod = 5,
        weekNumber = 9,
        colorValue = 123,
        shortName = null,
        sourceType = sourceType,
        sourceSemesterKey = semester,
        externalKey = key
    )
}
