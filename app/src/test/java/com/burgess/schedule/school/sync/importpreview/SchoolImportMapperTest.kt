package com.burgess.schedule.school.sync.importpreview

import com.burgess.schedule.school.sync.localadb.CourseBodyParseResult
import com.burgess.schedule.school.sync.localadb.parseCdpResponseBody
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchoolImportMapperTest {
    @Test
    fun mapsWeekdaySectionsTeacherAndLocationAndExpandsRuleWeeks() {
        val snapshot = fixtureSnapshot()
        val candidates = SchoolImportMapper.map(snapshot)
        val first = candidates.first { it.name == "示例课程甲" && it.dayIndex == 0 && it.weekNumber == 1 }
        val sunday = candidates.first { it.name == "示例课程甲" && it.dayIndex == 6 && it.weekNumber == 19 }

        assertEquals("示例教师甲", first.teacher)
        assertEquals("实训中心E区 [示例教室101]", first.room)
        assertEquals(0, first.period)
        assertEquals(1, first.endPeriod)
        assertEquals(19, sunday.weekNumber)
        assertTrue(first.importable)
        assertTrue(sunday.importable)
    }

    @Test
    fun missingOptionalFieldsAreAllowedWhileMissingNameAndInvalidSectionsAreNot() {
        val snapshot = fixtureSnapshot()
        val blankRoom = SchoolImportMapper.map(snapshot).first { it.name == "空地点示例课" }
        val unnamed = SchoolImportMapper.map(snapshot).first { it.name.isBlank() }

        assertTrue(blankRoom.importable)
        assertEquals("", blankRoom.teacher)
        assertEquals("", blankRoom.room)
        assertFalse(unnamed.importable)
        assertEquals(0, unnamed.period)
        assertFalse(unnamed.warnings.isEmpty())
    }

    @Test
    fun appOnlySupportsEightSectionsAndOutOfRangeWeekIsRetainedButNotImportable() {
        val snapshot = fixtureSnapshot()
        val rule = snapshot.courseRules.first { it.courseName == "离散周课程" }
        val sectionTen = rule.copy(startSection = 9, endSection = 10)
        val outOfRange = rule.copy(weeks = listOf(20), weekParseStatus = RuleParseStatus.WARNING)

        val sectionCandidate = SchoolImportMapper.mapRule(sectionTen, 19).first()
        val weekCandidate = SchoolImportMapper.mapRule(outOfRange, 19).first()

        assertFalse(sectionCandidate.importable)
        assertTrue(SchoolParseWarning.SECTION_OUT_OF_APP_RANGE in sectionCandidate.warnings)
        assertEquals(20, weekCandidate.weekNumber)
        assertFalse(weekCandidate.importable)
        assertTrue(SchoolParseWarning.WEEK_OUT_OF_RANGE in weekCandidate.warnings)
    }

    private fun fixtureSnapshot(): SchoolScheduleSnapshot {
        val fixture = javaClass.getResourceAsStream("/sdbi_schedule_snapshot.json")!!.use {
            String(it.readBytes(), StandardCharsets.UTF_8)
        }
        val parsed = parseCdpResponseBody(
            JSONObject().put("body", fixture).put("base64Encoded", false)
        )
        return (parsed as CourseBodyParseResult.Confirmed).scheduleSnapshot
    }
}
