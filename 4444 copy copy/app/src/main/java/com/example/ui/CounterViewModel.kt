package com.example.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.service.ReelShortCounterAccessibilityService
import com.example.data.CounterRepository
import com.example.data.DailyCount
import com.example.data.ViewedItem
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CounterViewModel(private val repository: CounterRepository) : ViewModel() {

    // Service active state flow
    private val _isServiceActive = MutableStateFlow(false)
    val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

    // Overlay permission state flow
    private val _isOverlayAllowed = MutableStateFlow(false)
    val isOverlayAllowed: StateFlow<Boolean> = _isOverlayAllowed.asStateFlow()

    // Battery optimization exemption state flow
    private val _isBatteryExempted = MutableStateFlow(true)
    val isBatteryExempted: StateFlow<Boolean> = _isBatteryExempted.asStateFlow()

    // Persistent toggle to stop/start counting instantly inside the app
    private val _isTrackingEnabled = MutableStateFlow(true)
    val isTrackingEnabled: StateFlow<Boolean> = _isTrackingEnabled.asStateFlow()

    // Flow of daily counts
    val dailyCounts: StateFlow<List<DailyCount>> = repository.allDailyCounts
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Flow of latest viewed items for the real-time log
    val latestViewedItems: StateFlow<List<ViewedItem>> = repository.latestViewedItems
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Current day's specific count
    private val _todayCount = MutableStateFlow<DailyCount?>(null)
    val todayCount: StateFlow<DailyCount?> = _todayCount.asStateFlow()

    private var todayCountCollectJob: kotlinx.coroutines.Job? = null
    private var currentCollectedDate: String? = null

    private fun startCollectingTodayCount() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (currentCollectedDate != todayStr) {
            currentCollectedDate = todayStr
            todayCountCollectJob?.cancel()
            todayCountCollectJob = viewModelScope.launch {
                repository.getDailyCountFlow(todayStr).collect { count ->
                    _todayCount.value = count
                }
            }
        }
    }

    init {
        startCollectingTodayCount()
        // Collect safety status changes from the service
        viewModelScope.launch {
            ReelShortCounterAccessibilityService.serviceStateChanged.collect { running ->
                _isServiceActive.value = running
            }
        }
    }

    /**
     * Checks if the service is enabled in System Settings.
     * This is useful to refresh when resuming.
     */
    fun checkAccessibilityStatus(context: Context) {
        startCollectingTodayCount()
        val isRunning = ReelShortCounterAccessibilityService.isServiceRunning
        
        // Secondary verify via Settings
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        
        val isEnabledInSettings = enabledServices.contains(context.packageName)
        
        _isServiceActive.value = isRunning || isEnabledInSettings
        _isOverlayAllowed.value = Settings.canDrawOverlays(context)

        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        _isBatteryExempted.value = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } else {
            true
        }

        // Read the latest persistent state of tracking toggle
        val prefs = context.getSharedPreferences("counter_prefs", Context.MODE_PRIVATE)
        _isTrackingEnabled.value = prefs.getBoolean("tracking_enabled", true)
    }

    /**
     * Toggles tracking state between start and stop.
     */
    fun toggleTracking(context: Context) {
        val prefs = context.getSharedPreferences("counter_prefs", Context.MODE_PRIVATE)
        val current = prefs.getBoolean("tracking_enabled", true)
        val target = !current
        prefs.edit().putBoolean("tracking_enabled", target).apply()
        _isTrackingEnabled.value = target

        // Update active service instance immediately
        ReelShortCounterAccessibilityService.updateTrackingState(context, target)
        sendSettingsBroadcast(context)
    }

    /**
     * Resets the repository counter database cleanly.
     */
    fun clearData() {
        viewModelScope.launch {
            repository.clearAllData()
        }
    }

    fun deleteDailyCount(date: String) {
        viewModelScope.launch {
            repository.deleteDailyCount(date)
        }
    }

    fun deleteViewedItem(id: Int) {
        viewModelScope.launch {
            repository.deleteViewedItem(id)
        }
    }

    class Factory(private val repository: CounterRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(CounterViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return CounterViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    companion object {
        fun sendSettingsBroadcast(context: Context) {
            val prefs = context.getSharedPreferences("counter_prefs", Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("tracking_enabled", true)
            val limit = prefs.getInt("reminder_limit", 20)
            try {
                val intent = Intent("com.example.ACTION_SETTINGS_CHANGED").apply {
                    setPackage(context.packageName)
                    putExtra("tracking_enabled", enabled)
                    putExtra("reminder_limit", limit)
                }
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                // Safe ignore
            }
        }
    }
}
