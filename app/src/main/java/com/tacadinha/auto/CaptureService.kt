package com.tacadinha.auto

import android.app.*
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.ImageButton
import androidx.core.app.NotificationCompat

class CaptureService : Service() {

    companion object {
        const val CH = "tac_auto"
        const val NID = 55
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vDisplay: VirtualDisplay? = null

    private lateinit var wm: WindowManager
    private var floatBtn: View? = null

    private var sw = 0
    private var sh = 0
    private var dpi = 0

    private val handler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            releaseCapture()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(m)

        sw = m.widthPixels
        sh = m.heightPixels
        dpi = m.densityDpi

        createChannel()
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", Activity.RESULT_CANCELED)
            ?: return START_NOT_STICKY

        val data: Intent = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("data", Intent::class.java)
                    ?: return START_NOT_STICKY
            } else {
                intent.getParcelableExtra("data")
                    ?: return START_NOT_STICKY
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return START_NOT_STICKY
        }

        startForeground(NID, buildNotif())

        try {
            releaseCapture()

            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = pm.getMediaProjection(code, data)

            projection?.registerCallback(
                projectionCallback,
                handler
            )

            reader = ImageReader.newInstance(
                sw,
                sh,
                PixelFormat.RGBA_8888,
                2
            )

            vDisplay = projection?.createVirtualDisplay(
                "TacAuto",
                sw,
                sh,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface,
                null,
                handler
            )

            if (floatBtn == null) {
                addFloatingButton()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            ToastHelper.show(this, "Erro ao iniciar captura")
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    private fun addFloatingButton() {
        val btn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_compass)
            setBackgroundColor(Color.argb(220, 0, 200, 80))
            setPadding(24, 24, 24, 24)
        }

        val lp = WindowManager.LayoutParams(
            160,
            160,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        var dX = 0f
        var dY = 0f
        var sX = 0f
        var sY = 0f
        var moved = false

        btn.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sX = e.rawX
                    sY = e.rawY
                    dX = lp.x.toFloat()
                    dY = lp.y.toFloat()
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val diffX = e.rawX - sX
                    val diffY = e.rawY - sY

                    if (kotlin.math.abs(diffX) > 8 || kotlin.math.abs(diffY) > 8) {
                        moved = true
                    }

                    lp.x = (dX - diffX).toInt()
                    lp.y = (dY + diffY).toInt()

                    try {
                        wm.updateViewLayout(v, lp)
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        v.performClick()
                        triggerAutoAim()
                    }
                    true
                }

                else -> true
            }
        }

        try {
            wm.addView(btn, lp)
            floatBtn = btn
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerAutoAim() {
        val svc = AimAccessibilityService.instance ?: return
        val image = reader?.acquireLatestImage() ?: return

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * sw
            val bitmapWidth = sw + rowPadding / pixelStride

            val bmp = Bitmap.createBitmap(
                bitmapWidth,
                sh,
                Bitmap.Config.ARGB_8888
            )

            bmp.copyPixelsFromBuffer(buffer)

            val finalBmp = if (bitmapWidth != sw) {
                Bitmap.createBitmap(bmp, 0, 0, sw, sh).also {
                    bmp.recycle()
                }
            } else {
                bmp
            }

            handler.post {
                try {
                    val result = Detector.analyze(finalBmp, sw, sh)
                    val cue = result.first
                    val balls = result.second
                    val pockets = result.third

                    finalBmp.recycle()

                    if (cue == null || balls.isEmpty()) return@post

                    val shot = ShotCalculator.bestShot(cue, balls, pockets)
                        ?: return@post

                    if (!shot.willScore) return@post

                    handler.postDelayed({
                        svc.aimCue(cue.x, cue.y, shot.angleRad)
                    }, 300L)

                } catch (e: Exception) {
                    e.printStackTrace()
                    try {
                        if (!finalBmp.isRecycled) finalBmp.recycle()
                    } catch (_: Exception) {
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                image.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun releaseCapture() {
        try {
            vDisplay?.release()
        } catch (_: Exception) {
        }
        vDisplay = null

        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
    }

    override fun onDestroy() {
        try {
            floatBtn?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }

        floatBtn = null

        releaseCapture()

        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        projection = null

        super.onDestroy()
    }

    override fun onBind(i: Intent?) = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(
                        CH,
                        "Tacadinha Auto",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }
    }

    private fun buildNotif() =
        NotificationCompat.Builder(this, CH)
            .setContentTitle("🎱 Tacadinha Auto")
            .setContentText("Toque no botão verde para mirar")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}

/**
 * Helper simples pra evitar crash caso Toast seja chamado fora da thread principal.
 */
object ToastHelper {
    fun show(context: android.content.Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                context.applicationContext,
                msg,
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
