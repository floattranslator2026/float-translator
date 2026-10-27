# 悬浮窗屏幕翻译（Float Translator）

手机原生 Android 应用：悬浮窗 + 屏幕翻译 + 语音字幕，中英互译。

## 功能

1. **悬浮窗**：在任何 App 上方显示，可拖动、可收起，含「翻译屏幕 / 字幕 / 收起 / 关闭」按钮。
2. **屏幕翻译**：MediaProjection 截取当前屏幕一帧 → ML Kit 本机 OCR 识别文字 → ML Kit 本机翻译（英文→中文，中文→英文，含汉字自动判断方向），结果大字显示在悬浮窗。
3. **选图翻译**：主界面点「选图翻译」，选一张截图或文章图片，同样 OCR + 翻译，结果发到悬浮窗。
4. **语音字幕**：悬浮窗点「字幕：关」切到「开」，持续听英文演讲，逐句翻译成中文字幕（大号黄字）显示在悬浮窗；中文演讲同理可出英文字幕（代码中 `SubtitleRecognizer.SpeechMode` 可切换，默认英→中）。
5. **翻译服务：先用谷歌 + 本机离线兜底，百度以后再加**：OCR 仍为 ML Kit 本机。翻译源在设置页切换（百度 / 谷歌），**默认谷歌**（百度注册目前被百度风控拦截，等风控过去拿到密钥后再切百度）。
   - 百度：翻译优先走百度翻译开放平台（`fanyi-api.baidu.com`，英↔中），App 在本地按百度文档做 MD5 签名：`sign = MD5(appid + q + salt + 密钥)`。
   - 谷歌：百度返回失败、额度用完，或用户在设置页手动切到谷歌时，用谷歌。谷歌用免密钥公开端点 `translate.googleapis.com/translate_a/single?client=gtx`，**不硬编码任何谷歌 API Key**。
   - 链路：百度（已填密钥时）→ 谷歌 → 本机 ML Kit；手动切谷歌时：谷歌 → 百度（已填密钥时）→ 本机 ML Kit；不会无限循环。
   - 长英文文章（200 字以上）翻译时，还会额外显示一次谷歌对照结果，方便对照主译文。
   - **谷歌在国内可能需要网络条件**：谷歌服务在国内可能无法直连或很慢，本 App 不做代理配置；谷歌失败时自动回落，设置页里也明确写了这一点。
   - **Muse 无法代签百度 API，也不要把密钥交给 Muse**：密钥绝不要发到聊天里、不要写进代码。
   - 在 App 主界面点「百度翻译设置」，在手机上自己填写百度翻译开放平台的 APP ID 和密钥，保存后只存在这台手机的私有 SharedPreferences 里。
   - **没填密钥时**：App 会明确提示，并自动用本机 ML Kit 离线翻译兜底（首次需联网下载一次模型，之后可离线）；百度调用失败时也会自动回落到本机翻译。
   - 语音识别用系统自带的语音识别服务（一般为 Google 语音服务）。

## 权限（为什么要）

| 权限 | 用途 | 授予方式 |
|---|---|---|
| 悬浮窗 SYSTEM_ALERT_WINDOW | 在其他 App 上方显示悬浮窗 | App 内点「1. 授权悬浮窗」，跳到系统设置手动开 |
| 录屏 MediaProjection | 截取当前屏幕做 OCR | App 内点「2. 授权录屏」，系统弹窗点「开始」确认一次 |
| 麦克风 RECORD_AUDIO | 语音字幕听演讲 | App 内点「3. 授权麦克风」，弹窗允许 |
| 联网 INTERNET | 首次下载 ML Kit 翻译模型 | 安装时自动授予 |

> 注意：Android 的录屏授权出于安全原因不能永久保存，每次重启 App / 重新启动悬浮窗后，如需屏幕翻译请再点一次「授权录屏」。

## 在 Android Studio 里编译

1. 用 Android Studio 打开本目录 `float-translator/`（不是只打开 app 文件夹）。
2. 等 Gradle 同步完成（首次会下载 Gradle 8.2 与 Android Gradle Plugin 8.2.2，需联网）。
3. 手机开「开发者选项 → USB 调试」，用数据线连电脑，点 Run ▶，或点 Build → Build App Bundle(s) / APK(s) → Build APK(s)。
4. 生成的调试包位置：`app/build/outputs/apk/debug/app-debug.apk`，把它传到手机安装即可。

环境要求：Android Studio Hedgehog 或更新版，JDK 17，compileSdk 34，minSdk 26（Android 8.0 及以上）。

## 在 OnePlus 手机上使用步骤

1. 安装 APK 后打开 App，按 1、2、3 顺序授权。
   - 一加路径参考：设置 → 应用 → 特殊应用权限 → 在其他应用的上层显示 → 找到「悬浮翻译」→ 允许；录屏与麦克风按 App 内弹窗操作即可。
   - 如悬浮窗被一加电池优化杀掉：设置 → 电池 → 应用电池管理 → 悬浮翻译 → 允许后台运行 / 不优化。
2. 点「启动悬浮窗」。
3. 切换到要翻译的 App（英文文章、视频、演讲画面等），点悬浮窗「翻译屏幕」，等 1～2 秒，原文与译文出现在悬浮窗。
4. 听演讲时点悬浮窗「字幕：关」→「字幕：开」，中文字幕会逐句出现在悬浮窗顶部大字区。
5. 不用时点悬浮窗「×」关闭，或回主界面点「关闭悬浮窗」。

## 常见问题

- **点翻译屏幕提示未授权**：先回主界面点「2. 授权录屏」，再去其他 App 翻译。
- **翻译失败、提示下载模型**：首次使用请保持联网，等模型下载完成后再试；之后可离线。
- **字幕没反应**：确认已授权麦克风，且手机装有 Google 语音服务；周围声音太小或网络语音识别不可用时也会无结果。
- **悬浮窗拖不动**：按住悬浮窗顶部「⋯ 悬浮翻译（按住此处拖动）⋯」那一行拖动。
- **本机没有 APK**：本仓库默认只提供源码；按上面步骤在 Android Studio 里编译即可得到 `app-debug.apk`。

## 目录结构

```
float-translator/
  settings.gradle.kts / build.gradle.kts / gradle.properties
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/floattranslator/
    MainActivity.kt        主界面：授权、启动悬浮窗、选图翻译、进百度设置页
    SettingsActivity.kt    百度翻译设置页：本机填写/保存/清除 APP ID 与密钥
    BaiduCredentials.kt    密钥本机存取（私有 SharedPreferences，不上传、不进代码）
    BaiduTranslator.kt     百度翻译调用：本地 MD5 签名，fanyi-api.baidu.com
    GoogleTranslator.kt    谷歌翻译辅源：免密钥 gtx 端点，不硬编码密钥
    TranslationSourcePrefs.kt 翻译源切换：百度 / 谷歌，默认百度，只存选择
    FloatingService.kt     悬浮窗服务：拖动、截屏 OCR、翻译显示、长文章谷歌对照
    TranslatorEngine.kt    翻译调度：百度→谷歌→本机 ML Kit，手动切谷歌时反向回落，自动判断中英方向
    SubtitleRecognizer.kt  连续语音识别 + 逐句翻译字幕
    ScreenCaptureHolder.kt 录屏授权结果暂存（内存，不落盘）
  app/src/main/res/layout/ activity_main.xml / overlay_floating.xml
  README.md
```
