package com.studyontv.tv

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.speech.tts.TextToSpeech
import android.webkit.JavascriptInterface
import org.json.JSONObject
import java.util.Locale

open class DeviceBridge(protected val activity: Activity, private val connectionAllowed: Boolean = false, private val web: android.webkit.WebView? = null) {
    @JavascriptInterface
    fun answerInput(voice: Boolean) {
        activity.runOnUiThread { web?.let { InputSupport.open(activity, it, voice) } }
    }

    @JavascriptInterface
    fun answerText(draft: String, prompt: String) {
        activity.runOnUiThread { web?.let { InputSupport.openText(activity, it, draft, prompt) } }
    }

    /**
     * 页面内的接口调用统一走原生层（进程内直接执行路由，不经过 TCP）。
     * 这样设备令牌永远不需要交给 JS，即使页面将来出现 XSS 也拿不到能直接提交答案的凭据。
     */
    @JavascriptInterface
    fun api(method: String, path: String, body: String): String = DeviceRuntime.local(method, path, body)

    /** 连接页（家长已验证）里的按钮：权限设置 / 修改密码 */
    @JavascriptInterface
    fun openPermissions() {
        if (!connectionAllowed) return
        activity.runOnUiThread { activity.startActivity(Intent(activity, OnboardingActivity::class.java)) }
    }

    @JavascriptInterface
    fun changePassword() {
        if (!connectionAllowed) return
        activity.runOnUiThread { (activity as? ParentActivity)?.changePassword() }
    }

    @JavascriptInterface
    fun deviceInfo(): String = JSONObject().put("platform", if (isTv(activity)) "android-tv" else "android")
        .put("model", Build.MODEL).put("version", BuildConfig.VERSION_NAME).put("night", TvUi.systemNight(activity)).toString()

    @JavascriptInterface
    fun lanInfo(): String = if (connectionAllowed) DeviceRuntime.connection(activity).toString() else ""

    @JavascriptInterface
    fun openParent() {
        activity.runOnUiThread {
            activity.startActivity(Intent(activity, ParentActivity::class.java))
        }
    }

    @JavascriptInterface
    fun exitToLauncher() {
        val status = DeviceRuntime.learning.status()
        if (status.getString("reason").isNotEmpty() || (status.optLong("remainingSeconds") <= 0 && !status.optBoolean("free"))) return
        activity.runOnUiThread {
            GateService.hideGates()
            // 个别电视（如小米）把 HOME 指向带私有权限的桌面，直接 startActivity 会抛 SecurityException。
            // 失败时退回隐藏本任务，绝不能因为回桌面把主进程带崩。
            val openedHome = try {
                activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: Exception) {
                Diag.error("UI", "HOME intent rejected, fallback to moveTaskToBack", e)
                false
            }
            if (!openedHome) activity.moveTaskToBack(true)
            if (activity is GateActivity) activity.finish()
        }
    }

    @JavascriptInterface
    fun backToQuiz() {
        activity.runOnUiThread {
            activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            if (activity is ParentActivity) activity.finish()
        }
    }

    @JavascriptInterface
    fun updateInfo(): String {
        val info = Updater.newerThanCurrent(activity, Updater.cachedInfo(activity)) ?: return ""
        return Updater.toJson(info).put("current", Updater.currentVersion(activity)).toString()
    }

    @JavascriptInterface
    fun installUpdate(url: String, latest: String, notes: String) {
        val cached = Updater.newerThanCurrent(activity, Updater.cachedInfo(activity))
        val payload = if (cached != null && cached.url == url) Updater.toJson(cached) else JSONObject()
            .put("latest", latest).put("notes", notes).put("url", url)
        payload.put("current", BuildConfig.VERSION_NAME)
        activity.runOnUiThread {
            activity.startActivity(Intent(activity, UpdateActivity::class.java).putExtra("payload", payload.toString()))
        }
    }

    private var tts: TextToSpeech? = null

    @JavascriptInterface
    fun readAloud(text: String) {
        if (text.isBlank()) return
        activity.runOnUiThread {
            if (tts == null) {
                tts = TextToSpeech(activity) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.language = Locale.CHINESE
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sotv_read")
                    }
                }
            } else {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sotv_read")
            }
        }
    }

    @JavascriptInterface
    fun stopReading() {
        tts?.stop()
    }

    companion object {
        fun isTv(context: Context): Boolean = BuildConfig.FLAVOR == "tv"
    }
}
