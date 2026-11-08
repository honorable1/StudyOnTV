package com.studyontv.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView

class GateOverlay(
    private val context: Context,
    private val windowType: Int,
    private val goHome: (() -> Boolean)? = null
) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var web: WebView? = null
    private var attached = false
    private var currentReason = ""
    private val remote = RemoteKeys { Bridge().openParent() }
    val isShowing: Boolean get() = attached
    var lastError: String = ""
        private set

    fun show(reason: String): Boolean {
        if (attached) return true
        handler.removeCallbacks(release); releaseScheduled = false
        val reused = web != null
        val page = if (reason == "timeup") "quiz" else "lock?reason=$reason"
        val url = Prefs.localBase(context) + "#/$page"
        val view = web ?: WebShell(context).create("about:blank").also { created ->
            created.addJavascriptInterface(Bridge(), "SOTVNative")
            created.setOnKeyListener { _, _, event -> remote.handle(created, event) }
            // 老电视（斐讯 T1 等）在 WebView 还没 attach 时就开始加载，挂到悬浮层后容易一直是黑屏。
            // 先创建空的 WebView，addView 成功后再 loadUrl；必要时用软件层兜底。
            if (Build.VERSION.SDK_INT < 26) created.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            web = created
        }
        val needLoad = !reused || reason != currentReason
        currentReason = reason
        val params = WindowManager.LayoutParams(-1, -1, windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE)
        params.gravity = Gravity.TOP or Gravity.START
        params.title = "StudyOnTV gate"
        return try {
            view.visibility = View.VISIBLE
            manager.addView(view, params)
            attached = true
            lastError = ""
            if (needLoad) {
                view.loadUrl(url)
                view.postDelayed({ try { view.invalidate(); view.requestLayout() } catch (_: Exception) { } }, 350)
            } else view.evaluateJavascript("window.SOTV_RESUME&&SOTV_RESUME()", null)
            view.requestFocus()
            MediaPause.pause(context)
            true
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            destroy()
            false
        }
    }

    fun suspend() {
        val view = web ?: return
        if (attached) try { manager.removeViewImmediate(view) } catch (_: Exception) { }
        if (attached && !InputSupport.active) MediaPause.release(context)
        attached = false
    }

    private val release = Runnable { destroy() }

    /**
     * 收起悬浮学习页。WebView 先保留 60 秒再销毁：孩子刚解锁又很快到时、或者家长调整规则时，
     * 再次弹出不必重新加载整页（以前每次都黑屏重载 1–2 秒）。
     */
    private var releaseScheduled = false

    fun hide() {
        if (web == null) return
        suspend()
        // hideAll() 每秒都会调用，只在第一次收起时安排销毁，否则计时会被不断推迟
        if (!releaseScheduled) { releaseScheduled = true; handler.postDelayed(release, 60000) }
    }

    fun destroy() {
        handler.removeCallbacks(release); releaseScheduled = false
        val view = web ?: return
        suspend()
        MediaPause.release(context)
        web = null
        currentReason = ""
        view.destroy()
    }

    private inner class Bridge {
        @JavascriptInterface fun api(method: String, path: String, body: String): String = DeviceRuntime.local(method, path, body)
        @JavascriptInterface fun deviceInfo(): String = org.json.JSONObject()
            .put("platform", if (DeviceBridge.isTv(context)) "android-tv" else "android").put("night", TvUi.systemNight(context)).toString()
        @JavascriptInterface fun answerInput(voice: Boolean) {
            handler.post {
                web?.let { InputSupport.open(context, it, voice) }
                if (InputSupport.active) suspend()
            }
        }
        @JavascriptInterface fun answerText(draft: String, prompt: String) {
            handler.post {
                web?.let { InputSupport.openText(context, it, draft, prompt) }
                if (InputSupport.active) suspend()
            }
        }
        @JavascriptInterface fun resumePrevious() {
            handler.post {
                val target = GateService.rememberedActive()
                hide()
                GateService.hideGates()
                if (target == null || target == context.packageName || Foreground.isLauncher(target)) return@post
                // 悬浮层收起后系统通常会把原应用带回前台；没回来就主动拉起
                handler.postDelayed({
                    val now = try { Foreground.current(context) } catch (_: Exception) { null }
                    if (now == null || now == context.packageName || Foreground.isLauncher(now))
                        DeviceBridge.launchPackage(context, target)
                }, 700)
            }
        }
        @JavascriptInterface fun backToQuiz() {
            handler.post {
                context.startActivity(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                hide()
            }
        }
        @JavascriptInterface fun updateInfo(): String = ""
        @JavascriptInterface fun openParent() {
            handler.post {
                GateService.hideGates()
                hide()
                context.startActivity(Intent(context, ParentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        @JavascriptInterface fun exitToLauncher() {
            val status = DeviceRuntime.learning.status()
            if (status.getString("reason").isNotEmpty() || (status.optLong("remainingSeconds") <= 0 && !status.optBoolean("free"))) return
            handler.post {
                hide()
                if (goHome?.invoke() != true) context.startActivity(Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun applicationWindowType(): Int = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

        fun canDraw(context: Context): Boolean = try {
            if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(context)
            else context.checkCallingOrSelfPermission(android.Manifest.permission.SYSTEM_ALERT_WINDOW) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
    }
}
