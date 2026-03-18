package com.mamotra.tracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "battle_records")
data class BattleRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val result: String,       // "WIN" or "LOSE"
    val myRating: Int,
    val opponentName: String,
    val opponentRating: Int,
    val trophyChange: Int
)
