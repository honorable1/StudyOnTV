package com.studyontv.tv

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 权限引导。参考 Apple TV「设置」：左侧说明与操作，右侧是可整行选中的权限列表，
 * 每行右侧显示“已开启 / 去开启”，从系统设置返回后自动刷新。手机上为单列。
 */
class OnboardingActivity : Activity() {
    private data class Item(val row: TvUi.Row, val enabled: () -> Boolean, val optional: Boolean)
    private val items = mutableListOf<Item>()
    private lateinit var modeTitle: TextView
    private lateinit var modeDescription: TextView
    private lateinit var modeDot: View
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshStatus = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }
    private val tv get() = DeviceBridge.isTv(this)
    private fun dp(value: Int) = TvUi.dp(this, value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AdminAuth.configured(this)) {
            startActivity(Intent(this, ParentActivity::class.java)); finish(); return
        }
        DeviceRuntime.ensure(this)
        if (tv) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val p = TvUi.palette(this)
        window.decorView.setBackgroundColor(p.bg)

        // 左侧：标题、当前状态、操作
        val intro = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        intro.addView(TvUi.text(this, "使用时间管理", if (tv) 15f else 13f, p.text2, true))
        intro.addView(TvUi.text(this, "开启几项权限，\n到时间自动提醒学习", if (tv) 34f else 26f, p.text, true).apply { setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        intro.addView(TvUi.text(this, "都可以稍后在家长设置里调整。只需要悬浮窗就能开始使用，其余权限让计时更准确。", if (tv) 16f else 14f, p.text2).apply { setLineSpacing(0f, 1.3f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(p.surface); cornerRadius = dp(14).toFloat() }
        }
        modeDot = View(this)
        status.addView(modeDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(12) })
        val statusCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        modeTitle = TvUi.text(this, "", if (tv) 16f else 14f, p.text, true)
        modeDescription = TvUi.text(this, "", if (tv) 13f else 12f, p.text2)
        statusCopy.addView(modeTitle); statusCopy.addView(modeDescription, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        status.addView(statusCopy, LinearLayout.LayoutParams(0, -2, 1f))
        intro.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        val actions = LinearLayout(this).apply { orientation = if (tv) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        val manage = TvUi.button(this, "完成，进入家长设置", true) { complete(true) }
        val learn = TvUi.button(this, "先开始学习") { complete(false) }
        if (tv) {
            actions.addView(manage, LinearLayout.LayoutParams(-1, dp(52)))
            actions.addView(learn, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        } else {
            actions.orientation = LinearLayout.VERTICAL
            actions.addView(manage, LinearLayout.LayoutParams(-1, dp(52)))
            actions.addView(learn, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        }

        // 右侧：权限列表
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun add(icon: Int, title: String, description: String, optional: Boolean, enabled: () -> Boolean, action: () -> Unit) {
            val row = TvUi.row(this, icon, title, description, action)
            items.add(Item(row, enabled, optional))
            list.addView(row.view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(if (tv) 8 else 10) })
        }
        list.addView(TvUi.text(this, "权限", if (tv) 14f else 13f, p.text3, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10); leftMargin = dp(4) })
        add(android.R.drawable.ic_menu_view, "悬浮窗", "到时间后在其他应用上方显示学习页", false, { GateOverlay.canDraw(this) }) {
            openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true)
        }
        add(android.R.drawable.ic_menu_manage, "无障碍服务", if (tv) "及时识别应用切换，部分电视不支持" else "及时识别应用切换", true, { WatchAccessibilityService.connected }) {
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }
        add(android.R.drawable.ic_menu_recent_history, "使用情况访问", "只统计其他应用的使用时长", true, { Foreground.hasUsageAccess(this) }) {
            openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        }
        add(android.R.drawable.ic_lock_idle_alarm, "后台运行", "关闭电池优化，减少被系统清理", true, { Build.VERSION.SDK_INT < 23 || ignoringBattery() }) {
            if (Build.VERSION.SDK_INT >= 23 && !ignoringBattery()) openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
        }
        add(android.R.drawable.ic_lock_lock, "防卸载", "孩子无法直接删除应用，家长可随时关闭", true, { UninstallGuard.active(this) }) {
            if (UninstallGuard.active(this)) openSettings(Settings.ACTION_SECURITY_SETTINGS)
            else try { GateService.openingSystemPage(parentAuthorized = true); startActivity(UninstallGuard.requestIntent(this)) } catch (_: Exception) {
                Toast.makeText(this, "这台设备不支持设备管理应用，无法开启防卸载", Toast.LENGTH_LONG).show()
            }
        }
        // 电视一般不显示通知，只在手机 / 平板上提示
        if (!tv && Build.VERSION.SDK_INT >= 33) add(android.R.drawable.ic_dialog_info, "通知", "显示“使用时间管理中”的常驻通知", true,
            { checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
        }
        list.addView(TvUi.text(this, if (tv) "部分电视没有“电池优化”，可在系统“应用管理”里允许自启动。" else "部分手机还需要在系统设置里允许“自启动”或“后台活动”。", if (tv) 13f else 12f, p.text3),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); leftMargin = dp(4) })
        // 针对常见品牌给出更具体的自启动引导
        val brand = android.os.Build.MANUFACTURER.lowercase()
        val brandTip = when {
            brand.contains("tcl") -> "TCL 电视：设置 → 应用 → 题了个题 → 自启动，开启「允许自启动」"
            brand.contains("hisense") || brand.contains("vidaa") -> "海信电视：设置 → 应用管理 → 题了个题 → 权限 → 允许自启动"
            brand.contains("skyworth") || brand.contains("coocaa") -> "创维 / 酷开：设置 → 应用 → 题了个题 → 自启动权限，设为允许"
            brand.contains("xiaomi") || brand.contains("redmi") -> "小米电视：设置 → 应用 → 应用管理 → 题了个题 → 自启动，开启。注意：小米 HyperOS 建议不授予悬浮窗权限，否则可能被系统强杀"
            brand.contains("changhong") -> "长虹电视：设置 → 应用管理 → 题了个题 → 允许后台运行"
            else -> null
        }
        if (brandTip != null) list.addView(TvUi.text(this, brandTip, if (tv) 12f else 11f, p.text3),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); leftMargin = dp(4) })

        // 电视和横屏平板（宽度 ≥ 840dp）用左右两栏，手机用单列
        val wide = tv || resources.configuration.screenWidthDp >= 840
        val root: View = if (wide) {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(64), dp(24), dp(64), dp(24))
                val left = LinearLayout(this@OnboardingActivity).apply { orientation = LinearLayout.VERTICAL; addView(intro); addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) }) }
                addView(left, LinearLayout.LayoutParams(0, -2, 0.42f))
                addView(ScrollView(this@OnboardingActivity).apply { isVerticalScrollBarEnabled = false; clipToPadding = false; setPadding(dp(12), dp(12), dp(12), dp(12)); addView(list) },
                    LinearLayout.LayoutParams(0, -2, 0.58f).apply { leftMargin = dp(48) })
            }
        } else {
            ScrollView(this).apply {
                isFillViewport = true
                addView(LinearLayout(this@OnboardingActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(32), dp(20), dp(24))
                    addView(intro); addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
                    addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
                })
            }
        }
        setContentView(root)
        refresh()
        // 第一个还没开启的权限获得焦点；都开好了就直接落在“完成”
        (items.firstOrNull { !it.enabled() }?.row?.view ?: manage).requestFocus()
    }

    private fun ignoringBattery(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        return try {
            (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) { false }
    }

    private fun openSettings(action: String, packageSpecific: Boolean = false, fallback: String? = null) {
        GateService.openingSystemPage(parentAuthorized = true)
        val options = mutableListOf(Intent(action).apply { if (packageSpecific) data = Uri.parse("package:$packageName") })
        if (packageSpecific) options.add(Intent(action))
        if (fallback != null) options.add(Intent(fallback))
        options.add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        options.add(Intent(Settings.ACTION_SETTINGS))
        for (intent in options) try { startActivity(intent); return } catch (_: Exception) { }
        Toast.makeText(this, "请在系统设置中调整权限", Toast.LENGTH_SHORT).show()
    }

    private fun complete(manage: Boolean) {
        Prefs.setOnboarded(this, true)
        try { GateService.start(this) } catch (_: RuntimeException) {
            Toast.makeText(this, "后台管理暂未启动，您仍可继续学习", Toast.LENGTH_SHORT).show()
        }
        startActivity(Intent(this, if (manage) ParentActivity::class.java else MainActivity::class.java))
        finish()
    }

    private fun refresh() {
        if (!::modeTitle.isInitialized) return
        val p = TvUi.palette(this)
        items.forEach { val on = it.enabled(); it.row.setStatus(if (on) "已开启" else if (it.optional) "可选" else "去开启", on) }
        val overlay = GateOverlay.canDraw(this)
        val usage = Foreground.hasUsageAccess(this)
        val (title, description, ready) = when {
            overlay && (WatchAccessibilityService.connected || usage) -> Triple("已就绪", "到时间会自动显示学习页，计时准确", true)
            overlay -> Triple("基本可用", "离开学习页后按亮屏时间计时", true)
            usage -> Triple("可以记录使用时间", "开启悬浮窗后，才能在到时间时显示学习页", false)
            else -> Triple("尚未开启", "建议至少开启悬浮窗", false)
        }
        modeTitle.text = title; modeDescription.text = description
        modeDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (ready) p.ok else p.warn) }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshStatus)
        handler.post(refreshStatus)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshStatus)
        super.onPause()
    }
}
