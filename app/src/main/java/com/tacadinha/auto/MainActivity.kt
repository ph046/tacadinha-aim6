package com.tacadinha.auto

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    companion object {
        const val REQ_PROJECTION = 1001
        const val REQ_OVERLAY = 1002
    }
    private lateinit var pm: MediaProjectionManager
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        tvStatus = findViewById(R.id.tvStatus)
        findViewById<Button>(R.id.btnStart).setOnClickListener { checkAndStart() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, CaptureService::class.java))
            tvStatus.text = "⚫ Desativado"
        }
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Ative 'Tacadinha Auto' na lista", Toast.LENGTH_LONG).show()
        }
        updateStatus()
    }

    override fun onResume() { super.onResume(); updateStatus() }

    private fun updateStatus() {
        val ok = AimAccessibilityService.instance != null
        tvStatus.text = if (ok) "✅ Acessibilidade ativa! Pronto." else "⚠️ Ative a Acessibilidade primeiro"
    }

    private fun checkAndStart() {
        if (AimAccessibilityService.instance == null) {
            Toast.makeText(this, "Ative a Acessibilidade primeiro!", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), REQ_OVERLAY); return
        }
        startActivityForResult(pm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> if (Settings.canDrawOverlays(this)) checkAndStart()
            REQ_PROJECTION -> if (resultCode == Activity.RESULT_OK && data != null) {
                val i = Intent(this, CaptureService::class.java).apply {
                    putExtra("code", resultCode); putExtra("data", data)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
                tvStatus.text = "🎱 Mira automática ATIVA!"
                tvStatus.setTextColor(0xFF00FF88.toInt())
                Toast.makeText(this, "Abra seu jogo e toque no botao verde!", Toast.LENGTH_LONG).show()
                moveTaskToBack(true)
            }
        }
    }
}
