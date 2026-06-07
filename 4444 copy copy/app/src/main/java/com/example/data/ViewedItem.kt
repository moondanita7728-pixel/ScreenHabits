package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "viewed_items")
data class ViewedItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val platform: String, // "Instagram" or "YouTube"
    val creatorHandle: String, // e.g., "travel_wild" or "@mkbhd"
    val timestamp: Long = System.currentTimeMillis()
)
