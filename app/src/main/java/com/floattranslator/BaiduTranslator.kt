package com.floattranslator

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 百度翻译开放平台通用翻译 API（fanyi-api.baidu.com），按百度文档做 MD5 签名。
 *
 * 签名算法（百度文档）：sign = MD5(appid + q + salt + 密钥)
 * 其中 salt 为随机数，q 为待翻译文本（UTF-8，签名时用原文，不做 URL 编码；
 * 实际请求参数中的 q 需要 URL 编码）。
 * 请求：GET https://fanyi-api.baidu.com/api/trans/vip/translate
 *   ?q=...&from=en&to=zh&appid=...&salt=...&sign=...
 * 响应示例：{"trans_result":[{"src":"hello","dst":"你好"}]}
 * 错误时返回 error_code / error_msg，见百度文档错误码表。
 *
 * 本调用只在用户已在设置页自己填好 APP ID / 密钥后才发起；密钥只读本机
 * SharedPreferences，不经过 Muse、不写进代码。
 */
object BaiduTranslator {

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun parseDst(json: String): String? {
        // 只取第一个 trans_result.dst，不引入 JSON 库，保持依赖最小。
        val dstKey = "\"dst\":\""
        val start = json.indexOf(dstKey)
        if (start < 0) return null
        val valueStart = start + dstKey.length
        // dst 内可能含转义字符，这里做最常见的 \" \\ \n 还原。
        val sb = StringBuilder()
        var i = valueStart
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                when (json[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 < json.length) {
                            val code = json.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) sb.append(code.toChar())
                            i += 4
                        }
                    }
                    else -> sb.append(json[i + 1])
                }
                i += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun parseError(json: String): String? {
        val key = "\"error_code\":\""
        val start = json.indexOf(key)
        if (start < 0) return null
        val end = json.indexOf('"', start + key.length)
        if (end < 0) return null
        return json.substring(start + key.length, end)
    }

    /**
     * 在后台线程调用百度翻译，主线程回调。
     * 成功 -> onResult(译文)；失败 -> onError(原因)，调用方应回落到本机 ML Kit 翻译。
     */
    fun translate(
        context: Context,
        text: String,
        direction: TranslatorEngine.Direction,
        onResult: (String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val appId = BaiduCredentials.appId(context)
        val secret = BaiduCredentials.secret(context)
        if (appId.isEmpty() || secret.isEmpty()) {
            mainHandler.post { onError(IllegalStateException("未填写百度 APP ID / 密钥")) }
            return
        }
        val (from, to) = when (direction) {
            TranslatorEngine.Direction.EN_TO_ZH -> "en" to "zh"
            TranslatorEngine.Direction.ZH_TO_EN -> "zh" to "en"
        }
        val salt = Random.nextInt(10000, 99999).toString()
        val sign = md5(appId + text + salt + secret)
        Thread {
            try {
                val query = "q=" + URLEncoder.encode(text, "UTF-8") +
                    "&from=" + from + "&to=" + to +
                    "&appid=" + URLEncoder.encode(appId, "UTF-8") +
                    "&salt=" + salt + "&sign=" + sign
                val url = URL("https://fanyi-api.baidu.com/api/trans/vip/translate?$query")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                }
                val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                val error = parseError(body)
                if (error != null) {
                    throw IllegalStateException("百度翻译错误码 $error，请检查 APP ID / 密钥与额度")
                }
                val dst = parseDst(body)
                    ?: throw IllegalStateException("百度翻译返回无法解析")
                mainHandler.post { onResult(dst) }
            } catch (e: Exception) {
                mainHandler.post { onError(e) }
            }
        }.start()
    }
}
