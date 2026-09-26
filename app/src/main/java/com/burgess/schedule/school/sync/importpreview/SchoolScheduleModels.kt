package com.burgess.schedule.school.sync.importpreview

import java.util.Date

internal enum class SchoolParseWarning {
    MISSING_COURSE_NAME,
    MISSING_TEACHER,
    MISSING_LOCATION,
    SECTION_PARSE_FAILED,
    WEEK_PARSE_FAILED,
    SECTION_MISMATCH,
    WEEK_OUT_OF_RANGE,
    ODD_EVEN_CONFLICT,
    DATE_PARSE_WARNING,
    NON_OBJECT_RULE,
    SECTION_OUT_OF_APP_RANGE,
    IMPORT_DEFAULT
}

internal enum class FieldImportDefault {
    DATABASE_ID,
    COLOR,
    SHORT_NAME
}

internal enum class RuleParseStatus {
    SUCCESS,
    WARNING,
    FAILURE
}

internal data class SchoolCourseRule(
    val ruleKey: String,
    val courseCode: String?,
    val courseName: String?,
    val credits: String?,
    val teacher: String?,
    val studentCount: String?,
    val teachingClassCode: String?,
    val sectionText: String?,
    val sectionCodeText: String?,
    val startSection: Int?,
    val endSection: Int?,
    val dayOfWeek: Int,
    val campus: String?,
    val rawLocation: String?,
    val displayLocation: String?,
    val rawWeekText: String?,
    val weeks: List<Int>,
    val weekParseStatus: RuleParseStatus,
    val oddEvenText: String?,
    val teacherCode: String?,
    val teachingClassName: String?,
    val courseOfferingCode: String?,
    val dateText: String?,
    val beginTimeText: String?,
    val endTimeText: String?,
    val liveUrl: String?,
    val note: String?,
    val sourceFieldNames: Set<String>,
    val warnings: Set<SchoolParseWarning>
) {
    override fun toString(): String =
        "SchoolCourseRule(ruleKey=<redacted>, dayOfWeek=${dayOfWeek}, weeks=${weeks.size}, warnings=${warnings.size})"
}

internal data class SchoolScheduleSnapshot(
    val source: String = "SDBI",
    val academicYear: String?,
    val semesterFlag: String?,
    val currentWeek: String?,
    val maxWeek: Int?,
    val rawMaxWeek: String?,
    val weekStartDateText: String?,
    val weekEndDateText: String?,
    val weekStartDate: Date?,
    val weekEndDate: Date?,
    val maxSection: Int?,
    val rawMaxSection: String?,
    val weekItemCounts: Map<Int, Int>,
    val courseRules: List<SchoolCourseRule>,
    val rawRuleCount: Int,
    val malformedRuleCount: Int,
    val exactDuplicateCount: Int,
    val uniqueRuleCount: Int,
    val warnings: Set<SchoolParseWarning>
) {
    override fun toString(): String =
        "SchoolScheduleSnapshot(source=${source}, courseRules=${courseRules.size}, rawRuleCount=${rawRuleCount}, warnings=${warnings.size})"
}

internal data class SchoolImportCandidate(
    val id: Long?,
    val name: String,
    val teacher: String,
    val room: String,
    val dayIndex: Int,
    val period: Int?,
    val endPeriod: Int?,
    val weekNumber: Int?,
    val colorValue: Int?,
    val shortName: String?,
    val sourceRuleKey: String,
    val warnings: Set<SchoolParseWarning>,
    val defaults: Set<FieldImportDefault>,
    val importable: Boolean
) {
    override fun toString(): String =
        "SchoolImportCandidate(sourceRuleKey=<redacted>, dayIndex=${dayIndex}, weekNumber=${weekNumber}, importable=${importable})"
}

internal data class SchoolImportPreviewSummary(
    val rawRuleCount: Int,
    val malformedRuleCount: Int,
    val exactDuplicateCount: Int,
    val uniqueRuleCount: Int,
    val importableRuleCount: Int,
    val importableCount: Int,
    val warningRuleCount: Int,
    val snapshotWarningCount: Int
) {
    companion object {
        fun from(
            snapshot: SchoolScheduleSnapshot,
            candidates: List<SchoolImportCandidate>
        ): SchoolImportPreviewSummary {
            val candidateWarnings = candidates.groupBy { it.sourceRuleKey }
            return SchoolImportPreviewSummary(
                rawRuleCount = snapshot.rawRuleCount,
                malformedRuleCount = snapshot.malformedRuleCount,
                exactDuplicateCount = snapshot.exactDuplicateCount,
                uniqueRuleCount = snapshot.uniqueRuleCount,
                importableRuleCount = snapshot.courseRules.count { SchoolImportMapper.canMapRule(it, snapshot.maxWeek) },
                importableCount = candidates.count { it.importable },
                warningRuleCount = snapshot.courseRules.count { rule ->
                    rule.warnings.isNotEmpty() || candidateWarnings[rule.ruleKey].orEmpty().any { candidate ->
                        candidate.warnings.any { it != SchoolParseWarning.IMPORT_DEFAULT }
                    }
                },
                snapshotWarningCount = snapshot.warnings.size
            )
        }
    }
}

internal data class SchoolImportPreview(
    val candidates: List<SchoolImportCandidate>,
    val summary: SchoolImportPreviewSummary
)
