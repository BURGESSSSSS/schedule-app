package com.burgess.schedule.school.sync.importpreview

import java.util.Date

internal enum class SemesterCollectionState {
    SEMESTER_COLLECTION_RUNNING,
    SEMESTER_COLLECTION_STOPPED,
    CDP_SESSION_LOST,
    SEMESTER_COVERAGE_COMPLETE
}

internal enum class SemesterCollectionWarning {
    SEMESTER_METADATA_MISMATCH,
    MAX_WEEK_MISMATCH,
    INVALID_CAPTURE_WEEK,
    INVALID_SEMESTER_METADATA,
    CDP_SESSION_LOST
}

internal enum class SemesterRuleCoverageWarning {
    RULE_COVERAGE_INCONSISTENCY,
    RULE_OBSERVATION_INCOMPLETE
}

internal data class SemesterCollectionSession(
    val academicYear: String? = null,
    val semesterFlag: String? = null,
    val maxWeek: Int? = null,
    val capturedWeeks: Set<Int> = emptySet(),
    val snapshotsByWeek: Map<Int, SchoolScheduleSnapshot> = emptyMap(),
    val rulesByKey: Map<String, SchoolCourseRule> = emptyMap(),
    val sourceWeeksByRuleKey: Map<String, Set<Int>> = emptyMap(),
    val ruleWarningsByKey: Map<String, Set<SemesterRuleCoverageWarning>> = emptyMap(),
    val warnings: Set<SemesterCollectionWarning> = emptySet(),
    val recaptureCountByWeek: Map<Int, Int> = emptyMap(),
    val startedAtEpochMs: Long,
    val lastCaptureAtEpochMs: Long? = null,
    val lastCapturedWeek: Int? = null,
    val backgroundCdpSurvived: Boolean = false,
    val sessionState: SemesterCollectionState = SemesterCollectionState.SEMESTER_COLLECTION_RUNNING
) {
    val expectedWeeks: Set<Int>
        get() = maxWeek?.takeIf { it in 1..MAX_SEMESTER_WEEKS }?.let { (1..it).toSet() }.orEmpty()

    val missingWeeks: Set<Int>
        get() = expectedWeeks - capturedWeeks

    val coverageComplete: Boolean
        get() = expectedWeeks.isNotEmpty() && capturedWeeks == expectedWeeks

    val rawRuleCount: Int
        get() = snapshotsByWeek.values.sumOf(SchoolScheduleSnapshot::rawRuleCount)

    val uniqueRuleCount: Int
        get() = rulesByKey.size

    fun withState(state: SemesterCollectionState): SemesterCollectionSession = copy(
        sessionState = if (coverageComplete && state == SemesterCollectionState.SEMESTER_COLLECTION_STOPPED) {
            SemesterCollectionState.SEMESTER_COVERAGE_COMPLETE
        } else {
            state
        }
    )

    fun markBackgroundCdpSurvived(): SemesterCollectionSession =
        if (backgroundCdpSurvived) this else copy(backgroundCdpSurvived = true)

    fun markCdpDisconnected(): SemesterCollectionSession = copy(
        warnings = warnings + SemesterCollectionWarning.CDP_SESSION_LOST,
        sessionState = if (coverageComplete) {
            SemesterCollectionState.SEMESTER_COVERAGE_COMPLETE
        } else {
            SemesterCollectionState.CDP_SESSION_LOST
        }
    )

    companion object {
        const val MAX_SEMESTER_WEEKS = 60

        fun start(nowEpochMs: Long = System.currentTimeMillis()) =
            SemesterCollectionSession(startedAtEpochMs = nowEpochMs)
    }
}

internal sealed interface SemesterSnapshotCaptureResult {
    data class Accepted(
        val session: SemesterCollectionSession,
        val week: Int,
        val replacedExistingWeek: Boolean
    ) : SemesterSnapshotCaptureResult

    data class Rejected(
        val session: SemesterCollectionSession,
        val warning: SemesterCollectionWarning,
        val week: Int? = null
    ) : SemesterSnapshotCaptureResult
}

internal fun SemesterCollectionSession.recordSnapshot(
    snapshot: SchoolScheduleSnapshot,
    capturedAtEpochMs: Long = System.currentTimeMillis()
): SemesterSnapshotCaptureResult {
    val week = snapshot.currentWeek?.trim()?.toIntOrNull()
    val incomingMaxWeek = snapshot.maxWeek
    if (week == null || incomingMaxWeek == null || incomingMaxWeek !in 1..SemesterCollectionSession.MAX_SEMESTER_WEEKS ||
        week !in 1..incomingMaxWeek
    ) {
        val updated = copy(warnings = warnings + SemesterCollectionWarning.INVALID_CAPTURE_WEEK)
        return SemesterSnapshotCaptureResult.Rejected(
            session = updated,
            warning = SemesterCollectionWarning.INVALID_CAPTURE_WEEK,
            week = week
        )
    }

    val year = snapshot.academicYear?.trim()?.takeIf(String::isNotEmpty)
    val term = snapshot.semesterFlag?.trim()?.takeIf(String::isNotEmpty)
    if (year == null || term == null) {
        val updated = copy(warnings = warnings + SemesterCollectionWarning.INVALID_SEMESTER_METADATA)
        return SemesterSnapshotCaptureResult.Rejected(
            session = updated,
            warning = SemesterCollectionWarning.INVALID_SEMESTER_METADATA,
            week = week
        )
    }

    if (academicYear != null && (academicYear != year || semesterFlag != term)) {
        val updated = copy(warnings = warnings + SemesterCollectionWarning.SEMESTER_METADATA_MISMATCH)
        return SemesterSnapshotCaptureResult.Rejected(
            session = updated,
            warning = SemesterCollectionWarning.SEMESTER_METADATA_MISMATCH,
            week = week
        )
    }
    if (maxWeek != null && maxWeek != incomingMaxWeek) {
        val updated = copy(warnings = warnings + SemesterCollectionWarning.MAX_WEEK_MISMATCH)
        return SemesterSnapshotCaptureResult.Rejected(
            session = updated,
            warning = SemesterCollectionWarning.MAX_WEEK_MISMATCH,
            week = week
        )
    }

    val replaced = week in capturedWeeks
    val updatedSnapshots = snapshotsByWeek + (week to snapshot)
    val mergedRules = LinkedHashMap<String, SchoolCourseRule>()
    val sourceWeeks = LinkedHashMap<String, MutableSet<Int>>()
    updatedSnapshots.toSortedMap().forEach { (sourceWeek, capturedSnapshot) ->
        capturedSnapshot.courseRules.forEach { rule ->
            mergedRules.putIfAbsent(rule.ruleKey, rule)
            sourceWeeks.getOrPut(rule.ruleKey) { linkedSetOf() }.add(sourceWeek)
        }
    }
    val updatedCapturedWeeks = capturedWeeks + week
    val ruleWarnings = calculateRuleCoverageWarnings(
        maxWeek = incomingMaxWeek,
        capturedWeeks = updatedCapturedWeeks,
        snapshotsByWeek = updatedSnapshots,
        rulesByKey = mergedRules,
        sourceWeeksByRuleKey = sourceWeeks.mapValues { it.value.toSet() }
    )
    val completed = updatedCapturedWeeks == (1..incomingMaxWeek).toSet()
    val updatedSession = copy(
        academicYear = year,
        semesterFlag = term,
        maxWeek = incomingMaxWeek,
        capturedWeeks = updatedCapturedWeeks,
        snapshotsByWeek = updatedSnapshots,
        rulesByKey = mergedRules,
        sourceWeeksByRuleKey = sourceWeeks.mapValues { it.value.toSet() },
        ruleWarningsByKey = ruleWarnings,
        recaptureCountByWeek = if (replaced) {
            recaptureCountByWeek + (week to ((recaptureCountByWeek[week] ?: 0) + 1))
        } else {
            recaptureCountByWeek
        },
        lastCaptureAtEpochMs = capturedAtEpochMs,
        lastCapturedWeek = week,
        sessionState = if (completed) {
            SemesterCollectionState.SEMESTER_COVERAGE_COMPLETE
        } else {
            sessionState
        }
    )
    return SemesterSnapshotCaptureResult.Accepted(updatedSession, week, replaced)
}

internal fun calculateRuleCoverageWarnings(
    maxWeek: Int,
    capturedWeeks: Set<Int>,
    snapshotsByWeek: Map<Int, SchoolScheduleSnapshot>,
    rulesByKey: Map<String, SchoolCourseRule>,
    sourceWeeksByRuleKey: Map<String, Set<Int>>
): Map<String, Set<SemesterRuleCoverageWarning>> = buildMap {
    rulesByKey.forEach { (ruleKey, rule) ->
        val warnings = linkedSetOf<SemesterRuleCoverageWarning>()
        val parsedWeeks = rule.weeks.filter { it in 1..maxWeek }.toSet()
        val expectedCapturedWeeks = parsedWeeks intersect capturedWeeks
        if (expectedCapturedWeeks.any { sourceWeek ->
                snapshotsByWeek[sourceWeek]?.courseRules?.none { it.ruleKey == ruleKey } == true
            }
        ) {
            warnings += SemesterRuleCoverageWarning.RULE_COVERAGE_INCONSISTENCY
        }
        val sourceWeeks = sourceWeeksByRuleKey[ruleKey].orEmpty()
        if (parsedWeeks.size >= 4 && sourceWeeks.size * 2 < parsedWeeks.size) {
            warnings += SemesterRuleCoverageWarning.RULE_OBSERVATION_INCOMPLETE
        }
        if (warnings.isNotEmpty()) put(ruleKey, warnings)
    }
}

internal data class MergedSemesterSchedule(
    val snapshot: SchoolScheduleSnapshot,
    val candidates: List<SchoolImportCandidate>,
    val capturedWeeks: Set<Int>,
    val missingWeeks: Set<Int>,
    val sourceWeeksByRuleKey: Map<String, Set<Int>>,
    val ruleWarningsByKey: Map<String, Set<SemesterRuleCoverageWarning>>,
    val rawSnapshotCount: Int,
    val rawRuleCount: Int,
    val crossWeekDuplicateCount: Int,
    val uniqueRuleCount: Int,
    val warningRuleCount: Int,
    val coverageComplete: Boolean,
    val collectionWarnings: Set<SemesterCollectionWarning>
)

internal fun mergeSemesterSession(session: SemesterCollectionSession): MergedSemesterSchedule {
    val orderedSnapshots = session.snapshotsByWeek.toSortedMap().values.toList()
    val rawRuleCount = orderedSnapshots.sumOf(SchoolScheduleSnapshot::rawRuleCount)
    val withinSnapshotUniqueCount = orderedSnapshots.sumOf(SchoolScheduleSnapshot::uniqueRuleCount)
    val rules = LinkedHashMap<String, SchoolCourseRule>()
    orderedSnapshots.forEach { snapshot ->
        snapshot.courseRules.forEach { rule -> rules.putIfAbsent(rule.ruleKey, rule) }
    }
    val first = orderedSnapshots.firstOrNull()
    val maxWeek = session.maxWeek
    val mergedSnapshot = SchoolScheduleSnapshot(
        academicYear = session.academicYear,
        semesterFlag = session.semesterFlag,
        currentWeek = session.lastCapturedWeek?.toString(),
        maxWeek = maxWeek,
        rawMaxWeek = maxWeek?.toString(),
        weekStartDateText = first?.weekStartDateText,
        weekEndDateText = first?.weekEndDateText,
        weekStartDate = first?.weekStartDate,
        weekEndDate = first?.weekEndDate,
        maxSection = orderedSnapshots.mapNotNull(SchoolScheduleSnapshot::maxSection).maxOrNull(),
        rawMaxSection = orderedSnapshots.lastOrNull()?.rawMaxSection,
        weekItemCounts = orderedSnapshots.flatMap { it.weekItemCounts.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, counts) -> counts.sum() },
        courseRules = rules.values.toList(),
        rawRuleCount = rawRuleCount,
        malformedRuleCount = orderedSnapshots.sumOf(SchoolScheduleSnapshot::malformedRuleCount),
        exactDuplicateCount = (rawRuleCount - rules.size).coerceAtLeast(0),
        uniqueRuleCount = rules.size,
        warnings = orderedSnapshots.flatMap(SchoolScheduleSnapshot::warnings).toSet()
    )
    val candidates = SchoolImportMapper.map(mergedSnapshot)
    val ruleWarnings = calculateRuleCoverageWarnings(
        maxWeek = maxWeek ?: 0,
        capturedWeeks = session.capturedWeeks,
        snapshotsByWeek = session.snapshotsByWeek,
        rulesByKey = rules,
        sourceWeeksByRuleKey = session.sourceWeeksByRuleKey
    )
    val warningRuleCount = rules.values.count { rule ->
        rule.warnings.isNotEmpty() || !ruleWarnings[rule.ruleKey].isNullOrEmpty()
    }
    return MergedSemesterSchedule(
        snapshot = mergedSnapshot,
        candidates = candidates,
        capturedWeeks = session.capturedWeeks,
        missingWeeks = session.missingWeeks,
        sourceWeeksByRuleKey = session.sourceWeeksByRuleKey,
        ruleWarningsByKey = ruleWarnings,
        rawSnapshotCount = orderedSnapshots.size,
        rawRuleCount = rawRuleCount,
        crossWeekDuplicateCount = (withinSnapshotUniqueCount - rules.size).coerceAtLeast(0),
        uniqueRuleCount = rules.size,
        warningRuleCount = warningRuleCount,
        coverageComplete = session.coverageComplete,
        collectionWarnings = session.warnings
    )
}

internal fun formatWeekSet(weeks: Set<Int>): String {
    if (weeks.isEmpty()) return "—"
    val sorted = weeks.sorted()
    val ranges = mutableListOf<String>()
    var start = sorted.first()
    var end = start
    sorted.drop(1).forEach { week ->
        if (week == end + 1) {
            end = week
        } else {
            ranges += if (start == end) "$start" else "$start-$end"
            start = week
            end = week
        }
    }
    ranges += if (start == end) "$start" else "$start-$end"
    return ranges.joinToString(",")
}

internal fun SchoolScheduleSnapshot.withoutNonPreviewFields(): SchoolScheduleSnapshot = copy(
    courseRules = courseRules.map { rule ->
        rule.copy(
            rawLocation = rule.displayLocation,
            studentCount = null,
            courseOfferingCode = null,
            dateText = null,
            beginTimeText = null,
            endTimeText = null,
            liveUrl = null,
            note = null,
            sourceFieldNames = emptySet()
        )
    },
    weekStartDate = weekStartDate?.let { Date(it.time) },
    weekEndDate = weekEndDate?.let { Date(it.time) }
)
