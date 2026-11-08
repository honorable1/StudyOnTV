package com.studyontv.tv

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class GateService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastTick = SystemClock.elapsedRealtime()
    private var wasUsing = false
    private var lastGate = 0L
    private var lastFallback = 0L
    private var lastGateError = ""
    private var lastDiag = ""
    @Volatile private var settingsUntil = 0L
    private val systemSettingsPackage by lazy {
        packageManager.resolveActivity(Intent(android.provider.Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
    }
    private var anchor: View? = null
    private lateinit var overlay: GateOverlay
    private val tick = object : Runnable {
        override fun run() {
            try { checkForeground() } catch (e: Exception) { android.util.Log.e("StudyOnTV", "Screen time check failed", e) }
            handler.postDelayed(this, 1000)
        }
    }

    private fun checkForeground() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastTick
        lastTick = now
        if (wasUsing) DeviceRuntime.learning.consume(elapsed)
        val canOverlay = GateOverlay.canDraw(this)
        updateAnchor(canOverlay)
        if (!canOverlay) overlay.hide()
        val status = DeviceRuntime.learning.status()
        val free = status.optBoolean("free")
        if (free) MediaPause.releaseAll(this)
        if (InputSupport.active) {
            wasUsing = false
            overlay.suspend(); WatchAccessibilityService.suspendGate()
            return
        }
        if (now < settingsUntil) { wasUsing = false; hideAll(); return }
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val locked = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        val awake = power.isInteractive && !locked
        val monitoring = canOverlay || WatchAccessibilityService.connected || Foreground.hasUsageAccess(this)
        val pkg = Foreground.current(this)
        // With only overlay permission, use awake time outside our own activities.
        val active = awake && monitoring && if (pkg == null) canOverlay else !exempt(pkg)
        val protecting = awake && monitoring && if (pkg == null) canOverlay else !exempt(pkg, false)
        val reason = status.getString("reason")
        // 这个方法每秒跑一次，config() 与 get() 都各自只取一次，避免同一 tick 内重复查库
        val c = DeviceRuntime.store.config()
        val pendingFeedback = DeviceRuntime.store.get("review_feedback").isNotEmpty()
        val practice = c.optString("playMode") == "practice"
        // 解锁 / 任务完成后确保释放所有音频持有者，避免悬浮页收起后仍残留静音或音频焦点。
        if (!free && !hasGate() && reason.isEmpty() && !pendingFeedback &&
            !practice && MediaPause.active) MediaPause.releaseAll(this)
        val settingsBlocked = awake && monitoring && !free && pkg != null && isSettings(pkg) &&
            c.optBoolean("blockSettingsDuringControl", true) && !AdminAuth.recentlyVerified()
        val gateReason = if (settingsBlocked) "settings" else reason
        val feedbackVisible = !free && hasGate() && pendingFeedback
        if (!free) Foreground.learningActivity()?.let {
            if (reason.isNotEmpty() || pendingFeedback || practice) MediaPause.pause(it)
            else MediaPause.release(it)
        }
        wasUsing = active && !free && reason.isEmpty() && !feedbackVisible
        if (!awake || !monitoring || (!settingsBlocked && ((reason.isEmpty() && !feedbackVisible) || (pkg != null && exempt(pkg, false))))) hideAll()
        val snapshot = "reason=$gateReason free=$free awake=$awake monitoring=$monitoring pkg=$pkg active=$active gate=${hasGate()} overlay=$canOverlay"
        if (snapshot != lastDiag) { lastDiag = snapshot; Diag.info("Gate", snapshot) }
        if ((protecting || settingsBlocked) && gateReason.isNotEmpty() && now - lastGate > 1800 && !hasGate()) {
            lastGate = now
            // 斐讯 T1 等 SDK < 26 的超老盒子悬浮 WebView 易黑屏，优先走全屏 Activity。
            // 悬浮窗有权限时：无障碍覆盖层 → 悬浮 WebView；无权限时直接兜底 GateActivity（#49）。
            val shown = if (Build.VERSION.SDK_INT < 26) false
                else if (canOverlay) WatchAccessibilityService.showGate(gateReason) || overlay.show(gateReason)
                else WatchAccessibilityService.showGate(gateReason)  // 无悬浮窗时无障碍仍可用
            if (shown) lastGateError = ""
            if (!shown) {
                Diag.warn("Gate", "cannot show gate reason=$gateReason overlay=$canOverlay error=$lastGateError")
                if (canOverlay && overlay.lastError.isNotEmpty()) lastGateError = overlay.lastError
                // Android 10+ 默认限制后台启动 Activity，但有悬浮窗权限时属于系统豁免；
                // 部分电视会拒绝 overlay，这里再尝试全屏学习页，并留一条可点击的通知。
                // 自己已经在学习页上时不再重复拉起（否则会每 10 秒重置一次答题）。
                val nowFallback = SystemClock.elapsedRealtime()
                if (Foreground.learningActivity() == null && nowFallback - lastFallback > 10_000) {
                    lastFallback = nowFallback
                    try {
                        startActivity(Intent(this, GateActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra("reason", gateReason))
                    } catch (e: Exception) {
                        lastGateError = "startActivity: " + (e.message ?: e.javaClass.simpleName)
                        android.util.Log.w("StudyOnTV", "Cannot open gate activity", e)
                    }
                    notifyGateFallback(gateReason)
                }
            }
        }
    }

    private fun updateAnchor(allowed: Boolean) {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!allowed) {
            anchor?.let { try { manager.removeViewImmediate(it) } catch (_: Exception) { } }
            anchor = null
        } else if (anchor == null) {
            val view = View(this).apply { setBackgroundColor(Color.argb(1, 0, 0, 0)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val params = WindowManager.LayoutParams(1, 1, GateOverlay.applicationWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
            params.gravity = Gravity.TOP or Gravity.START
            params.title = "StudyOnTV anchor"
            params.alpha = 0.01f
            try { manager.addView(view, params); anchor = view } catch (_: Exception) { }
        }
    }

    private fun hideAll() { overlay.hide(); WatchAccessibilityService.hideGate() }

    private val mediaTick = object : Runnable {
        override fun run() {
            if (MediaPause.active && !InputSupport.active) MediaPause.enforce(this@GateService)
            handler.postDelayed(this, 1000)
        }
    }

    private fun checkOverlayPermission() {
        val p = getSharedPreferences("sotv", MODE_PRIVATE)
        val granted = GateOverlay.canDraw(this)
        if (granted) {
            if (!p.getBoolean("overlay_was_granted", false)) p.edit().putBoolean("overlay_was_granted", true).apply()
            return
        }
        if (!p.getBoolean("overlay_was_granted", false)) return
        p.edit().putBoolean("overlay_was_granted", false).apply()
        Diag.warn("Gate", "overlay permission was revoked by system")
        BuglyInit.reportPermissionLost("SYSTEM_ALERT_WINDOW", "revoked by system firmware")
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "sotv_gate_alert"
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(channelId, "学习提醒", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(this, 2, Intent(this, OnboardingActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
            manager.notify(1003, builder.setContentTitle("悬浮窗权限缺失，答题拦截可能已停止")
                .setContentText("孩子可能已绕过时间限制，请点此重新授权悬浮窗")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentIntent(pi)
                .setAutoCancel(true).setOnlyAlertOnce(true).build())
        } catch (e: Exception) { Diag.error("Gate", "cannot notify overlay revoked", e) }
    }

    private fun checkAccessibilityLost() {
        // 如果无障碍曾经开启但现在不在服务列表（进程被杀后系统移除），发高优先级警告
        val p = getSharedPreferences("sotv", MODE_PRIVATE)
        val wasEnabled = p.getBoolean("accessibility_was_enabled", false)
        val nowEnabled = WatchAccessibilityService.enabled(this)
        p.edit().putBoolean("accessibility_was_enabled", nowEnabled).apply()
        if (!wasEnabled || nowEnabled) return   // 未开过或现在正常，不报警
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "sotv_gate_alert"
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(channelId, "学习提醒", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getActivity(this, 5, Intent(this, OnboardingActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
            manager.notify(1005, builder
                .setContentTitle("无障碍权限缺失，答题拦截可能已停止")
                .setContentText("孩子可能已绕过时间限制，请点此重新开启无障碍服务")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pi).setAutoCancel(true).setOnlyAlertOnce(false).build())
            Diag.warn("Gate", "accessibility lost after restart, notified parent")
            BuglyInit.reportPermissionLost("ACCESSIBILITY_SERVICE", "removed by system after process kill")
        } catch (e: Exception) { Diag.error("Gate", "cannot notify accessibility lost", e) }
    }

    private fun notifyGateFallback(reason: String) {
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "sotv_gate_alert"
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                NotificationChannel(channelId, "学习提醒", NotificationManager.IMPORTANCE_HIGH))
            val intent = Intent(this, GateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("reason", reason)
            val pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
            manager.notify(1002, builder.setContentTitle("到学习时间了")
                .setContentText("没自动弹出学习页时，点这里开始答题")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentIntent(pi)
                .setAutoCancel(true).setOnlyAlertOnce(true).build())
        } catch (e: Exception) { android.util.Log.w("StudyOnTV", "Cannot post fallback notification", e) }
    }

    private fun isSettings(pkg: String): Boolean = pkg == systemSettingsPackage ||
        pkg in setOf("com.android.settings", "com.android.tv.settings", "com.google.android.tv.settings")

    /** 桌面包名只解析一次：exempt() 每秒被调用 3~4 次，每次都走 PackageManager IPC 在电视上很贵 */
    private val launcherPackage: String? by lazy {
        try {
            packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        } catch (_: Exception) { null }
    }

    /**
     * 输入法包名从系统设置里动态读取，而不是用 contains("inputmethod") 糊弄：
     * 子串匹配会让 com.example.inputmethodhelper 这类无关应用整块逃逸管控，
     * 而写死白名单又会在各家电视盒子上误拦真正的输入法（打不了字）。
     */
    private val imePackages: Set<String> by lazy {
        try {
            val raw = listOf(
                android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ENABLED_INPUT_METHODS),
                android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            ).filterNotNull().joinToString(":")
            raw.split(':')
                .map { it.trim().substringBefore('/') }
                .filter { it.isNotEmpty() }
                .toSet()
        } catch (_: Exception) { emptySet() }
    }

    /** 系统安装器 / 权限控制器：包名末段精确匹配，避免 com.evil.packageinstaller2 也能豁免 */
    private fun isSystemComponent(pkg: String): Boolean {
        val tail = pkg.substringAfterLast('.')
        return tail == "packageinstaller" || tail == "permissioncontroller"
    }

    /** 动态列表拿不到时（个别电视盒子判空）退回粗匹配，宁可放宽也不能让孩子无法输入 */
    private fun isIme(pkg: String): Boolean =
        if (imePackages.isNotEmpty()) pkg in imePackages
        else pkg.contains("inputmethod") || pkg.endsWith(".keyboard")

    private fun exempt(pkg: String, includeLauncher: Boolean = true): Boolean {
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android" || isSystemComponent(pkg)) return true
        if (isIme(pkg)) return true
        if (pkg == systemSettingsPackage || pkg in setOf("com.android.settings", "com.android.tv.settings", "com.google.android.tv.settings", "com.google.android.dialer", "com.android.dialer", "com.android.phone", "com.android.server.telecom", "com.android.emergency")) return true
        if (!includeLauncher) return false
        return pkg == launcherPackage || Foreground.isLauncher(pkg)
    }

    override fun onCreate() {
        super.onCreate()
        DeviceRuntime.ensure(this)
        overlay = GateOverlay(this, GateOverlay.applicationWindowType())
        instance = this
        Prefs.setServiceRunning(this, true)
        val channelId = "sotv_gate"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channelId, "使用时间管理", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        val notification = builder.setContentTitle("题了个题 · 使用时间管理中").setContentText("点击打开学习任务")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentIntent(pi).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1001, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1001, notification)
        MediaPause.recover(this)
        checkOverlayPermission()
        checkAccessibilityLost()   // 进程被杀后重启，检查无障碍是否被系统清除
        Diag.info("Service", "GateService started via=$startVia")
        Watchdog.serviceStarted(this, startVia)
        Watchdog.schedule(this)
        handler.post(tick)
        handler.postDelayed(mediaTick, 1000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    /** 手机上从“最近任务”划掉应用时，部分系统会连带停止服务；2 秒后自动拉起 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // 余额现在是内存累计、定期落盘，这里补一次，避免划掉任务时丢掉最近 10 秒的用量
        try { DeviceRuntime.learning.flushUsage() } catch (_: Exception) { }
        scheduleRestart(this, 2000)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try { DeviceRuntime.learning.flushUsage() } catch (_: Exception) { }
        if (Prefs.onboarded(this)) scheduleRestart(this, 5000)
        handler.removeCallbacks(tick); handler.removeCallbacks(mediaTick)
        MediaPause.release(this)
        overlay.destroy(); WatchAccessibilityService.hideGate(); updateAnchor(false)
        instance = null
        Prefs.setServiceRunning(this, false)
        Diag.info("Service", "GateService destroyed")
        super.onDestroy()
    }

    companion object {
        private var instance: GateService? = null
        private var startVia = "app"
        val running: Boolean get() = instance != null

        fun start(context: Context) {
            val intent = Intent(context, GateService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        /** 不抛异常的启动：后台启动前台服务在 Android 12+ 可能被拒绝（有悬浮窗 / 无障碍权限时通常允许） */
        fun ensureRunning(context: Context, via: String = "") {
            if (instance != null || !Prefs.onboarded(context)) return
            if (via.isNotEmpty()) startVia = via
            try { start(context) } catch (e: Exception) { android.util.Log.w("StudyOnTV", "GateService restart refused", e) }
        }

        fun scheduleRestart(context: Context, delayMs: Long) {
            try {
                val intent = Intent(context, GateService::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val pending = if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(context, 7, intent, flags)
                    else PendingIntent.getService(context, 7, intent, flags)
                val alarm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                alarm.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pending)
            } catch (e: Exception) { android.util.Log.w("StudyOnTV", "Cannot schedule restart", e) }
        }
        fun hasGate(): Boolean = WatchAccessibilityService.hasGate() || instance?.overlay?.isShowing == true
        /** 家长面板里的能力自检；不依赖服务是否在运行 */
        fun diagnostics(context: Context): org.json.JSONObject = org.json.JSONObject()
            .put("service", running)
            .put("overlay", GateOverlay.canDraw(context))
            .put("usage", Foreground.hasUsageAccess(context))
            .put("accessibility", WatchAccessibilityService.connected)
            .put("accessibilityEnabled", WatchAccessibilityService.enabled(context))
            .put("gate", hasGate())
            .put("lastTickSeconds", if (instance == null) -1 else ((SystemClock.elapsedRealtime() - (instance?.lastTick ?: 0)) / 1000))
            .put("lastError", instance?.lastGateError ?: "")
        fun hideGates() {
            Handler(Looper.getMainLooper()).post { instance?.overlay?.hide(); WatchAccessibilityService.hideGate() }
        }
        fun onOwnActivityResumed() {
            instance?.settingsUntil = 0L
            Handler(Looper.getMainLooper()).post {
                if (InputSupport.active) { instance?.overlay?.suspend(); WatchAccessibilityService.suspendGate() }
                else { instance?.overlay?.hide(); WatchAccessibilityService.hideGate() }
            }
        }
        fun openingSystemPage(parentAuthorized: Boolean = false) {
            if (parentAuthorized) AdminAuth.markVerified()
            instance?.let { service ->
                val canIdentify = WatchAccessibilityService.connected || Foreground.hasUsageAccess(service)
                val settingsLocked = !DeviceRuntime.learning.status().optBoolean("free") &&
                    DeviceRuntime.store.config().optBoolean("blockSettingsDuringControl", true)
                // Installation is allowed; it must not grant a child access to every Settings page.
                if (!parentAuthorized && !AdminAuth.recentlyVerified() && canIdentify && settingsLocked) return
                service.settingsUntil = SystemClock.elapsedRealtime() + 120000
                service.wasUsing = false
            }
            hideGates()
        }
        /** 遥控操作后立即重新检查，不等下一秒；锁定时去掉 1.8 秒的防抖 */
        fun refreshNow() {
            instance?.handler?.post { instance?.lastGate = 0L; instance?.checkForeground() }
        }
        fun onForegroundChanged(context: Context, pkg: String?) {
            instance?.handler?.post { instance?.checkForeground() }
        }
    }
}
