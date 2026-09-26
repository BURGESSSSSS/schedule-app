package com.burgess.schedule.school.sync.importpreview

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** Stable identity for one school course occurrence; mutable content is deliberately excluded. */
internal object SchoolOccurrenceIdentity {
    fun externalKey(occurrence: SchoolCourseOccurrence): String? {
        val year = occurrence.academicYear.trim().takeIf(String::isNotEmpty) ?: return null
        val semester = occurrence.semesterFlag.trim().takeIf(String::isNotEmpty) ?: return null
        if (occurrence.week < 1 || occurrence.dayOfWeek !in 1..7) return null
        if (occurrence.startSection !in 1..8 || occurrence.endSection !in occurrence.startSection..8) return null

        val stableCourseKey = occurrence.courseCode.normalizeIdentityValue()
            ?.let { "CODE:$it" }
            ?: occurrence.courseName.normalizeIdentityValue()?.let { "NAME:$it" }
            ?: return null
        val stableTeachingClassKey = occurrence.teachingClassCode.normalizeIdentityValue()
            ?.let { "CODE:$it" }
            ?: occurrence.teachingClassName.normalizeIdentityValue()?.let { "NAME:$it" }
            ?: return null

        return digest(
            listOf(
                "SDBI_OCCURRENCE_V1",
                year,
                semester,
                stableCourseKey,
                stableTeachingClassKey,
                occurrence.week.toString(),
                occurrence.dayOfWeek.toString(),
                occurrence.startSection.toString(),
                occurrence.endSection.toString()
            )
        )
    }

    private fun String?.normalizeIdentityValue(): String? = this
        ?.trim()
        ?.replace(WHITESPACE, " ")
        ?.lowercase(Locale.ROOT)
        ?.takeIf(String::isNotEmpty)

    private fun digest(fields: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(fields).toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun canonical(fields: List<String>): String =
        fields.joinToString(separator = "") { "${it.length}:$it;" }

    private val WHITESPACE = Regex("\\s+")
}
