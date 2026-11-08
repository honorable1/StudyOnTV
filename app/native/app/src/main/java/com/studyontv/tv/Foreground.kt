package com.studyontv.tv

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

object Foreground {
    @Volatile var lastPackage: String? = null
        private set

    /** 最近一个「真正的」前台应用（滤掉系统临时窗口），系统界面抢前台时用它兜底 */
    @Volatile private var lastRealPackage: String? = null

    private var ownActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

    /**
     * 这些包会以 TYPE_WINDOW_STATE_CHANGED 的形式抢到前台（音量条、通知面板、权限弹窗、下拉面板…），
     * 但它们并不是「孩子正在用的应用」。一旦把它们当成前台，就会因为被豁免而停止计时，
     * 直到下一次真正切换应用才恢复（issue #69：小米电视上被识别成 com.android.systemui，时长不再扣减）。
     */
    private val transientPackages = setOf(
        "com.android.systemui", "android", "com.android.permissioncontroller",
        "com.google.android.permissioncontroller", "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    )
    fun isTransient(pkg: String?): Boolean = pkg != null && transientPackages.contains(pkg)

    fun resumeOwn(activity: android.app.Activity) {
        ownActivity = java.lang.ref.WeakReference(activity)
        update(activity.packageName)
    }
    fun pauseOwn(activity: android.app.Activity) {
        if (ownActivity?.get() === activity) ownActivity = null
        if (ownActivity?.get() == null && lastPackage == activity.packageName) lastPackage = null
    }
    fun update(pkg: String?) {
        if (pkg.isNullOrEmpty()) return
        lastPackage = pkg
        if (!isTransient(pkg)) lastRealPackage = pkg
    }
    fun clearExternal() { lastPackage = null; lastRealPackage = null }

    /** 最近一个真正的前台应用（不含系统临时窗口），供「解锁后回到原画面」使用 */
    fun lastReal(): String? = lastRealPackage?.takeUnless { it == lastPackage && isTransient(it) }
    fun learningActivity(): android.app.Activity? = ownActivity?.get()?.takeIf { it is MainActivity }

    private val launcherPackages = setOf(
        "com.android.tv.launcher", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
        "com.android.launcher", "com.android.launcher3", "com.tcl.launcher", "com.tcl.tv.launcher", "com.mitv.tvhome",
        "com.hisense.tv.launcher", "com.google.android.apps.nexuslauncher", "com.miui.home",
        "com.sec.android.app.launcher", "com.huawei.android.launcher", "com.oppo.launcher", "com.vivo.launcher",
        // 其余常见电视桌面，避免「桌面包在播放」的播放判定在真正的桌面上误触发
        "com.skyworth.launcher", "com.coocaa.launcher", "com.changhong.launcher", "com.philips.launcher",
        "com.sony.dtv.launcher", "com.mediatek.launcher", "com.amlogic.launcher", "com.dangbei.launcher",
        "com.hisense.vidaa.launcher", "com.tcl.tv.launcherx", "com.ktc.launcher", "com.haier.launcher"
    )
    fun isLauncher(pkg: String?): Boolean = pkg != null && launcherPackages.contains(pkg)

    @Suppress("DEPRECATION")
    fun current(context: Context): String? {
        ownActivity?.get()?.let {
            if (Build.VERSION.SDK_INT < 24 || !it.isInMultiWindowMode) return it.packageName
        }
        if (WatchAccessibilityService.connected) {
            val cached = lastPackage
            // 缓存里是系统临时窗口时不采信：先用使用情况统计重新确认，
            // 再退到最近一个「真正的前台应用」。否则小米电视上会被卡在
            // com.android.systemui 上，一直判定为系统界面而不再计时（issue #69）。
            if (cached != null && !isTransient(cached)) return cached
            if (hasUsageAccess(context)) usageFallback(context)?.let { return it }
            // 兜底时不能返回本应用自己：那会被当成「在自家学习页」而停扣时长。
            lastRealPackage?.takeIf { it != context.packageName }?.let { return it }
            return cached
        }
        if (!hasUsageAccess(context)) return lastRealPackage?.takeIf { it != context.packageName }
        return usageFallback(context)
    }

    private fun usageFallback(context: Context): String? {
        return try {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            // 以前每秒都查询过去 24 小时的全部事件，在电视上很耗 CPU；现在只查上次之后的新事件
            val from = if (usageQueriedAt <= 0 || now - usageQueriedAt > 3600000 || now < usageQueriedAt) now - 86400000 else usageQueriedAt - 2000
            val events = manager.queryEvents(from, now)
            val event = UsageEvents.Event()
            var latest: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // 系统临时窗口不算前台应用，否则同样会停扣时长
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && !isTransient(event.packageName)) latest = event.packageName
            }
            usageQueriedAt = now
            if (latest != null) usageLatest = latest
            return usageLatest ?: manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86400000, now)
                ?.filter { it.lastTimeUsed > 0 && !isTransient(it.packageName) }
                ?.maxByOrNull { it.lastTimeUsed }?.packageName?.also { usageLatest = it }
        } catch (_: Exception) { null }
    }

    @Volatile private var usageQueriedAt = 0L
    @Volatile private var usageLatest: String? = null

    fun hasUsageAccess(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        }
        mode == android.app.AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }
}
