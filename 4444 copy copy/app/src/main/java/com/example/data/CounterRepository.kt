package com.example.data

import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CounterRepository(private val counterDao: CounterDao) {

    val allDailyCounts: Flow<List<DailyCount>> = counterDao.getAllDailyCounts()
    val latestViewedItems: Flow<List<ViewedItem>> = counterDao.getLatestViewedItems()

    fun getDailyCountFlow(date: String): Flow<DailyCount?> = counterDao.getDailyCountByDateFlow(date)

    private fun getCurrentDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    suspend fun incrementCount(platform: String, creatorHandle: String) {
        val dateStr = getCurrentDateString()
        val existingCount = counterDao.getDailyCountByDate(dateStr)

        val updatedCount = if (existingCount != null) {
            if (platform.equals("Instagram", ignoreCase = true)) {
                existingCount.copy(reelsCount = existingCount.reelsCount + 1)
            } else {
                existingCount.copy(shortsCount = existingCount.shortsCount + 1)
            }
        } else {
            if (platform.equals("Instagram", ignoreCase = true)) {
                DailyCount(date = dateStr, reelsCount = 1, shortsCount = 0)
            } else {
                DailyCount(date = dateStr, reelsCount = 0, shortsCount = 1)
            }
        }

        counterDao.insertDailyCount(updatedCount)
        counterDao.insertViewedItem(
            ViewedItem(
                platform = platform,
                creatorHandle = creatorHandle
            )
        )
    }

    suspend fun clearAllData() {
        counterDao.clearViewedItems()
        counterDao.clearDailyCounts()
    }

    suspend fun deleteDailyCount(date: String) {
        counterDao.deleteDailyCount(date)
    }

    suspend fun deleteViewedItem(id: Int) {
        counterDao.deleteViewedItem(id)
    }
}
