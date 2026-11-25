package io.github.system32crowdstrikewin11.openaircursor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.exp

data class CursorRenderState(
    val id: Int,
    val x: Float,
    val y: Float,
    val isPinching: Boolean,
    val pinchProgress: Float,
    val dragStartX: Float?,
    val dragStartY: Float?,
    val badge: String
)

data class HandSkeletonState(
    val id: Int,
    val points: FloatArray
)

class CursorOverlayView(context: Context) : View(context) {

    private class SmoothVisual(var x: Float, var y: Float)
    private class Ripple(val x: Float, val y: Float, val startMs: Long)

    @Volatile private var targetCursors: List<CursorRenderState> = emptyList()
    @Volatile private var skeletons: List<HandSkeletonState> = emptyList()
    @Volatile private var previewBitmap: Bitmap? = null

    private val visualPos = HashMap<Int, SmoothVisual>()
    private val ripples = mutableListOf<Ripple>()
    private val windowOrigin = IntArray(2)
    private var lastFrameMs = 0L

    private val previewRect = RectF()
    private val previewClip = Path()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
    }

    private val pinchHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val previewBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(180, 255, 255, 255)
    }

    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.argb(170, 118, 255, 3)
    }

    private val skelLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val skelDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
    }

    private val handBones = arrayOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        5 to 9, 9 to 10, 10 to 11, 11 to 12,
        9 to 13, 13 to 14, 14 to 15, 15 to 16,
        13 to 17, 17 to 18, 18 to 19, 19 to 20,
        0 to 17
    )

    fun updateCursors(newCursors: List<CursorRenderState>, newSkeletons: List<HandSkeletonState>) {
        targetCursors = newCursors
        skeletons = newSkeletons
        postInvalidateOnAnimation()
    }

    fun updatePreviewFrame(bitmap: Bitmap?) {
        previewBitmap = bitmap
        postInvalidate()
    }

    fun triggerClickRipple(x: Float, y: Float) {
        post {
            ripples.add(Ripple(x, y, SystemClock.uptimeMillis()))
            invalidate()
        }
    }

    fun clearAll() {
        targetCursors = emptyList()
        skeletons = emptyList()
        previewBitmap = null
        post {
            visualPos.clear()
            ripples.clear()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrameMs) / 1000f).coerceIn(0.004f, 0.05f)
        lastFrameMs = now

        val bmp = previewBitmap
        if (AirCursorConfig.showPreview && bmp != null && !bmp.isRecycled) {
            drawMiniPreview(canvas, bmp)
        }

        getLocationOnScreen(windowOrigin)
        canvas.save()
        canvas.translate(-windowOrigin[0].toFloat(), -windowOrigin[1].toFloat())

        val iter = ripples.iterator()
        while (iter.hasNext()) {
            val r = iter.next()
            val t = (now - r.startMs) / 300f
            if (t >= 1f) {
                iter.remove()
            } else {
                val alpha = ((1f - t) * 220).toInt().coerceIn(0, 255)
                ripplePaint.color = Color.argb(alpha, 118, 255, 3)
                canvas.drawCircle(r.x, r.y, 22f + t * 52f, ripplePaint)
            }
        }

        var keepAnimating = ripples.isNotEmpty()

        val snapshot = targetCursors
        val activeIds = snapshot.map { it.id }.toSet()
        visualPos.keys.retainAll(activeIds)

        val followRate = 40f - AirCursorConfig.smoothness * 22f
        val blend = 1f - exp(-followRate * dt)

        for (c in snapshot) {
            val v = visualPos.getOrPut(c.id) { SmoothVisual(c.x, c.y) }
            if (c.isPinching && c.dragStartX == null) {
                v.x = c.x
                v.y = c.y
            } else {
                val dx = c.x - v.x
                val dy = c.y - v.y
                if (dx * dx + dy * dy < 0.36f) {
                    v.x = c.x
                    v.y = c.y
                } else {
                    v.x += dx * blend
                    v.y += dy * blend
                    keepAnimating = true
                }
            }

            val baseColor = if (c.id == 0) Color.parseColor("#00E5FF") else Color.parseColor("#FF4081")
            val activeColor = Color.parseColor("#76FF03")
            val color = if (c.isPinching) activeColor else baseColor

            if (c.isPinching && c.dragStartX != null && c.dragStartY != null) {
                linePaint.color = Color.argb(170, Color.red(color), Color.green(color), Color.blue(color))
                canvas.drawLine(c.dragStartX, c.dragStartY, v.x, v.y, linePaint)
            }

            val radius = if (c.isPinching) 22f else 32f
            fillPaint.color = Color.argb(
                if (c.isPinching) 95 else 40,
                Color.red(color), Color.green(color), Color.blue(color)
            )
            canvas.drawCircle(v.x, v.y, radius, fillPaint)

            ringPaint.color = color
            canvas.drawCircle(v.x, v.y, radius, ringPaint)

            if (!c.isPinching && c.pinchProgress > 0.05f) {
                pinchHintPaint.color = Color.argb(
                    (c.pinchProgress * 220).toInt(),
                    118, 255, 3
                )
                val innerR = radius * (1f - c.pinchProgress * 0.62f)
                canvas.drawCircle(v.x, v.y, innerR, pinchHintPaint)
            }

            fillPaint.color = Color.WHITE
            canvas.drawCircle(v.x, v.y, 6f, fillPaint)

            if (c.badge.isNotEmpty()) {
                canvas.drawText(c.badge, v.x, v.y - radius - 14f, textPaint)
            }
        }

        canvas.restore()

        if (keepAnimating) {
            postInvalidateOnAnimation()
        }
    }

    private fun drawMiniPreview(canvas: Canvas, bmp: Bitmap) {
        val density = resources.displayMetrics.density
        val isLandscape = width > height
        val boxW = (if (isLandscape) 132f else 108f) * density
        val aspect = bmp.height.toFloat() / bmp.width.toFloat().coerceAtLeast(1f)
        val boxH = (boxW * aspect).coerceAtMost((if (isLandscape) 140f else 210f) * density)

        val marginRight = 14f * density
        val marginTop = (if (isLandscape) 16f else 52f) * density
        val left = width - boxW - marginRight
        val top = marginTop
        previewRect.set(left, top, left + boxW, top + boxH)

        val corner = 14f * density
        previewClip.reset()
        previewClip.addRoundRect(previewRect, corner, corner, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(previewClip)
        canvas.drawBitmap(bmp, null, previewRect, null)

        val m = AirCursorConfig.activeMargin()
        canvas.drawRect(
            left + m * boxW,
            top + m * boxH,
            left + (1f - m) * boxW,
            top + (1f - m) * boxH,
            zonePaint
        )

        for (sk in skeletons) {
            skelLinePaint.color = if (sk.id == 0) Color.parseColor("#00E5FF") else Color.parseColor("#FF4081")
            val pts = sk.points
            for ((a, b) in handBones) {
                val ax = left + pts[a * 2] * boxW
                val ay = top + pts[a * 2 + 1] * boxH
                val bx = left + pts[b * 2] * boxW
                val by = top + pts[b * 2 + 1] * boxH
                canvas.drawLine(ax, ay, bx, by, skelLinePaint)
            }
            for (k in 0 until 21) {
                val px = left + pts[k * 2] * boxW
                val py = top + pts[k * 2 + 1] * boxH
                canvas.drawCircle(px, py, 2.2f * density, skelDotPaint)
            }
        }

        canvas.restore()
        canvas.drawRoundRect(previewRect, corner, corner, previewBorderPaint)
    }
}
