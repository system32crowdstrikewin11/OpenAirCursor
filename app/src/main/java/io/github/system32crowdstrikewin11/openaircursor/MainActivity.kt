package io.github.system32crowdstrikewin11.openaircursor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class MainActivity : ComponentActivity() {

    private var refreshTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AirCursorConfig.load(this)
        enableEdgeToEdge()

        setContent {
            val isDark = isSystemInDarkTheme()
            val colors = remember(isDark) {
                if (isDark) darkColorScheme() else lightColorScheme()
            }
            MiuixTheme(colors = colors) {
                AirCursorMainScreen(
                    isDark = isDark,
                    refreshTick = refreshTick,
                    onRefresh = { runOnUiThread { refreshTick++ } }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTick++
    }
}

private val GESTURE_GUIDE = listOf(
    "1 or 2 hands" to "Spawns 1 cursor (Cyan) or 2 cursors (Cyan + Magenta)",
    "Thumb + Index" to "Quick pinch = Tap  •  Double pinch = 2x Click  •  Drag = Swipe  •  Hold = Long press",
    "Both hands pinch" to "Move apart or together to Pinch-to-Zoom",
    "Thumb + Middle" to "Quick pinch = Back  •  Hold = Notifications",
    "Thumb + Ring" to "Quick pinch = Home  •  Hold = Volume Up",
    "Thumb + Pinky" to "Quick pinch = Recents  •  Hold = Volume Down"
)

@Composable
private fun AirCursorMainScreen(
    isDark: Boolean,
    refreshTick: Int,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current

    val camGranted = remember(refreshTick) {
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    val accConnected = remember(refreshTick) { AirCursorAccessibilityService.instance != null }
    val isTracking = remember(refreshTick) { AirCursorAccessibilityService.isTracking }
    val lastError = remember(refreshTick) { AirCursorAccessibilityService.lastError }

    val nativeInfo = remember {
        runCatching { NativeBridge.version() }.getOrDefault("arm64-v8a")
    }

    val successColor = if (isDark) Color(0xFF30D158) else Color(0xFF28CD41)
    val warnColor = if (isDark) Color(0xFFFF9F0A) else Color(0xFFFF9500)
    val dangerColor = if (isDark) Color(0xFFFF453A) else Color(0xFFFF3B30)

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onRefresh() }

    val onRequestPerms = remember(context) {
        {
            val needed = mutableListOf<String>()
            if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                needed += Manifest.permission.CAMERA
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                needed += Manifest.permission.POST_NOTIFICATIONS
            }
            if (needed.isEmpty()) {
                Toast.makeText(context, "Permissions already granted", Toast.LENGTH_SHORT).show()
                onRefresh()
            } else {
                permLauncher.launch(needed.toTypedArray())
            }
        }
    }

    val onToggleTracking = remember(camGranted, isTracking) {
        {
            val svc = AirCursorAccessibilityService.instance
            when {
                !camGranted -> Toast.makeText(context, "Grant camera permission first", Toast.LENGTH_SHORT).show()
                svc == null -> Toast.makeText(context, "Enable OpenAirCursor in Accessibility Settings first", Toast.LENGTH_SHORT).show()
                isTracking -> svc.stopTracking { onRefresh() }
                else -> svc.startTracking { onRefresh() }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = "OpenAirCursor") }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 28.dp)
        ) {
            item(key = "hero") {
                HeroStatusCard(
                    camGranted = camGranted,
                    accConnected = accConnected,
                    isTracking = isTracking,
                    successColor = successColor,
                    warnColor = warnColor,
                    dangerColor = dangerColor,
                    onToggle = onToggleTracking
                )
            }

            if (!lastError.isNullOrBlank()) {
                item(key = "error") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(dangerColor.copy(alpha = 0.14f))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(text = lastError, fontSize = 13.sp, color = dangerColor)
                    }
                }
            }

            item(key = "setup") {
                SmallTitle(text = "Setup")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onRequestPerms)
                    ) {
                        BasicComponent(
                            title = "Camera & Notifications",
                            summary = "Required for front camera hand tracking",
                            rightActions = {
                                StatusArrow(
                                    label = if (camGranted) "Granted" else "Required",
                                    color = if (camGranted) successColor else warnColor
                                )
                            }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                    ) {
                        BasicComponent(
                            title = "Accessibility Service",
                            summary = "Required to draw cursors and inject gestures",
                            rightActions = {
                                StatusArrow(
                                    label = if (accConnected) "Connected" else "Off",
                                    color = if (accConnected) successColor else warnColor
                                )
                            }
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val svc = AirCursorAccessibilityService.instance
                                if (svc != null) {
                                    svc.resetTouchInput()
                                    Toast.makeText(context, "Touch input flushed", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Enable Accessibility Service first", Toast.LENGTH_SHORT).show()
                                }
                            }
                    ) {
                        BasicComponent(
                            title = "Reset Touch Input",
                            summary = "Unstick Android touch injection if clicks stop responding",
                            rightActions = {
                                StatusArrow(
                                    label = "Flush",
                                    color = MiuixTheme.colorScheme.primary
                                )
                            }
                        )
                    }
                }
            }

            item(key = "tracking") {
                SmallTitle(text = "Tracking")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                ) {
                    IsolatedSliderPreference(
                        title = "Sensitivity",
                        summary = "Smaller hand travel to reach screen edges",
                        initialValue = AirCursorConfig.sensitivity,
                        onApplyMemory = { AirCursorConfig.sensitivity = it }
                    )
                    IsolatedSliderPreference(
                        title = "Smoothness",
                        summary = "Higher stabilizes small clicks; lower feels faster",
                        initialValue = AirCursorConfig.smoothness,
                        onApplyMemory = { AirCursorConfig.smoothness = it }
                    )
                }
            }

            item(key = "overlay") {
                SmallTitle(text = "Overlay")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                ) {
                    IsolatedSwitchPreference(
                        title = "Show preview",
                        summary = "Corner camera feed with active tracking box",
                        initialValue = AirCursorConfig.showPreview
                    ) { checked ->
                        AirCursorConfig.showPreview = checked
                        AirCursorConfig.save(context)
                        AirCursorAccessibilityService.instance?.refreshPreviewVisibility()
                    }
                    IsolatedSwitchPreference(
                        title = "Mirror view",
                        summary = "Flip horizontal tracking like a mirror",
                        initialValue = AirCursorConfig.mirrorView
                    ) { checked ->
                        AirCursorConfig.mirrorView = checked
                        AirCursorConfig.save(context)
                    }
                }
            }

            item(key = "gestures") {
                SmallTitle(text = "Gestures")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        for ((title, desc) in GESTURE_GUIDE) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = desc,
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                }
            }

            item(key = "about") {
                SmallTitle(text = "About")
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                ) {
                    BasicComponent(
                        title = "OpenAirCursor",
                        summary = "MediaPipe 21-pt Dual-Hand  •  $nativeInfo"
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroStatusCard(
    camGranted: Boolean,
    accConnected: Boolean,
    isTracking: Boolean,
    successColor: Color,
    warnColor: Color,
    dangerColor: Color,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            val dotColor = when {
                isTracking -> successColor
                camGranted && accConnected -> MiuixTheme.colorScheme.primary
                else -> warnColor
            }
            val statusTitle = when {
                isTracking -> "Air Cursors Active"
                camGranted && accConnected -> "Ready to Track"
                else -> "Setup Incomplete"
            }
            val statusSummary = when {
                isTracking -> "Tracking up to 2 hands via front camera"
                camGranted && accConnected -> "Tap below to enable spatial hand control"
                !camGranted -> "Grant camera permission to continue"
                else -> "Enable OpenAirCursor in Accessibility Settings"
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusTitle,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = statusSummary,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val buttonBg = if (isTracking) dangerColor else MiuixTheme.colorScheme.primary
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(buttonBg)
                    .clickable(onClick = onToggle)
                    .padding(vertical = 14.dp)
            ) {
                Text(
                    text = if (isTracking) "Stop Air Cursors" else "Start Air Cursors",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun IsolatedSliderPreference(
    title: String,
    summary: String,
    initialValue: Float,
    onApplyMemory: (Float) -> Unit
) {
    val context = LocalContext.current
    var value by remember { mutableFloatStateOf(initialValue) }
    var dirty by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        if (!dirty) return@LaunchedEffect
        delay(250L)
        AirCursorConfig.save(context)
        dirty = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${(value * 100f).roundToInt()}%",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = summary,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Slider(
            progress = value,
            onProgressChange = { next ->
                value = next
                dirty = true
                onApplyMemory(next)
            },
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun IsolatedSwitchPreference(
    title: String,
    summary: String,
    initialValue: Boolean,
    onChanged: (Boolean) -> Unit
) {
    var checked by remember { mutableStateOf(initialValue) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val next = !checked
                checked = next
                onChanged(next)
            }
    ) {
        BasicComponent(
            title = title,
            summary = summary,
            rightActions = {
                Switch(
                    checked = checked,
                    onCheckedChange = { next ->
                        checked = next
                        onChanged(next)
                    }
                )
            }
        )
    }
}

@Composable
private fun StatusArrow(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = color
        )
        Text(
            text = "›",
            fontSize = 20.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}
