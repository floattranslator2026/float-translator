package com.floattranslator

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 百度翻译设置页。
 *
 * 用户在这里自己填写百度翻译开放平台的 APP ID 和密钥。
 * Muse 无法代签百度 API，密钥绝不要发给 Muse、不要贴到聊天里、不要写进代码。
 * 填写后只存在本机私有 SharedPreferences，App 调用百度时才在本地做 MD5 签名。
 * 没填密钥时，App 自动用本机 ML Kit 离线翻译兜底，并在这里明确提示。
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etAppId = findViewById<EditText>(R.id.etAppId)
        val etSecret = findViewById<EditText>(R.id.etSecret)
        val tvHint = findViewById<TextView>(R.id.tvHint)

        etSecret.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        // 翻译源切换：百度为主、谷歌为辅，默认百度。
        val rgSource = findViewById<android.widget.RadioGroup>(R.id.rgSource)
        rgSource.check(
            if (TranslationSourcePrefs.get(this) == TranslationSourcePrefs.Source.GOOGLE)
                R.id.rbGoogle else R.id.rbBaidu
        )
        rgSource.setOnCheckedChangeListener { _, checkedId ->
            TranslationSourcePrefs.set(
                this,
                if (checkedId == R.id.rbGoogle)
                    TranslationSourcePrefs.Source.GOOGLE
                else
                    TranslationSourcePrefs.Source.BAIDU
            )
            Toast.makeText(
                this,
                if (checkedId == R.id.rbGoogle) "已切到谷歌（辅源）" else "已切到百度（主源）",
                Toast.LENGTH_SHORT
            ).show()
        }

        fun refreshHint() {
            tvHint.text = if (BaiduCredentials.hasCredentials(this)) {
                "已填写百度翻译密钥（仅存本机），翻译将优先走百度；百度失败时自动回落本机离线翻译。"
            } else {
                "未填写百度翻译密钥：当前用本机 ML Kit 离线翻译兜底。" +
                    "要走百度，请在百度翻译开放平台申请 APP ID 和密钥后，在此填写（不要发给任何人）。"
            }
            etAppId.setText(BaiduCredentials.appId(this))
            // 密钥不回显原文：已填时只显示占位，避免旁人窥屏；重新填写会覆盖。
            if (BaiduCredentials.hasCredentials(this)) {
                etSecret.setText("")
                etSecret.hint = "已保存（重新输入可覆盖）"
            }
        }
        refreshHint()

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val appId = etAppId.text.toString().trim()
            val secret = etSecret.text.toString().trim()
            if (appId.isEmpty()) {
                Toast.makeText(this, "请填写 APP ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (secret.isEmpty() && !BaiduCredentials.hasCredentials(this)) {
                Toast.makeText(this, "请填写密钥", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // 已有密钥且本次密钥留空 = 只改 APP ID，保留旧密钥。
            val finalSecret = if (secret.isNotEmpty()) secret else BaiduCredentials.secret(this)
            BaiduCredentials.save(this, appId, finalSecret)
            Toast.makeText(this, "已保存到本机", Toast.LENGTH_SHORT).show()
            refreshHint()
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            BaiduCredentials.clear(this)
            etAppId.setText("")
            etSecret.setText("")
            etSecret.hint = "密钥"
            Toast.makeText(this, "已清除本机保存的密钥", Toast.LENGTH_SHORT).show()
            refreshHint()
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
    }
}
