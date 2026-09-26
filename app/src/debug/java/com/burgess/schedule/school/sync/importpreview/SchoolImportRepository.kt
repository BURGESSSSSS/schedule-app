package com.burgess.schedule.school.sync.importpreview

import androidx.room.withTransaction
import com.burgess.schedule.data.CourseDatabase
import com.burgess.schedule.data.CourseEntity
import com.burgess.schedule.data.CourseSourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Database boundary for the explicit, local-only SDBI import flow. */
internal class SchoolImportRepository(
    private val database: CourseDatabase
) {
    private val dao get() = database.courseDao()

    suspend fun prepare(input: SchoolImportInput): SchoolImportPlan = withContext(Dispatchers.IO) {
        val semesterKey = input.sourceSemesterKey
        val existing = if (semesterKey == null) {
            emptyList()
        } else {
            dao.getBySourceSemester(CourseSourceType.SDBI, semesterKey)
        }
        SchoolImportPlanner.create(input, existing)
    }

    suspend fun import(plan: SchoolImportPlan): SchoolImportExecutionResult {
        if (!plan.canConfirm) {
            return SchoolImportExecutionResult.Blocked(
                plan.blockReason ?: SchoolImportBlockReason.FATAL_CONFLICTS
            )
        }

        return try {
            withContext(Dispatchers.IO) {
                database.withTransaction {
                    val semesterKey = requireNotNull(plan.input.sourceSemesterKey)
                    val currentExisting = dao.getBySourceSemester(CourseSourceType.SDBI, semesterKey)
                    val currentPlan = SchoolImportPlanner.create(plan.input, currentExisting)
                    if (!currentPlan.canConfirm) {
                        return@withTransaction SchoolImportExecutionResult.Blocked(
                            currentPlan.blockReason ?: SchoolImportBlockReason.FATAL_CONFLICTS
                        )
                    }
                    if (currentPlan.signature != plan.signature) {
                        return@withTransaction SchoolImportExecutionResult.StalePlan
                    }

                    var nextId = dao.maxId() ?: 0L
                    currentPlan.toInsert.forEach { candidate ->
                        check(nextId < Long.MAX_VALUE) { "Course ID space exhausted" }
                        nextId += 1L
                        dao.insertImportedCourse(candidate.copy(id = nextId))
                    }
                    currentPlan.toUpdate.forEach { update ->
                        val rows = dao.updateImportedCourse(update.replacement.copy(id = update.existing.id))
                        check(rows == 1) { "An SDBI course changed during import" }
                    }

                    SchoolImportExecutionResult.Success(
                        inserted = currentPlan.toInsert.size,
                        updated = currentPlan.toUpdate.size,
                        unchanged = currentPlan.unchanged.size,
                        missingFromSchool = currentPlan.missingFromSchool.size
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            SchoolImportExecutionResult.Failed(
                exception.javaClass.simpleName.ifBlank { "DatabaseException" }
            )
        }
    }
}
