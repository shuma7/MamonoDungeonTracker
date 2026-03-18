package com.mamotra.tracker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mamotra.tracker.data.AppDatabase
import com.mamotra.tracker.databinding.ActivityMainBinding
import com.mamotra.tracker.service.ScreenCaptureService
import com.mamotra.tracker.ui.StatsActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var isTracking = false

    // MediaProjection 権限リクエスト
    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                // consent result の data を resultCode と共にそのまま Service へ渡す。
                // Android 15 では data 内の EXTRA_MEDIA_PROJECTION トークンを
                // Service 起動 Intent のトップレベル extra に展開することで
                // startForeground(type=mediaProjection) の権限チェックを通過させる。
                ScreenCaptureService.startService(this, result.resultCode, result.data!!)
                isTracking = true
                updateTrackingUI()
            } else {
                Toast.makeText(this, "スクリーンキャプチャ権限が必要です", Toast.LENGTH_SHORT).show()
            }
        }

    // 通知権限リクエスト (Android 13+)
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                requestMediaProjection()
            } else {
                Toast.makeText(this, "通知権限がないと一部機能が制限されます", Toast.LENGTH_SHORT).show()
                requestMediaProjection()
            }
        }

    // オーバーレイ権限設定画面から戻ったとき
    private val overlaySettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updatePermissionStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        binding.btnGrantOverlay.setOnClickListener { openOverlaySettings() }
        binding.btnStartStop.setOnClickListener { onStartStopClicked() }
        binding.btnViewStats.setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
        }

        updatePermissionStatus()
        updateStatsPreview()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun onStartStopClicked() {
        if (isTracking) {
            ScreenCaptureService.stopService(this)
            isTracking = false
            updateTrackingUI()
        } else {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "先にオーバーレイ権限を許可してください", Toast.LENGTH_SHORT).show()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
            }
            requestMediaProjection()
        }
    }

    private fun requestMediaProjection() {
        mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlaySettingsLauncher.launch(intent)
    }

    private fun updatePermissionStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        binding.tvOverlayStatus.text =
            if (hasOverlay) "オーバーレイ権限: OK" else "オーバーレイ権限: 未設定"
        binding.btnGrantOverlay.visibility =
            if (hasOverlay) View.GONE else View.VISIBLE
        binding.btnStartStop.isEnabled = hasOverlay
    }

    private fun updateTrackingUI() {
        if (isTracking) {
            binding.btnStartStop.text = getString(R.string.stop_tracking)
            binding.tvTrackingStatus.text = getString(R.string.tracking_active)
            binding.tvTrackingStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            binding.btnStartStop.text = getString(R.string.start_tracking)
            binding.tvTrackingStatus.text = getString(R.string.tracking_inactive)
            binding.tvTrackingStatus.setTextColor(getColor(android.R.color.darker_gray))
        }
        updateStatsPreview()
    }

    private fun updateStatsPreview() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@MainActivity)
            val total = db.battleDao().getTotalCount()
            val wins = db.battleDao().getWinCount()
            val winRate = if (total > 0) wins * 100 / total else 0
            binding.tvStatsPreview.text = "総対戦: ${total}戦 / 勝率: ${winRate}%"
        }
    }
}
