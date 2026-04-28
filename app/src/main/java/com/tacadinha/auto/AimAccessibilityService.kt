package com.tacadinha.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlin.math.cos
import kotlin.math.sin

class AimAccessibilityService : AccessibilityService() {
    companion object { var instance: AimAccessibilityService? = null }
    override fun onServiceConnected() { instance = this }
    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    fun aimCue(cueX: Float, cueY: Float, angleRad: Double) {
        val dragRadius = 200f
        val startX = cueX - (cos(angleRad) * dragRadius * 0.5f).toFloat()
        val startY = cueY - (sin(angleRad) * dragRadius * 0.5f).toFloat()
        val endX = cueX + (cos(angleRad) * dragRadius).toFloat()
        val endY = cueY + (sin(angleRad) * dragRadius).toFloat()
        val path = Path().apply { moveTo(startX, startY); lineTo(endX, endY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 350L)).build()
        dispatchGesture(gesture, null, null)
    }
}
