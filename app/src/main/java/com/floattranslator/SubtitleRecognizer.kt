package com.floattranslator

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * 连续语音识别 -> 逐句翻译 -> 字幕回调。
 * 英语演讲 -> 中文字幕；中文演讲 -> 英文字幕，由调用方选语言。
 * 注意：SpeechRecognizer 本身依赖系统语音识别服务（通常是 Google 语音服务），
 * OCR 与翻译是本机 ML Kit；整条字幕链路不需要自带 API Key。
 */
class SubtitleRecognizer(
    private val context: Context,
    private val onSubtitle: (original: String, translated: String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    enum class SpeechMode { EN_TO_ZH, ZH_TO_EN }

    var mode: SpeechMode = SpeechMode.EN_TO_ZH
        set(value) {
            field = value
            if (running) restart()
        }

    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var lastFinal = ""

    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus("本机无语音识别服务，请确认已安装 Google 语音服务")
            return
        }
        running = true
        startListening()
    }

    fun stop() {
        running = false
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun restart() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        if (running) startListening()
    }

    private fun startListening() {
        val locale = when (mode) {
            SpeechMode.EN_TO_ZH -> Locale.US
            SpeechMode.ZH_TO_EN -> Locale.SIMPLIFIED_CHINESE
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onStatus(if (mode == SpeechMode.EN_TO_ZH) "正在听英文…" else "正在听中文…")
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                // 连续识别的标准做法：出错后自动重开，直到用户手动停止。
                if (running) restart()
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty().trim()
                if (text.isNotEmpty() && text != lastFinal) {
                    lastFinal = text
                    TranslatorEngine.translateAuto(
                        context,
                        text,
                        onResult = { translated, _ -> onSubtitle(text, translated) },
                        onError = { onSubtitle(text, "翻译失败：${it.message}") }
                    )
                }
                if (running) restart()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                // 只把最终句做字幕，避免中间结果闪烁；如需实时逐字字幕，
                // 可在此把 partial 文本直接回调为字幕（不翻译或先显示原文）。
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(intent)
    }
}
