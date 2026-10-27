package com.floattranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * 悬浮窗服务：始终置顶、可拖动、可收起。
 * 按钮：翻译屏幕 / 选图由主界面处理（见 MainActivity）/ 字幕开/关 / 收起 / 关闭。
 * 屏幕翻译流程：MediaProjection -> VirtualDisplay -> ImageReader 取一帧
 * -> ML Kit OCR -> ML Kit 翻译 -> 悬浮窗大字显示。
 */
class FloatingService : Service() {

    companion object {
        const val ACTION_SHOW_RESULT = "com.floattranslator.SHOW_RESULT"
        const val EXTRA_ORIGINAL = "original"
        const val EXTRA_TRANSLATED = "translated"
        const val EXTRA_COMPARISON = "comparison"
        const val ACTION_STOP = "com.floattranslator.STOP"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var root: View
    private lateinit var tvOriginal: TextView
    private lateinit var tvTranslated: TextView
    private lateinit var tvCompare: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvStatus: TextView
    private var expanded = true
    private var subtitleRecognizer: SubtitleRecognizer? = null
    private var subtitleOn = false

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        root = LayoutInflater.from(this).inflate(R.layout.overlay_floating, null)
        tvOriginal = root.findViewById(R.id.tvOriginal)
        tvTranslated = root.findViewById(R.id.tvTranslated)
        tvCompare = root.findViewById(R.id.tvCompare)
        tvSubtitle = root.findViewById(R.id.tvSubtitle)
        tvStatus = root.findViewById(R.id.tvStatus)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }
        windowManager.addView(root, params)
        makeDraggable(root, params)

        root.findViewById<Button>(R.id.btnTranslateScreen).setOnClickListener {
            captureAndTranslate()
        
        }
        root.findViewById<Button>(R.id.btnTranslateAccessibility).setOnClickListener {
            val accText = AccessibilityTextHolder.latestText.trim()
            if (accText.isEmpty()) {
                tvStatus.text = "无障碍还没读到当前页文字：请先在主界面开无障碍，再回到文章页等几秒"
                Toast.makeText(this, "无障碍还没读到当前页文字：请先在主界面开无障碍，再回到文章页等几秒", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            tvOriginal.text = "原文：$accText"
            tvStatus.text = "正在翻译…"
            TranslatorEngine.translateAuto(
                this,
                accText,
                onResult = { translated, direction ->
                    tvTranslated.text = "译文：$translated"
                    tvStatus.text = if (direction == TranslatorEngine.Direction.EN_TO_ZH) "英文 → 中文（无障碍读屏）" else "中文 → 英文（无障碍读屏）"
                },
                onError = { tvStatus.text = "翻译失败：${it.message}，请检查网络或在设置页填百度密钥" }
                )
        }
        root.findViewById<Button>(R.id.btnSubtitle).setOnClickListener {
            toggleSubtitle(it as Button)
        }
        root.findViewById<Button>(R.id.btnCollapse).setOnClickListener {
            expanded = !expanded
            root.findViewById<View>(R.id.resultPane).visibility =
                if (expanded) View.VISIBLE else View.GONE
            (it as Button).text = if (expanded) "收起" else "展开"
        }
        root.findViewById<Button>(R.id.btnClose).setOnClickListener { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SHOW_RESULT) {
            val original = intent.getStringExtra(EXTRA_ORIGINAL).orEmpty()
            val translated = intent.getStringExtra(EXTRA_TRANSLATED).orEmpty()
            val comparison = intent.getStringExtra(EXTRA_COMPARISON).orEmpty()
            if (original.isNotEmpty()) {
                tvOriginal.text = "原文：$original"
                tvTranslated.text = "译文：$translated"
                if (comparison.isNotEmpty()) {
                    tvCompare.text = "谷歌对照：$comparison"
                    tvCompare.visibility = View.VISIBLE
                } else {
                    tvCompare.visibility = View.GONE
                }
                tvStatus.text = "图片翻译完成"
                if (!expanded) {
                    expanded = true
                    root.findViewById<View>(R.id.resultPane).visibility = View.VISIBLE
                    root.findViewById<Button>(R.id.btnCollapse).text = "收起"
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "float_translator"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "悬浮翻译", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("悬浮翻译运行中")
                .setContentText("点悬浮窗的“翻译屏幕”翻译当前画面")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("悬浮翻译运行中")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }
        startForeground(1, notification)
    }

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.findViewById<View>(R.id.dragHandle).setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleSubtitle(button: Button) {
        subtitleOn = !subtitleOn
        if (subtitleOn) {
            if (subtitleRecognizer == null) {
                subtitleRecognizer = SubtitleRecognizer(
                    this,
                    onSubtitle = { original, translated ->
                        tvSubtitle.text = translated
                        tvOriginal.text = "听到：$original"
                    },
                    onStatus = { tvStatus.text = it }
                )
            }
            subtitleRecognizer?.start()
            button.text = "字幕：开"
            tvStatus.text = "字幕已开启，请对着手机说英文"
        } else {
            subtitleRecognizer?.stop()
            button.text = "字幕：关"
            tvStatus.text = "字幕已关闭"
        }
    }

    /** 截取当前屏幕一帧并 OCR + 翻译。 */
    private fun captureAndTranslate() {
        if (!ScreenCaptureHolder.hasProjection) {
            Toast.makeText(this, "请先在主界面点“授权录屏”，再回其他 App 点翻译屏幕", Toast.LENGTH_LONG).show()
            return
        }
        tvStatus.text = "正在截屏识别…"
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            mediaProjection = projectionManager.getMediaProjection(
                ScreenCaptureHolder.resultCode, ScreenCaptureHolder.data!!
            )
        } catch (e: Exception) {
            tvStatus.text = "录屏授权已失效，请回主界面重新点授权录屏"
            Toast.makeText(this, "录屏授权已失效，请回主界面重新点授权录屏", Toast.LENGTH_LONG).show()
            return
        }
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "float-translator", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
        // 给 VirtualDisplay 一帧渲染时间，再取图；取到后立即释放，避免一直占屏。
        root.postDelayed({
            try {
                val image = imageReader?.acquireLatestImage()
                if (image == null) {
                    tvStatus.text = "截屏失败，请再点一次翻译屏幕"
                    releaseCapture()
                    return@postDelayed
                }
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()
                releaseCapture()
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                ocrAndTranslate(cropped)
            } catch (e: Exception) {
                tvStatus.text = "截屏失败：${e.message}"
                releaseCapture()
            }
        }, 1200)
    }

    private fun releaseCapture() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        virtualDisplay = null
        imageReader = null
    }

    private fun ocrAndTranslate(bitmap: Bitmap) {
        tvStatus.text = "正在识别文字…"
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { visionText ->
                val text = visionText.text.trim()
                if (text.isEmpty()) {
                    tvStatus.text = "本屏没有识别到文字，试试选图翻译"
                    return@addOnSuccessListener
                }
                tvOriginal.text = "原文：$text"
                tvStatus.text = "正在翻译…"
                TranslatorEngine.translateAuto(
                    this,
                    text,
                    onResult = { translated, direction ->
                        tvTranslated.text = "译文：$translated"
                        tvStatus.text = if (direction == TranslatorEngine.Direction.EN_TO_ZH)
                            "英文 → 中文" else "中文 → 英文"
                        // 长英文文章：额外拉一次谷歌对照，显示在译文下方。
                        tvCompare.visibility = View.GONE
                        TranslatorEngine.fetchGoogleComparison(text) { comparison ->
                            if (comparison != translated) {
                                tvCompare.text = "谷歌对照：$comparison"
                                tvCompare.visibility = View.VISIBLE
                            }
                        }
                    },
                    onError = { tvStatus.text = "翻译失败：${it.message}，谷歌不通或本机模型未下载，请试无障碍读屏或填百度密钥" }
                )
            }
            .addOnFailureListener { tvStatus.text = "识别失败：${it.message}" }
    }

    override fun onDestroy() {
        subtitleRecognizer?.stop()
        releaseCapture()
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        runCatching { windowManager.removeView(root) }
        TranslatorEngine.close()
        super.onDestroy()
    }
}
