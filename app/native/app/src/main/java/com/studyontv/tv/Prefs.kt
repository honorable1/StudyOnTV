package com.studyontv.tv

import android.content.Context





object Prefs {
    private const val FILE = "sotv"
    /** 本分支自建的更新源；家长在面板里清空（存空串）即回落到蒲公英与官方清单 */
    const val DEFAULT_UPDATE_API = "http://55egrv9vqmkr.xiaomiqiu.com/update.json"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    
    fun localPort(c: Context): Int = sp(c).getInt("local_port", 8099)
    fun setLocalPort(c: Context, port: Int) = sp(c).edit().putInt("local_port", port).apply()

    fun localBase(c: Context): String = "http://127.0.0.1:" + localPort(c) + "/"

    fun onboarded(c: Context): Boolean = sp(c).getBoolean("onboarded", false)
    fun setOnboarded(c: Context, v: Boolean) = sp(c).edit().putBoolean("onboarded", v).apply()

    

    fun deviceName(c: Context): String =
        sp(c).getString("device_name", android.os.Build.MODEL ?: "客厅电视") ?: "客厅电视"
    fun setDeviceName(c: Context, v: String) = sp(c).edit().putString("device_name", v).apply()

    
    fun updateInfo(c: Context): String = sp(c).getString("update_info", "") ?: ""
    fun setUpdateInfo(c: Context, v: String) = sp(c).edit().putString("update_info", v).apply()
    fun updateCheckedAt(c: Context): Long = sp(c).getLong("update_checked_at", 0L)
    fun setUpdateCheckedAt(c: Context, v: Long) = sp(c).edit().putLong("update_checked_at", v).apply()
    /** 未配置过时走 DEFAULT_UPDATE_API；显式清空则返回空串，由 Updater 回落到官方渠道 */
    fun updateApi(c: Context): String = sp(c).getString("update_api", null) ?: DEFAULT_UPDATE_API
    fun setUpdateApi(c: Context, v: String) = sp(c).edit().putString("update_api", v).apply()
    /** null 表示使用默认镜像列表 */
    fun updateMirrors(c: Context): String? = sp(c).getString("update_mirrors", null)
    fun setUpdateMirrors(c: Context, v: String?) = sp(c).edit().apply { if (v == null) remove("update_mirrors") else putString("update_mirrors", v) }.apply()
    fun updateLastSource(c: Context): String = sp(c).getString("update_last_source", "") ?: ""
    fun setUpdateLastSource(c: Context, v: String) = sp(c).edit().putString("update_last_source", v).apply()
    fun updateLog(c: Context): String = sp(c).getString("update_log", "") ?: ""
    fun setUpdateLog(c: Context, v: String) = sp(c).edit().putString("update_log", v).apply()

    
    fun updateInProgress(c: Context): Boolean = sp(c).getLong("update_started_at", 0L).let { it > 0 && System.currentTimeMillis() - it < 10 * 60 * 1000 }
    fun setUpdateInProgress(c: Context, v: Boolean) = sp(c).edit().putLong("update_started_at", if (v) System.currentTimeMillis() else 0L).apply()

    fun serviceRunning(c: Context): Boolean = sp(c).getBoolean("service_running", false)
    fun setServiceRunning(c: Context, v: Boolean) = sp(c).edit().putBoolean("service_running", v).apply()

    /** null = 未表态（尚未被问过）, true = 同意, false = 拒绝 */
    fun buglyConsent(c: Context): Boolean = sp(c).getBoolean("bugly_consent", false)
    fun setBuglyConsent(c: Context, v: Boolean) = sp(c).edit().putBoolean("bugly_consent", v).apply()
    fun buglyConsentAsked(c: Context): Boolean = sp(c).getBoolean("bugly_consent_asked", false)
    fun setBuglyConsentAsked(c: Context, v: Boolean) = sp(c).edit().putBoolean("bugly_consent_asked", v).apply()
}
