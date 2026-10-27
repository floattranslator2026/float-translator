package com.floattranslator

import android.content.Intent

/**
 * MainActivity 拿到 MediaProjection 授权后，把 resultCode / data 暂存于此，
 * 供 FloatingService 创建 MediaProjection。进程内存对象，不落盘、不上传。
 */
object ScreenCaptureHolder {
    var resultCode: Int = 0
    var data: Intent? = null
    val hasProjection: Boolean get() = data != null
}
