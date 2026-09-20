package com.burgess.schedule.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pickup_codes")
data class PickupCodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val station: String,
    val status: String,
    val picked: Boolean,
    val createdAt: Long
)
