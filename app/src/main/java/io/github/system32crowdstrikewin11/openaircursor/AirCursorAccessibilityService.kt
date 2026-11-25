package io.github.system32crowdstrikewin11.openaircursor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot

class AirCursorAccessibilityService :
    AccessibilityService(),
    LifecycleOwner,
    HandGestureEngine.GestureActionListener {

    companion object {
        private const val TAG = "AirCursorService"
        private const val CHANNEL_ID = "air_cursor_camera"
        private const val NOTIF_ID = 1001

        @Volatile
        var instance: AirCursorAccessibilityService? = null
            private set

        @Volatile
        var isTracking: Boolean = false
            private set

        @Volatile
        var lastError: String? = null
            private set
    }

    private lateinit var lifecycleRegistry: LifecycleRegistry
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private lateinit var audioManager: AudioManager
    private var overlayView: CursorOverlayView? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService? = null
    private var handLandmarker: HandLandmarker? = null
    private lateinit var gestureEngine: HandGestureEngine
    private var lastTimestampMs: Long = 0L

    @Volatile private var cachedW = 1080f
    @Volatile private var cachedH = 2400f
    @Volatile private var cachedDensity = 3f
    @Volatile private var lastDisplayRotation = Surface.ROTATION_0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                handleDisplayChange()
            }
        }
    }

    // Reusable frame buffers
    private var rawBuffer: Bitmap? = null
    private val frameRing = arrayOfNulls<Bitmap>(3)
    private var ringIdx = 0
    private val frameCanvas = Canvas()
    private val frameMatrix = Matrix()
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onCreate() {
        super.onCreate()
        AirCursorConfig.load(this)
        lifecycleRegistry = LifecycleRegistry(this)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        gestureEngine = HandGestureEngine(this)
        lastDisplayRotation = currentDisplayRotation()
        displayManager.registerDisplayListener(displayListener, mainHandler)
        updateCachedMetrics()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        updateCachedMetrics()
        setupOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopTracking()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleDisplayChange()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun startTracking(onDone: (() -> Unit)? = null) {
        mainHandler.post {
            if (isTracking) {
                onDone?.invoke()
                return@post
            }
            lastError = null
            try {
                updateCachedMetrics()
                startCameraForegroundNotification()
                initMediaPipe()
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                startCameraX()
                isTracking = true
                // Clear any stuck InputDispatcher pointer on Android 15/16
                flushStuckTouch()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start tracking", e)
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                stopTracking()
            }
            onDone?.invoke()
        }
    }

    fun stopTracking(onDone: (() -> Unit)? = null) {
        mainHandler.post {
            isTracking = false
            imageAnalysis = null
            gestureEngine.requestReset()
            runCatching { cameraProvider?.unbindAll() }
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                lifecycleRegistry.currentState = Lifecycle.State.CREATED
            }
            cameraExecutor?.shutdown()
            cameraExecutor = null
            runCatching { handLandmarker?.close() }
            handLandmarker = null
            overlayView?.clearAll()
            rawBuffer = null
            for (i in frameRing.indices) frameRing[i] = null
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            onDone?.invoke()
        }
    }

    fun resetTouchInput() {
        mainHandler.post { flushStuckTouch() }
    }

    fun refreshPreviewVisibility() {
        if (!AirCursorConfig.showPreview) {
            overlayView?.updatePreviewFrame(null)
        }
    }

    /**
     * Workaround for Android 15/16 AOSP InputDispatcher bug (Issue 384188031):
     * A 2-finger micro-touch releases any stuck virtual pointer so single taps/swipes work again.
     */
    private fun flushStuckTouch() {
        val p1 = Path().apply { moveTo(2f, 2f); lineTo(2.5f, 2.5f) }
        val p2 = Path().apply { moveTo(6f, 2f); lineTo(6.5f, 2.5f) }
        val flush = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0L, 20L))
            .addStroke(GestureDescription.StrokeDescription(p2, 0L, 20L))
            .build()
        dispatchGesture(flush, null, null)
    }

    private fun dispatchSafe(
        gesture: GestureDescription,
        onDone: (() -> Unit)? = null
    ) {
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Gesture cancelled by system, flushing InputDispatcher")
                flushStuckTouch()
            }
        }, mainHandler)

        if (!ok) {
            Log.w(TAG, "dispatchGesture returned false, flushing InputDispatcher")
            flushStuckTouch()
        }
    }

    private fun currentDisplayRotation(): Int {
        return displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }

    private fun handleDisplayChange() {
        val newRotation = currentDisplayRotation()
        updateCachedMetrics()
        if (newRotation != lastDisplayRotation) {
            lastDisplayRotation = newRotation
            imageAnalysis?.targetRotation = newRotation
            gestureEngine.requestReset()
            overlayView?.clearAll()
        }
    }

    private fun updateCachedMetrics() {
        cachedDensity = resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            cachedW = bounds.width().toFloat()
            cachedH = bounds.height().toFloat()
        } else {
            val dm = resources.displayMetrics
            cachedW = dm.widthPixels.toFloat()
            cachedH = dm.heightPixels.toFloat()
        }
    }

    private fun setupOverlay() {
        if (overlayView != null) return
        val view = CursorOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                fitInsetsTypes = 0
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        view.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            val w = r - l
            val h = b - t
            if (w > 0 && h > 0 && (w != or_ - ol || h != ob - ot)) {
                updateCachedMetrics()
            }
        }
        windowManager.addView(view, params)
        overlayView = view
    }

    private fun startCameraForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OpenAirCursor Hand Tracking",
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenAirCursor Active")
            .setContentText("Tracking up to 2 hands via front camera")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun initMediaPipe() {
        handLandmarker?.close()
        lastTimestampMs = 0L
        handLandmarker = try {
            createLandmarker(Delegate.GPU)
        } catch (e: Throwable) {
            Log.w(TAG, "GPU delegate failed, falling back to CPU", e)
            createLandmarker(Delegate.CPU)
        }
    }

    private fun createLandmarker(delegate: Delegate): HandLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(delegate)
            .build()

        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.55f)
            .setMinHandPresenceConfidence(0.55f)
            .setMinTrackingConfidence(0.55f)
            .setResultListener { result, inputImage ->
                gestureEngine.processResult(
                    result, cachedW, cachedH, cachedDensity, inputImage.width, inputImage.height
                )
            }
            .setErrorListener { err ->
                Log.e(TAG, "MediaPipe error: ${err.message}", err)
                lastError = "MediaPipe: ${err.message}"
            }
            .build()

        return HandLandmarker.createFromOptions(this, options)
    }

    private fun startCameraX() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(320, 240),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val rotation = currentDisplayRotation()
                lastDisplayRotation = rotation
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setTargetRotation(rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                imageAnalysis = analysis

                analysis.setAnalyzer(cameraExecutor!!) { imageProxy ->
                    analyzeFrame(imageProxy)
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis
                )
            } catch (e: Throwable) {
                Log.e(TAG, "CameraX bind failed", e)
                lastError = "CameraX: ${e.message}"
                stopTracking()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            val landmarker = handLandmarker ?: return
            val w = imageProxy.width
            val h = imageProxy.height
            val plane = imageProxy.planes[0]
            var tempRaw: Bitmap? = null

            val raw = if (plane.rowStride == w * 4) {
                val buf = plane.buffer
                buf.rewind()
                val reused = rawBuffer?.takeIf { it.width == w && it.height == h }
                    ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { rawBuffer = it }
                reused.copyPixelsFromBuffer(buf)
                reused
            } else {
                imageProxy.toBitmap().also { tempRaw = it }
            }

            val rot = imageProxy.imageInfo.rotationDegrees
            val swap = rot == 90 || rot == 270
            val outW = if (swap) h else w
            val outH = if (swap) w else h
            val outBmp = frameRing[ringIdx]?.takeIf { it.width == outW && it.height == outH }
                ?: Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also { frameRing[ringIdx] = it }
            ringIdx = (ringIdx + 1) % frameRing.size

            frameMatrix.reset()
            frameMatrix.postTranslate(-w * 0.5f, -h * 0.5f)
            if (rot != 0) frameMatrix.postRotate(rot.toFloat())
            if (AirCursorConfig.mirrorView) frameMatrix.postScale(-1f, 1f)
            frameMatrix.postTranslate(outW * 0.5f, outH * 0.5f)

            frameCanvas.setBitmap(outBmp)
            frameCanvas.drawBitmap(raw, frameMatrix, framePaint)
            tempRaw?.recycle()

            if (AirCursorConfig.showPreview) {
                overlayView?.updatePreviewFrame(outBmp)
            }

            val mpImage = BitmapImageBuilder(outBmp).build()
            val now = SystemClock.uptimeMillis()
            val ts = if (now > lastTimestampMs) now else lastTimestampMs + 1L
            lastTimestampMs = ts
            landmarker.detectAsync(mpImage, ts)
        } catch (e: Throwable) {
            Log.e(TAG, "Frame inference error", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun clampPoint(x: Float, y: Float): Pair<Float, Float> {
        return x.coerceIn(1f, cachedW - 1f) to y.coerceIn(1f, cachedH - 1f)
    }

    private fun tapPath(x: Float, y: Float): Path {
        return Path().apply {
            moveTo(x, y)
            lineTo((x + 0.5f).coerceAtMost(cachedW - 1f), (y + 0.5f).coerceAtMost(cachedH - 1f))
        }
    }

    override fun onCursorsUpdated(
        cursors: List<CursorRenderState>,
        skeletons: List<HandSkeletonState>
    ) {
        overlayView?.updateCursors(cursors, skeletons)
    }

    override fun onTap(x: Float, y: Float) {
        mainHandler.post {
            val (cx, cy) = clampPoint(x, y)
            overlayView?.triggerClickRipple(cx, cy)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(tapPath(cx, cy), 0L, 55L))
                .build()
            dispatchSafe(gesture)
        }
    }

    override fun onDoubleTap(x: Float, y: Float) {
        mainHandler.post {
            val (cx, cy) = clampPoint(x, y)
            overlayView?.triggerClickRipple(cx, cy)
            val tap1 = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(tapPath(cx, cy), 0L, 35L))
                .build()
            dispatchSafe(tap1) {
                mainHandler.postDelayed({
                    overlayView?.triggerClickRipple(cx, cy)
                    val tap2 = GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(tapPath(cx, cy), 0L, 35L))
                        .build()
                    dispatchSafe(tap2)
                }, 55L)
            }
        }
    }

    override fun onLongPress(x: Float, y: Float) {
        mainHandler.post {
            val (cx, cy) = clampPoint(x, y)
            overlayView?.triggerClickRipple(cx, cy)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(tapPath(cx, cy), 0L, 650L))
                .build()
            dispatchSafe(gesture)
        }
    }

    override fun onSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        mainHandler.post {
            val (sx, sy) = clampPoint(startX, startY)
            val (ex, ey) = clampPoint(endX, endY)
            val dist = hypot(ex - sx, ey - sy)
            val durationMs = (220L + (dist / 12f).toLong()).coerceIn(240L, 380L)
            val path = Path().apply {
                moveTo(sx, sy)
                lineTo(ex, ey)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()
            dispatchSafe(gesture)
        }
    }

    override fun onPinchZoom(
        s1x: Float, s1y: Float, e1x: Float, e1y: Float,
        s2x: Float, s2y: Float, e2x: Float, e2y: Float
    ) {
        mainHandler.post {
            val (a1x, a1y) = clampPoint(s1x, s1y)
            val (b1x, b1y) = clampPoint(e1x, e1y)
            val (a2x, a2y) = clampPoint(s2x, s2y)
            val (b2x, b2y) = clampPoint(e2x, e2y)

            val p1 = Path().apply { moveTo(a1x, a1y); lineTo(b1x, b1y) }
            val p2 = Path().apply { moveTo(a2x, a2y); lineTo(b2x, b2y) }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p1, 0L, 280L))
                .addStroke(GestureDescription.StrokeDescription(p2, 0L, 280L))
                .build()
            dispatchSafe(gesture)
        }
    }

    override fun onVolumeUp() {
        mainHandler.post {
            audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        }
    }

    override fun onVolumeDown() {
        mainHandler.post {
            audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
    }

    override fun onBack() { mainHandler.post { performGlobalAction(GLOBAL_ACTION_BACK) } }
    override fun onHome() { mainHandler.post { performGlobalAction(GLOBAL_ACTION_HOME) } }
    override fun onRecents() { mainHandler.post { performGlobalAction(GLOBAL_ACTION_RECENTS) } }
    override fun onNotifications() { mainHandler.post { performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) } }

    override fun onDestroy() {
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        stopTracking()
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        instance = null
        super.onDestroy()
    }
}
