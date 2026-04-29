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

        private const val DRAG_RADIUS = 260f
        private const val GESTURE_DURATION_MS = 130L
        private const val START_BACK_FACTOR = 0.45f
        private const val END_FORWARD_FACTOR = 1.15f
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Não precisa ler eventos da tela.
    }

    override fun onInterrupt() {
        // Chamado quando o Android interrompe o serviço.
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

            if (!cueX.isFinite() || !cueY.isFinite() || angleRad.isNaN()) {
                return
            }

            val dirX = cos(angleRad).toFloat()
            val dirY = sin(angleRad).toFloat()

            val startX = cueX - dirX * DRAG_RADIUS * START_BACK_FACTOR
            val startY = cueY - dirY * DRAG_RADIUS * START_BACK_FACTOR

            val endX = cueX + dirX * DRAG_RADIUS * END_FORWARD_FACTOR
            val endY = cueY + dirY * DRAG_RADIUS * END_FORWARD_FACTOR

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        path,
                        0L,
                        GESTURE_DURATION_MS
                    )
                )
                .build()

            mainHandler.post {
                try {
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
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
