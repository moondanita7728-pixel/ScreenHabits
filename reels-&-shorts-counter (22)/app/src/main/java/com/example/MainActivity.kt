package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.DailyCount
import com.example.data.ViewedItem
import com.example.ui.CounterViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    companion object {
        @JvmStatic
        var isMainActivityResumed = false
    }

    override fun onPause() {
        super.onPause()
        isMainActivityResumed = false
    }

    private val viewModel: CounterViewModel by viewModels {
        CounterViewModel.Factory(CounterApplication.getRepository())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val prefs = remember { context.getSharedPreferences("counter_prefs", Context.MODE_PRIVATE) }
                var showOnboarding by remember {
                    mutableStateOf(!prefs.getBoolean("has_accepted_privacy", false))
                }

                var showAccessibilityDisclosure by remember { mutableStateOf(false) }
                var showOverlayDisclosure by remember { mutableStateOf(false) }
                var showBatteryDisclosure by remember { mutableStateOf(false) }

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        try {
                            val check = androidx.core.content.ContextCompat.checkSelfPermission(
                                context,
                                "android.permission.POST_NOTIFICATIONS"
                            )
                            if (check != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                requestPermissions(
                                    arrayOf("android.permission.POST_NOTIFICATIONS"),
                                    102
                                )
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Failed to request notification permission", e)
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.White
                ) { innerPadding ->
                    if (showOnboarding) {
                        PrivacyOnboardingScreen(
                            onOnboardingComplete = {
                                prefs.edit().putBoolean("has_accepted_privacy", true).apply()
                                showOnboarding = false
                            },
                            modifier = Modifier.padding(innerPadding)
                        )
                    } else {
                        MainDashboardScreen(
                            viewModel = viewModel,
                            onOpenSettingsClick = { showAccessibilityDisclosure = true },
                            onOpenOverlayClick = { showOverlayDisclosure = true },
                            onOpenBatteryClick = { showBatteryDisclosure = true },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }

                if (showAccessibilityDisclosure) {
                    AccessibilityDisclosureDialog(
                        onDismissRequest = { showAccessibilityDisclosure = false },
                        onConfirm = {
                            showAccessibilityDisclosure = false
                            openAccessibilitySettings()
                        }
                    )
                }

                if (showOverlayDisclosure) {
                    OverlayDisclosureDialog(
                        onDismissRequest = { showOverlayDisclosure = false },
                        onConfirm = {
                            showOverlayDisclosure = false
                            openOverlaySettings()
                        }
                    )
                }

                if (showBatteryDisclosure) {
                    BatteryDisclosureDialog(
                        onDismissRequest = { showBatteryDisclosure = false },
                        onConfirm = {
                            showBatteryDisclosure = false
                            openBatterySettings()
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isMainActivityResumed = true
        viewModel.checkAccessibilityStatus(this)
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
        }
    }

    private fun openOverlaySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                startActivity(intent)
            } catch (ex: Exception) {
                val intent = Intent(Settings.ACTION_SETTINGS)
                startActivity(intent)
            }
        }
    }

    private fun openBatterySettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (ex: Exception) {
                val intent = Intent(Settings.ACTION_SETTINGS)
                startActivity(intent)
            }
        }
    }
}

@Composable
fun MainDashboardScreen(
    viewModel: CounterViewModel,
    onOpenSettingsClick: () -> Unit,
    onOpenOverlayClick: () -> Unit,
    onOpenBatteryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isServiceActive by viewModel.isServiceActive.collectAsStateWithLifecycle()
    val isOverlayAllowed by viewModel.isOverlayAllowed.collectAsStateWithLifecycle()
    val isBatteryExempted by viewModel.isBatteryExempted.collectAsStateWithLifecycle()
    val isTrackingEnabled by viewModel.isTrackingEnabled.collectAsStateWithLifecycle()
    val todayCount by viewModel.todayCount.collectAsStateWithLifecycle()
    val dailyCounts by viewModel.dailyCounts.collectAsStateWithLifecycle()

    var showResetDialog by remember { mutableStateOf(false) }
    var logToDelete by remember { mutableStateOf<DailyCount?>(null) }

    val reelsToday = todayCount?.reelsCount ?: 0
    val shortsToday = todayCount?.shortsCount ?: 0
    val totalToday = reelsToday + shortsToday

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("counter_prefs", Context.MODE_PRIVATE) }
    var reminderLimit by remember { mutableStateOf(prefs.getInt("reminder_limit", 20)) }

    LaunchedEffect(Unit) {
        viewModel.checkAccessibilityStatus(context)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF4F8FC))
            .drawBehind {
                val w = size.width
                val h = size.height

                // Draw solid background color then soft glows
                drawRect(color = Color(0xFFF5F9FD))

                // Left side lavender radial glow (blunt and soft)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFE5D5FF).copy(alpha = 0.55f), Color.Transparent),
                        center = Offset(0f, h * 0.45f),
                        radius = w * 0.75f
                    ),
                    radius = w * 0.75f,
                    center = Offset(0f, h * 0.45f)
                )

                // Right side soft blue radial glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFCEE3FF).copy(alpha = 0.5f), Color.Transparent),
                        center = Offset(w, h * 0.8f),
                        radius = w * 0.65f
                    ),
                    radius = w * 0.65f,
                    center = Offset(w, h * 0.8f)
                )

                // Cozy wavy lavender cloud shape flowing from bottom-left
                val pathLeft = Path().apply {
                    moveTo(0f, h * 0.38f)
                    cubicTo(
                        w * 0.18f, h * 0.42f,
                        w * 0.32f, h * 0.50f,
                        w * 0.28f, h * 0.64f
                    )
                    cubicTo(
                        w * 0.25f, h * 0.75f,
                        w * 0.12f, h * 0.82f,
                        0f, h * 0.86f
                    )
                    close()
                }
                drawPath(
                    path = pathLeft,
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFE2D2FF).copy(alpha = 0.35f), Color(0xFFFFDDF2).copy(alpha = 0.25f)),
                        start = Offset(0f, h * 0.45f),
                        end = Offset(w * 0.25f, h * 0.60f)
                    )
                )
            }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            
            // 1. TOP HEADER - Sleek and modern Gen Z Logotype
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(modifier = Modifier.wrapContentSize()) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.padding(end = 12.dp) // Leave space for sparkle star
                        ) {
                            Text(
                                text = "Screen",
                                color = Color(0xFF0C1B33),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            )
                            Text(
                                text = "Habits",
                                color = Color(0xFF2563EB),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-0.5).sp
                            )
                        }
                        
                        // Drawn 4-point Sparkle Star to the top-right of 's'
                        Canvas(
                            modifier = Modifier
                                .size(14.dp)
                                .align(Alignment.TopEnd)
                                .offset(x = 10.dp, y = (-2).dp)
                        ) {
                            val path = Path().apply {
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val r = size.width / 2f
                                moveTo(cx, cy - r)
                                quadraticTo(cx, cy, cx + r, cy)
                                quadraticTo(cx, cy, cx, cy + r)
                                quadraticTo(cx, cy, cx - r, cy)
                                quadraticTo(cx, cy, cx, cy - r)
                                close()
                            }
                            drawPath(path = path, color = Color(0xFF2563EB))
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(2.dp))
                    
                    // Signature double wave underline matching logo doodle in screenshot
                    Canvas(
                        modifier = Modifier
                            .width(82.dp)
                            .height(6.dp)
                    ) {
                        val w = size.width
                        val h = size.height
                        val path = Path().apply {
                            moveTo(0f, h * 0.4f)
                            cubicTo(w * 0.25f, h * 0.1f, w * 0.35f, h * 0.9f, w * 0.5f, h * 0.4f)
                            cubicTo(w * 0.65f, h * 0.1f, w * 0.75f, h * 0.9f, w, h * 0.3f)
                        }
                        drawPath(
                            path = path,
                            color = Color(0xFF2563EB),
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                        )
                        
                        val path2 = Path().apply {
                            moveTo(12f, h * 0.8f)
                            cubicTo(w * 0.3f, h * 0.5f, w * 0.4f, h * 1.2f, w * 0.55f, h * 0.8f)
                            cubicTo(w * 0.7f, h * 0.5f, w * 0.8f, h * 1.2f, w - 8f, h * 0.7f)
                        }
                        drawPath(
                            path = path2,
                            color = Color(0xFF2563EB).copy(alpha = 0.6f),
                            style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }
            }

            // Single Feed Content Layout
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp)
            ) {
                
                // Active alerts & instructions shown ONLY when disabled or initial (Top Placement)
                if (!isServiceActive) {
                    item {
                        ServiceStatusCard(
                            onActionConfigClick = onOpenSettingsClick
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                if (!isOverlayAllowed) {
                    item {
                        FloatingOverlayStatusCard(
                            onActionConfigClick = onOpenOverlayClick
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                if (!isBatteryExempted) {
                    item {
                        BatteryOptimizationStatusCard(
                            onActionConfigClick = onOpenBatteryClick
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                // 2. Main Tracking Switch (Styled like the top switcher in the image)
                item {
                    MainStatusCard(
                        isEnabled = isTrackingEnabled,
                        onToggle = { viewModel.toggleTracking(context) }
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }

                // 3. Combined Today Count (Styled like the giant circle in the image)
                item {
                    TotalCountCard(
                        totalCount = totalToday,
                        reelsCount = reelsToday,
                        shortsCount = shortsToday
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }

                // 4. Side-by-side Instagram & YouTube Counters (Match the design/colors of bottom cards)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        InstagramCounterCard(
                            count = reelsToday,
                            modifier = Modifier.weight(1f)
                        )
                        YouTubeCounterCard(
                            count = shortsToday,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // 5. Beautiful Scrolling Limit Alert Controls (Newly added!)
                item {
                    ReminderLimitCard(
                        currentLimit = reminderLimit,
                        onLimitChange = { newLimit ->
                            reminderLimit = newLimit
                            prefs.edit().putInt("reminder_limit", newLimit).apply()
                            com.example.ui.CounterViewModel.sendSettingsBroadcast(context)
                        }
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }



                // 6. Daily summary lists
                if (dailyCounts.isNotEmpty()) {
                    item {
                        Text(
                            text = "Daily Highlights",
                            color = Color(0xFF0F172A),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                    items(dailyCounts) { log ->
                        DailyLogItemCard(
                            dailyCount = log,
                            onDelete = { logToDelete = log }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // 7. Clear habits data button (With Circular Arrow Refresh symbol matching screenshot)
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { showResetDialog = true }
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset Icon",
                            tint = Color(0xFF2563EB),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Reset all habits data",
                            color = Color(0xFF2563EB),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // Modal Confirmation Dialogs - Fully themed in modern Skyblue with no aggressive Red
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(text = "Reset Habits Info?") },
            text = { Text(text = "This will erase all recorded daily counts and scrolled stats permanently. Are you sure you want to proceed?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearData()
                        showResetDialog = false
                    }
                ) {
                    Text("Clear Data", color = Color(0xFF0EA5E9), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel", color = Color(0xFF64748B))
                }
            }
        )
    }



    if (logToDelete != null) {
        AlertDialog(
            onDismissRequest = { logToDelete = null },
            title = { Text(text = "Delete Daily Log?") },
            text = { Text(text = "Are you sure you want to delete the daily log for ${logToDelete?.date}? This will remove counts from your history view.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        logToDelete?.let { viewModel.deleteDailyCount(it.date) }
                        logToDelete = null
                    }
                ) {
                    Text("Delete", color = Color(0xFF0EA5E9), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { logToDelete = null }) {
                    Text("Cancel", color = Color(0xFF64748B))
                }
            }
        )
    }
}

@Composable
fun MainStatusCard(
    isEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(26.dp),
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(26.dp),
                clip = false,
                ambientColor = Color(0xFF000000).copy(alpha = 0.03f),
                spotColor = Color(0xFF2563EB).copy(alpha = 0.06f)
            ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF1F5F9))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Play Icon Container with Blue Linear Gradient
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF3B82F6), Color(0xFF1D4ED8))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Active Status Icon",
                        tint = Color.White,
                        modifier = Modifier
                            .size(22.dp)
                            .offset(x = 1.dp) // Optical centering of play triangle
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = "Active Counting Status",
                        color = Color(0xFF0C1B33),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isEnabled) "Counting is running & active" else "Counting is paused in-app",
                        color = if (isEnabled) Color(0xFF2563EB) else Color(0xFF64748B),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF2563EB),
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color(0xFFDBEAFE),
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
    }
}

@Composable
fun TotalCountCard(
    totalCount: Int,
    reelsCount: Int,
    shortsCount: Int,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(280.dp)
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            // Outer Concentric Dashed Ring Orbit (Exactly like the screenshot!)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val r = 132.dp.toPx() // Outer orbit radius
                drawCircle(
                    color = Color(0xFF93C5FD).copy(alpha = 0.5f),
                    radius = r,
                    style = Stroke(
                        width = 1.2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f), 0f)
                    )
                )
            }

            // Big Sparkle on upper-right
            Canvas(
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = (-16).dp, y = 36.dp)
            ) {
                val path = Path().apply {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = size.width / 2f
                    moveTo(cx, cy - r)
                    quadraticTo(cx, cy, cx + r, cy)
                    quadraticTo(cx, cy, cx, cy + r)
                    quadraticTo(cx, cy, cx - r, cy)
                    quadraticTo(cx, cy, cx, cy - r)
                    close()
                }
                drawPath(path = path, color = Color(0xFF818CF8)) // Purple indigo sparkle
            }

            // Small Sparkle on middle-left
            Canvas(
                modifier = Modifier
                    .size(12.dp)
                    .align(Alignment.CenterStart)
                    .offset(x = 10.dp, y = (-46).dp)
            ) {
                val path = Path().apply {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = size.width / 2f
                    moveTo(cx, cy - r)
                    quadraticTo(cx, cy, cx + r, cy)
                    quadraticTo(cx, cy, cx, cy + r)
                    quadraticTo(cx, cy, cx - r, cy)
                    quadraticTo(cx, cy, cx, cy - r)
                    close()
                }
                drawPath(path = path, color = Color(0xFF93C5FD))
            }

            // Another Small Sparkle on lower-left
            Canvas(
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.CenterStart)
                    .offset(x = (-2).dp, y = (-12).dp)
            ) {
                val path = Path().apply {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val r = size.width / 2f
                    moveTo(cx, cy - r)
                    quadraticTo(cx, cy, cx + r, cy)
                    quadraticTo(cx, cy, cx, cy + r)
                    quadraticTo(cx, cy, cx - r, cy)
                    quadraticTo(cx, cy, cx, cy - r)
                    close()
                }
                drawPath(path = path, color = Color(0xFF93C5FD))
            }

            // Main Glassmorphic Circular Count Card
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .shadow(
                        elevation = 12.dp,
                        shape = CircleShape,
                        clip = false,
                        ambientColor = Color(0xFF000000).copy(alpha = 0.04f),
                        spotColor = Color(0xFF2563EB).copy(alpha = 0.14f)
                    )
                    .background(Color.White, shape = CircleShape)
                    .border(
                        width = 3.dp,
                        brush = Brush.sweepGradient(
                            colors = listOf(
                                Color(0xFF3B82F6),
                                Color(0xFF93C5FD),
                                Color(0xFFC084FC),
                                Color(0xFF3B82F6)
                            )
                        ),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = "Total Counts Today",
                        color = Color(0xFF475569),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.2.sp
                    )
                    
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    Text(
                        text = totalCount.toString(),
                        color = Color(0xFF0C1B33),
                        fontSize = 76.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 76.sp
                    )
                    
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    Text(
                        text = "Instagram + YouTube Shorts",
                        color = Color(0xFF2563EB),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Subtitle slogan with target and rocket emojis: Less scrolling = Better focus
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Text(
                text = "🎯 Less scrolling = Better focus 🚀",
                color = Color(0xFF2563EB),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun InstagramCounterCard(
    count: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(144.dp)
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(26.dp),
                clip = false,
                ambientColor = Color(0xFF000000).copy(alpha = 0.03f),
                spotColor = Color(0xFF8B5CF6).copy(alpha = 0.08f)
            ),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF3E8FF))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White,
                            Color(0xFFFAF5FF)
                        )
                    )
                )
                .padding(18.dp)
        ) {
            // Instagram cute custom 3D mini-bar-chart drawn at bottom right
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 2.dp, end = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFFF472B6), Color(0xFFEC4899))))
                )
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFFEC4899), Color(0xFFD946EF))))
                )
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFFD946EF), Color(0xFF8B5CF6))))
                )
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Instagram Reels",
                        color = Color(0xFF8B5CF6),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Canvas(modifier = Modifier.size(20.dp)) {
                        val strokeWidth = 1.8.dp.toPx()
                        drawRoundRect(
                            color = Color(0xFF8B5CF6),
                            topLeft = Offset(1.5.dp.toPx(), 1.5.dp.toPx()),
                            size = Size(17.dp.toPx(), 17.dp.toPx()),
                            cornerRadius = CornerRadius(4.5.dp.toPx()),
                            style = Stroke(width = strokeWidth)
                        )
                        drawCircle(
                            color = Color(0xFF8B5CF6),
                            radius = 3.5.dp.toPx(),
                            center = center,
                            style = Stroke(width = strokeWidth)
                        )
                        drawCircle(
                            color = Color(0xFF8B5CF6),
                            radius = 0.9.dp.toPx(),
                            center = Offset(14.dp.toPx(), 4.dp.toPx())
                        )
                    }
                }

                Column {
                    Text(
                        text = count.toString(),
                        color = Color(0xFF0C1B33),
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 42.sp
                    )
                    Text(
                        text = "Scrolled Today",
                        color = Color(0xFFD946EF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun YouTubeCounterCard(
    count: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(144.dp)
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(26.dp),
                clip = false,
                ambientColor = Color(0xFF000000).copy(alpha = 0.03f),
                spotColor = Color(0xFFDC2626).copy(alpha = 0.08f)
            ),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFE4E4))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White,
                            Color(0xFFFFF5F5)
                        )
                    )
                )
                .padding(18.dp)
        ) {
            // YouTube cute custom 3D mini-bar-chart drawn at bottom right
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 2.dp, end = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFFFCA5A5), Color(0xFFF87171))))
                )
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFFF87171), Color(0xFFEF4444))))
                )
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(44.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFFEF4444), Color(0xFFDC2626))))
                )
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "YouTube Shorts",
                        color = Color(0xFFDC2626),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Canvas(modifier = Modifier.size(20.dp)) {
                        drawRoundRect(
                            color = Color(0xFFDC2626),
                            topLeft = Offset(0.5.dp.toPx(), 3.dp.toPx()),
                            size = Size(19.dp.toPx(), 14.dp.toPx()),
                            cornerRadius = CornerRadius(4.dp.toPx())
                        )
                        val path = Path().apply {
                            moveTo(8.5.dp.toPx(), 6.5.dp.toPx())
                            lineTo(13.dp.toPx(), 10.dp.toPx())
                            lineTo(8.5.dp.toPx(), 13.5.dp.toPx())
                            close()
                        }
                        drawPath(path = path, color = Color.White)
                    }
                }

                Column {
                    Text(
                        text = count.toString(),
                        color = Color(0xFF0C1B33),
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 42.sp
                    )
                    Text(
                        text = "Scrolled Today",
                        color = Color(0xFFEF4444),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun ServiceStatusCard(
    onActionConfigClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(24.dp),
                clip = false,
                ambientColor = Color(0xFF000000).copy(alpha = 0.03f),
                spotColor = Color(0xFF0EA5E9).copy(alpha = 0.05f)
            ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0F2FE))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF94A3B8))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Service Pending",
                        color = Color(0xFF0C1B33),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onActionConfigClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5E9)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Enable", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Tracking needs your permission to run. Tap Enable, select 'Reels & Shorts Counter' and turn on its Accessibility Services to start automated habit counts instantly.",
                color = Color(0xFF64748B),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun FloatingOverlayStatusCard(
    onActionConfigClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(24.dp),
                clip = false,
                ambientColor = Color(0xFF000000).copy(alpha = 0.03f),
                spotColor = Color(0xFF0EA5E9).copy(alpha = 0.05f)
            ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0F2FE))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Alert",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Interactive Overlay Count",
                        color = Color(0xFF0C1B33),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onActionConfigClick,
                    border = ButtonDefaults.outlinedButtonBorder.copy(width = 1.5.dp, brush = Brush.linearGradient(listOf(Color(0xFF0EA5E9), Color(0xFF38BDF8)))),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Grant", color = Color(0xFF0EA5E9), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Enjoy mindful real-time count badges floating gracefully at the corner of your screen when viewing videos, fading away automatically after a few seconds.",
                color = Color(0xFF64748B),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun BatteryOptimizationStatusCard(
    onActionConfigClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(24.dp),
                clip = false,
                ambientColor = Color(0xFF000000).copy(alpha = 0.03f),
                spotColor = Color(0xFF10B981).copy(alpha = 0.05f)
            ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD1FAE5))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Battery Alert",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Exempt Battery Limit (24h Run)",
                        color = Color(0xFF0C1B33),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onActionConfigClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Exempt", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "To keep ScreenHabits counting for 24 hours even after clearing recent apps, please exempt ScreenHabits from your phone's battery limits (select 'Unrestricted').",
                color = Color(0xFF64748B),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun BatteryDisclosureDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(26.dp),
        containerColor = Color.White,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFECFDF5)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Battery info",
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        title = {
            Text(
                text = "Keep Counting for 24 hours",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color(0xFF0F172A)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Some Android devices aggressively termiate tracking services when you swipe the main app off your recent list.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Color(0xFF475569)
                )
                Text(
                    text = "By exempting ScreenHabits from battery optimizations, Android will allow the accessibility process to run continuously without interruptions.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Color(0xFF475569)
                )
                Text(
                    text = "On the next screen:\n1. Find 'ScreenHabits' in the list (or toggle from 'Optimized' to 'All Apps' if you don't see it style list).\n2. Uncheck/choose 'Don't Optimize' or choose 'Unrestricted'.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10B981)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Proceed to Settings", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel", color = Color(0xFF64748B))
            }
        }
    )
}

@Composable
fun DailyLogItemCard(
    dailyCount: DailyCount,
    onDelete: () -> Unit
) {
    val totalCount = dailyCount.reelsCount + dailyCount.shortsCount
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(20.dp),
                clip = false,
                ambientColor = Color(0xFF000000).copy(alpha = 0.02f),
                spotColor = Color(0xFF2563EB).copy(alpha = 0.04f)
            ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE8F2FC))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = "Date Icon",
                        tint = Color(0xFF2563EB),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = dailyCount.date,
                        color = Color(0xFF0C1B33),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Instagram: ${dailyCount.reelsCount}",
                        color = Color(0xFF8B5CF6),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "YouTube: ${dailyCount.shortsCount}",
                        color = Color(0xFFDC2626),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "$totalCount item${if (totalCount == 1) "" else "s"}",
                    color = Color(0xFF2563EB),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 12.dp)
                )

                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .shadow(
                            elevation = 2.dp,
                            shape = CircleShape,
                            clip = false,
                            ambientColor = Color(0xFF000000).copy(alpha = 0.05f),
                            spotColor = Color(0xFF000000).copy(alpha = 0.05f)
                        )
                        .background(Color.White, shape = CircleShape)
                        .clip(CircleShape)
                        .clickable { onDelete() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Log",
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ReminderLimitCard(
    currentLimit: Int,
    onLimitChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(26.dp),
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(26.dp),
                clip = false,
                ambientColor = Color(0xFF000000).copy(alpha = 0.03f),
                spotColor = Color(0xFF2563EB).copy(alpha = 0.06f)
            ),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF1F5F9))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Alert Icon Container with beautiful Purple/Indigo Linear Gradient
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF6366F1), Color(0xFF4F46E5))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Alert Icon",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = "Scrolling Limit Alert",
                            color = Color(0xFF0C1B33),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Remind me on the " + (currentLimit + 1) + "th item",
                            color = Color(0xFF4F46E5),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Small badge showing selected target limit
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFEEF2F6))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "$currentLimit views",
                        color = Color(0xFF0F172A),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Controller elements: [-] state slider [+]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Minus button styled beautifully matching minimal design
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF8FAFC))
                        .clickable { if (currentLimit > 1) onLimitChange(currentLimit - 1) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "−",
                        color = Color(0xFF64748B),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Slider(
                    value = currentLimit.toFloat(),
                    onValueChange = { onLimitChange(it.toInt().coerceIn(1, 100)) },
                    valueRange = 1f..100f,
                    colors = SliderDefaults.colors(
                        activeTrackColor = Color(0xFF4F46E5),
                        inactiveTrackColor = Color(0xFFF1F5F9),
                        thumbColor = Color(0xFF4F46E5)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                )

                // Plus button styled beautifully
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF8FAFC))
                        .clickable { if (currentLimit < 100) onLimitChange(currentLimit + 1) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "+",
                        color = Color(0xFF64748B),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// Privacy-centric onboarding screen targeting modern aesthetic and safe disclosures
@Composable
fun PrivacyOnboardingScreen(
    onOnboardingComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .drawBehind {
                val w = size.width
                val h = size.height
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFCEE3FF).copy(alpha = 0.45f), Color.Transparent),
                        center = Offset(w * 0.8f, h * 0.2f),
                        radius = w * 0.8f
                    ),
                    radius = w * 0.8f,
                    center = Offset(w * 0.8f, h * 0.2f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFE5D5FF).copy(alpha = 0.4f), Color.Transparent),
                        center = Offset(w * 0.2f, h * 0.8f),
                        radius = w * 0.9f
                    ),
                    radius = w * 0.9f,
                    center = Offset(w * 0.2f, h * 0.8f)
                )
            }
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(28.dp))
            
            // App Logo Icon Frame
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF3B82F6), Color(0xFF6366F1))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Lock Icon",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Welcome to ScreenHabits",
                color = Color(0xFF0F172A),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "A modern wellbeing tracker with absolute security configuration.",
                color = Color(0xFF64748B),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    OnboardingFeatureCard(
                        icon = Icons.Default.Done,
                        iconColor = Color(0xFF10B981),
                        title = "What We Do",
                        description = "We count your YouTube Shorts and Instagram Reels to provide daily analytics and scrolling limit alerts."
                    )
                }
                item {
                    OnboardingFeatureCard(
                        icon = Icons.Default.Warning,
                        iconColor = Color(0xFFE11D48),
                        title = "What We NEVER Request",
                        description = "We do NOT request SMS, Contacts, Call logs, Storage, Financial accounts, Keystroke entries, passwords, or personal databases."
                    )
                }
                item {
                    OnboardingFeatureCard(
                        icon = Icons.Default.Lock,
                        iconColor = Color(0xFF3B82F6),
                        title = "100% Secure & Offline",
                        description = "All tracking data is analyzed purely on-device and stored in a local offline secure database. We do not have servers, tracker SDKs, or advertising engines."
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(18.dp))
            
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "Google Play Policy Consent",
                    color = Color(0xFF0F172A),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "This app uses Accessibility Service only to count Reels and Shorts activity for digital wellbeing tracking. No personal or sensitive data is collected.",
                    color = Color(0xFF475569),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            Button(
                onClick = onOnboardingComplete,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .shadow(
                        elevation = 6.dp,
                        shape = RoundedCornerShape(16.dp),
                        clip = false,
                        ambientColor = Color(0xFF2563EB).copy(alpha = 0.2f),
                        spotColor = Color(0xFF2563EB).copy(alpha = 0.2f)
                    )
            ) {
                Text(
                    text = "Accept & Get Started",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun OnboardingFeatureCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    title: String,
    description: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF1F5F9)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column {
                Text(
                    text = title,
                    color = Color(0xFF0F172A),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    color = Color(0xFF64748B),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

// Gorgeous disclosure dialogs that prominently explain permission usage before asking
@Composable
fun AccessibilityDisclosureDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(26.dp),
        containerColor = Color.White,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEFF6FF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Accessibility",
                    tint = Color(0xFF2563EB),
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        title = {
            Text(
                text = "Prominent Accessibility Permission Consent",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color(0xFF0F172A)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "To track and count Instagram Reels and YouTube Shorts, ScreenHabits requires you to enable the Accessibility Service.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Color(0xFF475569)
                )
                Text(
                    text = "• Local Scanning: It looks only at specific view elements (such as container resource IDs) when YouTube or Instagram are open on top, detecting layout flips to increment screen counts.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Color(0xFF475569)
                )
                Text(
                    text = "• 100% Private Offline: This service does NOT track text entries, passwords, messages, keystrokes or look at private chats, and collects ZERO data.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0EA5E9)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Agree & Set Up", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel", color = Color(0xFF64748B))
            }
        }
    )
}

@Composable
fun OverlayDisclosureDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(26.dp),
        containerColor = Color.White,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF5F3FF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "Overlay Option",
                    tint = Color(0xFF8B5CF6),
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        title = {
            Text(
                text = "Overlay Permission Explanation",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color(0xFF0F172A)
            )
        },
        text = {
            Text(
                text = "ScreenHabits uses the 'Display Over Other Apps' permission to overlay a thin, beautiful counting bubble when you start scrolling reels/shorts, fading gently away in 5 seconds. It does not display ads, hijack controls, or collect system information.",
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = Color(0xFF475569)
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Proceed", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel", color = Color(0xFF64748B))
            }
        }
    )
}


