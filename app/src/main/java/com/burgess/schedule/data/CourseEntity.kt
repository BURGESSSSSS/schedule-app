package com.burgess.schedule.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "courses")
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
    val shortName: String? = null
)
