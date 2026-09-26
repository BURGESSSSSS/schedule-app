package com.burgess.schedule.backup

import com.burgess.schedule.data.CourseEntity
import com.burgess.schedule.data.CourseSourceType

internal data class ScheduleBackupMergePlan(
    val toInsert: List<CourseEntity>,
    val toUpdate: List<CourseEntity>,
    val unchanged: Int
)

internal object ScheduleBackupPlanner {
    fun create(
        existing: List<CourseEntity>,
        incoming: List<ScheduleBackupCourse>
    ): ScheduleBackupMergePlan {
        val working = existing.toMutableList()
        val insertions = mutableListOf<CourseEntity>()
        val updates = linkedMapOf<Long, CourseEntity>()
        var unchanged = 0

        incoming.forEach { backupCourse ->
            val candidate = backupCourse.toEntity(id = 0L)
            val matchingIndices = working.indices.filter { index ->
                val current = working[index]
                if (backupCourse.sourceType == CourseSourceType.SDBI) {
                    current.sourceType == CourseSourceType.SDBI &&
                        current.sourceSemesterKey == backupCourse.sourceSemesterKey &&
                        current.externalKey == backupCourse.externalKey
                } else {
                    current.sourceType == CourseSourceType.NON_SDBI &&
                        current.toBackupFingerprint() == backupCourse.nonSchoolFingerprint()
                }
            }
            check(matchingIndices.size <= 1) { "Existing courses contain a duplicate backup identity" }

            val matchIndex = matchingIndices.singleOrNull()
            if (matchIndex == null) {
                insertions += candidate
                working += candidate
                return@forEach
            }

            val existingCourse = working[matchIndex]
            val replacement = candidate.copy(id = existingCourse.id)
            if (sameBackupContent(existingCourse, replacement)) {
                unchanged += 1
            } else {
                working[matchIndex] = replacement
                if (existingCourse.id == 0L) {
                    val insertionIndex = insertions.indexOfFirst {
                        if (backupCourse.sourceType == CourseSourceType.SDBI) {
                            it.sourceType == CourseSourceType.SDBI &&
                                it.sourceSemesterKey == backupCourse.sourceSemesterKey &&
                                it.externalKey == backupCourse.externalKey
                        } else {
                            it.toBackupFingerprint() == backupCourse.nonSchoolFingerprint()
                        }
                    }
                    if (insertionIndex >= 0) insertions[insertionIndex] = replacement
                } else {
                    updates[existingCourse.id] = replacement
                }
            }
        }

        return ScheduleBackupMergePlan(insertions, updates.values.toList(), unchanged)
    }

    private fun CourseEntity.toBackupFingerprint(): String = ScheduleBackupCourse(
        name = name,
        teacher = teacher,
        room = room,
        dayIndex = dayIndex,
        period = period,
        endPeriod = endPeriod,
        weekNumber = weekNumber,
        colorValue = colorValue,
        shortName = shortName,
        sourceType = sourceType,
        sourceSemesterKey = sourceSemesterKey,
        externalKey = externalKey
    ).nonSchoolFingerprint()

    private fun sameBackupContent(existing: CourseEntity, incoming: CourseEntity): Boolean {
        // A zero legacy color renders from the Room ID. The backup exports that effective color,
        // so treat it as equivalent during merge while still preserving it for a later restore.
        val existingColorForComparison = if (existing.colorValue == 0) incoming.colorValue else existing.colorValue
        return existing.copy(colorValue = existingColorForComparison) == incoming
    }
}
