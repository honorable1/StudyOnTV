package com.studyontv.tv

import android.content.Context

/**
 * Bugly 崩溃上报初始化。
 *
 * 只在用户明确同意（bugly_consent = true）后才初始化，否则完全不联网。
 * 同意状态在崩溃弹窗或设置页由用户选择，可随时撤回（撤回后下次启动生效）。
 *
 * 收集内容：崩溃调用堆栈、设备型号、Android 版本、应用版本号。
 * 不收集：题库、管理密码、答题记录、局域网 IP。
 * 数据上传至腾讯 Bugly 服务器（bugly.qq.com）。
 */
object BuglyInit {
    private const val APP_ID = "9396669eea"
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        if (!Prefs.buglyConsent(context)) return
        try {
            com.tencent.bugly.crashreport.CrashReport.initCrashReport(
                context.applicationContext, APP_ID, false
            )
            com.tencent.bugly.crashreport.CrashReport.setAppVersion(
                context.applicationContext, BuildConfig.VERSION_NAME
            )
            initialized = true
            Diag.info("Bugly", "initialized version=${BuildConfig.VERSION_NAME}")
        } catch (e: Exception) {
            Diag.warn("Bugly", "init failed: ${e.message}")
        }
    }

    /** 用户同意后立即初始化并上传待报告的崩溃 */
    fun initWithConsent(context: Context) {
        Prefs.setBuglyConsent(context, true)
        initialized = false   // 重置，让 init() 重新执行
        init(context)
    }

    /** 上报权限丢失等非致命事件（不需要用户同意，不含个人数据） */
    fun reportPermissionLost(name: String, detail: String) {
        if (!initialized) return
        try {
            com.tencent.bugly.crashreport.CrashReport.postCatchedException(
                RuntimeException("PermissionLost: $name — $detail")
            )
            Diag.warn("Bugly", "reported permission loss: $name")
        } catch (e: Exception) { Diag.warn("Bugly", "reportException failed: ${e.message}") }
    }

    /** 撤回同意：下次启动生效 */
    fun revoke(context: Context) {
        Prefs.setBuglyConsent(context, false)
        Diag.info("Bugly", "consent revoked, will take effect after restart")
    }
}
