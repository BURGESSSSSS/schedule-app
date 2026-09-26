package com.burgess.schedule.backup

import androidx.room.withTransaction
import com.burgess.schedule.data.CourseDatabase
import com.burgess.schedule.data.CourseEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class ScheduleBackupImportMode { MERGE, RESTORE }

internal data class ScheduleBackupImportResult(
    val mode: ScheduleBackupImportMode,
    val inserted: Int = 0,
    val updated: Int = 0,
    val deduplicated: Int = 0,
    val restored: Int = 0
)

internal class ScheduleBackupRepository(private val database: CourseDatabase) {
    private val dao get() = database.courseDao()

    suspend fun export(appVersion: String, exportedAt: String): String = withContext(Dispatchers.IO) {
        ScheduleBackupCodec.encode(dao.getAll(), appVersion, exportedAt)
    }

    suspend fun courseCount(): Int = withContext(Dispatchers.IO) { dao.count() }

    suspend fun importBackup(
        backup: ScheduleBackup,
        mode: ScheduleBackupImportMode
    ): ScheduleBackupImportResult = withContext(Dispatchers.IO) {
        require(backup.schemaVersion == SCHEDULE_BACKUP_SCHEMA_VERSION) { "Unsupported backup schema" }
        require(backup.courses.none {
            it.sourceType == com.burgess.schedule.data.CourseSourceType.SDBI &&
                (it.sourceSemesterKey.isNullOrBlank() || it.externalKey.isNullOrBlank())
        }) { "School course is missing its source identity" }
        val schoolIdentities = backup.courses.mapNotNull(ScheduleBackupCourse::schoolIdentity)
        require(schoolIdentities.size == schoolIdentities.toSet().size) { "Backup contains duplicate school identities" }
        database.withTransaction {
            when (mode) {
                ScheduleBackupImportMode.MERGE -> merge(backup)
                ScheduleBackupImportMode.RESTORE -> restore(backup)
            }
        }
    }

    private suspend fun merge(backup: ScheduleBackup): ScheduleBackupImportResult {
        val plan = ScheduleBackupPlanner.create(dao.getAll(), backup.courses)
        var currentId = dao.maxId() ?: 0L
        val inserts = plan.toInsert.map { course ->
            check(currentId < Long.MAX_VALUE) { "Course ID space exhausted" }
            currentId += 1L
            course.copy(id = currentId)
        }
        if (inserts.isNotEmpty()) dao.insertBackupCourses(inserts)
        plan.toUpdate.forEach { course ->
            check(dao.updateImportedCourse(course) == 1) { "A course changed during backup import" }
        }
        return ScheduleBackupImportResult(
            mode = ScheduleBackupImportMode.MERGE,
            inserted = inserts.size,
            updated = plan.toUpdate.size,
            deduplicated = plan.unchanged
        )
    }

    private suspend fun restore(backup: ScheduleBackup): ScheduleBackupImportResult {
        dao.clearAllCourses()
        var next = 0L
        val restored = backup.courses.map { course ->
            check(next < Long.MAX_VALUE) { "Course ID space exhausted" }
            next += 1L
            course.toEntity(next)
        }
        if (restored.isNotEmpty()) dao.insertBackupCourses(restored)
        return ScheduleBackupImportResult(
            mode = ScheduleBackupImportMode.RESTORE,
            restored = restored.size
        )
    }
}
