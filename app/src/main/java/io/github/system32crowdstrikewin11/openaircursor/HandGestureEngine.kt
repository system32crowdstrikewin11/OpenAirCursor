package io.github.system32crowdstrikewin11.openaircursor

import android.os.SystemClock
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

class HandGestureEngine(
    private val listener: GestureActionListener
) {
    interface GestureActionListener {
        fun onCursorsUpdated(
            cursors: List<CursorRenderState>,
            skeletons: List<HandSkeletonState>
        )
        fun onTap(x: Float, y: Float)
        fun onDoubleTap(x: Float, y: Float)
        fun onLongPress(x: Float, y: Float)
        fun onSwipe(startX: Float, startY: Float, endX: Float, endY: Float)
        fun onPinchZoom(
            s1x: Float, s1y: Float, e1x: Float, e1y: Float,
            s2x: Float, s2y: Float, e2x: Float, e2y: Float
        )
        fun onVolumeUp()
        fun onVolumeDown()
        fun onBack()
        fun onHome()
        fun onRecents()
        fun onNotifications()
    }

    private data class PosSample(val t: Long, val x: Float, val y: Float)

    private class OneEuroFilter {
        private var initialized = false
        private var xPrev = 0f
        private var dxPrev = 0f

        fun reset() {
            initialized = false
        }

        fun filter(x: Float, dtSec: Float, minCutoff: Float, beta: Float): Float {
            if (!initialized) {
                initialized = true
                xPrev = x
                dxPrev = 0f
                return x
            }
            val dt = dtSec.coerceIn(0.008f, 0.15f)
            val dx = (x - xPrev) / dt
            val aD = alpha(1.0f, dt)
            val dxHat = dxPrev + (dx - dxPrev) * aD
            val cutoff = minCutoff + beta * abs(dxHat)
            val aX = alpha(cutoff, dt)
            val xHat = xPrev + (x - xPrev) * aX
            xPrev = xHat
            dxPrev = dxHat
            return xHat
        }

        private fun alpha(cutoff: Float, dt: Float): Float {
            val tau = 1f / (2f * PI.toFloat() * cutoff)
            return 1f / (1f + tau / dt)
        }
    }

    private class HandSlot(val id: Int) {
        var visible = false
        var lastSeenMs = 0L
        var x = 0f
        var y = 0f
        var normX = 0.5f
        var normY = 0.5f
        var pinchProgress = 0f

        val filterX = OneEuroFilter()
        val filterY = OneEuroFilter()
        val history = ArrayDeque<PosSample>()

        var indexPinching = false
        var indexStartTime = 0L
        var indexStartX = 0f
        var indexStartY = 0f
        var dragStarted = false

        // Natural double-pinch tracking for Reels / Shorts likes
        var lastTapMs = 0L
        var lastTapX = 0f
        var lastTapY = 0f

        // 0 = none, 1 = middle, 2 = ring, 3 = pinky
        var secondaryFinger = 0
        var secondaryStartTime = 0L
        var secondaryHoldFired = false
        var lastVolumeRepeatMs = 0L

        var lastGlobalActionMs = 0L
        var badge = ""
        var badgeUntilMs = 0L

        fun reset() {
            visible = false
            pinchProgress = 0f
            filterX.reset()
            filterY.reset()
            history.clear()
            indexPinching = false
            dragStarted = false
            secondaryFinger = 0
            secondaryHoldFired = false
        }
    }

    private val slots = arrayOf(HandSlot(0), HandSlot(1))

    private var zoomActive = false
    private var zStart0X = 0f
    private var zStart0Y = 0f
    private var zStart1X = 0f
    private var zStart1Y = 0f

    // Clean, responsive pinch hysteresis
    private val prePinchRatio = 0.50f
    private val pinchDownRatio = 0.34f
    private val pinchUpRatio = 0.42f

    private val secondaryDownRatio = 0.34f
    private val secondaryUpRatio = 0.43f

    private val longPressMs = 650L
    private val notifHoldMs = 550L
    private val volumeHoldMs = 480L
    private val volumeRepeatMs = 300L
    private val doublePinchWindowMs = 520L
    private val globalCooldownMs = 600L
    private val dropoutGraceMs = 160L

    fun processResult(
        result: HandLandmarkerResult,
        screenW: Float,
        screenH: Float,
        density: Float,
        frameW: Int,
        frameH: Int
    ) {
        val now = SystemClock.uptimeMillis()
        val hands = result.landmarks()
        val handednessList = result.handedness()
        val aspect = frameW.toFloat() / frameH.toFloat().coerceAtLeast(1f)

        if (hands.isEmpty()) {
            var anyAlive = false
            for (slot in slots) {
                if (slot.visible && now - slot.lastSeenMs <= dropoutGraceMs) {
                    anyAlive = true
                } else {
                    slot.reset()
                }
            }
            if (!anyAlive) {
                zoomActive = false
                listener.onCursorsUpdated(emptyList(), emptyList())
            }
            return
        }

        val assigned = arrayOfNulls<List<NormalizedLandmark>>(2)
        val usedHands = BooleanArray(hands.size)

        val handAnchors = hands.map { lm ->
            ((lm[5].x() + lm[9].x()) * 0.5f) to ((lm[5].y() + lm[9].y()) * 0.5f)
        }

        for (slotId in 0..1) {
            val slot = slots[slotId]
            if (!slot.visible) continue
            var bestIdx = -1
            var bestDist = 0.32f
            for (i in hands.indices) {
                if (usedHands[i]) continue
                val (ax, ay) = handAnchors[i]
                val d = hypot((ax - slot.normX) * aspect, ay - slot.normY)
                if (d < bestDist) {
                    bestDist = d
                    bestIdx = i
                }
            }
            if (bestIdx != -1) {
                assigned[slotId] = hands[bestIdx]
                usedHands[bestIdx] = true
            }
        }

        for (i in hands.indices) {
            if (usedHands[i]) continue
            val label = handednessList.getOrNull(i)?.firstOrNull()?.categoryName()
            val preferred = if (label == "Left") 1 else 0
            if (assigned[preferred] == null) {
                assigned[preferred] = hands[i]
                usedHands[i] = true
            } else if (assigned[1 - preferred] == null) {
                assigned[1 - preferred] = hands[i]
                usedHands[i] = true
            }
        }

        val skeletons = mutableListOf<HandSkeletonState>()

        for (slotId in 0..1) {
            val lm = assigned[slotId]
            val slot = slots[slotId]
            if (lm == null || lm.size < 21) {
                if (slot.visible && now - slot.lastSeenMs > dropoutGraceMs) {
                    slot.reset()
                }
                continue
            }
            val dtSec = if (slot.visible) (now - slot.lastSeenMs) / 1000f else 0.033f
            val wasVisible = slot.visible
            slot.visible = true
            slot.lastSeenMs = now
            updateSlot(slot, lm, screenW, screenH, density, aspect, now, dtSec, wasVisible)

            val pts = FloatArray(42)
            for (k in 0 until 21) {
                pts[k * 2] = lm[k].x()
                pts[k * 2 + 1] = lm[k].y()
            }
            skeletons.add(HandSkeletonState(slotId, pts))
        }

        val bothPinching = slots[0].visible && slots[1].visible &&
            slots[0].indexPinching && slots[1].indexPinching

        if (bothPinching && !zoomActive) {
            zoomActive = true
            zStart0X = slots[0].x
            zStart0Y = slots[0].y
            zStart1X = slots[1].x
            zStart1Y = slots[1].y
            setBadge(slots[0], "ZOOM", now + 450)
            setBadge(slots[1], "ZOOM", now + 450)
        } else if (zoomActive && !bothPinching) {
            zoomActive = false
            val startDist = hypot(zStart0X - zStart1X, zStart0Y - zStart1Y)
            val endDist = hypot(slots[0].x - slots[1].x, slots[0].y - slots[1].y)
            if (abs(endDist - startDist) > 22f * density) {
                listener.onPinchZoom(
                    zStart0X, zStart0Y, slots[0].x, slots[0].y,
                    zStart1X, zStart1Y, slots[1].x, slots[1].y
                )
            }
        }

        val renderList = slots.filter { it.visible }.map { s ->
            val currentBadge = when {
                now < s.badgeUntilMs -> s.badge
                s.indexPinching && !s.dragStarted && (now - s.indexStartTime >= longPressMs) -> "LONG PRESS"
                s.indexPinching -> "HOLD"
                else -> ""
            }
            val lockPos = s.indexPinching && !s.dragStarted && !zoomActive
            CursorRenderState(
                id = s.id,
                x = if (lockPos) s.indexStartX else s.x,
                y = if (lockPos) s.indexStartY else s.y,
                isPinching = s.indexPinching,
                pinchProgress = s.pinchProgress,
                dragStartX = if (s.indexPinching && s.dragStarted && !zoomActive) s.indexStartX else null,
                dragStartY = if (s.indexPinching && s.dragStarted && !zoomActive) s.indexStartY else null,
                badge = currentBadge
            )
        }
        listener.onCursorsUpdated(renderList, skeletons)
    }

    private fun updateSlot(
        slot: HandSlot,
        lm: List<NormalizedLandmark>,
        screenW: Float,
        screenH: Float,
        density: Float,
        aspect: Float,
        now: Long,
        dtSec: Float,
        wasVisible: Boolean
    ) {
        val anchorX = (lm[5].x() + lm[9].x()) * 0.5f
        val anchorY = (lm[5].y() + lm[9].y()) * 0.5f
        slot.normX = anchorX
        slot.normY = anchorY

        val palmHeight = dist2D(lm[0], lm[9], aspect)
        val palmWidth = dist2D(lm[5], lm[17], aspect) * 1.25f
        val palmSize = max(max(palmHeight, palmWidth), 0.05f)

        val thumb = lm[4]
        val indexRatio = dist2D(thumb, lm[8], aspect) / palmSize
        val middleRatio = dist2D(thumb, lm[12], aspect) / palmSize
        val ringRatio = dist2D(thumb, lm[16], aspect) / palmSize
        val pinkyRatio = dist2D(thumb, lm[20], aspect) / palmSize

        // Only suppress clicks during a tightly closed fist (never locks out future clicks)
        val wrist = lm[0]
        val tightFist = dist2D(lm[8], wrist, aspect) < dist2D(lm[5], wrist, aspect) * 0.98f &&
            dist2D(lm[12], wrist, aspect) < dist2D(lm[9], wrist, aspect) * 0.98f &&
            dist2D(lm[16], wrist, aspect) < dist2D(lm[13], wrist, aspect) * 0.98f &&
            dist2D(lm[20], wrist, aspect) < dist2D(lm[17], wrist, aspect) * 0.98f

        val margin = AirCursorConfig.activeMargin()
        val mappedNormX = ((anchorX - margin) / (1f - 2f * margin)).coerceIn(0f, 1f)
        val mappedNormY = ((anchorY - margin) / (1f - 2f * margin)).coerceIn(0f, 1f)

        val s = AirCursorConfig.smoothness.coerceIn(0f, 1f)
        val minCutoff = 4.5f * 0.08f.pow(s)
        val beta = 20.0f * 0.10f.pow(s)

        val smoothNormX = slot.filterX.filter(mappedNormX, dtSec, minCutoff, beta).coerceIn(0f, 1f)
        val smoothNormY = slot.filterY.filter(mappedNormY, dtSec, minCutoff, beta).coerceIn(0f, 1f)

        val targetX = smoothNormX * screenW
        val targetY = smoothNormY * screenH

        if (!wasVisible) {
            slot.x = targetX
            slot.y = targetY
        } else if (!tightFist && !slot.dragStarted && !zoomActive &&
            indexRatio < prePinchRatio && !slot.indexPinching
        ) {
            val brake = ((indexRatio - pinchDownRatio) / (prePinchRatio - pinchDownRatio))
                .coerceIn(0.25f, 1f)
            slot.x += (targetX - slot.x) * brake
            slot.y += (targetY - slot.y) * brake
        } else {
            slot.x = targetX
            slot.y = targetY
        }

        if (tightFist) {
            slot.indexPinching = false
            slot.dragStarted = false
            slot.secondaryFinger = 0
            slot.secondaryHoldFired = false
            slot.pinchProgress = 0f
            return
        }

        slot.pinchProgress = ((prePinchRatio - indexRatio) / (prePinchRatio - pinchDownRatio))
            .coerceIn(0f, 1f)

        slot.history.addLast(PosSample(now, slot.x, slot.y))
        while (slot.history.size > 10 && now - slot.history.first().t > 160L) {
            slot.history.removeFirst()
        }

        val dragThresholdPx = 32f * density

        // 1. Simple Thumb + Index: Click / Double-Pinch (2x Click) / Drag Swipe / Long Press
        if (!slot.indexPinching) {
            if (indexRatio < pinchDownRatio) {
                slot.indexPinching = true
                slot.dragStarted = false
                slot.indexStartTime = now
                slot.secondaryFinger = 0
                slot.secondaryHoldFired = false

                val steady = slot.history.lastOrNull { now - it.t >= 50L }
                if (steady != null && hypot(slot.x - steady.x, slot.y - steady.y) <= 24f * density) {
                    slot.indexStartX = steady.x
                    slot.indexStartY = steady.y
                } else {
                    slot.indexStartX = slot.x
                    slot.indexStartY = slot.y
                }
            }
        } else {
            val moved = hypot(slot.x - slot.indexStartX, slot.y - slot.indexStartY)
            if (moved > dragThresholdPx) {
                slot.dragStarted = true
            }

            if (indexRatio > pinchUpRatio) {
                slot.indexPinching = false
                if (!zoomActive) {
                    val duration = now - slot.indexStartTime
                    when {
                        slot.dragStarted -> {
                            slot.lastTapMs = 0L
                            setBadge(slot, "SWIPE", now + 450)
                            listener.onSwipe(slot.indexStartX, slot.indexStartY, slot.x, slot.y)
                        }
                        duration >= longPressMs -> {
                            slot.lastTapMs = 0L
                            setBadge(slot, "LONG PRESS", now + 500)
                            listener.onLongPress(slot.indexStartX, slot.indexStartY)
                        }
                        else -> {
                            val sincePrev = now - slot.lastTapMs
                            val distPrev = hypot(slot.indexStartX - slot.lastTapX, slot.indexStartY - slot.lastTapY)
                            if (slot.lastTapMs != 0L && sincePrev <= doublePinchWindowMs && distPrev <= 68f * density) {
                                // Snap 2nd pinch to exact 1st tap spot so Reels/Shorts double-tap always hits
                                val tx = slot.lastTapX
                                val ty = slot.lastTapY
                                slot.lastTapMs = 0L
                                setBadge(slot, "2X CLICK", now + 450)
                                if (sincePrev <= 240L) {
                                    listener.onTap(tx, ty)
                                } else {
                                    listener.onDoubleTap(tx, ty)
                                }
                            } else {
                                slot.lastTapMs = now
                                slot.lastTapX = slot.indexStartX
                                slot.lastTapY = slot.indexStartY
                                setBadge(slot, "CLICK", now + 350)
                                listener.onTap(slot.indexStartX, slot.indexStartY)
                            }
                        }
                    }
                }
                slot.dragStarted = false
            }
        }

        if (slot.indexPinching || zoomActive) return

        // 2. Classic secondary gestures + Volume on Ring/Pinky hold
        if (slot.secondaryFinger == 0) {
            var bestFinger = 0
            var bestRatio = secondaryDownRatio
            if (middleRatio < bestRatio) { bestRatio = middleRatio; bestFinger = 1 }
            if (ringRatio < bestRatio) { bestRatio = ringRatio; bestFinger = 2 }
            if (pinkyRatio < bestRatio) { bestRatio = pinkyRatio; bestFinger = 3 }

            if (bestFinger != 0) {
                slot.secondaryFinger = bestFinger
                slot.secondaryStartTime = now
                slot.secondaryHoldFired = false
                slot.lastVolumeRepeatMs = 0L
            }
        } else {
            val activeRatio = when (slot.secondaryFinger) {
                1 -> middleRatio
                2 -> ringRatio
                else -> pinkyRatio
            }
            val heldMs = now - slot.secondaryStartTime

            when (slot.secondaryFinger) {
                1 -> {
                    // Middle hold -> Notifications
                    if (!slot.secondaryHoldFired && heldMs >= notifHoldMs) {
                        slot.secondaryHoldFired = true
                        if (now - slot.lastGlobalActionMs > globalCooldownMs) {
                            slot.lastGlobalActionMs = now
                            setBadge(slot, "NOTIFS", now + 600)
                            listener.onNotifications()
                        }
                    }
                }
                2 -> {
                    // Ring hold -> Volume Up (repeats while held)
                    if (heldMs >= volumeHoldMs &&
                        (!slot.secondaryHoldFired || now - slot.lastVolumeRepeatMs >= volumeRepeatMs)
                    ) {
                        slot.secondaryHoldFired = true
                        slot.lastVolumeRepeatMs = now
                        setBadge(slot, "VOL +", now + 450)
                        listener.onVolumeUp()
                    }
                }
                3 -> {
                    // Pinky hold -> Volume Down (repeats while held)
                    if (heldMs >= volumeHoldMs &&
                        (!slot.secondaryHoldFired || now - slot.lastVolumeRepeatMs >= volumeRepeatMs)
                    ) {
                        slot.secondaryHoldFired = true
                        slot.lastVolumeRepeatMs = now
                        setBadge(slot, "VOL -", now + 450)
                        listener.onVolumeDown()
                    }
                }
            }

            // Quick release -> classic Back / Home / Recents
            if (activeRatio > secondaryUpRatio) {
                val finger = slot.secondaryFinger
                val wasHold = slot.secondaryHoldFired
                slot.secondaryFinger = 0
                slot.secondaryHoldFired = false

                if (!wasHold && now - slot.lastGlobalActionMs > globalCooldownMs) {
                    slot.lastGlobalActionMs = now
                    when (finger) {
                        1 -> { setBadge(slot, "BACK", now + 500); listener.onBack() }
                        2 -> { setBadge(slot, "HOME", now + 600); listener.onHome() }
                        3 -> { setBadge(slot, "RECENTS", now + 600); listener.onRecents() }
                    }
                }
            }
        }
    }

    private fun dist2D(a: NormalizedLandmark, b: NormalizedLandmark, aspect: Float): Float {
        return hypot((a.x() - b.x()) * aspect, a.y() - b.y())
    }

    private fun setBadge(slot: HandSlot, label: String, untilMs: Long) {
        slot.badge = label
        slot.badgeUntilMs = untilMs
    }
}
