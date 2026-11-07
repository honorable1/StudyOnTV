package com.studyontv.tv

import android.content.Context

object DeviceRuntime {
    private var server: LanServer? = null
    lateinit var store: LocalStore
        private set
    lateinit var appContext: Context
        private set
    lateinit var learning: LearningEngine
        private set

    @Synchronized
    fun ensure(context: Context) {
        if (server != null) return
        val app = context.applicationContext
        appContext = app
        Diag.installCrashHandler()
        CrashDetector.install(app)   // 在 Diag handler 之后安装，确保我们的 handler 在最外层
        BuglyInit.init(app)          // 用户已同意过则立即初始化，否则 no-op
        Diag.info("App", "start version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) sdk=${android.os.Build.VERSION.SDK_INT} model=${android.os.Build.MODEL}")
        (app as android.app.Application).registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                Foreground.resumeOwn(activity)
                GateService.onOwnActivityResumed()
            }
            override fun onActivityPaused(activity: android.app.Activity) { Foreground.pauseOwn(activity) }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: android.app.Activity) = Unit
        })
        store = LocalStore(app)
        SeedAssets.ensureQuestions(app, store)
        learning = LearningEngine(store)
        server = LanServer(app, store, app.assets).also {
            it.start()
            Prefs.setLocalPort(app, it.boundPort)
        }
        WebShell.PrefsHolder.baseUrl = Prefs.localBase(app)
        if (GradingService.settings(store).optString("mode") == "ai") {
            val pending = store.reviews("pending")
            for (i in 0 until pending.length()) GradingService.enqueue(store, learning, pending.getJSONObject(i).getString("id"))
        }
    }

    fun connection(context: Context): org.json.JSONObject {
        ensure(context)
        return org.json.JSONObject().put("ip", server?.localIp() ?: "")
            .put("port", Prefs.localPort(context)).put("token", store.adminToken())
    }

    /**
     * WebView 里的页面通过 JS 桥调用接口：进程内直接执行路由，避免把设备令牌交给 JS，
     * 也省掉一次 TCP + HTTP 解析。返回 JSON 字符串。
     */
    fun local(method: String, path: String, body: String): String {
        val current = server ?: return org.json.JSONObject().put("error", "not_ready")
            .put("message", "设备还没准备好，请稍后重试").toString()
        return current.localJson(method, path, body)
    }
}
