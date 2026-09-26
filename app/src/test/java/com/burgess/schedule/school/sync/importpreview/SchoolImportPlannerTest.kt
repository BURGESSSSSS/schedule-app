package com.burgess.schedule.school.sync.importpreview

import com.burgess.schedule.data.CourseEntity
import com.burgess.schedule.data.CourseSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchoolImportPlannerTest {
    @Test
    fun firstCompleteImportInsertsEveryDiscreteWeekOccurrence() {
        val rows = listOf(
            schoolCourse("rule-a", week = 1),
            schoolCourse("rule-a", week = 3),
            schoolCourse("rule-a", week = 7),
            schoolCourse("rule-b", week = 8, day = 2)
        )

        val plan = SchoolImportPlanner.create(input(rows), emptyList())

        assertTrue(plan.canConfirm)
        assertEquals(4, plan.toInsert.size)
        assertEquals(setOf(1, 3, 7, 8), plan.toInsert.map { it.weekNumber }.toSet())
        assertEquals(0, plan.toUpdate.size)
        assertEquals(0, plan.unchanged.size)
    }

    @Test
    fun exactSecondImportIsIdempotent() {
        val rows = listOf(schoolCourse("rule-a", 1), schoolCourse("rule-a", 3), schoolCourse("rule-a", 7))
        val first = SchoolImportPlanner.create(input(rows), emptyList())
        val persisted = first.toInsert.mapIndexed { index, course -> course.copy(id = index + 10L) }

        val second = SchoolImportPlanner.create(input(rows), persisted)

        assertTrue(second.canConfirm)
        assertEquals(0, second.toInsert.size)
        assertEquals(0, second.toUpdate.size)
        assertEquals(3, second.unchanged.size)
    }

    @Test
    fun teacherRoomAndChangedWeekSetUpdateTheExistingRuleRows() {
        val oldRows = listOf(
            schoolCourse("node", 1, teacher = "Teacher A", room = "E209"),
            schoolCourse("node", 3, teacher = "Teacher A", room = "E209"),
            schoolCourse("node", 5, teacher = "Teacher A", room = "E209")
        ).mapIndexed { index, course -> course.copy(id = index + 1L) }
        val changedRows = listOf(
            schoolCourse("node", 1, teacher = "Teacher B", room = "E303"),
            schoolCourse("node", 3, teacher = "Teacher B", room = "E303"),
            schoolCourse("node", 7, teacher = "Teacher B", room = "E303")
        )

        val plan = SchoolImportPlanner.create(input(changedRows), oldRows)

        assertTrue(plan.canConfirm)
        assertEquals(0, plan.toInsert.size)
        assertEquals(3, plan.toUpdate.size)
        assertEquals(setOf(1, 3, 7), plan.toUpdate.map { it.replacement.weekNumber }.toSet())
        assertTrue(plan.toUpdate.all { it.replacement.teacher == "Teacher B" && it.replacement.room == "E303" })
    }

    @Test
    fun matchingLocalCourseIsNotConsideredAnExistingSchoolCourse() {
        val local = schoolCourse("local", 1).copy(
            sourceType = CourseSourceType.NON_SDBI,
            sourceSemesterKey = null,
            externalKey = null,
            id = 44L
        )

        val plan = SchoolImportPlanner.create(input(listOf(schoolCourse("local", 1))), listOf(local))

        assertEquals(1, plan.toInsert.size)
        assertEquals(0, plan.toUpdate.size)
        assertEquals(local, local.copy())
    }

    @Test
    fun schoolMissingRowsAreReportedButRemainOutsideWriteOperations() {
        val old = schoolCourse("removed", 2).copy(id = 99L)
        val plan = SchoolImportPlanner.create(input(listOf(schoolCourse("current", 4))), listOf(old))

        assertEquals(1, plan.missingFromSchool.size)
        assertEquals(99L, plan.missingFromSchool.single().id)
        assertFalse(plan.toInsert.any { it.id == old.id })
        assertFalse(plan.toUpdate.any { it.existing.id == old.id })
    }

    @Test
    fun eighteenOfNineteenWeeksIsBlocked() {
        val partial = input(
            courses = listOf(schoolCourse("rule-a", 1)),
            capturedWeeks = (1..18).toSet(),
            coverageComplete = false
        )

        val plan = SchoolImportPlanner.create(partial, emptyList())

        assertFalse(plan.canConfirm)
        assertEquals(SchoolImportBlockReason.INCOMPLETE_SEMESTER, plan.blockReason)
        assertTrue(plan.toInsert.isEmpty())
    }

    @Test
    fun missingCandidateIdentityBlocksWithoutThrowingOrWritingRows() {
        val plan = SchoolImportPlanner.create(
            input(listOf(schoolCourse("unused", 1).copy(externalKey = null))),
            emptyList()
        )

        assertFalse(plan.canConfirm)
        assertEquals(0, plan.toInsert.size)
        assertEquals(1, plan.conflicts.single { it.code == SchoolImportConflictCode.MISSING_STABLE_IDENTITY }.count)
    }

    @Test
    fun missingStoredIdentityIsReportedAndBlocksImport() {
        val stored = schoolCourse("unused", 1).copy(
            id = 77L,
            externalKey = null
        )

        val plan = SchoolImportPlanner.create(input(listOf(schoolCourse("current", 1))), listOf(stored))

        assertFalse(plan.canConfirm)
        assertEquals(0, plan.toUpdate.size)
        assertEquals(1, plan.toInsert.size)
        assertEquals(1, plan.conflicts.single { it.code == SchoolImportConflictCode.MISSING_STORED_IDENTITY }.count)
    }

    @Test
    fun repeatedConflictCodesKeepTheirAggregateCount() {
        val base = input(listOf(schoolCourse("rule-a", 1)))
        val plan = SchoolImportPlanner.create(
            base.copy(
                conflicts = listOf(
                    SchoolImportConflict(SchoolImportConflictCode.INVALID_COURSE_RULE, 2),
                    SchoolImportConflict(SchoolImportConflictCode.INVALID_COURSE_RULE, 3)
                )
            ),
            emptyList()
        )

        assertEquals(1, plan.conflicts.count { it.code == SchoolImportConflictCode.INVALID_COURSE_RULE })
        assertEquals(5, plan.conflicts.single { it.code == SchoolImportConflictCode.INVALID_COURSE_RULE }.count)
        assertFalse(plan.canConfirm)
    }

    @Test
    fun sameSubjectOnDifferentDaysAndSameSlotAtDifferentPlacesStayDistinct() {
        val rows = listOf(
            schoolCourse("mon", week = 1, day = 0, period = 0, room = "E209"),
            schoolCourse("wed", week = 1, day = 2, period = 0, room = "E209"),
            schoolCourse("room-a", week = 1, day = 4, period = 2, room = "E303"),
            schoolCourse("room-b", week = 1, day = 4, period = 2, room = "E304")
        )

        val plan = SchoolImportPlanner.create(input(rows), emptyList())

        assertEquals(4, plan.toInsert.size)
        assertEquals(4, plan.toInsert.map { it.externalKey }.toSet().size)
    }

    private fun input(
        courses: List<CourseEntity>,
        capturedWeeks: Set<Int> = (1..19).toSet(),
        coverageComplete: Boolean = true
    ) = SchoolImportInput(
        academicYear = "2026-2027",
        semesterFlag = "1",
        maxWeek = 19,
        capturedWeeks = capturedWeeks,
        coverageComplete = coverageComplete,
        sourceSemesterKey = "semester-hash",
        courses = courses,
        conflicts = emptyList()
    )

    private fun schoolCourse(
        identity: String,
        week: Int,
        name: String = "Course",
        teacher: String = "Teacher",
        room: String = "E209",
        day: Int = 0,
        period: Int = 0
    ) = CourseEntity(
        id = 0L,
        name = name,
        teacher = teacher,
        room = room,
        dayIndex = day,
        period = period,
        endPeriod = period + 1,
        weekNumber = week,
        colorValue = 0xff123456.toInt(),
        sourceType = CourseSourceType.SDBI,
        sourceSemesterKey = "semester-hash",
        externalKey = identity
    )
}
