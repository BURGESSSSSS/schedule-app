package com.burgess.schedule.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses ORDER BY dayIndex ASC, period ASC")
    fun observeAll(): Flow<List<CourseEntity>>

    @Query("SELECT COUNT(*) FROM courses")
    suspend fun count(): Int

    @Query("DELETE FROM courses WHERE weekNumber = :weekNumber")
    suspend fun clearWeek(weekNumber: Int)

    @Query("SELECT * FROM courses ORDER BY dayIndex ASC, period ASC")
    suspend fun getAll(): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE sourceType = :sourceType AND sourceSemesterKey = :semesterKey")
    suspend fun getBySourceSemester(sourceType: String, semesterKey: String): List<CourseEntity>

    @Query("SELECT MAX(id) FROM courses")
    suspend fun maxId(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(course: CourseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(courses: List<CourseEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertImportedCourse(course: CourseEntity)

    @Update
    suspend fun updateImportedCourse(course: CourseEntity): Int

    /**
     * Replaces one week's imported courses atomically.  Keeping the delete and
     * insert in the same Room transaction prevents a failed import from
     * leaving the user with an empty week.
     */
    @Transaction
    suspend fun replaceWeek(weekNumber: Int, courses: List<CourseEntity>) {
        clearWeek(weekNumber)
        if (courses.isNotEmpty()) {
            insertAll(courses)
        }
    }

    @Delete
    suspend fun delete(course: CourseEntity)
}
