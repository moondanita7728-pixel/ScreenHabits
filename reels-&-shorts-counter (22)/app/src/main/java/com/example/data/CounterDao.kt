package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CounterDao {
    @Query("SELECT * FROM daily_counts ORDER BY date DESC")
    fun getAllDailyCounts(): Flow<List<DailyCount>>

    @Query("SELECT * FROM daily_counts WHERE date = :date LIMIT 1")
    suspend fun getDailyCountByDate(date: String): DailyCount?

    @Query("SELECT * FROM daily_counts WHERE date = :date LIMIT 1")
    fun getDailyCountByDateFlow(date: String): Flow<DailyCount?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDailyCount(dailyCount: DailyCount)

    @Query("SELECT * FROM viewed_items ORDER BY timestamp DESC LIMIT 50")
    fun getLatestViewedItems(): Flow<List<ViewedItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertViewedItem(viewedItem: ViewedItem)

    @Query("DELETE FROM viewed_items")
    suspend fun clearViewedItems()

    @Query("DELETE FROM daily_counts")
    suspend fun clearDailyCounts()

    @Query("DELETE FROM daily_counts WHERE date = :date")
    suspend fun deleteDailyCount(date: String)

    @Query("DELETE FROM viewed_items WHERE id = :id")
    suspend fun deleteViewedItem(id: Int)
}
