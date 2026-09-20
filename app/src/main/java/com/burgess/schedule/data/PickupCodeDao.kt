package com.burgess.schedule.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PickupCodeDao {
    @Query("SELECT * FROM pickup_codes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PickupCodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(codes: List<PickupCodeEntity>)

    @Query("SELECT * FROM pickup_codes WHERE code = :code AND station = :station LIMIT 1")
    suspend fun findByCodeAndStation(code: String, station: String): PickupCodeEntity?

    @Query("UPDATE pickup_codes SET picked = :picked WHERE id = :id")
    suspend fun setPicked(id: Long, picked: Boolean)

    @Query("DELETE FROM pickup_codes WHERE picked = 1")
    suspend fun clearPicked()
}
