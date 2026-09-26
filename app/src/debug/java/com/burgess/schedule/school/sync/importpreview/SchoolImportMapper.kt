package com.burgess.schedule.school.sync.importpreview

import androidx.compose.ui.graphics.toArgb
import com.burgess.schedule.data.CourseEntity
import com.burgess.schedule.data.CourseSourceType
import com.burgess.schedule.data.SchoolSchedule
import com.burgess.schedule.ui.model.courseAccentFor

internal object SchoolImportMapper {
    fun map(snapshot: SchoolScheduleSnapshot): List<SchoolImportCandidate> =
        snapshot.courseRules.flatMap { rule -> mapRule(rule, snapshot.maxWeek) }

    fun mapRule(rule: SchoolCourseRule, maxWeek: Int?): List<SchoolImportCandidate> {
        val weeks: List<Int?> = rule.weeks.map { it }.ifEmpty { listOf(null) }
        return weeks.map { weekNumber ->
            val warnings = rule.warnings.toMutableSet()
            val sectionIsValid = rule.startSection != null &&
                rule.endSection != null &&
                rule.startSection >= 1 &&
                rule.endSection >= rule.startSection &&
                rule.endSection <= SchoolSchedule.PERIOD_COUNT &&
                SchoolParseWarning.SECTION_MISMATCH !in rule.warnings
            if (rule.startSection != null && rule.endSection != null &&
                rule.endSection > SchoolSchedule.PERIOD_COUNT
            ) {
                warnings += SchoolParseWarning.SECTION_OUT_OF_APP_RANGE
            }
            if (weekNumber != null && (weekNumber < 1 || (maxWeek != null && weekNumber > maxWeek))) {
                warnings += SchoolParseWarning.WEEK_OUT_OF_RANGE
            }
            val weekIsValid = weekNumber != null &&
                weekNumber >= 1 &&
                (maxWeek == null || weekNumber <= maxWeek) &&
                rule.weekParseStatus == RuleParseStatus.SUCCESS
            val importable = !rule.courseName.isNullOrBlank() && sectionIsValid && weekIsValid
            if (importable) warnings += SchoolParseWarning.IMPORT_DEFAULT
            SchoolImportCandidate(
                id = null,
                name = rule.courseName.orEmpty(),
                teacher = rule.teacher.orEmpty(),
                room = rule.displayLocation.orEmpty(),
                dayIndex = (rule.dayOfWeek - 1).coerceIn(0, 6),
                period = rule.startSection?.minus(1),
                endPeriod = rule.endSection?.minus(1),
                weekNumber = weekNumber,
                colorValue = null,
                shortName = null,
                sourceRuleKey = rule.ruleKey,
                warnings = warnings,
                defaults = setOf(FieldImportDefault.DATABASE_ID, FieldImportDefault.COLOR, FieldImportDefault.SHORT_NAME),
                importable = importable
            )
        }
    }

    fun canMapRule(rule: SchoolCourseRule, maxWeek: Int?): Boolean {
        if (rule.courseName.isNullOrBlank()) return false
        if (rule.startSection == null || rule.endSection == null ||
            rule.startSection < 1 || rule.endSection < rule.startSection ||
            rule.endSection > SchoolSchedule.PERIOD_COUNT ||
            SchoolParseWarning.SECTION_MISMATCH in rule.warnings
        ) return false
        if (rule.weeks.isEmpty() || rule.weekParseStatus != RuleParseStatus.SUCCESS) return false
        return rule.weeks.all { it >= 1 && (maxWeek == null || it <= maxWeek) }
    }

    fun prepare(semester: MergedSemesterSchedule): SchoolImportInput {
        val snapshot = semester.snapshot
        val academicYear = snapshot.academicYear?.trim()?.takeIf(String::isNotEmpty)
        val semesterFlag = snapshot.semesterFlag?.trim()?.takeIf(String::isNotEmpty)
        val maxWeek = snapshot.maxWeek
        val semesterKey = SchoolImportIdentity.semesterKey(academicYear, semesterFlag)
        val conflicts = mutableListOf<SchoolImportConflict>()
        data class MappedCourse(val identity: String, val ruleKey: String, val course: CourseEntity)
        val mapped = mutableListOf<MappedCourse>()
        val candidatesByRule = semester.candidates.groupBy(SchoolImportCandidate::sourceRuleKey)
        val knownRuleKeys = semester.snapshot.courseRules.mapTo(mutableSetOf(), SchoolCourseRule::ruleKey)
        val hasMetadata = academicYear != null && semesterFlag != null && semesterKey != null

        val orphanCandidates = semester.candidates.count { it.sourceRuleKey !in knownRuleKeys }
        if (orphanCandidates > 0) {
            conflicts += SchoolImportConflict(SchoolImportConflictCode.INVALID_COURSE_RULE, orphanCandidates)
        }

        if (!hasMetadata) {
            conflicts += SchoolImportConflict(SchoolImportConflictCode.MISSING_SEMESTER_METADATA)
        } else {
            semester.snapshot.courseRules.forEach { rule ->
                val externalKey = SchoolImportIdentity.externalKey(academicYear, semesterFlag, rule)
                if (externalKey == null) {
                    conflicts += SchoolImportConflict(SchoolImportConflictCode.MISSING_STABLE_IDENTITY)
                    return@forEach
                }

                val coverageWarnings = semester.ruleWarningsByKey[rule.ruleKey].orEmpty()
                if (coverageWarnings.isNotEmpty()) {
                    conflicts += SchoolImportConflict(
                        SchoolImportConflictCode.SEMESTER_RULE_COVERAGE_WARNING
                    )
                    return@forEach
                }

                val ruleCandidates = candidatesByRule[rule.ruleKey].orEmpty()
                val expectedRuleWeeks = rule.weeks.distinct().sorted()
                val mappedWeeks = ruleCandidates.mapNotNull(SchoolImportCandidate::weekNumber).distinct().sorted()
                if (!canMapRule(rule, maxWeek) || ruleCandidates.isEmpty() ||
                    ruleCandidates.any { !it.importable } || mappedWeeks != expectedRuleWeeks ||
                    ruleCandidates.any { it.period == null || it.endPeriod == null }
                ) {
                    conflicts += SchoolImportConflict(SchoolImportConflictCode.INVALID_COURSE_RULE)
                    return@forEach
                }

                val colorArgb = courseAccentFor(SchoolImportIdentity.colorSeed(rule)).toArgb()
                ruleCandidates.forEach candidateLoop@{ candidate ->
                    val week = candidate.weekNumber ?: return@candidateLoop
                    mapped += MappedCourse(externalKey, rule.ruleKey, CourseEntity(
                        id = 0L,
                        name = candidate.name,
                        teacher = candidate.teacher,
                        room = candidate.room,
                        dayIndex = candidate.dayIndex,
                        period = requireNotNull(candidate.period),
                        endPeriod = requireNotNull(candidate.endPeriod),
                        weekNumber = week,
                        colorValue = colorArgb,
                        shortName = null,
                        sourceType = CourseSourceType.SDBI,
                        sourceSemesterKey = semesterKey,
                        externalKey = externalKey
                    ))
                }
            }
        }

        val duplicateRuleIdentities = mapped.groupBy(MappedCourse::identity)
            .filterValues { rows -> rows.map(MappedCourse::ruleKey).distinct().size > 1 }
            .keys
        val duplicateOccurrences = mapped.groupBy { it.identity to it.course.weekNumber }
            .filterValues { it.size > 1 }.keys.mapTo(mutableSetOf()) { it.first }
        val duplicateKeys = duplicateRuleIdentities + duplicateOccurrences
        if (duplicateKeys.isNotEmpty()) {
            conflicts += SchoolImportConflict(
                SchoolImportConflictCode.DUPLICATE_SCHOOL_IDENTITY,
                duplicateKeys.size
            )
        }

        // Do not emit partially mapped rows for an identity that was duplicated by separate rules.
        val validCourses = mapped.filterNot { it.identity in duplicateKeys }.map(MappedCourse::course)
        val exactCoverage = maxWeek?.takeIf { it > 0 }?.let { semester.capturedWeeks == (1..it).toSet() } == true
        val coverageComplete = semester.coverageComplete && exactCoverage && semester.missingWeeks.isEmpty() &&
            semester.collectionWarnings.none {
                it == SemesterCollectionWarning.SEMESTER_METADATA_MISMATCH ||
                    it == SemesterCollectionWarning.MAX_WEEK_MISMATCH ||
                    it == SemesterCollectionWarning.INVALID_CAPTURE_WEEK ||
                    it == SemesterCollectionWarning.INVALID_SEMESTER_METADATA
            }

        return SchoolImportInput(
            academicYear = academicYear,
            semesterFlag = semesterFlag,
            maxWeek = maxWeek,
            capturedWeeks = semester.capturedWeeks,
            coverageComplete = coverageComplete,
            sourceSemesterKey = semesterKey,
            courses = validCourses,
            conflicts = conflicts
        )
    }
}
