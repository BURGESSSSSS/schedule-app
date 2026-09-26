package com.burgess.schedule.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "courses",
    indices = [Index(value = ["sourceType", "sourceSemesterKey", "externalKey"])]
)
data class CourseEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val teacher: String,
    val room: String,
    val dayIndex: Int,
    val period: Int,
    @ColumnInfo(defaultValue = "0") val endPeriod: Int,
    @ColumnInfo(defaultValue = "0") val weekNumber: Int,
    val colorValue: Int,
    val shortName: String? = null,
    @ColumnInfo(defaultValue = "'NON_SDBI'") val sourceType: String = CourseSourceType.NON_SDBI,
    val sourceSemesterKey: String? = null,
    val externalKey: String? = null
)

object CourseSourceType {
    const val NON_SDBI = "NON_SDBI"
    const val SDBI = "SDBI"
}
