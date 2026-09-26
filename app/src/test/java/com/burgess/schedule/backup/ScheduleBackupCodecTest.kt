package com.burgess.schedule.backup

import com.burgess.schedule.data.CourseEntity
import com.burgess.schedule.data.CourseSourceType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleBackupCodecTest {
    @Test
    fun exportImportRoundTripPreservesChineseDiscreteWeeksAndSchoolIdentity() {
        val entities = listOf(
            schoolEntity(),
            CourseEntity(
                id = 945L,
                name = "人工智能应用基础",
                teacher = "",
                room = "先进制造产教融合实训基地[D202AI数据增值科创工坊2]",
                dayIndex = 4,
                period = 4,
                endPeriod = 5,
                weekNumber = 13,
                colorValue = -923401,
                shortName = null
            )
        )

        val text = ScheduleBackupCodec.encode(entities, "1.3.2", "2026-09-27T12:00:00.000Z")
        val parsed = ScheduleBackupCodec.parse(text) as ScheduleBackupParseResult.Valid

        assertTrue(parsed.canImport)
        assertEquals(1, parsed.backup.schemaVersion)
        assertEquals("1.3.2", parsed.backup.appVersion)
        assertEquals("2026-09-27T12:00:00.000Z", parsed.backup.exportedAt)
        assertEquals(2, parsed.backup.courses.size)
        assertEquals("Node.js应用开发", parsed.backup.courses[0].name)
        assertEquals("SEMESTER-2026-FALL", parsed.backup.courses[0].sourceSemesterKey)
        assertEquals("sdbi-occurrence-key", parsed.backup.courses[0].externalKey)
        assertEquals(13, parsed.backup.courses[1].weekNumber)
        assertTrue(parsed.backup.courses[1].room.contains("D202AI数据增值科创工坊2"))
        assertEquals("", parsed.backup.courses[1].teacher)
        assertEquals(null, parsed.backup.courses[1].shortName)
        assertFalse(text.contains("\"id\""))
    }

    @Test
    fun nonSchoolCourseAndEmptyOptionalFieldsAreSupported() {
        val parsed = ScheduleBackupCodec.parse(
            ScheduleBackupCodec.encode(
                listOf(CourseEntity(1, "手动课程", "", "", 0, 0, 0, 1, 0)),
                "test",
                "2026-09-27T12:00:00.000Z"
            )
        ) as ScheduleBackupParseResult.Valid

        assertTrue(parsed.canImport)
        assertEquals(CourseSourceType.NON_SDBI, parsed.backup.courses.single().sourceType)
        assertEquals("", parsed.backup.courses.single().room)
        assertEquals(null, parsed.backup.courses.single().externalKey)
    }

    @Test
    fun legacyIdDerivedColorIsPreservedWhenRestoringWithANewDatabaseId() {
        val legacyCourse = CourseEntity(123L, "旧课程", "", "", 0, 0, 1, 1, 0)

        val parsed = ScheduleBackupCodec.parse(
            ScheduleBackupCodec.encode(listOf(legacyCourse), "test", "2026-09-27T12:00:00.000Z")
        ) as ScheduleBackupParseResult.Valid
        val backupCourse = parsed.backup.courses.single()

        assertTrue(backupCourse.colorValue != 0)
        assertEquals(backupCourse.colorValue, backupCourse.toEntity(456L).colorValue)
    }

    @Test
    fun schemaVersionOneIsAcceptedAndFutureSchemaIsRejected() {
        assertTrue(ScheduleBackupCodec.parse(json()).let { it is ScheduleBackupParseResult.Valid })
        val newer = JSONObject(json()).put("schemaVersion", 2).toString()
        assertEquals(
            ScheduleBackupParseResult.UnsupportedNewerVersion(2),
            ScheduleBackupCodec.parse(newer)
        )
    }

    @Test
    fun malformedJsonAndWrongFormatAreRejected() {
        assertTrue(ScheduleBackupCodec.parse("{bad").let { it is ScheduleBackupParseResult.Invalid })
        val wrongFormat = JSONObject(json()).put("format", "other-app").toString()
        assertTrue(ScheduleBackupCodec.parse(wrongFormat).let { it is ScheduleBackupParseResult.Invalid })
    }

    @Test
    fun missingFormatAndNonArrayCoursesAreRejected() {
        val missingFormat = JSONObject(json()).apply { remove("format") }.toString()
        val nonArray = JSONObject(json()).put("courses", JSONObject()).toString()

        assertTrue(ScheduleBackupCodec.parse(missingFormat).let { it is ScheduleBackupParseResult.Invalid })
        assertTrue(ScheduleBackupCodec.parse(nonArray).let { it is ScheduleBackupParseResult.Invalid })
    }

    @Test
    fun malformedIndividualCourseIsReportedAndBlocksImport() {
        val root = JSONObject(json()).put("courses", JSONArray().put(JSONObject().put("name", "缺字段课程")))
        val parsed = ScheduleBackupCodec.parse(root.toString()) as ScheduleBackupParseResult.Valid

        assertEquals(1, parsed.invalidCourseCount)
        assertFalse(parsed.canImport)
    }

    @Test
    fun duplicateSchoolSourceIdentityIsReportedAndBlocksImport() {
        val course = JSONObject().put("name", "学校课程").put("teacher", "老师").put("room", "E209")
            .put("dayIndex", 0).put("period", 0).put("endPeriod", 1).put("weekNumber", 1)
            .put("colorValue", 0).put("shortName", JSONObject.NULL).put("sourceType", CourseSourceType.SDBI)
            .put("sourceSemesterKey", "fall").put("externalKey", "occurrence-1")
        val parsed = ScheduleBackupCodec.parse(
            JSONObject(json()).put("courses", JSONArray().put(course).put(JSONObject(course.toString()))).toString()
        ) as ScheduleBackupParseResult.Valid

        assertEquals(1, parsed.duplicateSchoolIdentityGroups)
        assertFalse(parsed.canImport)
    }

    private fun json(): String = JSONObject()
        .put("format", SCHEDULE_BACKUP_FORMAT)
        .put("schemaVersion", SCHEDULE_BACKUP_SCHEMA_VERSION)
        .put("appVersion", "1.0")
        .put("exportedAt", "2026-09-27T12:00:00.000Z")
        .put("courses", JSONArray())
        .toString()

    private fun schoolEntity() = CourseEntity(
        id = 12345L,
        name = "Node.js应用开发",
        teacher = "李老师",
        room = "实训中心E区[数智基地32-实训中心E303]",
        dayIndex = 0,
        period = 4,
        endPeriod = 5,
        weekNumber = 3,
        colorValue = -16711936,
        shortName = "Node.js",
        sourceType = CourseSourceType.SDBI,
        sourceSemesterKey = "SEMESTER-2026-FALL",
        externalKey = "sdbi-occurrence-key"
    )
}
