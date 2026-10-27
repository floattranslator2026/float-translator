package com.floattranslator

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.Translation
import java.io.InputStream

/**
 * 主界面：三步开通。
 * 1. 授权悬浮窗（系统设置里手动开）
 * 2. 授权录屏（系统弹窗确认一次，授权结果存到 ScreenCaptureHolder）
 * 3. 授权麦克风（语音字幕用）
 * 另有：启动/关闭悬浮窗、选图翻译（截图、文章图片均可）。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureHolder.resultCode = result.resultCode
            ScreenCaptureHolder.data = result.data
            tvStatus.text = "录屏已授权：现在可以去其他 App，点悬浮窗“翻译屏幕”"
        } else {
            tvStatus.text = "录屏未授权：屏幕翻译不可用，仍可用选图翻译"
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        translatePickedImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tvStatus)

        findViewById<Button>(R.id.btnOverlayPermission).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "悬浮窗权限已授予", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnProjectionPermission).setOnClickListener {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        }
        findViewById<Button>(R.id.btnAudioPermission).setOnClickListener {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1001
            )
        }
        findViewById<Button>(R.id.btnAccessibilityPermission).setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(TranslateLanguage.CHINESE).build()).downloadModelIfNeeded()
        .addOnSuccessListener {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(TranslateLanguage.CHINESE).setTargetLanguage(TranslateLanguage.ENGLISH).build()).downloadModelIfNeeded()
            .addOnSuccessListener { tvStatus.text = "本机翻译模型已就绪" } .addOnFailureListener { tvStatus.text = "本机模型下载失败：国内谷歌服务可能不可用，请用无障碍读屏或填百度密钥" }
        }.addOnFailureListener { tvStatus.text = "本机模型下载失败：国内谷歌服务可能不可用，请用无障碍读屏或填百度密钥" }
        findViewById<Button>(R.id.btnStartFloat).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授权悬浮窗", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startService(Intent(this, FloatingService::class.java))
            tvStatus.text = "悬浮窗已启动：去其他 App，拖动悬浮窗，点“翻译屏幕”"
        }
        findViewById<Button>(R.id.btnStopFloat).setOnClickListener {
            val intent = Intent(this, FloatingService::class.java).apply {
                action = FloatingService.ACTION_STOP
            }
            startService(intent)
            stopService(Intent(this, FloatingService::class.java))
        }
        findViewById<Button>(R.id.btnPickImage).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnSubtitleMode).setOnClickListener {
            tvStatus.text = "字幕模式：在悬浮窗点“字幕：关”切到“开”，默认听英文出中文字幕"
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
        }
    }

    private fun translatePickedImage(uri: Uri) {
        tvStatus.text = "正在识别图片文字…"
        val input: InputStream? = contentResolver.openInputStream(uri)
        val bitmap = input?.use { BitmapFactory.decodeStream(it) }
        if (bitmap == null) {
            tvStatus.text = "图片读取失败"
            return
        }
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { visionText ->
                val text = visionText.text.trim()
                if (text.isEmpty()) {
                    tvStatus.text = "图片里没有识别到文字"
                    return@addOnSuccessListener
                }
                TranslatorEngine.translateAuto(
                    this,
                    text,
                    onResult = { translated, _ ->
                        tvStatus.text = "识别并翻译完成，已发到悬浮窗"
                        // 长英文文章：谷歌对照也一起发到悬浮窗（无对照时不带该字段）。
                        TranslatorEngine.fetchGoogleComparison(text) { comparison ->
                            if (comparison == translated) return@fetchGoogleComparison
                            val intent = Intent(this, FloatingService::class.java).apply {
                                action = FloatingService.ACTION_SHOW_RESULT
                                putExtra(FloatingService.EXTRA_ORIGINAL, text)
                                putExtra(FloatingService.EXTRA_TRANSLATED, translated)
                                putExtra(FloatingService.EXTRA_COMPARISON, comparison)
                            }
                            startService(intent)
                        }
                        val intent = Intent(this, FloatingService::class.java).apply {
                            action = FloatingService.ACTION_SHOW_RESULT
                            putExtra(FloatingService.EXTRA_ORIGINAL, text)
                            putExtra(FloatingService.EXTRA_TRANSLATED, translated)
                        }
                        startService(intent)
                    },
                    onError = { tvStatus.text = "翻译失败：${it.message}（首次需联网下载模型）" }
                )
            }
            .addOnFailureListener { tvStatus.text = "识别失败：${it.message}" }
    }
}
