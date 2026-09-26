package com.burgess.schedule.backup

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.burgess.schedule.data.CourseDatabase
import com.burgess.schedule.data.CourseEntity
import com.burgess.schedule.data.CourseSourceType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduleBackupRoomTest {
    private lateinit var database: CourseDatabase
    private lateinit var repository: ScheduleBackupRepository

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            CourseDatabase::class.java
        ).build()
        repository = ScheduleBackupRepository(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun exportAndMergeAreIdempotentAndRetainSchoolSourceKey() = runBlocking {
        val school = schoolCourse()
        database.courseDao().upsert(school)
        database.courseDao().upsert(manualCourse())

        val text = repository.export("1.3.2", "2026-09-27T12:00:00.000Z")
        val parsed = ScheduleBackupCodec.parse(text) as ScheduleBackupParseResult.Valid
        assertEquals(2, parsed.backup.courses.size)
        assertTrue(text.contains("\"externalKey\": \"school-key-3\""))
        assertTrue(!text.contains("\"id\""))

        val first = repository.importBackup(parsed.backup, ScheduleBackupImportMode.MERGE)
        assertEquals(0, first.inserted)
        assertEquals(2, first.deduplicated)
        val second = repository.importBackup(parsed.backup, ScheduleBackupImportMode.MERGE)
        assertEquals(0, second.inserted)
        assertEquals(2, second.deduplicated)
        val restoredSchool = database.courseDao().getAll().single { it.sourceType == CourseSourceType.SDBI }
        assertEquals("school-key-3", restoredSchool.externalKey)
        assertEquals("2026-fall", restoredSchool.sourceSemesterKey)
        assertEquals(2, database.courseDao().count())
    }

    @Test
    fun emptyDatabaseMergeInsertsBackupAndSecondMergeAddsNothing() = runBlocking {
        val backup = backup(schoolBackupCourse(), manualCourse().toBackupCourse())

        val first = repository.importBackup(backup, ScheduleBackupImportMode.MERGE)
        val second = repository.importBackup(backup, ScheduleBackupImportMode.MERGE)

        assertEquals(2, first.inserted)
        assertEquals(0, first.updated)
        assertEquals(2, second.deduplicated)
        assertEquals(0, second.inserted)
        assertEquals(2, database.courseDao().count())
    }

    @Test
    fun sameSchoolIdentityUpdatesCourseContent() = runBlocking {
        val original = schoolCourse()
        database.courseDao().upsert(original)
        val backup = backup(schoolBackupCourse(room = "E303", teacher = "教師乙"))

        val result = repository.importBackup(backup, ScheduleBackupImportMode.MERGE)

        assertEquals(0, result.inserted)
        assertEquals(1, result.updated)
        val updated = database.courseDao().getAll().single()
        assertEquals(original.id, updated.id)
        assertEquals("E303", updated.room)
        assertEquals("教師乙", updated.teacher)
    }

    @Test
    fun restoreReplacesCoursesAndDoesNotReuseBackupPrimaryKeys() = runBlocking {
        database.courseDao().upsert(manualCourse(id = 9000L, name = "当前课程"))
        val backup = backup(
            ScheduleBackupCourse(
                name = "备份课程",
                teacher = "老师",
                room = "B204",
                dayIndex = 1,
                period = 2,
                endPeriod = 3,
                weekNumber = 4,
                colorValue = 7,
                shortName = "简称",
                sourceType = CourseSourceType.NON_SDBI,
                sourceSemesterKey = null,
                externalKey = null
            )
        )

        val result = repository.importBackup(backup, ScheduleBackupImportMode.RESTORE)
        val restored = database.courseDao().getAll().single()

        assertEquals(1, result.restored)
        assertEquals("备份课程", restored.name)
        assertNotEquals(9000L, restored.id)
        assertEquals("简称", restored.shortName)
    }

    @Test
    fun roomTransactionRollsBackClearAndInsertWhenAnOperationFails() = runBlocking {
        val original = manualCourse(id = 77L, name = "原有课程")
        database.courseDao().upsert(original)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_backup_insert BEFORE INSERT ON courses " +
                "BEGIN SELECT RAISE(ABORT, 'simulated backup write failure'); END"
        )

        val failure = runCatching {
            repository.importBackup(
                backup(manualCourse().toBackupCourse()),
                ScheduleBackupImportMode.RESTORE
            )
        }

        assertTrue(failure.isFailure)
        assertEquals(listOf(original), database.courseDao().getAll())
    }

    private fun backup(vararg courses: ScheduleBackupCourse) = ScheduleBackup(
        schemaVersion = SCHEDULE_BACKUP_SCHEMA_VERSION,
        appVersion = "test",
        exportedAt = "2026-09-27T12:00:00.000Z",
        courses = courses.toList()
    )

    private fun schoolBackupCourse(
        room: String = "E209",
        teacher: String = "教師甲"
    ) = ScheduleBackupCourse(
        name = "Node.js应用开发",
        teacher = teacher,
        room = room,
        dayIndex = 0,
        period = 4,
        endPeriod = 5,
        weekNumber = 3,
        colorValue = 0xFF337799.toInt(),
        shortName = "Node.js",
        sourceType = CourseSourceType.SDBI,
        sourceSemesterKey = "2026-fall",
        externalKey = "school-key-3"
    )

    private fun schoolCourse() = schoolBackupCourse().toEntity(400L)

    private fun manualCourse(id: Long = 500L, name: String = "手动课程") = CourseEntity(
        id = id,
        name = name,
        teacher = "",
        room = "",
        dayIndex = 2,
        period = 1,
        endPeriod = 2,
        weekNumber = 4,
        colorValue = 0,
        shortName = null,
        sourceType = CourseSourceType.NON_SDBI,
        sourceSemesterKey = null,
        externalKey = null
    )

    private fun CourseEntity.toBackupCourse() = ScheduleBackupCourse(
        name, teacher, room, dayIndex, period, endPeriod, weekNumber, colorValue, shortName,
        sourceType, sourceSemesterKey, externalKey
    )
}
