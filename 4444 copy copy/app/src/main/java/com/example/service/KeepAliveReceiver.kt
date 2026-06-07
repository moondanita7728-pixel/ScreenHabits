package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * KeepAliveReceiver handles system-level broadcasts to keep the counting service
 * alive and properly initialized after:
 *  - Device reboot (BOOT_COMPLETED, LOCKED_BOOT_COMPLETED)
 *  - User unlock (USER_PRESENT)
 *  - Midnight date change (ACTION_DATE_CHANGED, ACTION_TIME_CHANGED)
 *
 * This receiver does NOT restart the Accessibility Service (Android does not allow that).
 * Instead it warms up the database and notifies the running service to reload settings
 * and handle the new day if needed.
 */
class KeepAliveReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "KeepAliveReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return
        Log.d(TAG, "Received broadcast: $action")

        try {
            // Warm up the repository / database so Room observers are ready
            val repository = com.example.CounterApplication.getRepository()
            Log.d(TAG, "Repository ready on broadcast: $action")

            when (action) {
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.LOCKED_BOOT_COMPLETED",
                Intent.ACTION_USER_PRESENT -> {
                    // After a reboot the user must re-enable the Accessibility Service manually
                    // (Android security requirement). We just warm up the database here so
                    // it is ready the moment the service connects.
                    Log.d(TAG, "Boot/unlock broadcast — database warmed up.")
                }

                Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_TIME_CHANGED -> {
                    // Midnight crossed or time was changed manually.
                    // Notify the running service so it can open a new day record immediately.
                    Log.d(TAG, "Date/time changed — notifying service to handle new day.")
                    val updateIntent = Intent("com.example.ACTION_SETTINGS_CHANGED").apply {
                        setPackage(context.packageName)
                        putExtra("force_day_check", true)
                    }
                    context.sendBroadcast(updateIntent)
                }

                else -> {
                    // Generic keep-alive ping
                    val updateIntent = Intent("com.example.ACTION_SETTINGS_CHANGED").apply {
                        setPackage(context.packageName)
                    }
                    context.sendBroadcast(updateIntent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in KeepAliveReceiver", e)
        }
    }
}