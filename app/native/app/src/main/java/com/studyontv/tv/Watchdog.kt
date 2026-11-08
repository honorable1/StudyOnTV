package com.studyontv.tv

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

/**
 * 开机自启与常驻的“第二条路”：
 * - 很多国产电视 / 盒子默认拦截第三方应用的开机广播（需要在「自启动管理」里放行），
 *   系统的持久化定时任务（JobScheduler，setPersisted）在开机后由系统重新排程，可作为兜底；
 * - 管理服务被清理后，最迟 15 分钟由这里重新拉起（无障碍服务开启时会更快）。
 * 同时记录每次开机后服务是怎样、多久启动的，家长面板据此提示“开机自启可能被拦截”。
 */
class Watchdog : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        GateService.ensureRunning(this, "job")
        return false
    }
    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 7301

        fun schedule(context: Context) {
            try {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                if (scheduler.allPendingJobs.any { it.id == JOB_ID }) return
                scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, Watchdog::class.java))
                    .setPeriodic(15 * 60 * 1000L).setPersisted(true).build())
            } catch (e: Exception) { android.util.Log.w("StudyOnTV", "Cannot schedule watchdog", e) }
        }

        /**
         * 第二条兜底：部分国产固件会限制 JobScheduler（省电策略里直接掐掉作业），
         * 但放行 AlarmManager 唤醒。这里用不精确闹钟（不需要任何额外权限）每 15 分钟喊一次，
         * 进程被杀后最迟约 20 分钟能被拉回来，比只有 JobScheduler 的机型成功率高。
         */
        fun scheduleAlarm(context: Context) {
            try {
                val manager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                val intent = android.content.Intent(context, BootReceiver::class.java).setAction(REVIVE_ACTION)
                val pending = android.app.PendingIntent.getBroadcast(context, 7302, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                manager.setInexactRepeating(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 5 * 60 * 1000L, 15 * 60 * 1000L, pending)
            } catch (e: Exception) { android.util.Log.w("StudyOnTV", "Cannot schedule revive alarm", e) }
        }

        const val REVIVE_ACTION = "com.studyontv.tv.REVIVE"

        fun bootCount(context: Context): Int = try {
            android.provider.Settings.Global.getInt(context.contentResolver, "boot_count", -1)
        } catch (_: Throwable) { -1 }

        private fun prefs(context: Context) = context.getSharedPreferences("sotv_boot", Context.MODE_PRIVATE)

        fun bootBroadcast(context: Context) {
            prefs(context).edit().putInt("broadcast_boot", bootCount(context))
                .putLong("broadcast_after", SystemClock.elapsedRealtime()).apply()
        }

        /** 本次开机第一次启动服务时记一笔 */
        fun serviceStarted(context: Context, via: String) {
            val boot = bootCount(context)
            val p = prefs(context)
            // 首次安装或刚更新：这次开机没经历过“开机”，要等下次重启才能判断
            if (!p.contains("service_boot") || via == "update") p.edit().putInt("install_boot", boot).apply()
            if (p.getInt("service_boot", -2) == boot && boot != -1) return
            p.edit().putInt("service_boot", boot).putString("service_via", via)
                .putLong("service_after", SystemClock.elapsedRealtime()).apply()
        }

        fun report(context: Context): JSONObject {
            val p = prefs(context)
            val boot = bootCount(context)
            val sameBoot = boot != -1 && p.getInt("service_boot", -2) == boot
            val broadcast = boot != -1 && p.getInt("broadcast_boot", -2) == boot
            val after = if (sameBoot) p.getLong("service_after", 0) / 1000 else -1
            // 开机广播到了，或服务在开机 3 分钟内启动，都算自启正常
            val ok = broadcast || (sameBoot && after in 0..180)
            val pending = !ok && boot != -1 && p.getInt("install_boot", -2) == boot
            return JSONObject().put("ok", ok).put("bootBroadcast", broadcast).put("startedAfterSeconds", after)
                .put("via", if (sameBoot) p.getString("service_via", "") else "")
                .put("uptimeMinutes", SystemClock.elapsedRealtime() / 60000)
                .put("accessibility", WatchAccessibilityService.connected)
                .put("known", boot != -1).put("pending", pending)
        }
    }
}
