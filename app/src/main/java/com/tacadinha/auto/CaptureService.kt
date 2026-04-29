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

        // Deixe false para ficar rápido.
        const val DEBUG_SAVE_CAPTURE = false

        // Deixe false para não ficar mostrando mensagem toda hora.
        const val DEBUG_TOASTS = false

        // Se mirar invertido, troque para true.
        const val INVERT_AIM = false
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vDisplay: VirtualDisplay? = null

    private lateinit var wm: WindowManager
    private var floatBtn: View? = null

    private var sw = 0
    private var sh = 0
    private var dpi = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var processingThread: HandlerThread
    private lateinit var processingHandler: Handler

    private var isProcessing = false

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

        processingThread = HandlerThread("TacadinhaProcessing")
        processingThread.start()
        processingHandler = Handler(processingThread.looper)

        refreshScreenMetrics()
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

            refreshScreenMetrics()

            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = pm.getMediaProjection(code, data)

            projection?.registerCallback(
                projectionCallback,
                mainHandler
            )

            reader = ImageReader.newInstance(
                sw,
                sh,
                PixelFormat.RGBA_8888,
                3
            )

            vDisplay = projection?.createVirtualDisplay(
                "TacAuto",
                sw,
                sh,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface,
                null,
                mainHandler
            )

            if (floatBtn == null) {
                addFloatingButton()
            }

            debugToast("Captura iniciada: ${sw}x${sh}")

        } catch (e: Exception) {
            e.printStackTrace()
            ToastHelper.show(this, "Erro ao iniciar captura")
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun refreshScreenMetrics() {
        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)

        sw = m.widthPixels
        sh = m.heightPixels
        dpi = m.densityDpi

        if (sw <= 0 || sh <= 0) {
            val fallback = resources.displayMetrics
            sw = fallback.widthPixels
            sh = fallback.heightPixels
            dpi = fallback.densityDpi
        }
    }

    private fun resizeCaptureIfNeeded(): Boolean {
        val oldW = sw
        val oldH = sh
        val oldDpi = dpi

        refreshScreenMetrics()

        if (oldW == sw && oldH == sh && oldDpi == dpi) {
            return false
        }

        val vd = vDisplay ?: return false

        return try {
            val oldReader = reader

            val newReader = ImageReader.newInstance(
                sw,
                sh,
                PixelFormat.RGBA_8888,
                3
            )

            vd.resize(sw, sh, dpi)
            vd.setSurface(newReader.surface)

            reader = newReader

            try {
                oldReader?.close()
            } catch (_: Exception) {
            }

            ToastHelper.show(this, "Orientação ajustada. Toque de novo.")
            true

        } catch (e: Exception) {
            e.printStackTrace()
            ToastHelper.show(this, "Erro ao ajustar orientação")
            false
        }
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
            ToastHelper.show(this, "Erro ao mostrar botão flutuante")
        }
    }

    private fun triggerAutoAim() {
        if (isProcessing) return

        val svc = AimAccessibilityService.instance
        if (svc == null) {
            ToastHelper.show(this, "Acessibilidade não conectada")
            return
        }

        val resized = resizeCaptureIfNeeded()
        if (resized) return

        val image = reader?.acquireLatestImage()
        if (image == null) {
            debugToast("Imagem ainda não disponível")
            return
        }

        isProcessing = true

        try {
            val plane = image.planes[0]
            val buffer = plane.buffer

            val imageWidth = image.width
            val imageHeight = image.height

            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            if (pixelStride <= 0 || rowStride <= 0) {
                debugToast("Frame inválido")
                isProcessing = false
                return
            }

            val bitmapWidth = rowStride / pixelStride

            val bmp = Bitmap.createBitmap(
                bitmapWidth,
                imageHeight,
                Bitmap.Config.ARGB_8888
            )

            bmp.copyPixelsFromBuffer(buffer)

            val finalBmp = if (bitmapWidth != imageWidth) {
                Bitmap.createBitmap(bmp, 0, 0, imageWidth, imageHeight).also {
                    bmp.recycle()
                }
            } else {
                bmp
            }

            if (DEBUG_SAVE_CAPTURE) {
                saveDebugImage(finalBmp)
            }

            processingHandler.post {
                try {
                    val result = Detector.analyze(
                        finalBmp,
                        finalBmp.width,
                        finalBmp.height
                    )

                    val cue = result.first
                    val balls = result.second
                    val pockets = result.third

                    if (!finalBmp.isRecycled) {
                        finalBmp.recycle()
                    }

                    if (cue == null || balls.isEmpty()) {
                        debugToast("Não reconheceu mesa")
                        return@post
                    }

                    val shot = ShotCalculator.bestShot(cue, balls, pockets)
                        ?: return@post

                    if (!shot.willScore) {
                        return@post
                    }

                    val finalAngle = if (INVERT_AIM) {
                        shot.angleRad + Math.PI
                    } else {
                        shot.angleRad
                    }

                    mainHandler.postDelayed({
                        svc.aimCue(cue.x, cue.y, finalAngle)
                    }, 60L)

                } catch (e: Exception) {
                    e.printStackTrace()
                    debugToast("Erro no cálculo")

                    try {
                        if (!finalBmp.isRecycled) finalBmp.recycle()
                    } catch (_: Exception) {
                    }

                } finally {
                    isProcessing = false
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            isProcessing = false
            debugToast("Erro ao processar imagem")
        } finally {
            try {
                image.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun saveDebugImage(bitmap: Bitmap) {
        try {
            val dir = java.io.File(
                getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "debug"
            )

            if (!dir.exists()) dir.mkdirs()

            val file = java.io.File(dir, "capture_debug.png")

            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            android.media.MediaScannerConnection.scanFile(
                this,
                arrayOf(file.absolutePath),
                arrayOf("image/png"),
                null
            )

            debugToast("Print salvo: ${bitmap.width}x${bitmap.height}")

        } catch (e: Exception) {
            e.printStackTrace()
            debugToast("Erro ao salvar print")
        }
    }

    private fun debugToast(msg: String) {
        if (DEBUG_TOASTS) {
            ToastHelper.show(this, msg)
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

        try {
            processingThread.quitSafely()
        } catch (_: Exception) {
        }

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
