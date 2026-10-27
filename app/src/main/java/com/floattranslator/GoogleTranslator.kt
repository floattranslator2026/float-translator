package com.floattranslator

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray

/**
 * 谷歌翻译（辅源，无需密钥、不硬编码任何密钥）。
 *
 * 用的是公开的免密钥 gtx 端点：
 *   https://translate.googleapis.com/translate_a/single
 *   ?client=gtx&sl=en&tl=zh-CN&dt=t&q=...
 * 这不是谷歌云 Translation API（后者需要 API Key，不在这里用）。
 *
 * 国内网络条件说明：谷歌服务在国内可能无法直连或很慢，本 App 不做任何
 * 代理配置；调用失败时调用方会自动回落到百度（若已填密钥）或本机 ML Kit
 * 离线翻译，并在设置页里明确写了这一点。不要把任何谷歌 API Key 写进代码。
 */
object GoogleTranslator {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun translate(
        text: String,
        direction: TranslatorEngine.Direction,
        onResult: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val (sl, tl) = when (direction) {
            TranslatorEngine.Direction.EN_TO_ZH -> "en" to "zh-CN"
            TranslatorEngine.Direction.ZH_TO_EN -> "zh-CN" to "en"
        }
        Thread {
            try {
                val query = "client=gtx&sl=" + sl + "&tl=" + tl + "&dt=t" +
                    "&q=" + URLEncoder.encode(text, "UTF-8")
                val url = URL("https://translate.googleapis.com/translate_a/single?$query")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 15000
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36"
                    )
                }
                val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val translated = parseGtx(body)
                    ?: throw IllegalStateException("谷歌翻译返回无法解析")
                mainHandler.post { onResult(translated) }
            } catch (e: Exception) {
                mainHandler.post { onError(e) }
            }
        }.start()
    }

    private fun parseGtx(json: String): String? {
        return try {
            val root = JSONArray(json)
            val sentences = root.getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val seg = sentences.optJSONArray(i) ?: continue
                if (seg.length() > 0) sb.append(seg.optString(0))
            }
            val out = sb.toString().trim()
            if (out.isEmpty()) null else out
        } catch (e: Exception) {
            null
        }
    }
}
