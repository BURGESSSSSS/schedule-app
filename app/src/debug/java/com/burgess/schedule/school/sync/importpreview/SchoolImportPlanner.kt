package com.burgess.schedule.school.sync.importpreview

import com.burgess.schedule.data.CourseEntity
import com.burgess.schedule.data.CourseSourceType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Pure planner. It only considers SDBI records for the same academic semester. */
internal object SchoolImportPlanner {
    fun create(input: SchoolImportInput, existingForSemester: List<CourseEntity>): SchoolImportPlan {
        val conflicts = input.conflicts.toMutableList()
        val maxWeek = input.maxWeek
        val expectedWeeks = maxWeek?.takeIf { it > 0 }?.let { (1..it).toSet() }.orEmpty()
        val complete = input.coverageComplete && expectedWeeks.isNotEmpty() &&
            input.capturedWeeks == expectedWeeks
        val blockReason = when {
            !complete -> SchoolImportBlockReason.INCOMPLETE_SEMESTER
            input.sourceSemesterKey == null || input.academicYear.isNullOrBlank() || input.semesterFlag.isNullOrBlank() ->
                SchoolImportBlockReason.MISSING_SEMESTER_METADATA
            else -> null
        }

        if (input.sourceSemesterKey == null || input.academicYear.isNullOrBlank() || input.semesterFlag.isNullOrBlank()) {
            if (conflicts.none { it.code == SchoolImportConflictCode.MISSING_SEMESTER_METADATA }) {
                conflicts += SchoolImportConflict(SchoolImportConflictCode.MISSING_SEMESTER_METADATA)
            }
        }
        if (input.courses.isEmpty() && complete) {
            conflicts += SchoolImportConflict(SchoolImportConflictCode.EMPTY_SCHOOL_SEMESTER)
        }

        if (!complete || input.sourceSemesterKey == null) {
            return SchoolImportPlan(
                input = input,
                toInsert = emptyList(),
                toUpdate = emptyList(),
                unchanged = emptyList(),
                missingFromSchool = emptyList(),
                conflicts = aggregateConflicts(conflicts),
                blockReason = blockReason ?: SchoolImportBlockReason.FATAL_CONFLICTS,
                signature = signature(input, emptyList(), emptyList(), emptyList(), emptyList(), conflicts)
            )
        }

        val planned = input.courses.filter { it.sourceType == CourseSourceType.SDBI }
        val candidatesWithoutIdentity = planned.count { it.externalKey.isNullOrBlank() }
        if (candidatesWithoutIdentity > 0) {
            conflicts += SchoolImportConflict(
                SchoolImportConflictCode.MISSING_STABLE_IDENTITY,
                candidatesWithoutIdentity
            )
        }
        val candidateDuplicates = planned.filter { !it.externalKey.isNullOrBlank() }
            .groupBy { it.externalKey to it.weekNumber }
            .filterValues { it.size > 1 }
        val conflictingKeys = candidateDuplicates.keys.mapTo(mutableSetOf()) { requireNotNull(it.first) }
        if (candidateDuplicates.isNotEmpty()) {
            conflicts += SchoolImportConflict(
                SchoolImportConflictCode.DUPLICATE_SCHOOL_OCCURRENCE_IDENTITY,
                candidateDuplicates.size
            )
        }

        val existing = existingForSemester.filter {
            it.sourceType == CourseSourceType.SDBI && it.sourceSemesterKey == input.sourceSemesterKey
        }
        val existingWithoutIdentity = existing.count { it.externalKey.isNullOrBlank() }
        if (existingWithoutIdentity > 0) {
            conflicts += SchoolImportConflict(
                SchoolImportConflictCode.MISSING_STORED_IDENTITY,
                existingWithoutIdentity
            )
        }
        val existingDuplicates = existing.filter { !it.externalKey.isNullOrBlank() }
            .groupBy { it.externalKey to it.weekNumber }
            .filterValues { it.size > 1 }
        val existingConflictKeys = existingDuplicates.keys.mapTo(mutableSetOf()) { requireNotNull(it.first) }
        if (existingDuplicates.isNotEmpty()) {
            conflicts += SchoolImportConflict(
                SchoolImportConflictCode.DUPLICATE_STORED_IDENTITY,
                existingDuplicates.size
            )
        }

        val candidateGroups = planned.filter {
            !it.externalKey.isNullOrBlank() && it.externalKey !in conflictingKeys
        }.groupBy { requireNotNull(it.externalKey) }
        val existingGroups = existing.filter { !it.externalKey.isNullOrBlank() }.groupBy { requireNotNull(it.externalKey) }
        val insert = mutableListOf<CourseEntity>()
        val update = mutableListOf<SchoolCourseUpdate>()
        val unchanged = mutableListOf<CourseEntity>()
        val missing = mutableListOf<CourseEntity>()
        (candidateGroups.keys + existingGroups.keys.filterNotNull()).toSortedSet().forEach { key ->
            if (key in existingConflictKeys || key in conflictingKeys) {
                return@forEach
            }
            val newRows = candidateGroups[key].orEmpty().sortedWith(compareBy<CourseEntity> { it.weekNumber }.thenBy { it.dayIndex }.thenBy { it.period })
            val oldRows = existingGroups[key].orEmpty().sortedWith(compareBy<CourseEntity> { it.weekNumber }.thenBy { it.id })
            if (oldRows.isEmpty()) {
                insert += newRows
                return@forEach
            }
            if (newRows.isEmpty()) {
                missing += oldRows
                return@forEach
            }

            val oldByWeek = oldRows.associateBy { it.weekNumber }
            val newByWeek = newRows.associateBy { it.weekNumber }
            val commonWeeks = oldByWeek.keys intersect newByWeek.keys
            commonWeeks.sorted().forEach { week ->
                val old = oldByWeek.getValue(week)
                val new = newByWeek.getValue(week)
                if (sameImportedContent(old, new)) {
                    unchanged += old
                } else {
                    update += SchoolCourseUpdate(old, new.copy(id = old.id))
                }
            }

            val oldUnmatched = oldRows.filter { it.weekNumber !in commonWeeks }.toMutableList()
            val newUnmatched = newRows.filter { it.weekNumber !in commonWeeks }.toMutableList()
            val pairCount = minOf(oldUnmatched.size, newUnmatched.size)
            repeat(pairCount) {
                val old = oldUnmatched.removeAt(0)
                val new = newUnmatched.removeAt(0)
                update += SchoolCourseUpdate(old, new.copy(id = old.id))
            }
            insert += newUnmatched
            missing += oldUnmatched
        }

        // Mapping conflicts make absence claims unsafe; never present them as delete candidates.
        val safeMissing = if (conflicts.any { it.code in FATAL_MAPPING_CONFLICTS }) emptyList() else missing
        val finalConflicts = aggregateConflicts(conflicts)
        val finalBlock = when {
            blockReason != null -> blockReason
            finalConflicts.isNotEmpty() -> SchoolImportBlockReason.FATAL_CONFLICTS
            else -> null
        }
        val planSignature = signature(input, insert, update, unchanged, safeMissing, finalConflicts, existing)
        return SchoolImportPlan(
            input = input,
            toInsert = insert,
            toUpdate = update,
            unchanged = unchanged,
            missingFromSchool = safeMissing,
            conflicts = finalConflicts,
            blockReason = finalBlock,
            signature = planSignature
        )
    }

    private fun sameImportedContent(old: CourseEntity, new: CourseEntity): Boolean =
        old.copy(id = 0L) == new.copy(id = 0L)

    private fun aggregateConflicts(conflicts: List<SchoolImportConflict>): List<SchoolImportConflict> =
        conflicts.groupBy(SchoolImportConflict::code)
            .map { (code, occurrences) -> SchoolImportConflict(code, occurrences.sumOf(SchoolImportConflict::count)) }
            .sortedBy(SchoolImportConflict::code)

    private fun signature(
        input: SchoolImportInput,
        inserts: List<CourseEntity>,
        updates: List<SchoolCourseUpdate>,
        unchanged: List<CourseEntity>,
        missing: List<CourseEntity>,
        conflicts: List<SchoolImportConflict>,
        existing: List<CourseEntity> = emptyList()
    ): String {
        val fields = buildList {
            add(input.academicYear.orEmpty())
            add(input.semesterFlag.orEmpty())
            add(input.sourceSemesterKey.orEmpty())
            add(input.maxWeek?.toString().orEmpty())
            add(input.capturedWeeks.sorted().joinToString(","))
            add(input.coverageComplete.toString())
            input.courses.sortedWith(compareBy<CourseEntity> { it.externalKey }.thenBy { it.weekNumber })
                .forEach { add(it.canonicalFields(includeId = false)) }
            existing.sortedWith(compareBy<CourseEntity> { it.externalKey }.thenBy { it.weekNumber }.thenBy { it.id })
                .forEach { add(it.canonicalFields(includeId = true)) }
            inserts.sortedWith(compareBy<CourseEntity> { it.externalKey }.thenBy { it.weekNumber })
                .forEach { add("I:${it.canonicalFields(includeId = false)}") }
            updates.sortedBy { it.existing.id }.forEach {
                add("U:${it.existing.canonicalFields(includeId = true)}:${it.replacement.canonicalFields(includeId = false)}")
            }
            unchanged.sortedBy { it.id }.forEach { add("N:${it.canonicalFields(includeId = true)}") }
            missing.sortedBy { it.id }.forEach { add("M:${it.canonicalFields(includeId = true)}") }
            conflicts.sortedBy { it.code.name }.forEach { add("C:${it.code.name}:${it.count}") }
        }
        val canonical = fields.joinToString("") { "${it.length}:$it;" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private fun CourseEntity.canonicalFields(includeId: Boolean): String = buildList {
        if (includeId) add(id.toString())
        add(name)
        add(teacher)
        add(room)
        add(dayIndex.toString())
        add(period.toString())
        add(endPeriod.toString())
        add(weekNumber.toString())
        add(colorValue.toString())
        add(shortName.orEmpty())
        add(sourceType)
        add(sourceSemesterKey.orEmpty())
        add(externalKey.orEmpty())
    }.joinToString("") { "${it.length}:$it;" }

    private val FATAL_MAPPING_CONFLICTS = setOf(
        SchoolImportConflictCode.MISSING_SEMESTER_METADATA,
        SchoolImportConflictCode.MISSING_STABLE_IDENTITY,
        SchoolImportConflictCode.INVALID_COURSE_RULE,
        SchoolImportConflictCode.SEMESTER_RULE_COVERAGE_WARNING,
        SchoolImportConflictCode.DUPLICATE_SCHOOL_OCCURRENCE_IDENTITY,
        SchoolImportConflictCode.DUPLICATE_STORED_IDENTITY,
        SchoolImportConflictCode.MISSING_STORED_IDENTITY,
        SchoolImportConflictCode.EMPTY_SCHOOL_SEMESTER
    )
}
