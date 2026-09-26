package com.burgess.schedule.school.sync.importpreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemesterCollectionSessionTest {
    @Test
    fun zcIsTheCapturedWeekAndLeadingZeroIsAccepted() {
        val result = SemesterCollectionSession.start(10L).recordSnapshot(snapshot("03", maxWeek = 19), 11L)

        assertTrue(result is SemesterSnapshotCaptureResult.Accepted)
        assertEquals(setOf(3), (result as SemesterSnapshotCaptureResult.Accepted).session.capturedWeeks)
        assertEquals(3, result.week)
    }

    @Test
    fun recapturingSameWeekReplacesSnapshotButDoesNotIncreaseCoverage() {
        val firstRule = rule("first")
        val secondRule = rule("second")
        val first = SemesterCollectionSession.start(1L).recordSnapshot(snapshot("3", 19, listOf(firstRule)), 2L)
            as SemesterSnapshotCaptureResult.Accepted
        val second = first.session.recordSnapshot(snapshot("03", 19, listOf(secondRule)), 3L)
            as SemesterSnapshotCaptureResult.Accepted

        assertTrue(second.replacedExistingWeek)
        assertEquals(setOf(3), second.session.capturedWeeks)
        assertEquals(1, second.session.recaptureCountByWeek[3])
        assertEquals(setOf("second"), second.session.rulesByKey.keys)
        assertEquals(setOf("second"), second.session.snapshotsByWeek[3]?.courseRules?.map { it.ruleKey }?.toSet())
    }

    @Test
    fun confirmedEmptyWeekStillCountsAsCaptured() {
        val result = SemesterCollectionSession.start(1L).recordSnapshot(snapshot("5", 19, emptyList()), 2L)
            as SemesterSnapshotCaptureResult.Accepted

        assertEquals(setOf(5), result.session.capturedWeeks)
        assertTrue(result.session.snapshotsByWeek.getValue(5).courseRules.isEmpty())
        assertEquals(1, result.session.snapshotsByWeek.size)
    }

    @Test
    fun academicYearMismatchIsRejectedAndRecorded() {
        val first = accepted(snapshot("1", 3))
        val result = first.recordSnapshot(snapshot("2", 3, year = "2027"), 3L)

        assertTrue(result is SemesterSnapshotCaptureResult.Rejected)
        assertEquals(SemesterCollectionWarning.SEMESTER_METADATA_MISMATCH, (result as SemesterSnapshotCaptureResult.Rejected).warning)
        assertEquals(setOf(1), result.session.capturedWeeks)
        assertTrue(SemesterCollectionWarning.SEMESTER_METADATA_MISMATCH in result.session.warnings)
    }

    @Test
    fun semesterFlagMismatchIsRejected() {
        val first = accepted(snapshot("1", 3))
        val result = first.recordSnapshot(snapshot("2", 3, term = "1"), 3L)

        assertEquals(SemesterCollectionWarning.SEMESTER_METADATA_MISMATCH, (result as SemesterSnapshotCaptureResult.Rejected).warning)
        assertEquals(setOf(1), result.session.capturedWeeks)
    }

    @Test
    fun maxWeekMismatchIsRejectedAndRecordedSeparately() {
        val first = accepted(snapshot("1", 19))
        val result = first.recordSnapshot(snapshot("2", 20), 3L)

        assertEquals(SemesterCollectionWarning.MAX_WEEK_MISMATCH, (result as SemesterSnapshotCaptureResult.Rejected).warning)
        assertTrue(SemesterCollectionWarning.MAX_WEEK_MISMATCH in result.session.warnings)
        assertEquals(setOf(1), result.session.capturedWeeks)
        assertEquals(19, result.session.maxWeek)
    }

    @Test
    fun identicalRuleAcrossWeeksIsMergedOnceAndSourcesAreTracked() {
        val recurring = rule("same")
        var session = SemesterCollectionSession.start(1L)
        (1..3).forEach { week ->
            session = (session.recordSnapshot(snapshot(week.toString(), 3, listOf(recurring)), week.toLong())
                as SemesterSnapshotCaptureResult.Accepted).session
        }
        val merged = mergeSemesterSession(session)

        assertEquals(1, merged.uniqueRuleCount)
        assertEquals(2, merged.crossWeekDuplicateCount)
        assertEquals(setOf(1, 2, 3), merged.sourceWeeksByRuleKey.getValue("same"))
    }

    @Test
    fun specialRuleAppearingOnlyInWeekEightIsPreserved() {
        val normal = rule("normal", weeks = listOf(1, 2, 3, 4, 5, 6, 7, 8))
        val special = rule("week-eight", name = "单周特殊课程", weeks = listOf(8))
        var session = accepted(snapshot("1", 8, listOf(normal)))
        session = (session.recordSnapshot(snapshot("8", 8, listOf(normal, special)), 8L)
            as SemesterSnapshotCaptureResult.Accepted).session

        val merged = mergeSemesterSession(session)
        assertTrue(merged.snapshot.courseRules.any { it.ruleKey == "week-eight" })
        assertEquals(setOf(8), merged.sourceWeeksByRuleKey.getValue("week-eight"))
    }

    @Test
    fun semesterFixturesCoverWeekOneThreeEmptyFiveSpecialEightAndWeekNineteen() {
        val recurring = rule("term-course", weeks = listOf(1, 3, 19))
        val special = rule("special-eight", name = "第八周专题课", weeks = listOf(8))
        var session = SemesterCollectionSession.start(1L)
        val fixtures = listOf(
            snapshot("1", 19, listOf(recurring)),
            snapshot("3", 19, listOf(recurring)),
            snapshot("5", 19, emptyList()),
            snapshot("8", 19, listOf(special)),
            snapshot("19", 19, listOf(recurring))
        )
        fixtures.forEachIndexed { index, fixture ->
            session = (session.recordSnapshot(fixture, (index + 2).toLong())
                as SemesterSnapshotCaptureResult.Accepted).session
        }

        val merged = mergeSemesterSession(session)
        assertEquals(setOf(1, 3, 5, 8, 19), merged.capturedWeeks)
        assertEquals(setOf("term-course", "special-eight"), merged.snapshot.courseRules.map { it.ruleKey }.toSet())
        assertEquals(listOf(8), merged.snapshot.courseRules.single { it.ruleKey == "special-eight" }.weeks)
        assertEquals(setOf(8), merged.sourceWeeksByRuleKey.getValue("special-eight"))
        assertTrue(merged.missingWeeks.containsAll(setOf(2, 4, 6, 7, 9, 18)))
        assertFalse(merged.coverageComplete)
    }

    @Test
    fun coveredExpectedWeekMissingItsRuleProducesWarningOnly() {
        val recurring = rule("recurring", weeks = listOf(1, 2, 3, 4))
        var session = accepted(snapshot("1", 4, listOf(recurring)))
        session = (session.recordSnapshot(snapshot("2", 4, emptyList()), 3L)
            as SemesterSnapshotCaptureResult.Accepted).session

        val merged = mergeSemesterSession(session)
        assertTrue(
            SemesterRuleCoverageWarning.RULE_COVERAGE_INCONSISTENCY in
                merged.ruleWarningsByKey.getValue("recurring")
        )
        assertEquals("recurring", merged.snapshot.courseRules.single().ruleKey)
    }

    @Test
    fun lowSourceWeekCountIsReportedWithoutChangingRule() {
        val recurring = rule("long-course", weeks = (1..19).toList())
        val session = accepted(snapshot("1", 19, listOf(recurring)))

        val merged = mergeSemesterSession(session)
        assertTrue(
            SemesterRuleCoverageWarning.RULE_OBSERVATION_INCOMPLETE in
                merged.ruleWarningsByKey.getValue("long-course")
        )
        assertEquals(listOf(1), merged.snapshot.courseRules.single().weeks.take(1))
        assertEquals((1..19).toList(), merged.snapshot.courseRules.single().weeks)
    }

    @Test
    fun differentLocationsRemainDifferentRules() {
        val locationA = rule("location-a", location = "地点 A")
        val locationB = rule("location-b", location = "地点 B")
        var session = accepted(snapshot("1", 2, listOf(locationA)))
        session = (session.recordSnapshot(snapshot("2", 2, listOf(locationB)), 2L)
            as SemesterSnapshotCaptureResult.Accepted).session

        assertEquals(setOf("location-a", "location-b"), mergeSemesterSession(session).snapshot.courseRules.map { it.ruleKey }.toSet())
    }

    @Test
    fun missingWeeksAreCalculatedFromCoverageNotCourseCount() {
        var session = SemesterCollectionSession.start(1L)
        listOf(3, 5, 1).forEach { week ->
            session = (session.recordSnapshot(snapshot(week.toString(), 6, if (week == 5) emptyList() else listOf(rule("r$week"))), week.toLong())
                as SemesterSnapshotCaptureResult.Accepted).session
        }
        val merged = mergeSemesterSession(session)

        assertEquals(setOf(1, 3, 5), merged.capturedWeeks)
        assertEquals(setOf(2, 4, 6), merged.missingWeeks)
        assertFalse(merged.coverageComplete)
    }

    @Test
    fun allWeeksOneThroughMaxBecomeCoverageComplete() {
        var session = SemesterCollectionSession.start(1L)
        listOf(3, 1, 2).forEach { week ->
            session = (session.recordSnapshot(snapshot(week.toString(), 3), week.toLong())
                as SemesterSnapshotCaptureResult.Accepted).session
        }

        assertEquals(setOf(1, 2, 3), session.capturedWeeks)
        assertTrue(session.coverageComplete)
        assertEquals(SemesterCollectionState.SEMESTER_COVERAGE_COMPLETE, session.sessionState)
        assertEquals(emptySet<Int>(), session.missingWeeks)
    }

    @Test
    fun eighteenOfNineteenRemainsPartial() {
        var session = SemesterCollectionSession.start(1L)
        (1..18).forEach { week ->
            session = (session.recordSnapshot(snapshot(week.toString(), 19), week.toLong())
                as SemesterSnapshotCaptureResult.Accepted).session
        }

        assertEquals(setOf(19), session.missingWeeks)
        assertFalse(session.coverageComplete)
    }

    @Test
    fun sessionCodecRestoresCoverageAndDoesNotPersistRawResponseOrOptionalSensitiveFields() {
        val sensitiveOptionalFields = rule("safe-rule").copy(
            teachingClassName = "  Example   Teaching Class  ",
            liveUrl = "https://example.invalid/?token=private-token-value",
            note = "raw-body-private-marker",
            sourceFieldNames = setOf("authorization")
        )
        val session = accepted(snapshot("1", 2, listOf(sensitiveOptionalFields)))
            .withState(SemesterCollectionState.SEMESTER_COLLECTION_STOPPED)
        val encoded = SemesterCollectionCodec.encode(session)
        val restored = SemesterCollectionCodec.decode(encoded)

        assertEquals(setOf(1), restored.capturedWeeks)
        assertEquals(setOf(1), restored.sourceWeeksByRuleKey.getValue("safe-rule"))
        assertEquals("  Example   Teaching Class  ", restored.rulesByKey.getValue("safe-rule").teachingClassName)
        assertNull(restored.rulesByKey.getValue("safe-rule").liveUrl)
        assertNull(restored.rulesByKey.getValue("safe-rule").note)
        assertFalse(encoded.contains("private-token-value"))
        assertFalse(encoded.contains("raw-body-private-marker"))
        assertFalse(encoded.contains("authorization"))
        assertFalse(encoded.contains("week1"))
    }

    @Test
    fun restoredRunningSessionIsMarkedAsCdpLostButKeepsCapturedWeeks() {
        val running = accepted(snapshot("2", 4)).withState(SemesterCollectionState.SEMESTER_COLLECTION_RUNNING)
        val restored = SemesterCollectionCodec.decode(SemesterCollectionCodec.encode(running))

        assertEquals(setOf(2), restored.capturedWeeks)
        assertEquals(SemesterCollectionState.CDP_SESSION_LOST, restored.sessionState)
        assertTrue(SemesterCollectionWarning.CDP_SESSION_LOST in restored.warnings)
    }

    private fun accepted(snapshot: SchoolScheduleSnapshot): SemesterCollectionSession =
        (SemesterCollectionSession.start(1L).recordSnapshot(snapshot, 2L) as SemesterSnapshotCaptureResult.Accepted).session

    private fun snapshot(
        currentWeek: String,
        maxWeek: Int,
        rules: List<SchoolCourseRule> = emptyList(),
        year: String = "2026",
        term: String = "0"
    ) = SchoolScheduleSnapshot(
        academicYear = year,
        semesterFlag = term,
        currentWeek = currentWeek,
        maxWeek = maxWeek,
        rawMaxWeek = maxWeek.toString(),
        weekStartDateText = "2026-09-01",
        weekEndDateText = "2026-09-07",
        weekStartDate = null,
        weekEndDate = null,
        maxSection = 8,
        rawMaxSection = "8",
        weekItemCounts = (1..7).associateWith { 0 },
        courseRules = rules,
        rawRuleCount = rules.size,
        malformedRuleCount = 0,
        exactDuplicateCount = 0,
        uniqueRuleCount = rules.size,
        warnings = emptySet()
    )

    private fun rule(
        key: String,
        name: String = "示例课",
        location: String = "教室 101",
        weeks: List<Int> = listOf(1, 2, 3)
    ) = SchoolCourseRule(
        ruleKey = key,
        courseCode = "C-$key",
        courseName = name,
        credits = "2",
        teacher = "教师",
        studentCount = null,
        teachingClassCode = null,
        sectionText = "1-2",
        sectionCodeText = null,
        startSection = 1,
        endSection = 2,
        dayOfWeek = 1,
        campus = null,
        rawLocation = location,
        displayLocation = location,
        rawWeekText = weeks.joinToString(",") + "周",
        weeks = weeks,
        weekParseStatus = RuleParseStatus.SUCCESS,
        oddEvenText = null,
        teacherCode = null,
        teachingClassName = null,
        courseOfferingCode = null,
        dateText = null,
        beginTimeText = null,
        endTimeText = null,
        liveUrl = null,
        note = null,
        sourceFieldNames = emptySet(),
        warnings = emptySet()
    )
}
