package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_counts")
data class DailyCount(
    @PrimaryKey val date: String, // String format yyyy-MM-dd
    val reelsCount: Int = 0,
    val shortsCount: Int = 0
)
