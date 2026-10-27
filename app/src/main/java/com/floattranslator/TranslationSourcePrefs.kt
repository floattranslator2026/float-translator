package com.floattranslator

import android.content.Context

/**
 * 翻译源切换：先用谷歌，百度风控通过、拿到密钥后再切百度。
 * 默认谷歌，只存用户在设置页的选择（"baidu" / "google"），不存任何密钥。
 */
object TranslationSourcePrefs {
    private const val PREFS = "translation_source_prefs"
    private const val KEY_SOURCE = "source"

    enum class Source { BAIDU, GOOGLE }

    fun get(context: Context): Source {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE, "google").orEmpty()
        return if (raw == "baidu") Source.BAIDU else Source.GOOGLE
    }

    fun set(context: Context, source: Source) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SOURCE, if (source == Source.GOOGLE) "google" else "baidu")
            .apply()
    }
}
