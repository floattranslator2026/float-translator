package com.floattranslator

import android.content.Context

/**
 * 百度翻译密钥只存在用户自己的手机上。
 *
 * 重要：不要把 APP ID / 密钥发给 Muse、不要贴到聊天里、不要提交到代码仓库。
 * 本文件只读写本机私有 SharedPreferences，密钥不会上传到任何服务器
 * （除百度翻译开放平台本身，调用时按百度文档只传签名后的请求）。
 */
object BaiduCredentials {
    private const val PREFS = "baidu_translate_prefs"
    private const val KEY_APP_ID = "baidu_app_id"
    private const val KEY_SECRET = "baidu_secret"

    fun save(context: Context, appId: String, secret: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_APP_ID, appId.trim())
            .putString(KEY_SECRET, secret.trim())
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun appId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_APP_ID, "").orEmpty()

    fun secret(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SECRET, "").orEmpty()

    fun hasCredentials(context: Context): Boolean =
        appId(context).isNotEmpty() && secret(context).isNotEmpty()
}
