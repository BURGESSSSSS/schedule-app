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
    fun occurrenceIdentityIncludesWeekAndScheduleButIgnoresMutableContent() {
        val occurrence = schoolOccurrence(week = 9)
        val original = SchoolOccurrenceIdentity.externalKey(occurrence)
        val changedContent = SchoolOccurrenceIdentity.externalKey(
            occurrence.copy(
                teacher = "Changed teacher",
                location = "E303",
                campus = "Other campus",
                sourceRuleKey = "different-preview-key"
            )
        )

        assertNotNull(original)
        assertEquals(original, changedContent)
        assertEquals(
            original,
            SchoolOccurrenceIdentity.externalKey(
                occurrence.copy(courseName = "Renamed course", teachingClassName = "Renamed class")
            )
        )
        assertNotEquals(original, SchoolOccurrenceIdentity.externalKey(occurrence.copy(week = 10)))
        assertNotEquals(original, SchoolOccurrenceIdentity.externalKey(occurrence.copy(semesterFlag = "2")))
        assertNotEquals(original, SchoolOccurrenceIdentity.externalKey(occurrence.copy(dayOfWeek = 4)))
        assertNotEquals(original, SchoolOccurrenceIdentity.externalKey(occurrence.copy(startSection = 4, endSection = 5)))
    }

    @Test
    fun occurrenceIdentityFallsBackToNormalizedCourseAndTeachingClassNames() {
        val occurrence = schoolOccurrence(
            courseName = "  Example   Course ",
            courseCode = " ",
            teachingClassCode = null,
            teachingClassName = "  Section   A "
        )
        val key = SchoolOccurrenceIdentity.externalKey(occurrence)

        assertNotNull(key)
        assertEquals(
            key,
            SchoolOccurrenceIdentity.externalKey(
                occurrence.copy(courseName = "example course", teachingClassName = "section a")
            )
        )
    }

    @Test
    fun occurrenceIdentityFailsClosedWithoutAnyStableCourseOrTeachingClassKey() {
        val occurrence = schoolOccurrence().copy(
            courseName = " ",
            courseCode = null,
            teachingClassCode = null,
            teachingClassName = " "
        )
        assertNull(SchoolOccurrenceIdentity.externalKey(occurrence))
    }

    @Test
    fun currentArtificialIntelligenceRulesBecomeThreeNonConflictingOccurrences() {
        val firstRule = schoolRule(
            ruleKey = "ai-week-9",
            rawWeekText = "9周",
            weeks = listOf(9),
            location = "先进制造产教融合实训基地D207"
        )
        val secondRule = schoolRule(
            ruleKey = "ai-weeks-13-17",
            rawWeekText = "13,17周",
            weeks = listOf(13, 17),
            location = "实训中心E区E311"
        )

        val semester = mergedSemester(listOf(firstRule, secondRule))
        val input = SchoolImportMapper.prepare(semester)

        assertEquals(2, semester.snapshot.courseRules.size)
        assertEquals(3, input.mappedOccurrenceCount)
        assertEquals(3, input.courses.size)
        assertEquals(setOf(9, 13, 17), input.courses.map { it.weekNumber }.toSet())
        assertEquals(3, input.courses.map { it.externalKey }.toSet().size)
        assertEquals("本校区 · 先进制造产教融合实训基地D207", input.courses.single { it.weekNumber == 9 }.room)
        assertEquals("本校区 · 实训中心E区E311", input.courses.filter { it.weekNumber in setOf(13, 17) }.map { it.room }.distinct().single())
        assertTrue(input.conflicts.isEmpty())
        assertTrue(input.occurrenceIdentityCollisions.isEmpty())
    }

    @Test
    fun sameOccurrenceFromTwoDistinctRulesIsReportedWithBothRuleDetails() {
        val firstRule = schoolRule(
            ruleKey = "duplicate-a",
            rawWeekText = "9周",
            weeks = listOf(9),
            location = "D207"
        )
        val secondRule = schoolRule(
            ruleKey = "duplicate-b",
            rawWeekText = "9周",
            weeks = listOf(9),
            location = "E311",
            teacher = "Different teacher"
        )

        val input = SchoolImportMapper.prepare(mergedSemester(listOf(firstRule, secondRule)))

        assertEquals(2, input.mappedOccurrenceCount)
        assertTrue(input.courses.isEmpty())
        assertEquals(1, input.conflicts.single {
            it.code == SchoolImportConflictCode.DUPLICATE_SCHOOL_OCCURRENCE_IDENTITY
        }.count)
        assertEquals(2, input.occurrenceIdentityCollisions.single().occurrences.size)
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
        assertEquals(rule.weeks.size, input.courses.map { it.externalKey }.toSet().size)
        assertTrue(input.courses.all { it.sourceType == "SDBI" })
    }

    private fun schoolOccurrence(
        week: Int = 9,
        courseName: String = "Example Course",
        courseCode: String? = "COURSE-1",
        teachingClassCode: String? = "CLASS-1",
        teachingClassName: String? = "Section A"
    ) = SchoolCourseOccurrence(
        academicYear = "2026-2027",
        semesterFlag = "1",
        week = week,
        courseName = courseName,
        courseCode = courseCode,
        teachingClassCode = teachingClassCode,
        teachingClassName = teachingClassName,
        teacher = "Teacher A",
        dayOfWeek = 5,
        startSection = 5,
        endSection = 6,
        location = "E209",
        campus = "本校区",
        sourceRuleKey = "rule-a"
    )

    private fun schoolRule(
        ruleKey: String,
        rawWeekText: String,
        weeks: List<Int>,
        location: String,
        teacher: String = "Teacher A"
    ) = SchoolCourseRule(
        ruleKey = ruleKey,
        courseCode = "363600",
        courseName = "人工智能应用基础",
        credits = null,
        teacher = teacher,
        studentCount = null,
        teachingClassCode = "363600-031",
        sectionText = "5-6",
        sectionCodeText = null,
        startSection = 5,
        endSection = 6,
        dayOfWeek = 5,
        campus = "本校区",
        rawLocation = location,
        displayLocation = location,
        rawWeekText = rawWeekText,
        weeks = weeks,
        weekParseStatus = RuleParseStatus.SUCCESS,
        oddEvenText = null,
        teacherCode = null,
        teachingClassName = "人工智能应用基础教学班",
        courseOfferingCode = null,
        dateText = null,
        beginTimeText = null,
        endTimeText = null,
        liveUrl = null,
        note = null,
        sourceFieldNames = emptySet(),
        warnings = emptySet()
    )

    private fun mergedSemester(rules: List<SchoolCourseRule>): MergedSemesterSchedule {
        val snapshot = SchoolScheduleSnapshot(
            academicYear = "2026-2027",
            semesterFlag = "1",
            currentWeek = "19",
            maxWeek = 19,
            rawMaxWeek = "19",
            weekStartDateText = null,
            weekEndDateText = null,
            weekStartDate = null,
            weekEndDate = null,
            maxSection = 8,
            rawMaxSection = "8",
            weekItemCounts = emptyMap(),
            courseRules = rules,
            rawRuleCount = rules.size,
            malformedRuleCount = 0,
            exactDuplicateCount = 0,
            uniqueRuleCount = rules.size,
            warnings = emptySet()
        )
        return MergedSemesterSchedule(
            snapshot = snapshot,
            candidates = SchoolImportMapper.map(snapshot),
            capturedWeeks = (1..19).toSet(),
            missingWeeks = emptySet(),
            sourceWeeksByRuleKey = rules.associate { it.ruleKey to it.weeks.toSet() },
            ruleWarningsByKey = emptyMap(),
            rawSnapshotCount = 19,
            rawRuleCount = rules.size,
            crossWeekDuplicateCount = 0,
            uniqueRuleCount = rules.size,
            warningRuleCount = 0,
            coverageComplete = true,
            collectionWarnings = emptySet()
        )
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
