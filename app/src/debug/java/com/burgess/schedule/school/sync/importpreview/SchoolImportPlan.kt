package com.burgess.schedule.school.sync.importpreview

import com.burgess.schedule.data.CourseEntity

internal enum class SchoolImportConflictCode {
    MISSING_SEMESTER_METADATA,
    MISSING_STABLE_IDENTITY,
    INVALID_COURSE_RULE,
    SEMESTER_RULE_COVERAGE_WARNING,
    DUPLICATE_SCHOOL_IDENTITY,
    DUPLICATE_STORED_IDENTITY,
    MISSING_STORED_IDENTITY,
    EMPTY_SCHOOL_SEMESTER
}

internal data class SchoolImportConflict(
    val code: SchoolImportConflictCode,
    val count: Int = 1
)

/** A local-only, immutable input for planning and for revalidation at confirmation time. */
internal data class SchoolImportInput(
    val academicYear: String?,
    val semesterFlag: String?,
    val maxWeek: Int?,
    val capturedWeeks: Set<Int>,
    val coverageComplete: Boolean,
    val sourceSemesterKey: String?,
    val courses: List<CourseEntity>,
    val conflicts: List<SchoolImportConflict> = emptyList()
)

internal data class SchoolCourseUpdate(
    val existing: CourseEntity,
    val replacement: CourseEntity
)

internal enum class SchoolImportBlockReason {
    INCOMPLETE_SEMESTER,
    MISSING_SEMESTER_METADATA,
    FATAL_CONFLICTS
}

internal data class SchoolImportPlan(
    val input: SchoolImportInput,
    val toInsert: List<CourseEntity>,
    val toUpdate: List<SchoolCourseUpdate>,
    val unchanged: List<CourseEntity>,
    val missingFromSchool: List<CourseEntity>,
    val conflicts: List<SchoolImportConflict>,
    val blockReason: SchoolImportBlockReason?,
    val signature: String
) {
    val canConfirm: Boolean
        get() = blockReason == null && conflicts.isEmpty()

    val conflictCount: Int
        get() = conflicts.sumOf(SchoolImportConflict::count)
}

internal sealed interface SchoolImportExecutionResult {
    data class Success(
        val inserted: Int,
        val updated: Int,
        val unchanged: Int,
        val missingFromSchool: Int
    ) : SchoolImportExecutionResult

    data object StalePlan : SchoolImportExecutionResult

    data class Blocked(val reason: SchoolImportBlockReason) : SchoolImportExecutionResult

    data class Failed(val exceptionType: String) : SchoolImportExecutionResult
}
