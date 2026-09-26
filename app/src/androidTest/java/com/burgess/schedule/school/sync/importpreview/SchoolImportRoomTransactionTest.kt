package com.burgess.schedule.school.sync.importpreview

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.burgess.schedule.data.CourseDatabase
import com.burgess.schedule.data.CourseEntity
import com.burgess.schedule.data.CourseSourceType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchoolImportRoomTransactionTest {
    private lateinit var database: CourseDatabase
    private lateinit var repository: SchoolImportRepository

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            CourseDatabase::class.java
        ).build()
        repository = SchoolImportRepository(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun firstAndRepeatedSemesterImportAreTransactionalAndIdempotent() = runBlocking {
        val local = localCourse("Node.js应用开发", room = "E209")
        database.courseDao().upsert(local)
        val input = input(
            listOf(
                schoolCourse("node", week = 1, room = "E209"),
                schoolCourse("node", week = 3, room = "E209"),
                schoolCourse("node", week = 7, room = "E209"),
                schoolCourse("one-week", week = 8, day = 2)
            )
        )

        val firstPlan = repository.prepare(input)
        assertTrue(firstPlan.canConfirm)
        assertEquals(4, firstPlan.toInsert.size)
        val firstResult = repository.import(firstPlan) as SchoolImportExecutionResult.Success
        assertEquals(4, firstResult.inserted)
        assertEquals(local, database.courseDao().getAll().first { it.id == local.id })

        val secondPlan = repository.prepare(input)
        assertEquals(0, secondPlan.toInsert.size)
        assertEquals(0, secondPlan.toUpdate.size)
        assertEquals(4, secondPlan.unchanged.size)
        val secondResult = repository.import(secondPlan) as SchoolImportExecutionResult.Success
        assertEquals(0, secondResult.inserted)
        assertEquals(0, secondResult.updated)
        assertEquals(5, database.courseDao().count())
    }

    @Test
    fun contentAndWeekSetChangeUpdateExistingRowsWithoutDuplicatingThem() = runBlocking {
        val original = listOf(
            schoolCourse("node", week = 1, teacher = "Teacher A", room = "E209"),
            schoolCourse("node", week = 3, teacher = "Teacher A", room = "E209"),
            schoolCourse("node", week = 5, teacher = "Teacher A", room = "E209")
        )
        repository.import(repository.prepare(input(original)))

        val changed = listOf(
            schoolCourse("node", week = 1, teacher = "Teacher B", room = "E303"),
            schoolCourse("node", week = 3, teacher = "Teacher B", room = "E303"),
            schoolCourse("node", week = 7, teacher = "Teacher B", room = "E303")
        )
        val plan = repository.prepare(input(changed))
        assertEquals(0, plan.toInsert.size)
        assertEquals(3, plan.toUpdate.size)
        val result = repository.import(plan) as SchoolImportExecutionResult.Success

        assertEquals(3, result.updated)
        val stored = database.courseDao().getBySourceSemester(CourseSourceType.SDBI, SEMESTER_KEY)
        assertEquals(3, stored.size)
        assertEquals(setOf(1, 3, 7), stored.map { it.weekNumber }.toSet())
        assertTrue(stored.all { it.teacher == "Teacher B" && it.room == "E303" })
    }

    @Test
    fun missingSchoolRowsAreKeptAndReportedInsteadOfDeleted() = runBlocking {
        repository.import(repository.prepare(input(listOf(schoolCourse("old", week = 2)))))
        val plan = repository.prepare(input(listOf(schoolCourse("new", week = 4))))
        assertEquals(1, plan.missingFromSchool.size)

        val result = repository.import(plan) as SchoolImportExecutionResult.Success

        assertEquals(1, result.missingFromSchool)
        val stored = database.courseDao().getBySourceSemester(CourseSourceType.SDBI, SEMESTER_KEY)
        assertEquals(2, stored.size)
        assertTrue(stored.any { it.externalKey == "old" && it.weekNumber == 2 })
        assertTrue(stored.any { it.externalKey == "new" && it.weekNumber == 4 })
    }

    @Test
    fun incompleteCoverageCannotWriteAnything() = runBlocking {
        val partial = input(
            listOf(schoolCourse("node", week = 1)),
            capturedWeeks = (1..18).toSet(),
            coverageComplete = false
        )
        val plan = repository.prepare(partial)

        assertFalse(plan.canConfirm)
        assertTrue(plan.toInsert.isEmpty())
        val result = repository.import(plan)

        assertTrue(result is SchoolImportExecutionResult.Blocked)
        assertEquals(0, database.courseDao().count())
    }

    @Test
    fun insertFailureRollsBackEarlierRowsInSameTransaction() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_second_school_insert BEFORE INSERT ON courses " +
                "WHEN NEW.name = 'FAIL_IMPORT' BEGIN SELECT RAISE(ABORT, 'forced test failure'); END"
        )
        val plan = repository.prepare(
            input(
                listOf(
                    schoolCourse("a-good", week = 1, name = "Valid course"),
                    schoolCourse("z-bad", week = 2, name = "FAIL_IMPORT")
                )
            )
        )
        assertTrue(plan.canConfirm)
        assertEquals(2, plan.toInsert.size)

        val result = repository.import(plan)

        assertTrue(result is SchoolImportExecutionResult.Failed)
        assertEquals(0, database.courseDao().count())
    }

    @Test
    fun databaseChangeAfterPlanRequiresNewConfirmation() = runBlocking {
        val input = input(listOf(schoolCourse("node", week = 1)))
        val plan = repository.prepare(input)
        database.courseDao().upsert(localCourse("changed unrelated local row"))

        val result = repository.import(plan)

        // Non-SDBI rows do not affect the plan for this school semester.
        assertTrue(result is SchoolImportExecutionResult.Success)
        assertEquals(2, database.courseDao().count())
    }

    @Test
    fun changedSchoolRowAfterPlanIsRejectedAsStale() = runBlocking {
        val input = input(listOf(schoolCourse("node", week = 1)))
        val plan = repository.prepare(input)
        val persisted = plan.toInsert.single().copy(id = 501L)
        database.courseDao().upsert(persisted)

        val result = repository.import(plan)

        assertTrue(result is SchoolImportExecutionResult.StalePlan)
        assertEquals(persisted, database.courseDao().getBySourceSemester(CourseSourceType.SDBI, SEMESTER_KEY).single())
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
        sourceSemesterKey = SEMESTER_KEY,
        courses = courses,
        conflicts = emptyList()
    )

    private fun schoolCourse(
        identity: String,
        week: Int,
        name: String = "Course $identity",
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
        sourceSemesterKey = SEMESTER_KEY,
        externalKey = identity
    )

    private fun localCourse(name: String, room: String = "E209") = CourseEntity(
        id = System.nanoTime(),
        name = name,
        teacher = "",
        room = room,
        dayIndex = 0,
        period = 0,
        endPeriod = 1,
        weekNumber = 1,
        colorValue = 0xff654321.toInt()
    )

    private companion object {
        const val SEMESTER_KEY = "semester-hash"
    }
}
