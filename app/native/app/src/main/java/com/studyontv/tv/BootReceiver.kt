package com.studyontv.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent


class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        // 开机、部分电视的快速开机、以及应用自更新覆盖安装后，都要重新拉起管理服务
        if (action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                "android.intent.action.QUICKBOOT_POWERON", "com.htc.intent.action.QUICKBOOT_POWERON",
                "android.intent.action.LOCKED_BOOT_COMPLETED",
                "com.tcl.android.intent.action.BOOT_COMPLETED",
                "com.hisense.intent.action.BOOT_COMPLETED")) {
            Prefs.setUpdateInProgress(context, false)
            if (action != Intent.ACTION_MY_PACKAGE_REPLACED) Watchdog.bootBroadcast(context)
            GateService.ensureRunning(context, if (action == Intent.ACTION_MY_PACKAGE_REPLACED) "update" else "boot")
            return
        }
        // 屏幕点亮 / 用户解锁 / 网络变化：进程被杀后快速拉起的兜底
        // 只在管控配置已存在时才拉起，避免干扰未配对的设备
        if (action in setOf(Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT,
                "android.net.conn.CONNECTIVITY_CHANGE", Watchdog.REVIVE_ACTION)) {
            if (GateService.running) return          // 已在运行，无需操作
            if (!Prefs.onboarded(context)) return      // 未完成引导，不拉起
            GateService.ensureRunning(context, "revive")
        }
    }
}
