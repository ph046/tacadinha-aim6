package com.tacadinha.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlin.math.cos
import kotlin.math.sin

class AimAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AimAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Não precisa fazer nada aqui por enquanto
    }

    override fun onInterrupt() {
        // Chamado quando o Android interrompe o serviço
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) {
            instance = null
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun aimCue(cueX: Float, cueY: Float, angleRad: Double) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

            if (!cueX.isFinite() || !cueY.isFinite() || angleRad.isNaN()) return

            val dragRadius = 200f

            val startX = cueX - (cos(angleRad) * dragRadius * 0.5f).toFloat()
            val startY = cueY - (sin(angleRad) * dragRadius * 0.5f).toFloat()

            val endX = cueX + (cos(angleRad) * dragRadius).toFloat()
            val endY = cueY + (sin(angleRad) * dragRadius).toFloat()

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0L,
                        350L
                    )
                )
                .build()

            Handler(Looper.getMainLooper()).post {
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            super.onCompleted(gestureDescription)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            super.onCancelled(gestureDescription)
                        }
                    },
                    null
                )
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
