package com.floattranslator

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * 本机翻译引擎：ML Kit Translate，无 API Key。
 * 方向判断：含汉字 -> 中文译英文；否则 -> 英文译中文。
 * 模型按需下载一次（需要联网），下载后可离线使用。
 */
object TranslatorEngine {

    enum class Direction { EN_TO_ZH, ZH_TO_EN }

    fun detectDirection(text: String): Direction {
        val hasChinese = text.any { it in '\u4e00'..'\u9fff' }
        return if (hasChinese) Direction.ZH_TO_EN else Direction.EN_TO_ZH
    }

    private val translators = mutableMapOf<Direction, Translator>()

    private fun translatorFor(direction: Direction): Translator {
        return translators.getOrPut(direction) {
            val options = when (direction) {
                Direction.EN_TO_ZH -> TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.CHINESE)
                    .build()
                Direction.ZH_TO_EN -> TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.CHINESE)
                    .setTargetLanguage(TranslateLanguage.ENGLISH)
                    .build()
            }
            Translation.getClient(options)
        }
    }

    /**
     * 自动判断方向并翻译。onResult 在主线程回调。
     * 翻译源：百度为主、谷歌为辅，默认百度（设置页可切）。
     * 链路：百度（已填密钥时） -> 谷歌 -> 本机 ML Kit 离线翻译。
     * 手动切到谷歌时：谷歌 -> 百度（已填密钥时） -> 本机 ML Kit。
     * 百度返回失败 / 额度用完 / 未填密钥时都会自动往下一级回落，不会卡住。
     */
    fun translateAuto(
        context: Context,
        text: String,
        onResult: (translated: String, direction: Direction) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            onResult("", detectDirection(clean))
            return
        }
        val direction = detectDirection(clean)
        val mlkit: () -> Unit = { translateAuto(clean, onResult, onError) }
        val google: () -> Unit = {
            GoogleTranslator.translate(
                clean, direction,
                onResult = { onResult(it, direction) },
                onError = { mlkit() }
            )
        }
        val baidu: () -> Unit = {
            if (BaiduCredentials.hasCredentials(context)) {
                BaiduTranslator.translate(
                    context, clean, direction,
                    onResult = { onResult(it, direction) },
                    onError = { google() }
                )
            } else {
                google()
            }
        }
        when (TranslationSourcePrefs.get(context)) {
            TranslationSourcePrefs.Source.GOOGLE -> {
                GoogleTranslator.translate(
                    clean, direction,
                    onResult = { onResult(it, direction) },
                    onError = {
                        // 手动切谷歌时回落：有百度密钥才试百度，否则直接本机，
                        // 避免无密钥时百度->谷歌->百度无限循环。
                        if (BaiduCredentials.hasCredentials(context)) {
                            BaiduTranslator.translate(
                                context, clean, direction,
                                onResult = { onResult(it, direction) },
                                onError = { mlkit() }
                            )
                        } else {
                            mlkit()
                        }
                    }
                )
            }
            TranslationSourcePrefs.Source.BAIDU -> baidu()
        }
    }

    /**
     * 长英文文章的谷歌对照：仅当原文是英文且较长（>200 字）时调用。
     * 主译文走上面 translateAuto 的链路，对照结果单独回调，不影响主译文。
     * 谷歌不可用时静默无对照，不报错、不阻塞主结果。
     */
    fun fetchGoogleComparison(
        text: String,
        onComparison: (String) -> Unit
    ) {
        val clean = text.trim()
        if (clean.length <= 200) return
        if (detectDirection(clean) != Direction.EN_TO_ZH) return
        GoogleTranslator.translate(
            clean, Direction.EN_TO_ZH,
            onResult = { onComparison(it) },
            onError = { /* 静默：无对照 */ }
        )
    }

    /** 自动判断方向并翻译（仅本机 ML Kit，不走百度）。onResult 在主线程回调。 */
    fun translateAuto(
        text: String,
        onResult: (translated: String, direction: Direction) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            onResult("", detectDirection(clean))
            return
        }
        val direction = detectDirection(clean)
        val translator = translatorFor(direction)
        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator.translate(clean)
                    .addOnSuccessListener { onResult(it, direction) }
                    .addOnFailureListener { onError(it) }
            }
            .addOnFailureListener { onError(it) }
    }

    fun close() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }
}
