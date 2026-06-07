package com.example.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.CounterApplication
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import android.provider.Settings
import android.widget.FrameLayout
import android.widget.TextView
import android.view.WindowManager
import android.view.Gravity
import android.graphics.PixelFormat
import android.view.View
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.MotionEvent
import android.app.ActivityManager
import android.content.Context

class ReelShortCounterAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var trackingReceiver: android.content.BroadcastReceiver? = null

    private val repository: com.example.data.CounterRepository by lazy {
        try {
            com.example.CounterApplication.getRepository()
        } catch (e: Exception) {
            val db = com.example.data.AppDatabase.getDatabase(applicationContext ?: this)
            com.example.data.CounterRepository(db.counterDao())
        }
    }

    private var isTrackingEnabled = true
    private var reminderLimit = 20
    
    // Live Floating Counter Overlay members
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var hideOverlayJob: Job? = null
    private var todayCountJob: Job? = null
    private var activeScanJob: Job? = null
    private var currentCount = 0
    private var reminderOverlayView: View? = null
    private var lastActivePackage: String? = null
    private var hasLeftTargetApps = true
    private var currentPackageName: String? = null
    private var timeOutsideTargetApps = 0L
    private var firstTimeOutsideTargetApp = 0L
    private var lastScrollTime = 0L
    private var lastContentChangeTime = 0L
    private var lastCountedPlatform: String? = null
    private var lastCountedCreator: String? = null
    private val recentViews = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var hasScrolledSinceLastCount = true
    private var lastTriggeredSignature: String? = null
    private var lastCheckedDateStr: String? = null

    // Platform Session States
    private class PlatformSessionState {
        var sessionCount = 0
        var hasShownReminderForCurrentSession = false
        var nextReminderThreshold = -1
        val sessionViewedSignatures = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var lastRecordedSignature: String? = null
    }

    private val youtubeSession = PlatformSessionState()
    private val instagramSession = PlatformSessionState()
    private var activeSession = youtubeSession

    // Tracking if app processes are completely cut out of recent tabs/killed
    private var wasYouTubeIconOrCardVisibleInRecents = false
    private var wasInstagramIconOrCardVisibleInRecents = false
    private var lastRecentsCheckTime = 0L

    companion object {
        private const val TAG = "ReelShortService"
        
        @Volatile
        var isServiceRunning = false
            private set

        // Flow/State to notify active service status change in real-time
        val serviceStateChanged = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(replay = 1)

        @Volatile
        private var activeInstance: ReelShortCounterAccessibilityService? = null

        fun updateTrackingState(context: android.content.Context, enabled: Boolean) {
            activeInstance?.let { service ->
                if (!enabled) {
                    service.removeOverlay()
                } else {
                    service.observeTodayCount()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            showForegroundNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Failed start foreground in onStartCommand", e)
        }
        return START_STICKY
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        activeInstance = this
        
        try {
            showForegroundNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground notification on connect", e)
        }

        serviceScope.launch {
            serviceStateChanged.emit(true)
        }

        // Initialize settings synchronously from preferences on connect
        val p = getSharedPreferences("counter_prefs", MODE_PRIVATE)
        isTrackingEnabled = p.getBoolean("tracking_enabled", true)
        reminderLimit = p.getInt("reminder_limit", 20)

        // Restore active session counts from persistent storage
        youtubeSession.sessionCount = getStoredSessionCount("YouTube")
        instagramSession.sessionCount = getStoredSessionCount("Instagram")

        if (isTrackingEnabled) {
            observeTodayCount()
        } else {
            removeOverlay()
        }
        Log.d(TAG, "Accessibility Service Connected")

        // Register tracking receiver for cross-process toggle & settings sync
        val filter = android.content.IntentFilter("com.example.ACTION_SETTINGS_CHANGED")
        trackingReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val enabled = intent?.getBooleanExtra("tracking_enabled", isTrackingEnabled) ?: isTrackingEnabled
                val limit = intent?.getIntExtra("reminder_limit", reminderLimit) ?: reminderLimit
                
                isTrackingEnabled = enabled
                reminderLimit = limit

                if (!enabled) {
                    removeOverlay()
                } else {
                    observeTodayCount()
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(trackingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(trackingReceiver, filter)
        }

        // Start safety check loop to hide overlay when leaving YouTube/Instagram
        var lastTimeInTargetApp = System.currentTimeMillis()
        serviceScope.launch {
            while (isActive) {
                delay(1000L)
                checkAndHandleNewDay()

                val activePkg = getActivePackageName()
                val isVisible = isTargetAppVisible()
                val isTarget = isVisible || (activePkg != null && (
                    isExactTargetPackage(activePkg) ||
                    (activePkg == packageName && !com.example.MainActivity.isMainActivityResumed)
                ))
                
                val isTempOverlay = activePkg != null && isTemporarySystemOverlay(activePkg)
                
                if (isTarget) {
                    lastTimeInTargetApp = System.currentTimeMillis()
                    if (hasLeftTargetApps) {
                        Log.d(TAG, "Poller detected user returned to target app: $activePkg")
                        hasLeftTargetApps = false
                    }
                    timeOutsideTargetApps = 0L
                } else if (isTempOverlay) {
                    // Temporary system overlays (Control Center, Assist, Keyboards, volume, lockscreen) 
                    // should not count as leaving app, but hide the overlay to remain non-irritating.
                    hideOverlayInstantly()
                } else {
                    // Users exited to third-party apps (or Home Launcher, or our app, or null package) - this is a cut/exit!
                    timeOutsideTargetApps += 1000L
                    if (!hasLeftTargetApps) {
                        Log.d(TAG, "Poller detected user left target app (cut/exit) to $activePkg")
                        hasLeftTargetApps = true
                        hideOverlayInstantly()
                    }
                    
                    // Exiting to Launcher or Recents implies a quick, intentional close (allow fast 2 sec reset).
                    // Exiting to other apps (e.g., volume overlays, split screen) demands a reliable 15 sec delay
                    // so that brief multi-tasking or overlays never restart custom session counters.
                    val activePkgName = activePkg ?: ""
                    val isLauncherOrSystem = isRecentsOrLauncher(activePkgName) || 
                                             activePkgName.contains("launcher", ignoreCase = true) ||
                                             activePkgName.contains("trebuchet", ignoreCase = true) ||
                                             activePkgName.contains("pixelpartner", ignoreCase = true)

                    val resetThreshold = if (isLauncherOrSystem) 2000L else 15000L
                    
                    if (timeOutsideTargetApps >= resetThreshold) {
                        if (youtubeSession.sessionCount > 0) {
                            Log.d(TAG, "Poller resetting YouTube session count upon exit (activePkg=$activePkg)")
                            resetSession(youtubeSession)
                        }
                        if (instagramSession.sessionCount > 0) {
                            Log.d(TAG, "Poller resetting Instagram session count upon exit (activePkg=$activePkg)")
                            resetSession(instagramSession)
                        }
                    }
                }
            }
        }
    }

    private fun showForegroundNotification() {
        try {
            val channelId = "reels_shorts_counter_channel"
            val channelName = "Reels & Shorts Tracker"
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    channelName,
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps the Reels & Shorts counter active across apps."
                    setShowBadge(false)
                }
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
            
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            val pendingIntent = if (launchIntent != null) {
                val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT
                }
                android.app.PendingIntent.getActivity(this, 0, launchIntent, flags)
            } else {
                null
            }
            
            val notificationBuilder = androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setContentTitle("Reels & Shorts Tracker Active")
                .setContentText("Keeping track of screen boundaries in background.")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_SERVICE)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                
            if (pendingIntent != null) {
                notificationBuilder.setContentIntent(pendingIntent)
            }
            
            val notification = notificationBuilder.build()
            
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1001, notification)
            }
            Log.d(TAG, "Foreground service notification started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground notification", e)
        }
    }

    private fun getActivePackageName(): String? {
        try {
            val windowList = windows
            if (windowList != null && windowList.isNotEmpty()) {
                for (window in windowList) {
                    // Ignore Picture-in-Picture (PiP) windows
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        if (window.isInPictureInPictureMode) {
                            continue
                        }
                    }
                    if (window.isActive) {
                        val root = window.root
                        if (root != null) {
                            val activePkgName = root.packageName?.toString()
                            try { root.recycle() } catch (e: Exception) {}
                            if (activePkgName != null) return activePkgName
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking active window package", e)
        }
        try {
            val root = rootInActiveWindow
            if (root != null) {
                val activePkgName = root.packageName?.toString()
                try { root.recycle() } catch (e: Exception) {}
                return activePkgName
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking rootInActiveWindow", e)
        }
        return null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceRunning = false
        activeInstance = null
        serviceScope.launch {
            serviceStateChanged.emit(false)
        }
        removeOverlay()
        Log.d(TAG, "Accessibility Service Unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isServiceRunning = false
        activeInstance = null
        serviceScope.launch {
            serviceStateChanged.emit(false)
        }
        removeOverlay()
        trackingReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // Safe ignore
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun triggerDelayedScan(platform: String) {
        val targetPkg = if (platform == "Instagram") {
            val curr = currentPackageName
            if (curr != null && curr.contains("instagram", ignoreCase = true)) curr else "com.instagram.android"
        } else {
            "com.google.android.youtube"
        }
        activeScanJob?.cancel()
        activeScanJob = serviceScope.launch {
            delay(700) // Exactly 0.7 seconds (700ms) after a reel or short starts
            
            val scanResult = scanScreen(targetPkg)
            var resolvedCreator = scanResult.creator
            var resolvedDescription = scanResult.description
            var adDetected = scanResult.isAd
            var finalWordsSeen = scanResult.wordsSeen
            var finalViewIdsSeen = scanResult.viewIdsSeen
            
            // If we didn't find a creator and it's not an ad, we can do a single quick retry after 50ms to be robust
            if (resolvedCreator == null && !adDetected) {
                delay(50)
                val retryResult = scanScreen(targetPkg)
                resolvedCreator = retryResult.creator ?: resolvedCreator
                resolvedDescription = retryResult.description ?: resolvedDescription
                adDetected = retryResult.isAd
                finalWordsSeen = retryResult.wordsSeen
                finalViewIdsSeen = retryResult.viewIdsSeen
                for (txt in retryResult.dynamicTexts) {
                    if (!scanResult.dynamicTexts.contains(txt)) {
                        scanResult.dynamicTexts.add(txt)
                    }
                }
            }
            
            if (adDetected) {
                // Ad detected - DO NOT count, keep overlay hidden
                Log.d(TAG, "Ad detected on $platform, skipping count increment")
                hideOverlayInstantly()
            } else {
                // Determine if a Reel/Short is actually active on the screen!
                val isActive = isShortOrReelActiveOnScreen(platform, scanResult)
                
                if (!isActive) {
                    Log.d(TAG, "Not a valid active short/reel screen ($platform), skipping increase and keeping overlay hidden")
                    hideOverlayInstantly()
                } else {
                    scanResult.creator = resolvedCreator
                    scanResult.description = resolvedDescription
                    val signature = getStableSignature(scanResult, platform)
                    lastTriggeredSignature = signature
                    
                    val isGenericSignature = signature == "$platform:unknown:nodesc" || 
                                             signature == "$platform:unknown:nodesc:"
                    
                    val wasLastSignatureGeneric = activeSession.lastRecordedSignature == null ||
                                                  activeSession.lastRecordedSignature == "$platform:unknown:nodesc" ||
                                                  activeSession.lastRecordedSignature == "$platform:unknown:nodesc:"
                    
                    // Transitioning from a known video (non-generic) to a generic/unknown one (overlays hidden while seeking/scrubbing/seeking)
                    // MUST NOT count as a new video!
                    val isTransitionToGeneric = isGenericSignature && !wasLastSignatureGeneric
                    
                    val isSessionDuplicate = !isGenericSignature && activeSession.sessionViewedSignatures.contains(signature)
                    
                    var isDuplicate = false
                    if (isSessionDuplicate) {
                        isDuplicate = true
                    } else if (isTransitionToGeneric) {
                        Log.d(TAG, "Transition from non-generic to generic signature (seek/scrub) detected, treating as duplicate: last=${activeSession.lastRecordedSignature}, current=$signature")
                        isDuplicate = true
                    } else if (signature == activeSession.lastRecordedSignature) {
                        isDuplicate = if (isGenericSignature && hasScrolledSinceLastCount) {
                            false
                        } else {
                            true
                        }
                    }
                    
                    if (!isDuplicate) {
                        // Any signature change detected without scrolling is treated as a dynamic interaction, comments load, or late update to prevent double counting!
                        val isSameVideoInteraction = !hasScrolledSinceLastCount && activeSession.lastRecordedSignature != null
                        
                        if (isSameVideoInteraction) {
                            Log.d(TAG, "Interaction/late update on same video: updating signature from ${activeSession.lastRecordedSignature} to $signature without incrementing")
                            activeSession.lastRecordedSignature = signature
                            if (!isGenericSignature) {
                                activeSession.sessionViewedSignatures.add(signature)
                            }
                        } else {
                            // Increment count and show overlay
                            currentCount++
                            activeSession.sessionCount++
                            saveStoredSessionCount(platform, activeSession.sessionCount)
                            updateOverlayText(activeSession.sessionCount, show = true)
                            
                            if (!isGenericSignature) {
                                activeSession.sessionViewedSignatures.add(signature)
                            }
                            activeSession.lastRecordedSignature = signature
                            hasScrolledSinceLastCount = false
                            
                            val targetThreshold = if (activeSession.nextReminderThreshold > 0) activeSession.nextReminderThreshold else (reminderLimit + 1)
                            if (activeSession.sessionCount == targetThreshold && !activeSession.hasShownReminderForCurrentSession) {
                                activeSession.hasShownReminderForCurrentSession = true
                                showLimitReminderOverlay(reminderLimit)
                            }
                            
                            val finalCreator = resolvedCreator ?: "Creator"
                            if (finalCreator != "Creator") {
                                lastCountedPlatform = platform
                                lastCountedCreator = finalCreator
                            }
                            incrementAndPersist(platform, finalCreator)
                        }
                    } else {
                        // Already watched (old reel/short) or duplicate: do NOT increase count, and DO NOT force show/open overlay (keep current visibility state)
                        Log.d(TAG, "Duplicate/Old reel/short detected ($signature), keeping overlay unchanged/hidden")
                        updateOverlayText(activeSession.sessionCount, show = false)
                    }
                }
            }
        }
    }

    private fun isShortOrReelActiveOnScreen(
        platform: String,
        result: ScreenScanResult
    ): Boolean {
        // 1. General Keyboard / Comment Box / Share sheet / Dialog exclusions
        val isCommentOrSheetOrInputOpen = result.hasEditText || result.viewIdsSeen.any { id ->
            id.contains("engagement_panel", ignoreCase = true) ||
            id.contains("comment_sheet", ignoreCase = true) ||
            id.contains("comments_sheet", ignoreCase = true) ||
            id.contains("comment_thread", ignoreCase = true) ||
            id.contains("comment_list", ignoreCase = true) ||
            id.contains("comments_list", ignoreCase = true) ||
            id.contains("layout_comment_thread", ignoreCase = true) ||
            id.contains("comment_list_container", ignoreCase = true) ||
            id.contains("bottom_sheet_container", ignoreCase = true) ||
            id.contains("action_sheet_container", ignoreCase = true) ||
            id.contains("comments_container", ignoreCase = true) ||
            id.contains("direct_search_recipients", ignoreCase = true) ||
            id.contains("share_sheet", ignoreCase = true) ||
            id.contains("keyboard", ignoreCase = true) ||
            id.contains("dialog", ignoreCase = true)
        } || result.wordsSeen.any {
            it == "add a comment..." ||
            it == "add a public comment..." ||
            it == "votre commentaire..." ||
            it == "add comment" ||
            it.contains("टिप्पणी जोड़ें", ignoreCase = true) ||
            it == "comentar..."
        }

        if (isCommentOrSheetOrInputOpen) {
            Log.d(TAG, "Keyboard, Comment box, Share sheet, or Dialog open. Excluding.")
            return false
        }

        val creator = result.creator
        val description = result.description
        val wordsSeen = result.wordsSeen
        val viewIdsSeen = result.viewIdsSeen

        if (platform == "YouTube") {
            // Check if bottom main navigation bars/tabs are visible or standard metadata text is present.
            val isMainHomeOrFeedTab = viewIdsSeen.any { id ->
                id.contains("pivot_bar", ignoreCase = true) ||
                id.contains("bottom_bar", ignoreCase = true) ||
                id.contains("bottom_navigation", ignoreCase = true) ||
                id.contains("navigation_bar", ignoreCase = true) ||
                id.contains("tab_bar", ignoreCase = true) ||
                id.contains("bottom_menu", ignoreCase = true)
            } || (wordsSeen.contains("home") && (wordsSeen.contains("subscriptions") || wordsSeen.contains("library") || wordsSeen.contains("you"))) ||
                 (wordsSeen.contains("inicio") && wordsSeen.contains("suscripciones")) ||
                 (wordsSeen.contains("मुख्य पृष्ठ") && wordsSeen.contains("सदस्यताएं"))

            if (isMainHomeOrFeedTab) {
                Log.d(TAG, "YouTube Home page, Subscriptions feed, or main tab bar detected. Excluding from Shorts.")
                return false
            }

            // Exclude standard scroll feeds (e.g. Home, Subscriptions, search results list) by detecting standard video list item cards or view metadata like "views" and "ago"
            val containsFeedMetadata = wordsSeen.any { w ->
                val lw = w.lowercase()
                lw.contains(" views") || lw.contains(" vistas") || lw.contains(" vues") || 
                lw.contains(" aufrufe") || lw.contains(" visualizzazioni") || lw.contains(" दृश्य")
            } || (wordsSeen.any { w ->
                val lw = w.lowercase()
                lw.contains("views") || lw.contains("vistas") || lw.contains("vues") || lw.contains("aufrufe") || lw.contains("दृश्य")
            } && wordsSeen.any { w ->
                val lw = w.lowercase()
                lw.contains("ago") || lw.contains("hace") || lw.contains("il y a") || lw.contains("vor") || lw.contains("पहले") || lw.contains("घंटे")
            })

            val resemblesStandardLayout = viewIdsSeen.any { id ->
                id.contains("feed_item", ignoreCase = true) ||
                id.contains("grid_layout", ignoreCase = true) ||
                id.contains("list_item", ignoreCase = true) ||
                id.contains("video_info_layout", ignoreCase = true) ||
                id.contains("video_card", ignoreCase = true) ||
                id.contains("attachment_card", ignoreCase = true) ||
                id.contains("metadata_layout", ignoreCase = true) ||
                id.contains("toolbar", ignoreCase = true) ||
                id.contains("action_bar", ignoreCase = true) ||
                id.contains("youtube_logo", ignoreCase = true)
            }

            if (containsFeedMetadata || resemblesStandardLayout) {
                Log.d(TAG, "YouTube feed-resembling metadata, list cards or action bar identified. Excluding from Shorts.")
                return false
            }

            // Check if we are on a standard long-form watch page or suggested layout
            val isStandardWatchPage = viewIdsSeen.any { id ->
                id.contains("watch_next", ignoreCase = true) ||
                id.contains("watch_metadata", ignoreCase = true) ||
                id.contains("movie_view", ignoreCase = true) ||
                id.contains("watch_player", ignoreCase = true) ||
                id.contains("player_over_caption", ignoreCase = true) ||
                id.contains("video_info", ignoreCase = true) ||
                id.contains("watch_panel", ignoreCase = true) ||
                id.contains("watch_under_player", ignoreCase = true)
            }

            if (isStandardWatchPage) {
                Log.d(TAG, "YouTube standard watch page or panel detected. Excluding from Shorts.")
                return false
            }

            // Exclude channel/home tabs or search feed pages IF there's no active Shorts player or interaction
            val hasChannelTabs = wordsSeen.any {
                it.contains("playlists", ignoreCase = true) || 
                it.contains("listas", ignoreCase = true) ||
                it.contains("community", ignoreCase = true) || 
                it.contains("communauté", ignoreCase = true) ||
                it.contains("comunidad", ignoreCase = true) || 
                it.contains("about", ignoreCase = true) || 
                it.contains("à propos", ignoreCase = true) ||
                it.contains("información", ignoreCase = true) ||
                it.contains("channels", ignoreCase = true) ||
                it.contains("canales", ignoreCase = true) ||
                it.contains("uploads", ignoreCase = true) ||
                (it.contains("home", ignoreCase = true) && it.contains("videos", ignoreCase = true) && it.contains("shorts", ignoreCase = true)) ||
                (it.contains("inicio", ignoreCase = true) && it.contains("videos", ignoreCase = true) && it.contains("shorts", ignoreCase = true))
            }

            val isSearchOrFeedPage = viewIdsSeen.any { id ->
                id.contains("search_box", ignoreCase = true) ||
                id.contains("search_edit_text", ignoreCase = true) ||
                id.contains("filter_header", ignoreCase = true) ||
                id.contains("results_list", ignoreCase = true) ||
                id.contains("search_suggestion", ignoreCase = true)
            } || wordsSeen.contains("search") || wordsSeen.contains("खोजें") || wordsSeen.contains("खोज")

            // Robust validation of an active Short player via view IDs or UI interaction components
            val hasReelActivePlayer = viewIdsSeen.any { id ->
                (id.contains("reel", ignoreCase = true) || id.contains("shorts", ignoreCase = true)) &&
                !id.contains("tab", ignoreCase = true) &&
                !id.contains("shelf", ignoreCase = true) &&
                !id.contains("thumbnail", ignoreCase = true) &&
                !id.contains("title", ignoreCase = true) &&
                (!id.contains("item", ignoreCase = true) || id.contains("player", ignoreCase = true) || id.contains("viewer", ignoreCase = true) || id.contains("container", ignoreCase = true)) &&
                !id.contains("grid", ignoreCase = true) &&
                !id.contains("header", ignoreCase = true)
            }

            val hasDislikeWordOrId = viewIdsSeen.any { it.contains("dislike", ignoreCase = true) } || 
                                     wordsSeen.any { 
                                         it.contains("dislike", ignoreCase = true) || 
                                         it.contains("नापसंद", ignoreCase = true) ||
                                         it.contains("no me gusta", ignoreCase = true) ||
                                         it.contains("não gostei", ignoreCase = true) ||
                                         it.contains("je n'aime pas", ignoreCase = true)
                                     }

            val hasRemixWordOrId = viewIdsSeen.any { it.contains("remix", ignoreCase = true) } || 
                                   wordsSeen.any { 
                                       it.contains("remix", ignoreCase = true) ||
                                       it.contains("réunir", ignoreCase = true) ||
                                       it.contains("recombinar", ignoreCase = true)
                                   }

            val hasShareWordOrId = viewIdsSeen.any { it.contains("share", ignoreCase = true) } || 
                                   wordsSeen.any { 
                                       it.contains("share", ignoreCase = true) || 
                                       it.contains("शेयर", ignoreCase = true) ||
                                       it.contains("compartir", ignoreCase = true) ||
                                       it.contains("compartilhar", ignoreCase = true) ||
                                       it.contains("partager", ignoreCase = true)
                                   }

            val hasCommentWordOrId = viewIdsSeen.any { id -> id.contains("comment", ignoreCase = true) && !id.contains("live_chat", ignoreCase = true) } || 
                                     wordsSeen.any { 
                                         it.contains("comment", ignoreCase = true) || 
                                         it.contains("टिप्पणी", ignoreCase = true) ||
                                         it.contains("comentario", ignoreCase = true) ||
                                         it.contains("comentário", ignoreCase = true) ||
                                         it.contains("commentaire", ignoreCase = true)
                                     }

            val hasSubscribeWordOrId = viewIdsSeen.any { it.contains("subscribe", ignoreCase = true) } || 
                                        wordsSeen.any { 
                                            it.contains("subscribe", ignoreCase = true) ||
                                            it.contains("subscribed", ignoreCase = true) ||
                                            it.contains("सदस्यता", ignoreCase = true) ||
                                            it.contains("suscribirse", ignoreCase = true) ||
                                            it.contains("suscrito", ignoreCase = true) ||
                                            it.contains("s'abonner", ignoreCase = true) ||
                                            it.contains("abonné", ignoreCase = true) ||
                                            it.contains("inscrever", ignoreCase = true)
                                        }

            val hasShortsOnlyInteractions = (hasDislikeWordOrId || hasRemixWordOrId || hasShareWordOrId || hasCommentWordOrId) && 
                                             (hasSubscribeWordOrId || creator?.startsWith("@") == true)

            val isShortActive = hasReelActivePlayer || hasShortsOnlyInteractions

            if (!isShortActive) {
                if (hasChannelTabs) {
                    Log.d(TAG, "YouTube channel page or home tabs detected with no active Short. Excluding.")
                    return false
                }
                if (isSearchOrFeedPage) {
                    Log.d(TAG, "YouTube search results layout detected with no active Short. Excluding.")
                    return false
                }
                return false
            }

            // Short is active! Bypassing any background search/feed leftovers
            Log.d(TAG, "YouTube active Short detected (hasReelActivePlayer=$hasReelActivePlayer, hasShortsOnlyInteractions=$hasShortsOnlyInteractions)")
            return true
        } else if (platform == "Instagram") {
            val isLite = currentPackageName?.contains("lite", ignoreCase = true) == true
            
            // Exclude the main home feed, navigation tabs, or activity feeds when bottom navigation panel is visible.
            // Immersive full-screen reels do not display these standard bottom menu/nav bars or action tabs.
            val isMainFeedOrTab = viewIdsSeen.any { id ->
                id.contains("bottom_navigation", ignoreCase = true) ||
                id.contains("navigation_bar", ignoreCase = true) ||
                id.contains("tab_bar", ignoreCase = true) ||
                id.contains("pivot_bar", ignoreCase = true)
            }

            if (isMainFeedOrTab) {
                Log.d(TAG, "Instagram main feed or tab navigation panel detected. Excluding.")
                return false
            }

            // 1. Check if there is an active Reel container or Reel description.
            val hasReelsContainer = viewIdsSeen.any { id ->
                id.contains("rebound_view_pager", ignoreCase = true) || 
                id.contains("reel_viewer", ignoreCase = true) || 
                id.contains("reel_video", ignoreCase = true) ||
                id.contains("clipping_info", ignoreCase = true) ||
                id.contains("reels_video_container", ignoreCase = true) ||
                id.contains("reel_viewer_layout", ignoreCase = true) ||
                id.contains("instagram_reels", ignoreCase = true) ||
                id.contains("reels_share_sheet", ignoreCase = true) ||
                (id.contains("reel", ignoreCase = true) && 
                 !id.contains("tab", ignoreCase = true) && 
                 !id.contains("shelf", ignoreCase = true) && 
                 !id.contains("list", ignoreCase = true))
            }
            
            val hasExplicitReelDescription = wordsSeen.any { 
                it.startsWith("reel by ") || 
                it.startsWith("reel de ") || 
                it.startsWith("trilha de ") ||
                it.contains("reel de", ignoreCase = true) ||
                it.contains("reel by", ignoreCase = true)
            } || description?.contains("reel by", ignoreCase = true) == true ||
                 description?.contains("reel de", ignoreCase = true) == true

            // Interactive post/player overlay elements (Like, Comment, Share, etc.) indicating we are in an open post/player detail and not a thumbnail grid
            val hasActivePostInteractions = viewIdsSeen.any { id ->
                id.contains("like", ignoreCase = true) ||
                id.contains("comment", ignoreCase = true) ||
                id.contains("share", ignoreCase = true) ||
                id.contains("direct", ignoreCase = true) ||
                id.contains("save_button", ignoreCase = true) ||
                id.contains("ufi", ignoreCase = true) // Unified Feedback Interface
            } || wordsSeen.any { 
                it.contains("comment", ignoreCase = true) || it.contains("comentar", ignoreCase = true) || it.contains("comentarios", ignoreCase = true) ||
                it.contains("share", ignoreCase = true) || it.contains("compartir", ignoreCase = true) || it.contains("compartilhar", ignoreCase = true) ||
                it.contains("टिप्पणी", ignoreCase = true) || it.contains("शेयर", ignoreCase = true)
            }

            // Audio track / sound / contains audio clues - absolutely specific to video/Reel playback
            val hasActiveAudioTrackClue = wordsSeen.any {
                it == "original audio" || 
                it == "audio original" || 
                it.contains("original sound", ignoreCase = true) || 
                it.contains("original_sound", ignoreCase = true) || 
                it.contains("trilha de", ignoreCase = true) || 
                it.contains("contém áudio", ignoreCase = true) ||
                it.contains("audio original", ignoreCase = true) ||
                it.contains("original audio", ignoreCase = true) ||
                it.contains("ऑडियो", ignoreCase = true) ||
                it.contains("मूल ऑडियो", ignoreCase = true) ||
                it.contains("संगीत", ignoreCase = true) ||
                it.contains("sonic", ignoreCase = true) ||
                it.contains("originales", ignoreCase = true) ||
                it.contains("sonido original", ignoreCase = true)
            }

            // Active video/Reel clues on screen to distinguish from static photos
            val hasVideoOrReelClue = hasReelsContainer || hasExplicitReelDescription || hasActiveAudioTrackClue || result.hasSurfaceOrVideoView || viewIdsSeen.any { id ->
                id.contains("video", ignoreCase = true) ||
                id.contains("player", ignoreCase = true) ||
                id.contains("volume", ignoreCase = true) ||
                id.contains("mute", ignoreCase = true) ||
                id.contains("audio", ignoreCase = true) ||
                id.contains("sound", ignoreCase = true) ||
                id.contains("music", ignoreCase = true) ||
                id.contains("play_button", ignoreCase = true)
            } || wordsSeen.any {
                it.contains("sound", ignoreCase = true) ||
                it.contains("audio", ignoreCase = true) ||
                it.contains("trilha", ignoreCase = true) ||
                it.contains("music", ignoreCase = true) ||
                it.contains("ऑडियो", ignoreCase = true) ||
                it.contains("संगीत", ignoreCase = true) ||
                it == "reel" || it == "reels" || it == "video" ||
                it.contains("reels", ignoreCase = true) ||
                it.contains("video", ignoreCase = true)
            } || description?.contains("reel", ignoreCase = true) == true ||
                 description?.contains("video", ignoreCase = true) == true ||
                 description?.contains("audio", ignoreCase = true) == true

            val isSearchOrExplorePage = viewIdsSeen.any { id ->
                id.contains("search", ignoreCase = true) ||
                id.contains("explore", ignoreCase = true) ||
                id.contains("query", ignoreCase = true) ||
                id.contains("input", ignoreCase = true) ||
                id.contains("find_people", ignoreCase = true) ||
                id.contains("search_bar", ignoreCase = true) ||
                id.contains("search_box", ignoreCase = true) ||
                id.contains("search_query", ignoreCase = true) ||
                id.contains("action_bar_search", ignoreCase = true)
            } || wordsSeen.any {
                it == "search" || it == "explore" || it == "buscar" || it == "rechercher" || it == "pesquisar" ||
                it.contains("search instagram", ignoreCase = true) ||
                it.contains("search in", ignoreCase = true) ||
                it == "खोजें" || it == "खोज" ||
                it == "explore tab" || it == "recent searches" || it == "cancel search" ||
                it == "accounts" || it == "audio" || it == "tags" || it == "places" ||
                it == "cuentas" || it == "audios" || it == "lugares" ||
                it.contains("search...", ignoreCase = true) || it.contains("buscar...", ignoreCase = true)
            }

            if (isSearchOrExplorePage) {
                Log.d(TAG, "Instagram Search/Explore/Typing page detected. Excluding from Reels.")
                return false
            }

            // Strong positive cases:
            if (hasActiveAudioTrackClue) {
                return true
            }

            if (hasReelsContainer || hasExplicitReelDescription) {
                return true
            }

            // If we are in Instagram Lite (which has very generic custom containers) and have any video clues, return true!
            if (isLite && hasVideoOrReelClue) {
                val hasGridOrProfileIndicators = viewIdsSeen.any { id ->
                    id.contains("grid", ignoreCase = true) ||
                    id.contains("profile_section", ignoreCase = true) ||
                    id.contains("profile_header", ignoreCase = true) ||
                    id.contains("search_results", ignoreCase = true) ||
                    id.contains("user_profile", ignoreCase = true) ||
                    id.contains("row_profile_", ignoreCase = true)
                }
                if (!hasGridOrProfileIndicators) {
                    return true
                }
            }

            // If we have active post interactive controls and it shows video/Reel characteristics, count immediately!
            if (hasActivePostInteractions && hasVideoOrReelClue) {
                return true
            }

            // If Like/Comment/Share are present, then we are on an active single post detail screen. 
            // If it didn't pass target video/Reel criteria above, then it's a photo Post, so exclude it.
            if (hasActivePostInteractions) {
                return false
            }

            // Otherwise, apply thumbnail grid or profile page list exclusions
            val hasGridOrProfileIndicators = viewIdsSeen.any { id ->
                id.contains("grid", ignoreCase = true) ||
                id.contains("profile_section", ignoreCase = true) ||
                id.contains("profile_header", ignoreCase = true) ||
                id.contains("search_results", ignoreCase = true) ||
                id.contains("user_profile", ignoreCase = true) ||
                id.contains("row_profile_", ignoreCase = true)
            }
            if (hasGridOrProfileIndicators) {
                Log.d(TAG, "Instagram profile/grid/search layout detected. Excluding screen to prevent counting thumbnail grids.")
                return false
            }

            // Exclude home/search feeds ONLY if no video/Reel clue is found.
            // If there is indeed a video/Reel clue, then we are view-scrolling a Reel/video on the home or search page!
            val hasFeedListElements = viewIdsSeen.any { id ->
                id.contains("feed_recycler", ignoreCase = true) ||
                id.contains("row_feed_", ignoreCase = true) ||
                id.contains("action_bar_container", ignoreCase = true)
            }
            if (hasFeedListElements && !hasVideoOrReelClue) {
                Log.d(TAG, "Instagram standard feed list layout detected with no active video. Excluding.")
                return false
            }
            
            // To prevent false positives, do NOT loosely fall back to true under search list or other ambiguous situations.
            Log.d(TAG, "Instagram fallback reached - no clear positive immersive reel signature. Excluding screen from Reels.")
            return false
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        checkAndHandleNewDay()

        // Instant check if user chose to PAUSE/STOP counting on their own accord
        if (!isTrackingEnabled) {
            removeOverlay()
            return
        }

        val packageName = event.packageName?.toString() ?: return
        currentPackageName = packageName
        
        val isTargetApp = isExactTargetPackage(packageName)
        
        if (isTargetApp) {
            val platform = if (packageName.contains("instagram", ignoreCase = true)) "Instagram" else "YouTube"
            activeSession = if (platform == "Instagram") instagramSession else youtubeSession
            
            if (lastActivePackage != platform || hasLeftTargetApps) {
                lastActivePackage = platform
                hasLeftTargetApps = false
                hasScrolledSinceLastCount = true
                lastTriggeredSignature = null
                Log.d(TAG, "Entered platform $platform from outside. Resetting session count to restart counting!")
                resetSession(activeSession)
                updateOverlayText(activeSession.sessionCount, show = true)
                
                // Trigger initial delayed scan for the first/landing short/reel!
                triggerDelayedScan(platform)
            }

            if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED || 
                event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED
            ) {
                val className = event.className?.toString() ?: ""
                val textValue = event.text?.toString()?.lowercase(java.util.Locale.getDefault()) ?: ""
                val descValue = event.contentDescription?.toString()?.lowercase(java.util.Locale.getDefault()) ?: ""
                
                val isProgressOrSeek = className.contains("SeekBar", ignoreCase = true) ||
                                       className.contains("ProgressBar", ignoreCase = true) ||
                                       className.contains("TimeBar", ignoreCase = true) ||
                                       className.contains("Timeline", ignoreCase = true) ||
                                       className.contains("Progress", ignoreCase = true) ||
                                       className.contains("Volume", ignoreCase = true) ||
                                       className.contains("Slider", ignoreCase = true) ||
                                       className.contains("Thumb", ignoreCase = true) ||
                                       textValue.contains("seek") || textValue.contains("progress") || textValue.contains("duration") ||
                                       descValue.contains("seek") || descValue.contains("progress") || descValue.contains("duration")

                if (!isProgressOrSeek) {
                    hideOverlayInstantly()
                    hasScrolledSinceLastCount = true
                    lastTriggeredSignature = null
                    val now = System.currentTimeMillis()
                    if (now - lastScrollTime > 300L) {
                        lastScrollTime = now
                        triggerDelayedScan(platform)
                    }
                } else {
                    Log.d(TAG, "Excluded seek/scrub scroll event: class=$className, text=$textValue, desc=$descValue")
                }
            } else if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                val className = event.className?.toString() ?: ""
                val textValue = event.text?.toString()?.lowercase(java.util.Locale.getDefault()) ?: ""
                val descValue = event.contentDescription?.toString()?.lowercase(java.util.Locale.getDefault()) ?: ""
                
                val isProgressOrSeek = className.contains("SeekBar", ignoreCase = true) ||
                                       className.contains("ProgressBar", ignoreCase = true) ||
                                       className.contains("TimeBar", ignoreCase = true) ||
                                       className.contains("Timeline", ignoreCase = true) ||
                                       className.contains("Progress", ignoreCase = true) ||
                                       className.contains("Volume", ignoreCase = true) ||
                                       className.contains("Slider", ignoreCase = true) ||
                                       className.contains("Thumb", ignoreCase = true) ||
                                       textValue.contains("seek") || textValue.contains("progress") || textValue.contains("duration") ||
                                       descValue.contains("seek") || descValue.contains("progress") || descValue.contains("duration")
                if (!isProgressOrSeek) {
                    // Do NOT set hasScrolledSinceLastCount = true on clicked to prevent pause/play from double counting
                    lastTriggeredSignature = null
                    triggerDelayedScan(platform)
                }
            } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // Instantly update text silently when opening/switching app states, but do not show overlay yet
                updateOverlayText(activeSession.sessionCount, show = false)
                hasScrolledSinceLastCount = true
                lastTriggeredSignature = null
                triggerDelayedScan(platform)
            } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                val now = System.currentTimeMillis()
                if (now - lastContentChangeTime > 800L) {
                    lastContentChangeTime = now
                    // Instead of executing a heavy synchronous scanScreen on the main/UI thread for every frequent content tick,
                    // we offload/schedule it to our debounced, delayed scan channel. This keeps the main thread safe, responsive, and prevents ANR.
                    triggerDelayedScan(platform)
                }
            }
        } else {
            // Outside of YouTube and Instagram
            // Check for recent apps cut or swipe behavior
            val isSystemOverlay = isTemporarySystemOverlay(packageName)
            val isLauncherOrSysUI = isRecentsOrLauncher(packageName)
            
            if (isLauncherOrSysUI && isTrackingEnabled) {
                // If the user clicks the launcher icon for YouTube or Instagram, reset the session immediately!
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                    val clickedText = event.text?.toString()?.lowercase(java.util.Locale.getDefault()) ?: ""
                    val clickedContentDesc = event.contentDescription?.toString()?.lowercase(java.util.Locale.getDefault()) ?: ""
                    
                    if (clickedText.contains("youtube") || clickedContentDesc.contains("youtube")) {
                        Log.d(TAG, "Launcher click detected on YouTube icon, resetting YouTube session!")
                        resetSession(youtubeSession)
                    }
                    if (clickedText.contains("instagram") || clickedContentDesc.contains("instagram")) {
                        Log.d(TAG, "Launcher click detected on Instagram icon, resetting Instagram session!")
                        resetSession(instagramSession)
                    }
                }

                val rootNode = rootInActiveWindow
                val inRecents = isRecentsScreenActive(rootNode)
                
                if (inRecents) {
                    val now = System.currentTimeMillis()
                    if (now - lastRecentsCheckTime > 500L) {
                        lastRecentsCheckTime = now
                        val visibleCards = scanRecentsForTargetCards(rootNode)
                        val containsYouTube = visibleCards.contains("YouTube")
                        val containsInstagram = visibleCards.contains("Instagram")
                        
                        Log.d(TAG, "Recents screen scanning: visible target cards: $visibleCards")
                        
                        if (containsYouTube) {
                            wasYouTubeIconOrCardVisibleInRecents = true
                        }
                        if (containsInstagram) {
                            wasInstagramIconOrCardVisibleInRecents = true
                        }
                        
                        // Swipe-to-dismiss check: if it was visible but is no longer visible, and we receive a scroll or content change:
                        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED || 
                            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                        ) {
                            if (wasYouTubeIconOrCardVisibleInRecents && !containsYouTube) {
                                Log.d(TAG, "YouTube card disappeared from Recents while swiping, resetting YouTube session!")
                                resetSession(youtubeSession)
                                wasYouTubeIconOrCardVisibleInRecents = false
                            }
                            if (wasInstagramIconOrCardVisibleInRecents && !containsInstagram) {
                                Log.d(TAG, "Instagram card disappeared from Recents while swiping, resetting Instagram session!")
                                resetSession(instagramSession)
                                wasInstagramIconOrCardVisibleInRecents = false
                            }
                        }
                    }
                } else {
                    // We are in the launcher Home screen/Workspace (not Recents screen)
                    wasYouTubeIconOrCardVisibleInRecents = false
                    wasInstagramIconOrCardVisibleInRecents = false
                }
                
                // Clear All / Close All button detection
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                    val clickedText = event.text?.toString()?.lowercase(java.util.Locale.getDefault()) ?: ""
                    val clickedContentDesc = event.contentDescription?.toString()?.lowercase(java.util.Locale.getDefault()) ?: ""
                    
                    val isClearAllBtn = clickedText.contains("clear all") || 
                                        clickedText.contains("close all") || 
                                        clickedText.contains("remove all") || 
                                        clickedContentDesc.contains("clear all") || 
                                        clickedContentDesc.contains("close all") || 
                                        clickedContentDesc.contains("remove all")
                    
                    if (isClearAllBtn) {
                        Log.d(TAG, "Clear All / Close All clicked in Launcher/Recents, resetting all sessions!")
                        resetSession(youtubeSession)
                        resetSession(instagramSession)
                    }
                }
                if (rootNode != null) {
                    try {
                        rootNode.recycle()
                    } catch (e: Exception) {
                        // Safe ignore
                    }
                }
            }
            
            // Checking for leaving target apps on actual window state transitions (where the active window shifts package)
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (!isSystemOverlay) {
                    val isRealExit = ((packageName != this.packageName) || com.example.MainActivity.isMainActivityResumed) && !isTargetAppVisible()
                    if (isRealExit) {
                        if (lastActivePackage == "Instagram" || lastActivePackage == "YouTube") {
                            Log.d(TAG, "User left target app (cut/exit) to $packageName")
                        }
                        hasLeftTargetApps = true
                        lastActivePackage = packageName
                        hideOverlayInstantly()
                    }
                }
            }
        }
    }

    private fun isExactTargetPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        val lower = pkg.lowercase(java.util.Locale.getDefault())
        
        // Explicit blacklist of known utility/creator/child/music apps containing target names
        if (lower.contains("creator") || 
            lower.contains("studio") || 
            lower.contains("music") || 
            lower.contains("cutter") || 
            lower.contains("trimmer") || 
            lower.contains("downloader") || 
            lower.contains("editor") || 
            lower.contains("converter") ||
            lower.contains("helper") ||
            lower.contains("kids")
        ) {
            return false
        }
        
        // Explicit list of permitted primary target packages
        if (lower == "com.google.android.youtube" || 
            lower == "com.instagram.android" || 
            lower == "com.instagram.lite" ||
            lower == "com.vanced.android.youtube" ||
            lower == "app.revanced.android.youtube"
        ) {
            return true
        }
        
        // Fallback safe checks: must end with .youtube or .instagram
        if (lower.endsWith(".youtube") || lower == "youtube" || lower.endsWith(".instagram") || lower == "instagram") {
            return true
        }
        
        return false
    }

    private fun isTargetAppVisible(): Boolean {
        try {
            val windowList = windows
            if (windowList != null && windowList.isNotEmpty()) {
                for (window in windowList) {
                    // Ignore Picture-in-Picture (PiP) windows entirely
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        if (window.isInPictureInPictureMode) {
                            continue
                        }
                    }
                    val root = window.root
                    if (root != null) {
                        val pkgName = root.packageName?.toString() ?: ""
                        try { root.recycle() } catch (e: Exception) {}
                        if (isExactTargetPackage(pkgName)) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking candidate visible windows on screen", e)
        }
        return false
    }

    private fun isTemporarySystemOverlay(pkg: String): Boolean {
        if (pkg == "android") return true
        
        val lowerPkg = pkg.lowercase(java.util.Locale.getDefault())
        // Input Methods (Keyboards) must not be counted as leaving to an app, they overlay
        if (lowerPkg.contains("inputmethod") || lowerPkg.contains("latin") || lowerPkg.contains("keyboard")) {
            return true
        }

        // SystemUI, Lockscreen, Notification Shade, Control Center, System Bar, Volume Panel
        if (lowerPkg.startsWith("com.android.systemui") || 
            lowerPkg.contains("systemui") || 
            lowerPkg.contains("controlcenter") || 
            lowerPkg.contains("statusbar") || 
            lowerPkg.contains("volume") ||
            lowerPkg.contains("keyguard")
        ) {
            return true
        }

        // Google Assistant and voice overlays (e.g., Google Assistant, Bixby, etc.)
        if (lowerPkg.contains("googlequicksearchbox") || 
            lowerPkg.contains("googleassistant") || 
            lowerPkg.contains("assistant") || 
            lowerPkg.contains("bixby") || 
            lowerPkg.contains("voice")
        ) {
            return true
        }

        // Permissions and basic system dialog overlays
        if (lowerPkg.contains("permissioncontroller")) {
            return true
        }

        return false
    }

    private fun isSystemPackage(pkg: String): Boolean {
        if (pkg == "android" ||
            pkg.startsWith("com.android.systemui") ||
            pkg.startsWith("com.google.android.inputmethod") ||
            pkg.contains("inputmethod") ||
            pkg.startsWith("com.google.android.permissioncontroller") ||
            pkg.startsWith("com.android.permissioncontroller") ||
            pkg == "com.example" ||
            pkg == this.packageName ||
            pkg.startsWith("com.google.android.gms")
        ) {
            return true
        }

        // OEM-specific system launchers/UIs or Google tools
        if (pkg.contains("launcher", ignoreCase = true) ||
            pkg.contains("systemui", ignoreCase = true) ||
            pkg.contains("controlcenter", ignoreCase = true) ||
            pkg.contains("googlequicksearchbox", ignoreCase = true) ||
            pkg.contains("googleassistant", ignoreCase = true) ||
            pkg.contains("assistant", ignoreCase = true) ||
            pkg.contains("bixby", ignoreCase = true)
        ) {
            return true
        }

        // Robust package manager flag check to identify any custom pre-installed system UIs or launchers
        try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(pkg, 0)
            val isSys = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                        (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSys) {
                return true
            }
        } catch (e: Exception) {
            // Safe ignore if the package isn't currently installed/accessible
        }

        return false
    }



    private class ScreenScanResult {
        var creator: String? = null
        var isAd: Boolean = false
        val instagramUsernameCandidates = ArrayList<String>()
        var description: String? = null
        val wordsSeen = HashSet<String>()
        val viewIdsSeen = HashSet<String>()
        val dynamicTexts = ArrayList<String>()
        var hasEditText: Boolean = false
        var hasSurfaceOrVideoView: Boolean = false
    }

    private fun scanScreen(packageName: String): ScreenScanResult {
        val result = ScreenScanResult()
        
        var targetRoot: AccessibilityNodeInfo? = null
        try {
            val activeRoot = rootInActiveWindow
            if (activeRoot != null) {
                val pkg = activeRoot.packageName?.toString() ?: ""
                if (pkg.equals(packageName, ignoreCase = true)) {
                    targetRoot = activeRoot
                } else {
                    try { activeRoot.recycle() } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking rootInActiveWindow", e)
        }

        if (targetRoot == null) {
            try {
                val windowList = windows
                if (windowList != null) {
                    for (window in windowList) {
                        // Ignore Picture-in-Picture (PiP) windows
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            if (window.isInPictureInPictureMode) {
                                continue
                            }
                        }
                        val root = window.root
                        if (root != null) {
                            val pkg = root.packageName?.toString() ?: ""
                            if (pkg.equals(packageName, ignoreCase = true)) {
                                targetRoot = root
                                break
                            } else {
                                try { root.recycle() } catch (e: Exception) {}
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error iterating windows", e)
            }
        }
        
        val rootNode = targetRoot ?: rootInActiveWindow ?: return result
        
        scanNode(rootNode, result, packageName, result.wordsSeen)
        
        try {
            rootNode.recycle()
        } catch (e: Exception) {
            // Ignore
        }
        
        if (result.isAd) {
            return result
        }
        
        if ((packageName == "com.instagram.android" || packageName == "com.instagram.lite") && result.creator == null) {
            val hasFollowButton = result.wordsSeen.contains("follow") || result.wordsSeen.contains("following")
            if (hasFollowButton) {
                result.creator = result.instagramUsernameCandidates.firstOrNull()
            }
        }
        
        return result
    }

    private fun scanNode(
        node: AccessibilityNodeInfo?, 
        result: ScreenScanResult, 
        packageName: String, 
        wordsSeen: HashSet<String>,
        depth: Int = 0
    ) {
        if (node == null || result.isAd || depth > 50) return
        try {
            // Some customized ROMs return false for isVisibleToUser on active layout containers or scrolling items.
            // By ignoring physical visibility restrictions, we ensure text and viewId are extracted successfully on all Android devices.
            val isVisible = true
            
            val navVid = node.viewIdResourceName?.toString() ?: ""
            val navClassName = node.className?.toString() ?: ""
            
            if (navVid.isNotEmpty()) {
                result.viewIdsSeen.add(navVid)
            }
            
            if (navClassName.contains("EditText", ignoreCase = true) || node.isEditable) {
                result.hasEditText = true
            }

            if (navClassName.contains("TextureView", ignoreCase = true) ||
                navClassName.contains("SurfaceView", ignoreCase = true) ||
                navClassName.contains("VideoView", ignoreCase = true) ||
                navClassName.contains("PlayerView", ignoreCase = true)
            ) {
                result.hasSurfaceOrVideoView = true
            }

            val isProgressOrSeekView = 
                navVid.contains("progress", ignoreCase = true) ||
                navVid.contains("seekbar", ignoreCase = true) ||
                navVid.contains("time_bar", ignoreCase = true) ||
                navVid.contains("timebar", ignoreCase = true) ||
                navVid.contains("timeline", ignoreCase = true) ||
                navVid.contains("duration", ignoreCase = true) ||
                navVid.contains("volume", ignoreCase = true) ||
                navVid.contains("slider", ignoreCase = true) ||
                navVid.contains("thumb", ignoreCase = true) ||
                navClassName.contains("SeekBar", ignoreCase = true) ||
                navClassName.contains("ProgressBar", ignoreCase = true) ||
                navClassName.contains("TimeBar", ignoreCase = true) ||
                navClassName.contains("Timeline", ignoreCase = true) ||
                navClassName.contains("Volume", ignoreCase = true) ||
                navClassName.contains("Slider", ignoreCase = true) ||
                navClassName.contains("Progress", ignoreCase = true)
            
            if (isProgressOrSeekView) {
                return
            }
            
            // Avoid false positives from tab labels, system bars, status lines or search headers
            if (navVid.contains("tab_bar", ignoreCase = true) || 
                navVid.contains("pivot_bar", ignoreCase = true) || 
                navVid.contains("bottom_navigation", ignoreCase = true) || 
                navVid.contains("navigation_bar", ignoreCase = true) || 
                navVid.contains("bottom_menu", ignoreCase = true) || 
                navVid.contains("action_bar", ignoreCase = true) ||
                navVid.contains("header", ignoreCase = true) ||
                navVid.contains("title_bar", ignoreCase = true) ||
                navVid.contains("reels_tab", ignoreCase = true) ||
                navVid.contains("shorts_tab", ignoreCase = true) ||
                navClassName.contains("TabBar", ignoreCase = true) ||
                navClassName.contains("BottomNavigation", ignoreCase = true) ||
                navClassName.contains("NavigationBar", ignoreCase = true)
            ) {
                return
            }
            
            val isProfileOrStaticOrTabNode = navVid.contains("profile", ignoreCase = true) || 
                                             navVid.contains("bio", ignoreCase = true) || 
                                             navVid.contains("header", ignoreCase = true) || 
                                             navVid.contains("follower", ignoreCase = true) ||
                                             navVid.contains("tab", ignoreCase = true) ||
                                             navVid.contains("button_row", ignoreCase = true) ||
                                             navVid.contains("search", ignoreCase = true) ||
                                             navVid.contains("menu", ignoreCase = true)
            
            if (isVisible) {
                val vid = node.viewIdResourceName?.toString()
                if (!vid.isNullOrEmpty()) {
                    result.viewIdsSeen.add(vid)
                }
                
                val txt = node.text?.toString()?.trim()
                if (!txt.isNullOrEmpty()) {
                    val lowerTxt = txt.lowercase()
                    wordsSeen.add(lowerTxt)
                    
                    // Active Ad Check
                    if (isAdText(lowerTxt)) {
                        result.isAd = true
                        return
                    }
                    
                    if (!isProfileOrStaticOrTabNode && !isStaticControlWord(lowerTxt) && !isNumericOrCount(lowerTxt) && !isProgressOrTimeText(txt) && txt.length >= 2) {
                        if (!result.dynamicTexts.contains(txt)) {
                            result.dynamicTexts.add(txt)
                        }
                    }
                    
                    // YouTube Shorts Creator Check
                    if (packageName != "com.instagram.android" && packageName != "com.instagram.lite") {
                        if (!isProfileOrStaticOrTabNode && result.creator == null && txt.startsWith("@") && txt.length > 1) {
                            result.creator = txt
                        }
                        
                        // Sibling detection if it is a subscribe button
                        val isSubscribeBtn = lowerTxt == "subscribe" || 
                                             lowerTxt == "subscribed" || 
                                             lowerTxt == "s'abonner" || 
                                             lowerTxt == "abonné" || 
                                             lowerTxt == "suscribirse" || 
                                             lowerTxt == "suscrito" || 
                                             lowerTxt == "abonnieren" || 
                                             lowerTxt == "abbonati"
                        if (isSubscribeBtn) {
                            val parent = node.parent
                            if (parent != null) {
                                try {
                                    val childCount = parent.childCount
                                    for (j in 0 until childCount) {
                                        val sibling = parent.getChild(j)
                                        if (sibling != null) {
                                            val sibTxt = sibling.text?.toString()?.trim()
                                            if (!sibTxt.isNullOrEmpty() && 
                                                sibTxt != txt && 
                                                sibTxt.length in 3..45 && 
                                                !sibTxt.lowercase().startsWith("subscrib") && 
                                                !sibTxt.contains("likes", ignoreCase = true) && 
                                                !sibTxt.contains("comments", ignoreCase = true)
                                            ) {
                                                result.creator = sibTxt
                                                try { sibling.recycle() } catch (e: Exception) {}
                                                break
                                            }
                                            try { sibling.recycle() } catch (e: Exception) {}
                                        }
                                    }
                                } finally {
                                    try { parent.recycle() } catch (e: Exception) {}
                                }
                            }
                        }
                    } else {
                        // Instagram candidates inside node texts
                        if (!isProfileOrStaticOrTabNode && txt.length in 3..30 && txt.matches(Regex("^[a-zA-Z0-9_.]+$"))) {
                            val lower = txt.lowercase()
                            if (lower != "reels" && lower != "search" && lower != "profile" && lower != "follow" && lower != "following") {
                                result.instagramUsernameCandidates.add(txt)
                            }
                        }
                    }

                    // Collect description / captions to enrich video unique signature
                    if (!isProfileOrStaticOrTabNode && 
                        txt.length in 15..200 && 
                        !txt.contains("likes", ignoreCase = true) && 
                        !txt.contains("views", ignoreCase = true) && 
                        !txt.contains("comments", ignoreCase = true) && 
                        !txt.contains("subscribe", ignoreCase = true)
                    ) {
                        if (result.description == null) {
                            result.description = txt
                        }
                    }
                }
                
                val desc = node.contentDescription?.toString()?.trim()
                if (!desc.isNullOrEmpty()) {
                    val lowerDesc = desc.lowercase()
                    wordsSeen.add(lowerDesc)
                    
                    // Active Ad Check
                    if (isAdText(lowerDesc)) {
                        result.isAd = true
                        return
                    }
                    
                    if (!isProfileOrStaticOrTabNode && !isStaticControlWord(lowerDesc) && !isNumericOrCount(lowerDesc) && !isProgressOrTimeText(desc) && desc.length >= 2) {
                        if (!result.dynamicTexts.contains(desc)) {
                            result.dynamicTexts.add(desc)
                        }
                    }
                    
                    // Instagram Creator contentDescription check
                    if (!isProfileOrStaticOrTabNode && (packageName == "com.instagram.android" || packageName == "com.instagram.lite") && result.creator == null) {
                        if (desc.startsWith("Reel by ", ignoreCase = true)) {
                            val handle = desc.substringAfter("Reel by ").trim()
                            if (handle.isNotEmpty()) {
                                result.creator = handle
                            }
                        }
                    }
                }
            }
            
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    scanNode(child, result, packageName, wordsSeen, depth + 1)
                    try {
                        child.recycle()
                    } catch (e: Exception) {
                        // Safe ignore
                    }
                }
            }
        } catch (e: Exception) {
            // Safe ignore
        }
    }

    private fun isStaticControlWord(word: String): Boolean {
        val statics = setOf(
            "subscribe", "subscribed", "s'abonner", "abonné", "suscribirse", "suscrito", "abonnieren", "abbonati",
            "like", "likes", "dislike", "dislikes", "comment", "comments", "share", "remix", "follow", "following",
            "reels", "shorts", "search", "profile", "home", "shop", "add a comment...", "add comment", "votre commentaire...",
            "views", "view",
            "नापसंद", "नापसंद करें", "पसंद", "शेयर", "शेयर करें", "टिप्पणी", "टिप्पणियां", "सदस्यता", "सदस्यता लें", "सदस्यता ली गई", "फॉलो", "फॉलो करें", "वीडियो", "खोजें", "होम", "शॉर्ट्स", "रील्स"
        )
        if (word in statics) return true
        if (word.contains("subscriber") || word.contains("subscrib")) return true
        return false
    }

    private fun isNumericOrCount(word: String): Boolean {
        val numRegex = Regex("""^\d+([.,]\d+)?[kMmbB]?$""")
        return word.matches(numRegex) || word.all { it.isDigit() || it == ',' || it == '.' || it == '%' }
    }

    private fun isProgressOrTimeText(word: String): Boolean {
        val clean = word.replace("\\s".toRegex(), "")
        // Matches things like "0:15", "1:23", "12:34", "1:23:45", "0:01/2:30", "05:12/10:00"
        val timePattern = Regex("""^\d+(:\d+)+((/\d+(:\d+)+)?)$""")
        if (clean.matches(timePattern)) return true
        
        val lower = word.lowercase(java.util.Locale.getDefault())
        if (lower.contains("seek") || lower.contains("duration") || lower.contains("progress") || lower.contains("timeline")) {
            return true
        }
        
        return false
    }

    private fun getStableSignature(result: ScreenScanResult, platform: String): String {
        val creator = result.creator?.lowercase()?.trim() ?: "unknown"
        val desc = result.description?.lowercase()?.trim() ?: "nodesc"
        
        if (creator == "unknown" || desc == "nodesc") {
            val longestStableText = result.dynamicTexts
                .filter { it.length >= 4 && !isNumericOrCount(it) && !isStaticControlWord(it.lowercase()) && !isProgressOrTimeText(it) }
                .maxByOrNull { it.length }
                ?.lowercase()?.trim() ?: ""
            return "$platform:$creator:$desc:$longestStableText"
        }
        
        return "$platform:$creator:$desc"
    }

    private fun isAdText(lower: String): Boolean {
        val adKeywords = setOf(
            "sponsored", "sponsorisé", "sponsorisée", "patrocinado", "gesponsert", "sponsorizzato",
            "advertisement", "publicidad", "werbung", "anuncio", "promoted",
            "shop now", "install now", "play game", 
            "order now", "get offer", "apply now", "book now", "visit website", "visit advertiser site", "go to site"
        )
        return lower in adKeywords || lower.startsWith("ad •") || lower.contains("paid partnership with") || lower.contains("sponsored by")
    }

    private fun incrementAndPersist(platform: String, creatorHandle: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                repository.incrementCount(platform, creatorHandle)
                Log.d(TAG, "Successfully logged view of $platform Reel/Short by $creatorHandle")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving count", e)
            }
        }
    }

    private fun checkAndHandleNewDay(): Boolean {
        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        if (lastCheckedDateStr != todayStr) {
            val isFirstInit = lastCheckedDateStr == null
            lastCheckedDateStr = todayStr
            if (!isFirstInit) {
                Log.d(TAG, "New day detected! Resetting state and re-observing daily count. New date: $todayStr")
                
                // Reset in-memory cached counts
                currentCount = 0
                
                // Cleanly reset both active sessions for the new day
                youtubeSession.sessionCount = 0
                youtubeSession.hasShownReminderForCurrentSession = false
                youtubeSession.nextReminderThreshold = -1
                youtubeSession.sessionViewedSignatures.clear()
                youtubeSession.lastRecordedSignature = null
                saveStoredSessionCount("YouTube", 0)

                instagramSession.sessionCount = 0
                instagramSession.hasShownReminderForCurrentSession = false
                instagramSession.nextReminderThreshold = -1
                instagramSession.sessionViewedSignatures.clear()
                instagramSession.lastRecordedSignature = null
                saveStoredSessionCount("Instagram", 0)

                // Re-observe today's database count
                observeTodayCount()
                
                // Hide or update overlay to reflect the reset
                updateOverlayText(0, show = false)
                return true
            }
        }
        return false
    }

    private fun observeTodayCount() {
        val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        if (lastCheckedDateStr == null) {
            lastCheckedDateStr = todayStr
        }
        todayCountJob?.cancel()
        todayCountJob = serviceScope.launch {
            // Collect daily count
            launch {
                while (isActive) {
                    try {
                        repository.getDailyCountFlow(todayStr).collect { dailyCount ->
                            val reels = dailyCount?.reelsCount ?: 0
                            val shorts = dailyCount?.shortsCount ?: 0
                            val dbCount = reels + shorts
                            // Only update locally if DB has a larger count or was reset to 0 to prevent rollbacks during race conditions
                            if (dbCount > currentCount || dbCount == 0) {
                                currentCount = dbCount
                                if (dbCount == 0) {
                                    youtubeSession.sessionCount = 0
                                    saveStoredSessionCount("YouTube", 0)
                                    youtubeSession.hasShownReminderForCurrentSession = false
                                    youtubeSession.nextReminderThreshold = -1
                                    youtubeSession.sessionViewedSignatures.clear()
                                    youtubeSession.lastRecordedSignature = null

                                    instagramSession.sessionCount = 0
                                    saveStoredSessionCount("Instagram", 0)
                                    instagramSession.hasShownReminderForCurrentSession = false
                                    instagramSession.nextReminderThreshold = -1
                                    instagramSession.sessionViewedSignatures.clear()
                                    instagramSession.lastRecordedSignature = null

                                    updateOverlayText(0, show = false)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception collecting daily counts flow, retrying...", e)
                        delay(2000L)
                    }
                }
            }
            // Collect viewed items to automatically maintain current session's deduplication set
            launch {
                while (isActive) {
                    try {
                        repository.latestViewedItems.collect { items ->
                            recentViews.clear()
                            items.forEach { item ->
                                val creator = item.creatorHandle
                                if (creator.isNotEmpty() && !creator.equals("Creator", ignoreCase = true)) {
                                    recentViews.add("${item.platform}:${creator.lowercase()}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception collecting latest viewed items flow, retrying...", e)
                        delay(2000L)
                    }
                }
            }
        }
    }

    private fun createOverlayView() {
        if (!Settings.canDrawOverlays(this)) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val context = this
        val dpToPx = { dp: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
        }

        // Parent FrameLayout to intercept drag gestures safely
        val frameLayout = FrameLayout(context)

        // Pill holder container
        val pillContainer = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12f), dpToPx(8f), dpToPx(12f), dpToPx(8f))

            val gd = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(20f).toFloat()
                setColor(Color.parseColor("#E6121216")) // Elegant translucent absolute dark slate
                setStroke(dpToPx(1.5f), Color.parseColor("#33FFFFFF")) // Glow White border
            }
            background = gd
        }

        // Active glowing confirmation dot
        val dotView = View(context).apply {
            val size = dpToPx(8f)
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dpToPx(8f)
            }
            val dotGd = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#34D399")) // Neon emerald
            }
            background = dotGd
        }

        // Live total views counter text
        val textView = TextView(context).apply {
            text = "Count: 0"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        pillContainer.addView(dotView)
        pillContainer.addView(textView)
        frameLayout.addView(pillContainer)

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.END
            x = dpToPx(16f)
            y = dpToPx(85f) // Safely offset below standard camera hole lines
        }

        overlayParams = params
        overlayView = frameLayout

        // Drag to reposition overlay action
        frameLayout.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        // Moving left from gravity END decreases position on X, so subtract dx
                        params.x = initialX - dx
                        params.y = initialY + dy

                        try {
                            windowManager?.updateViewLayout(frameLayout, params)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed updating view placement during drag gesture", e)
                        }
                        return true
                    }
                }
                return false
            }
        })

        // Start hidden to remain perfectly non-offensive/non-irritating
        frameLayout.visibility = View.GONE

        try {
            windowManager?.addView(frameLayout, params)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to draw system overlay window", e)
        }
    }

    private fun updateOverlayText(count: Int, show: Boolean = true) {
        if (overlayView == null) {
            createOverlayView()
        }

        val frame = overlayView as? FrameLayout ?: return
        val pillContainer = frame.getChildAt(0) as? android.widget.LinearLayout ?: return
        val textView = pillContainer.getChildAt(1) as? TextView ?: return

        try {
            textView.text = "Count: $count"
        } catch (e: Exception) {
            // Safe ignore
        }

        if (show) {
            showOverlayWithAnimation(frame)
        }
    }

    private fun hideOverlayInstantly() {
        try {
            overlayView?.let { frame ->
                frame.animate()?.cancel()
                frame.visibility = View.GONE
                frame.alpha = 0f
            }
            hideOverlayJob?.cancel()
        } catch (e: Exception) {
            // Safe ignore
        }
    }

    private fun showOverlayWithAnimation(frame: View) {
        try {
            frame.visibility = View.VISIBLE
            frame.alpha = 1.0f

            // Play smooth, comforting pop scale bounce animation on new view tracked
            frame.animate()
                .scaleX(1.15f)
                .scaleY(1.15f)
                .setDuration(100)
                .withEndAction {
                    try {
                        frame.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(100)
                            .start()
                    } catch (e: Exception) {
                        // Guard animations
                    }
                }
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "Error executing entrance animations", e)
        }

        hideOverlayJob?.cancel()
        
        // Hide overlay automatically after exactly 5 seconds of perfect quietness as requested to maximize visibility context
        hideOverlayJob = serviceScope.launch {
            delay(5000)
            try {
                frame.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction {
                        try {
                            frame.visibility = View.GONE
                        } catch (e: Exception) {
                            // Guard visibility change
                        }
                    }
                    .start()
            } catch (e: Exception) {
                Log.e(TAG, "Error executing fade-out exit animations", e)
                try {
                    frame.visibility = View.GONE
                } catch (ex: Exception) {
                    // Ignore
                }
            }
        }
    }

    private fun removeOverlay() {
        todayCountJob?.cancel()
        hideOverlayJob?.cancel()
        activeScanJob?.cancel()
        removeLimitReminderOverlay()
        overlayView?.let {
            try {
                it.animate()?.cancel()
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing window overlay", e)
            }
            overlayView = null
        }
    }

    private fun showLimitReminderOverlay(limit: Int) {
        if (!Settings.canDrawOverlays(this)) return

        // Ensure any existing reminder is removed first
        removeLimitReminderOverlay()

        val context = this
        val dpToPx = { dp: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics).toInt()
        }

        // Root container (to fill parent and allow dimming/padding)
        val rootLayout = FrameLayout(context).apply {
            setPadding(dpToPx(24f), dpToPx(24f), dpToPx(24f), dpToPx(24f))
        }

        // Card frame
        val cardFrame = FrameLayout(context).apply {
            val gd = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(24f).toFloat()
                setColor(Color.WHITE)
                setStroke(dpToPx(1.5f), Color.parseColor("#E2E8F0")) // Pale border matching app design
            }
            background = gd
            setPadding(dpToPx(20f), dpToPx(24f), dpToPx(20f), dpToPx(24f))
            
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(300f),
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
        }

        // Content container inside Card Frame
        val contentContainer = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Beautiful illustrated Warning Icon Container (Matches dynamic circles)
        val iconContainer = FrameLayout(context).apply {
            val size = dpToPx(56f)
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                bottomMargin = dpToPx(16f)
            }
            val gd = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EEF2F6"))
            }
            background = gd
        }

        // Hourglass emoji to be centered as modern Gen Z illustrative art
        val iconView = TextView(context).apply {
            text = "⏳"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        iconContainer.addView(iconView)
        contentContainer.addView(iconContainer)

        // Alert Title
        val titleView = TextView(context).apply {
            text = "Time for a Break!"
            setTextColor(Color.parseColor("#0C1B33"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(8f)
            }
        }
        contentContainer.addView(titleView)

        // Alert Body
        val bodyView = TextView(context).apply {
            text = "You have watched $limit reels or shorts in this session. Let's take a quick breather to keep your screen habits healthy!"
            setTextColor(Color.parseColor("#475569"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(20f)
            }
        }
        contentContainer.addView(bodyView)

        // Action button "Got it!" that dismisses the reminder
        val actionButton = TextView(context).apply {
            text = "Got it"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dpToPx(24f), dpToPx(12f), dpToPx(24f), dpToPx(12f))
            
            val gd = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(14f).toFloat()
                setColor(Color.parseColor("#4F46E5")) // Elegant brand purple/blue matching app design
            }
            background = gd
            
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            
            setOnClickListener {
                removeLimitReminderOverlay()
            }
        }
        contentContainer.addView(actionButton)

        // "Remind me again" button below "Got it"
        val remindAgainButton = TextView(context).apply {
            text = "Remind me again (in $limit views)"
            setTextColor(Color.parseColor("#4F46E5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dpToPx(24f), dpToPx(12f), dpToPx(24f), dpToPx(12f))
            
            val gd = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(14f).toFloat()
                setColor(Color.parseColor("#EEF2F6")) // Light background matching material pairing
            }
            background = gd
            
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(10f)
            }
            
            setOnClickListener {
                activeSession.nextReminderThreshold = activeSession.sessionCount + limit
                activeSession.hasShownReminderForCurrentSession = false
                removeLimitReminderOverlay()
            }
        }
        contentContainer.addView(remindAgainButton)

        cardFrame.addView(contentContainer)
        rootLayout.addView(cardFrame)

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            dimAmount = 0.55f // Dim background nicely
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.CENTER
        }

        reminderOverlayView = rootLayout

        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.addView(rootLayout, params)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to draw system limit reminder overlay", e)
        }
    }

    private fun removeLimitReminderOverlay() {
        reminderOverlayView?.let { v ->
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                wm.removeView(v)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing limit reminder overlay", e)
            }
            reminderOverlayView = null
        }
    }

    private fun resetSession(session: PlatformSessionState) {
        session.sessionCount = 0
        if (session == youtubeSession) {
            saveStoredSessionCount("YouTube", 0)
        } else if (session == instagramSession) {
            saveStoredSessionCount("Instagram", 0)
        }
        session.hasShownReminderForCurrentSession = false
        session.nextReminderThreshold = -1
        session.sessionViewedSignatures.clear()
        session.lastRecordedSignature = null
    }

    private fun getStoredSessionCount(platform: String): Int {
        try {
            val prefs = getSharedPreferences("counter_prefs", MODE_PRIVATE) ?: return 0
            val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            val savedDate = prefs.getString("${platform}_session_date", "") ?: ""
            if (savedDate == todayStr) {
                return prefs.getInt("${platform}_session_count", 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading stored session count for $platform", e)
        }
        return 0
    }

    private fun saveStoredSessionCount(platform: String, count: Int) {
        try {
            val prefs = getSharedPreferences("counter_prefs", MODE_PRIVATE) ?: return
            val todayStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            prefs.edit()
                .putString("${platform}_session_date", todayStr)
                .putInt("${platform}_session_count", count)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving session count for $platform", e)
        }
    }

    private fun getLauncherPackageName(): String {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            intent.addCategory(android.content.Intent.CATEGORY_HOME)
            val resolveInfo = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            return resolveInfo?.activityInfo?.packageName ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving launcher package name", e)
        }
        return ""
    }

    private fun isRecentsOrLauncher(pkg: String): Boolean {
        val launcherPkg = getLauncherPackageName()
        if (pkg == launcherPkg || pkg == "com.android.systemui") return true
        
        val lower = pkg.lowercase(java.util.Locale.getDefault())
        if (lower.contains("launcher") || 
            lower.contains("trebuchet") || 
            lower.contains("recents") || 
            lower.contains("nexuslauncher") || 
            lower.contains("pixelpartner") || 
            lower.contains("sec.android.app.launcher")
        ) {
            return true
        }
        return false
    }

    private fun isRecentsScreenActive(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        val classNamesSeen = mutableListOf<String>()
        val viewIdsSeen = mutableListOf<String>()
        val textsSeen = mutableListOf<String>()
        
        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val cls = node.className?.toString() ?: ""
                if (cls.isNotEmpty()) classNamesSeen.add(cls)
                val id = node.viewIdResourceName?.toString() ?: ""
                if (id.isNotEmpty()) viewIdsSeen.add(id)
                val txt = node.text?.toString() ?: ""
                if (txt.isNotEmpty()) textsSeen.add(txt)
                val desc = node.contentDescription?.toString() ?: ""
                if (desc.isNotEmpty()) textsSeen.add(desc)
                
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        traverse(child)
                        try { child.recycle() } catch (ex: Exception) {}
                    }
                }
            } catch (e: Exception) {
                // Ignore node recycling/access errors
            }
        }
        
        traverse(rootNode)
        
        val hasRecentsIndicator = classNamesSeen.any { cls ->
            cls.contains("RecentsView", ignoreCase = true) ||
            cls.contains("TaskView", ignoreCase = true) ||
            cls.contains("TaskThumbnailView", ignoreCase = true)
        } || viewIdsSeen.any { id ->
            id.contains("recents", ignoreCase = true) ||
            id.contains("overview", ignoreCase = true) ||
            id.contains("task_view", ignoreCase = true)
        } || textsSeen.any { txt ->
            txt.contains("Clear all", ignoreCase = true) ||
            txt.contains("Close all", ignoreCase = true) ||
            txt.contains("Screenshot", ignoreCase = true) ||
            txt.contains("Overview", ignoreCase = true)
        }
        
        return hasRecentsIndicator
    }

    private fun scanRecentsForTargetCards(rootNode: AccessibilityNodeInfo?): Set<String> {
        val found = mutableSetOf<String>()
        if (rootNode == null) return found
        
        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            try {
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                
                // Match target platforms
                if (text.contains("YouTube", ignoreCase = true) || desc.contains("YouTube", ignoreCase = true)) {
                    found.add("YouTube")
                }
                if (text.contains("Instagram", ignoreCase = true) || desc.contains("Instagram", ignoreCase = true)) {
                    found.add("Instagram")
                }
                
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        traverse(child)
                        try { child.recycle() } catch (ex: Exception) {}
                    }
                }
            } catch (e: Exception) {
                // Ignore node recycling/access errors
            }
        }
        
        traverse(rootNode)
        return found
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service Interrupt called")
    }
}
