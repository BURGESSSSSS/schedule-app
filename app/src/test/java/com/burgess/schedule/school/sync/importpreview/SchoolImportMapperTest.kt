package com.burgess.schedule.school.sync.importpreview

import com.burgess.schedule.school.sync.localadb.CourseBodyParseResult
import com.burgess.schedule.school.sync.localadb.parseCdpResponseBody
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun stableIdentityIgnoresTeacherRoomWeekAndTransientRuleKey() {
        val rule = fixtureSnapshot().courseRules.first {
            !it.courseCode.isNullOrBlank() && !it.teachingClassCode.isNullOrBlank() &&
                it.startSection != null && it.endSection != null
        }
        val original = SchoolImportIdentity.externalKey("2026-2027", "1", rule)
        val changedContent = SchoolImportIdentity.externalKey(
            "2026-2027",
            "1",
            rule.copy(
                ruleKey = "different-preview-key",
                teacher = "Changed teacher",
                displayLocation = "E303",
                weeks = listOf(2, 4, 6)
            )
        )

        assertNotNull(original)
        assertEquals(original, changedContent)
        assertNotEquals(original, SchoolImportIdentity.externalKey("2026-2027", "2", rule))
        assertNotEquals(
            original,
            SchoolImportIdentity.externalKey("2026-2027", "1", rule.copy(dayOfWeek = (rule.dayOfWeek % 7) + 1))
        )
    }

    @Test
    fun stableIdentityFailsClosedWhenBothSchoolCodesAreMissing() {
        val rule = fixtureSnapshot().courseRules.first().copy(courseCode = null, teachingClassCode = null)
        assertNull(SchoolImportIdentity.externalKey("2026-2027", "1", rule))
    }

    @Test
    fun formalMapperExpandsDiscreteWeeksWithoutFlatteningTheSet() {
        val snapshot = fixtureSnapshot()
        val rule = snapshot.courseRules.first {
            it.courseName == "离散周课程" && SchoolImportMapper.canMapRule(it, snapshot.maxWeek)
        }
        val onlyRuleSnapshot = snapshot.copy(courseRules = listOf(rule))
        val maxWeek = requireNotNull(snapshot.maxWeek)
        val merged = MergedSemesterSchedule(
            snapshot = onlyRuleSnapshot,
            candidates = SchoolImportMapper.map(onlyRuleSnapshot),
            capturedWeeks = (1..maxWeek).toSet(),
            missingWeeks = emptySet(),
            sourceWeeksByRuleKey = mapOf(rule.ruleKey to rule.weeks.toSet()),
            ruleWarningsByKey = emptyMap(),
            rawSnapshotCount = maxWeek,
            rawRuleCount = 1,
            crossWeekDuplicateCount = 0,
            uniqueRuleCount = 1,
            warningRuleCount = 0,
            coverageComplete = true,
            collectionWarnings = emptySet()
        )

        val input = SchoolImportMapper.prepare(merged)

        assertTrue(input.conflicts.isEmpty())
        assertEquals(rule.weeks.toSet(), input.courses.map { it.weekNumber }.toSet())
        assertEquals(1, input.courses.map { it.externalKey }.toSet().size)
        assertTrue(input.courses.all { it.sourceType == "SDBI" })
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
