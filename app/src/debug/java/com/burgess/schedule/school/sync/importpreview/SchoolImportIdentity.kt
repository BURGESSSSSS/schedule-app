package com.burgess.schedule.school.sync.importpreview

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Stable school identity excludes mutable teacher, room, and week-set fields. */
internal object SchoolImportIdentity {
    fun semesterKey(academicYear: String?, semesterFlag: String?): String? {
        val year = academicYear?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val semester = semesterFlag?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return digest(listOf("SDBI_TERM_V1", year, semester))
    }

    fun externalKey(
        academicYear: String?,
        semesterFlag: String?,
        rule: SchoolCourseRule
    ): String? {
        val year = academicYear?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val semester = semesterFlag?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val courseCode = rule.courseCode?.trim().orEmpty()
        val teachingClassCode = rule.teachingClassCode?.trim().orEmpty()
        if (courseCode.isEmpty() && teachingClassCode.isEmpty()) return null
        val start = rule.startSection ?: return null
        val end = rule.endSection ?: return null
        if (rule.dayOfWeek !in 1..7 || start !in 1..8 || end !in start..8) return null

        return digest(
            listOf(
                "SDBI_RULE_V1",
                year,
                semester,
                courseCode,
                teachingClassCode,
                rule.dayOfWeek.toString(),
                start.toString(),
                end.toString()
            )
        )
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
