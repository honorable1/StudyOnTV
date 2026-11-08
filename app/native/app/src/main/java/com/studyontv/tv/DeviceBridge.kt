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

    /** 网页在 HTTP 局域网环境下拿不到 Clipboard API，交给原生写剪贴板最稳 */
    @JavascriptInterface
    fun copyText(value: String): Boolean {
        if (!connectionAllowed) return false
        return try {
            val manager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return false
            manager.setPrimaryClip(android.content.ClipData.newPlainText("题了个题", value))
            true
        } catch (e: Exception) {
            Diag.warn("UI", "copyText failed: ${e.message}")
            false
        }
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
            // 答题结束回到「上一个任务」，绝不能主动跳桌面，否则正在播放的视频会被顶掉（#54 / #63）。
            // GateActivity 是独立任务（singleInstance + 独立 taskAffinity），finish 之后系统会自动
            // 把下面的任务（云视听 / 腾讯视频）带回前台；主界面则直接把整个任务压到后台。
            if (activity is GateActivity) activity.finish()
            else activity.moveTaskToBack(true)
        }
    }

    /**
     * 答题解锁后回到解题前的画面。
     *
     * 先收起管控页并把自己的任务退到后台，正常情况下之前那个应用就会回到前台；
     * 如果几百毫秒后前台仍是我们自己或桌面（部分机型会把答题页当成独立任务，
     * finish 之后露出的是桌面），再显式把记下的应用拉起来。
     */
    @JavascriptInterface
    fun resumePrevious() {
        // 不检查 connectionAllowed：和学习页 / GateActivity 共用同一个桥，
        // 管控页里 connectionAllowed 是 false，检查了会导致解锁后无法还原画面。
        // 这个调用只会收起管控页并恢复自己记下的受控应用，不暴露任何设备信息。
        activity.runOnUiThread {
            GateService.hideGates()
            val target = GateService.rememberedActive()
            if (activity is GateActivity) activity.finish() else activity.moveTaskToBack(true)
            if (target == null || target == activity.packageName || Foreground.isLauncher(target)) return@runOnUiThread
            val decor = activity.window?.decorView ?: return@runOnUiThread
            decor.postDelayed({
                val now = try { Foreground.current(activity) } catch (_: Exception) { null }
                if (now == null || now == activity.packageName || Foreground.isLauncher(now)) launchPackage(target)
            }, 700)
        }
    }

    private fun launchPackage(pkg: String): Boolean = launchPackage(activity, pkg)

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
        /** 把记下的受控应用拉回前台；失败只记日志，不影响管控 */
        fun launchPackage(context: android.content.Context, pkg: String): Boolean = try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) false else {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                context.startActivity(intent)
                Diag.info("UI", "resumed previous app $pkg")
                true
            }
        } catch (e: Exception) {
            Diag.warn("UI", "resume $pkg failed: ${e.message}")
            false
        }

        fun isTv(context: Context): Boolean = BuildConfig.FLAVOR == "tv"
    }
}
