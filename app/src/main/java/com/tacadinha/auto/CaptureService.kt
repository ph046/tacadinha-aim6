package com.tacadinha.auto

import android.app.*
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.ImageButton
import androidx.core.app.NotificationCompat

class CaptureService : Service() {
    companion object { const val CH = "tac_auto"; const val NID = 55 }
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vDisplay: android.hardware.display.VirtualDisplay? = null
    private lateinit var wm: WindowManager
    private var floatBtn: View? = null
    private var sw = 0; private var sh = 0; private var dpi = 0
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate(); wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(m)
        sw = m.widthPixels; sh = m.heightPixels; dpi = m.densityDpi; createChannel()
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        val data: Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra("data", Intent::class.java)!!
        else intent.getParcelableExtra("data")!!
        startForeground(NID, buildNotif())
        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = pm.getMediaProjection(code, data)
        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
        vDisplay = projection!!.createVirtualDisplay("TacAuto", sw, sh, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
        addFloatingButton(); return START_STICKY
    }

    private fun addFloatingButton() {
        val btn = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_compass)
            setBackgroundColor(Color.argb(220, 0, 200, 80)); setPadding(24, 24, 24, 24)
        }
        val lp = WindowManager.LayoutParams(160, 160,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 200 }
        btn.setOnClickListener { triggerAutoAim() }
        var dX = 0f; var dY = 0f; var sX = 0f; var sY = 0f
        btn.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sX = e.rawX; sY = e.rawY; dX = lp.x.toFloat(); dY = lp.y.toFloat(); false }
                MotionEvent.ACTION_MOVE -> { lp.x = (dX+(sX-e.rawX)).toInt(); lp.y = (dY+(e.rawY-sY)).toInt(); wm.updateViewLayout(v, lp); true }
                else -> false
            }
        }
        wm.addView(btn, lp); floatBtn = btn
    }

    private fun triggerAutoAim() {
        val svc = AimAccessibilityService.instance ?: return
        val image = reader?.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]; val bw = plane.rowStride / plane.pixelStride
            val bmp = Bitmap.createBitmap(bw, sh, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            val final = if (bw != sw) Bitmap.createBitmap(bmp, 0, 0, sw, sh).also { bmp.recycle() } else bmp
            handler.post {
                val (cue, balls, pockets) = Detector.analyze(final, sw, sh)
                final.recycle()
                if (cue == null || balls.isEmpty()) return@post
                val shot = ShotCalculator.bestShot(cue, balls, pockets) ?: return@post
                if (!shot.willScore) return@post
                handler.postDelayed({ svc.aimCue(cue.x, cue.y, shot.angleRad) }, 300L)
            }
        } catch (e: Exception) { e.printStackTrace() } finally { image.close() }
    }

    override fun onDestroy() {
        try { floatBtn?.let { wm.removeView(it) } } catch (_: Exception) {}
        vDisplay?.release(); projection?.stop(); reader?.close(); super.onDestroy()
    }
    override fun onBind(i: Intent?) = null
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CH, "Tacadinha Auto", NotificationManager.IMPORTANCE_LOW))
    }
    private fun buildNotif() = NotificationCompat.Builder(this, CH)
        .setContentTitle("🎱 Tacadinha Auto").setContentText("Toque no botao verde para mirar")
        .setSmallIcon(android.R.drawable.ic_menu_compass).setPriority(NotificationCompat.PRIORITY_LOW).build()
}
