package com.burgess.schedule.school.sync.importpreview

import com.burgess.schedule.school.sync.localadb.CourseBodyParseResult
import com.burgess.schedule.school.sync.localadb.parseCdpResponseBody
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdbiScheduleParserTest {
    @Test
    fun fixtureRepresentsMondayThroughSundayAndKeepsExactSchedulingRules() {
        val snapshot = parseFixture()

        assertEquals((1..7).associateWith { if (it == 1) 2 else if (it == 6) 0 else if (it == 7) 2 else 1 },
            snapshot.weekItemCounts)
        assertEquals(8, snapshot.rawRuleCount)
        assertEquals(1, snapshot.exactDuplicateCount)
        assertEquals(7, snapshot.uniqueRuleCount)
        assertEquals(0, snapshot.malformedRuleCount)
        assertEquals(7, snapshot.courseRules.size)
        assertEquals(2026, snapshot.weekStartDate?.let { java.text.SimpleDateFormat("yyyy", java.util.Locale.ROOT).format(it) }?.toInt())
        assertEquals("0", snapshot.semesterFlag)

        val monday = snapshot.courseRules.first { it.courseName == "示例课程甲" && it.dayOfWeek == 1 }
        val sunday = snapshot.courseRules.first { it.courseName == "示例课程甲" && it.dayOfWeek == 7 }
        assertEquals(64, monday.ruleKey.length)
        assertEquals(listOf(1, 2, 3, 4, 5) + (7..19).toList(), monday.weeks)
        assertEquals((1..19).toList(), sunday.weeks)
        assertEquals(1, monday.startSection)
        assertEquals(2, monday.endSection)
        assertEquals(" 实训中心E区 [示例教室101] ", monday.rawLocation)
        assertEquals("实训中心E区 [示例教室101]", monday.displayLocation)
        assertNull(monday.liveUrl)
        assertTrue("liveUrl" in monday.sourceFieldNames)

        val artificialIntelligence = snapshot.courseRules.filter {
            it.courseCode == "SDBI-002" && it.startSection == 5
        }
        assertEquals(2, artificialIntelligence.size)
        assertEquals(setOf("地点 A", "地点 B"), artificialIntelligence.mapNotNull { it.displayLocation }.toSet())
        assertEquals(listOf(9), artificialIntelligence.first { it.dayOfWeek == 2 }.weeks)
        assertEquals(listOf(5, 13, 17), artificialIntelligence.first { it.dayOfWeek == 5 }.weeks)
    }

    @Test
    fun optionalTeacherAndLocationDoNotBlockCandidateButMissingNameDoes() {
        val snapshot = parseFixture()
        val optionalMissing = snapshot.courseRules.first { it.courseName == "空地点示例课" }
        val noName = snapshot.courseRules.single { SchoolParseWarning.MISSING_COURSE_NAME in it.warnings }

        assertTrue(SchoolParseWarning.MISSING_TEACHER in optionalMissing.warnings)
        assertTrue(SchoolParseWarning.MISSING_LOCATION in optionalMissing.warnings)
        assertTrue(SchoolImportMapper.canMapRule(optionalMissing, snapshot.maxWeek))
        assertTrue(SchoolParseWarning.MISSING_COURSE_NAME in noName.warnings)
        assertFalse(SchoolImportMapper.canMapRule(noName, snapshot.maxWeek))
    }

    @Test
    fun malformedWeeksAndSectionMismatchWarnOnlyTheAffectedRule() {
        val json = fixtureJson()
        val monday = json.getJSONArray("week1").getJSONObject(0)
        monday.put("skzs", "2，bad，4周")
        val tuesday = json.getJSONArray("week2").getJSONObject(0)
        tuesday.put("jcdm", "03,04")
        val snapshot = parse(json)

        val malformedWeeks = snapshot.courseRules.first { it.courseName == "示例课程甲" && it.dayOfWeek == 1 }
        val mismatch = snapshot.courseRules.first { it.courseName == "人工智能应用基础" }
        assertEquals(listOf(2, 4), malformedWeeks.weeks)
        assertTrue(SchoolParseWarning.WEEK_PARSE_FAILED in malformedWeeks.warnings)
        assertTrue(SchoolParseWarning.SECTION_MISMATCH in mismatch.warnings)
        assertFalse(SchoolImportMapper.canMapRule(mismatch, snapshot.maxWeek))
        assertEquals(8, snapshot.courseRules.size)
    }

    @Test
    fun nonObjectEntryWarnsWithoutDroppingOtherRules() {
        val json = fixtureJson()
        json.getJSONArray("week2").put(17)

        val snapshot = parse(json)

        assertEquals(9, snapshot.rawRuleCount)
        assertEquals(1, snapshot.malformedRuleCount)
        assertEquals(7, snapshot.uniqueRuleCount)
        assertTrue(SchoolParseWarning.NON_OBJECT_RULE in snapshot.warnings)
    }

    @Test
    fun dateParseWarningsDoNotDropRulesAndOddEvenWarningDoesNotOverrideSkzs() {
        val json = fixtureJson()
        json.put("qssj", "2026-02-30")
        json.getJSONArray("week3").getJSONObject(0).put("dsz", "单周")
        val snapshot = parse(json)

        assertNull(snapshot.weekStartDate)
        assertTrue(SchoolParseWarning.DATE_PARSE_WARNING in snapshot.warnings)
        val rule = snapshot.courseRules.first { it.courseName == "离散周课程" }
        assertEquals(listOf(3, 7, 11, 15, 19), rule.weeks)
        assertTrue(SchoolParseWarning.ODD_EVEN_CONFLICT !in rule.warnings)

        json.getJSONArray("week3").getJSONObject(0).put("skzs", "2,3周")
        val conflict = parse(json).courseRules.first { it.courseName == "离散周课程" }
        assertTrue(SchoolParseWarning.ODD_EVEN_CONFLICT in conflict.warnings)
    }

    @Test
    fun summaryStatisticsComeFromSnapshotAndMappedCandidates() {
        val snapshot = parseFixture()
        val candidates = SchoolImportMapper.map(snapshot)
        val summary = SchoolImportPreviewSummary.from(snapshot, candidates)

        assertEquals(8, summary.rawRuleCount)
        assertEquals(1, summary.exactDuplicateCount)
        assertEquals(7, summary.uniqueRuleCount)
        assertEquals(6, summary.importableRuleCount)
        assertEquals(47, summary.importableCount)
        assertEquals(2, summary.warningRuleCount)
        assertEquals(0, summary.snapshotWarningCount)
    }

    @Test
    fun candidateMappingUsesAppZeroBasedIndexesAndLeavesDatabaseDefaultsUnset() {
        val snapshot = parseFixture()
        val candidates = SchoolImportMapper.map(snapshot)
        val mondayFirstWeek = candidates.first {
            it.name == "示例课程甲" && it.dayIndex == 0 && it.weekNumber == 1
        }

        assertEquals(0, mondayFirstWeek.dayIndex)
        assertEquals(0, mondayFirstWeek.period)
        assertEquals(1, mondayFirstWeek.endPeriod)
        assertEquals("示例教师甲", mondayFirstWeek.teacher)
        assertEquals("实训中心E区 [示例教室101]", mondayFirstWeek.room)
        assertNull(mondayFirstWeek.id)
        assertNull(mondayFirstWeek.colorValue)
        assertNull(mondayFirstWeek.shortName)
        assertEquals(
            setOf(FieldImportDefault.DATABASE_ID, FieldImportDefault.COLOR, FieldImportDefault.SHORT_NAME),
            mondayFirstWeek.defaults
        )
        assertEquals(18, candidates.count { it.sourceRuleKey == mondayFirstWeek.sourceRuleKey })
    }

    @Test
    fun ruleKeyIncludesWeekAndLocationSoOnlyExactDuplicateIsRemoved() {
        val json = JSONObject()
            .put("xn", "2026")
            .put("zc", "03")
            .put("maxzc", 19)
            .put("week1", JSONArray()
                .put(courseRule("地点 A", "9周"))
                .put(courseRule("地点 A", "10周"))
                .put(courseRule("地点 B", "9周"))
                .put(courseRule("地点 A", "9周"))
                .put(courseRule("地点 A", "9周").put("jcdm", "02,03")))
        (2..7).forEach { json.put("week$it", JSONArray()) }
        val snapshot = parse(json)

        assertEquals(5, snapshot.rawRuleCount)
        assertEquals(1, snapshot.exactDuplicateCount)
        assertEquals(4, snapshot.uniqueRuleCount)
        assertEquals(4, snapshot.courseRules.size)
    }

    @Test
    fun parserDoesNotKeepRawResponseInResultDiagnostics() {
        val result = parseCdpResponseBody(
            JSONObject()
                .put("body", fixtureText().replace("示例课程甲", "COURSE_NAME_SENTINEL"))
                .put("base64Encoded", false)
        ) as CourseBodyParseResult.Confirmed

        assertFalse(result.toString().contains("COURSE_NAME_SENTINEL"))
        assertFalse(result.scheduleSnapshot.toString().contains("COURSE_NAME_SENTINEL"))
    }

    private fun parseFixture(): SchoolScheduleSnapshot = parse(fixtureJson())

    private fun parse(json: JSONObject): SchoolScheduleSnapshot {
        val result = parseCdpResponseBody(
            JSONObject().put("body", json.toString()).put("base64Encoded", false)
        )
        assertTrue("fixture should be recognized as a schedule", result is CourseBodyParseResult.Confirmed)
        return (result as CourseBodyParseResult.Confirmed).scheduleSnapshot
    }

    private fun fixtureJson() = JSONObject(fixtureText())

    private fun fixtureText(): String =
        javaClass.getResourceAsStream("/sdbi_schedule_snapshot.json")!!.use {
            String(it.readBytes(), StandardCharsets.UTF_8)
        }

    private fun courseRule(location: String, weekText: String) = JSONObject()
        .put("kcdm", "RULE")
        .put("kcmc", "相同课程")
        .put("rkjs", "教师")
        .put("skbj", "班级")
        .put("jcxx", "1-2")
        .put("jcdm", "01,02")
        .put("xq", "校区")
        .put("skdd", location)
        .put("skzs", weekText)
}
