package com.burgess.schedule.school.sync.importpreview

/** Database-sized unit: one concrete course meeting in one teaching week. */
internal data class SchoolCourseOccurrence(
    val academicYear: String,
    val semesterFlag: String,
    val week: Int,
    val courseName: String,
    val courseCode: String?,
    val teachingClassCode: String?,
    val teachingClassName: String?,
    val teacher: String?,
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    val location: String?,
    val campus: String?,
    val sourceRuleKey: String
) {
    /** CourseEntity has one room field, so retain campus there as mutable imported content. */
    fun databaseRoomContent(): String {
        val place = location?.trim()?.takeIf(String::isNotEmpty)
        val site = campus?.trim()?.takeIf(String::isNotEmpty)
        return when {
            place == null -> site.orEmpty()
            site == null || place.contains(site, ignoreCase = true) -> place
            else -> "$site · $place"
        }
    }

    override fun toString(): String =
        "SchoolCourseOccurrence(week=$week, dayOfWeek=$dayOfWeek, sections=$startSection-$endSection, sourceRuleKey=<redacted>)"
}

internal data class SchoolOccurrenceIdentityCollision(
    val identity: String,
    val occurrences: List<SchoolCourseOccurrence>
)
