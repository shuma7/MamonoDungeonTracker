package com.mamotra.tracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface BattleDao {
    @Insert
    suspend fun insert(record: BattleRecord): Long

    @Query("SELECT * FROM battle_records ORDER BY timestamp DESC")
    suspend fun getAllRecords(): List<BattleRecord>

    @Query("SELECT COUNT(*) FROM battle_records WHERE result = 'WIN'")
    suspend fun getWinCount(): Int

    @Query("SELECT COUNT(*) FROM battle_records")
    suspend fun getTotalCount(): Int
}
