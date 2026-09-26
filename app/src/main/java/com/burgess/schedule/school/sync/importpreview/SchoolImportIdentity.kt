package com.burgess.schedule.school.sync.importpreview

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Semester identity and stable course color seed. Occurrence keys live in SchoolOccurrenceIdentity. */
internal object SchoolImportIdentity {
    fun semesterKey(academicYear: String?, semesterFlag: String?): String? {
        val year = academicYear?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val semester = semesterFlag?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return digest(listOf("SDBI_TERM_V1", year, semester))
    }

    /** Keeps the existing ID-derived course palette stable across weeks and imports. */
    fun colorSeed(rule: SchoolCourseRule): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            canonical(
                listOf(
                    "SDBI_COLOR_V1",
                    rule.courseCode?.trim().orEmpty(),
                    rule.teachingClassCode?.trim().orEmpty()
                )
            ).toByteArray(StandardCharsets.UTF_8)
        )
        return digest.take(Long.SIZE_BYTES).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xff) }
    }

    private fun digest(fields: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(fields).toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun canonical(fields: List<String>): String =
        fields.joinToString(separator = "") { "${it.length}:$it;" }
}
