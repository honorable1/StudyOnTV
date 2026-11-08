package com.studyontv.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.UUID

object AnswerInput {
    private var target = WeakReference<WebView>(null)
    private var requestId = ""
    var active = false
        private set

    /** 打开一个原生输入页（手机：手写 / 语音；电视：遥控器 + 系统输入法），结果经 SOTV_INPUT 回传给网页 */
    fun open(context: Context, web: WebView, screen: Class<out Activity>, extras: Intent.() -> Unit = {}) {
        if (active) return
        active = true
        requestId = UUID.randomUUID().toString()
        target = WeakReference(web)
        if (context !is Activity) web.visibility = View.GONE
        try {
            context.startActivity(Intent(context, screen)
                .putExtra("requestId", requestId)
                .apply(extras)
                .apply {
                    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                })
        } catch (_: RuntimeException) {
            active = false
            requestId = ""
            target.clear()
            web.visibility = View.VISIBLE
            android.widget.Toast.makeText(context, "暂时无法打开输入页面，请稍后重试", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 把结果回传给网页。
     *
     * 部分电视（如 TCL mt5879 系列）的 WebView 在从原生页切回来时，JS 上下文还没准备好，
     * 单次 evaluateJavascript 会静默丢失，表现为「点了确定回到题目还是空的」（issue #64）。
     * 这里带上一次性 token 重复注入，直到 JS 回写 window.__sotvAck 确认为止。
     */
    fun complete(id: String, result: JSONObject?) {
        if (id != requestId) return
        Handler(Looper.getMainLooper()).post {
            val view = target.get()
            view?.visibility = View.VISIBLE
            active = false
            requestId = ""
            target.clear()
            if (result == null || view == null) return@post
            val token = UUID.randomUUID().toString()
            val payload = result.toString()
            var attempt = 0
            fun send() {
                                attempt++
                try {
                    view.evaluateJavascript("window.SOTV_INPUT&&SOTV_INPUT($payload,'$token')", null)
                } catch (_: Throwable) { return }
                if (attempt >= 8) return
                view.postDelayed({
                                        try {
                        view.evaluateJavascript("window.__sotvAck==='$token'") { ack ->
                            if (ack == null || !ack.contains("true")) send()
                        }
                    } catch (_: Throwable) { }
                }, (180L * attempt).coerceAtMost(1200L))
            }
            view.postDelayed({ send() }, 120)
        }
    }
}
