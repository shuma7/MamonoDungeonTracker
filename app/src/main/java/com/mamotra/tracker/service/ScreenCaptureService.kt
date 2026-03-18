package com.mamotra.tracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.util.Log
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.mamotra.tracker.R
import com.mamotra.tracker.data.AppDatabase
import com.mamotra.tracker.ocr.BattleResultParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScreenCaptureService : LifecycleService() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        private const val CHANNEL_ID = "MamotraChannel"
        private const val NOTIFICATION_ID = 1
        private const val CAPTURE_INTERVAL_MS = 2000L
        private const val COOLDOWN_MS = 45_000L

        fun startService(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                // Android 15: consent result (data) の extras を Service 起動 Intent の
                // トップレベルに展開する。
                // data には EXTRA_MEDIA_PROJECTION (IBinder トークン) が含まれており、
                // これをトップレベルに置くことでシステムが consent との紐付けを認識し、
                // startForeground(type=mediaProjection) の権限チェックを通過できる。
                // また onStartCommand 内で同 intent を getMediaProjection() に渡すことで
                // MediaProjection オブジェクトも取得可能になる。
                putExtras(data)
            }
            context.startForegroundService(intent)
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var overlayView: android.view.View? = null
    private var statusText: TextView? = null
    private var windowManager: WindowManager? = null
    private val textRecognizer =
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private var captureJob: Job? = null
    private var lastDetectionTime = 0L
    // バトル前レーティング追跡：通常フレームで更新し、結果画面でWIN/LOSEの判定に使う
    private var preBattleRating: Int = 0
    // 二段階検出: 結果画面で判定不能だった場合にロビー画面まで持ち越すデータ
    private var pendingBattleData: BattleResultParser.PendingBattleData? = null

    private val screenWidth by lazy { resources.displayMetrics.widthPixels }
    private val screenHeight by lazy { resources.displayMetrics.heightPixels }
    private val screenDensity by lazy { resources.displayMetrics.densityDpi }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -999)
            if (resultCode == android.app.Activity.RESULT_OK) {
                // startForeground(type=mediaProjection) を呼ぶ前に
                // consent トークンが intent のトップレベル extras に入っているため
                // Android 15 の android:project_media チェックを通過できる
                val notification = buildNotification("トラッキング中...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                showOverlay()
                // intent には putExtras(data) で展開した EXTRA_MEDIA_PROJECTION が入っているので
                // そのまま getMediaProjection() に渡せる
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, intent)
                setupImageReader()
                startCapturing()
            } else {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun setupImageReader() {
        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
        )
        // Android 14+: createVirtualDisplay() の前に Callback の登録が必須
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // ユーザーが画面共有を停止したときにサービスも後片付けして停止する
                captureJob?.cancel()
                virtualDisplay?.release()
                imageReader?.close()
                overlayView?.let { windowManager?.removeView(it) }
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "MamotraCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun startCapturing() {
        captureJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(CAPTURE_INTERVAL_MS)
                val now = System.currentTimeMillis()
                if (now - lastDetectionTime < COOLDOWN_MS) continue

                val bitmap = captureScreen() ?: continue
                try {
                    processBitmap(bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    private fun captureScreen(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth
            val raw = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            raw.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(raw, 0, 0, screenWidth, screenHeight)
            raw.recycle()
            cropped
        } catch (e: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private suspend fun processBitmap(bitmap: Bitmap) {
        try {
            val fullText = recognizeText(bitmap)
            if (fullText.isBlank()) return

            // デバッグ: OCR テキストを出力（解析できない場合の原因調査用）
            Log.d("MamotraOCR", "=== OCR テキスト ===\n$fullText\n===================")

            val isResultScreen = fullText.contains("獲得ポ")

            // === Phase 2: pendingBattleData がある場合、ロビー画面で新レーティングを確認 ===
            val pending = pendingBattleData
            if (pending != null && !isResultScreen && fullText.contains("BATTLE", ignoreCase = true)) {
                Log.d("MamotraOCR", "Phase2: ロビー画面検出 (BATTLE) → 新レーティングで WIN/LOSE 判定")
                val record = BattleResultParser.parseFromLobby(
                    lobbyText          = fullText,
                    preBattleRating    = pending.preBattleRating,
                    resultScreenRating = pending.resultScreenRating,
                    opponentName       = pending.opponentName,
                    opponentRating     = pending.opponentRating,
                    ocrTrophyChange    = pending.ocrTrophyChange
                )
                pendingBattleData = null  // 結果に関わらずリセット
                if (record != null) {
                    saveRecord(record)
                    return
                }
                Log.d("MamotraOCR", "Phase2: parseFromLobby → null（判定不能）")
                return
            }

            // === 通常フレーム: バトル前レーティングを追跡（結果画面以外・pending なし）===
            if (!isResultScreen && pending == null) {
                val rating = BattleResultParser.extractMyRating(fullText)
                if (rating > 0) {
                    preBattleRating = rating
                    Log.d("MamotraOCR", "バトル中レーティング追跡: $rating")
                }
            }

            // === Phase 1: 結果画面の解析 ===
            if (!isResultScreen) return

            val parseResult = BattleResultParser.parse(fullText, preBattleRating)

            when {
                parseResult.record != null -> {
                    // WIN/LOSE 確定 → 即座に保存
                    Log.d("MamotraOCR", "Phase1: WIN/LOSE 確定 → 保存")
                    pendingBattleData = null
                    saveRecord(parseResult.record)
                }
                parseResult.pendingData != null -> {
                    // 判定不能 → ロビー画面まで持ち越し
                    Log.d("MamotraOCR", "Phase1: 判定不能 → pendingData 保存してロビー待ち")
                    pendingBattleData = parseResult.pendingData
                }
                else -> {
                    Log.d("MamotraOCR", "parse() → 両方 null（結果画面だが必須データ未取得）")
                }
            }
        } catch (e: Exception) {
            // OCR/解析失敗は無視して次のフレームへ
            Log.e("MamotraOCR", "processBitmap 例外", e)
        }
    }

    private suspend fun saveRecord(record: com.mamotra.tracker.data.BattleRecord) {
        val db = AppDatabase.getInstance(this@ScreenCaptureService)
        db.battleDao().insert(record)
        lastDetectionTime = System.currentTimeMillis()
        // pending と preBattleRating はバトル終了後にリセット
        pendingBattleData = null

        withContext(Dispatchers.Main) {
            val msg = if (record.result == "WIN") "WIN! 記録しました +" + record.trophyChange
                      else "LOSE 記録しました " + record.trophyChange
            updateOverlayStatus(msg)
            Toast.makeText(this@ScreenCaptureService, msg, Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({
                updateOverlayStatus("トラッキング中...")
            }, 5000)
        }
    }

    private suspend fun recognizeText(bitmap: Bitmap): String =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(image)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result.text)
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }
        }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_status, null)
        statusText = overlayView?.findViewById(R.id.overlayStatusText)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 120
        }
        windowManager?.addView(overlayView, params)
    }

    private fun updateOverlayStatus(text: String) {
        statusText?.text = text
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "マモトラ", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "まものダンジョン+の戦績トラッキング中" }
        (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("マモトラ")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

    override fun onDestroy() {
        captureJob?.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        overlayView?.let { windowManager?.removeView(it) }
        textRecognizer.close()
        super.onDestroy()
    }
}
