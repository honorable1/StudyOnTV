package com.studyontv.tv

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 蒲公英应用内更新。官方 Android SDK 已停止维护，这里使用 SDK 相同的公开检查接口
 * （appKey + 当前版本，不使用账户 API Key），返回的 installUpdate 地址可以直接下载。
 * 不访问 GitHub 或第三方镜像。
 */
object Pgyer {
    private val shortcut get() = if (BuildConfig.FLAVOR == "tv") "StudyOnTVTV" else "StudyOnTVMobile"
    private val appKey get() = if (BuildConfig.FLAVOR == "tv") "5c78c116f305ded2cd7f34edb69a70ec" else "9fa3ea546ca7601e842e3ead0be1679c"
    val pageUrl get() = "https://www.pgyer.com/$shortcut"
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 5.0; StudyOnTV) AppleWebKit/537.36 StudyOnTV-Updater"

    private const val CHECK_URL = "https://www.pgyer.com/apiv1/update/check"
    // 蒲公英 Android SDK 3.0.10 内置的公共检查 Key，仅用于 appKey + 版本查询，不是账户 API Key。
    private const val SDK_API_KEY = "305092bc73c180b55c26012a94809131"

    fun info(context: Context, current: String, currentCode: Int): Updater.Info? {
        val form = listOf(
            "_api_key" to SDK_API_KEY,
            "agKey" to appKey,
            "version" to current,
            "build" to currentCode.toString(),
            "sdkapi" to android.os.Build.VERSION.SDK_INT.toString()
        ).joinToString("&") { (key, value) -> key + "=" + URLEncoder.encode(value, "UTF-8") }

        val connection = (URL(CHECK_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 6000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            require(code in 200..299) { "蒲公英返回 HTTP $code" }
            if (Net.declaredLength(connection) > 1_000_000) throw IllegalStateException("蒲公英响应过大")
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = try { Net.text(stream, 1_000_000) } catch (_: Net.TooLarge) { throw IllegalStateException("蒲公英响应过大") }
            val root = JSONObject(text)
            require(root.optInt("code", 0) == 0) { root.optString("message").ifEmpty { "蒲公英检查更新失败" } }
            val data = root.optJSONObject("data") ?: return null
            if (!data.optBoolean("haveNewVersion", false)) return null
            val version = data.optString("version").trim().removePrefix("v")
            if (version.isEmpty() || !Updater.isNewer(version, current)) return null
            val url = data.optString("downloadURL").trim()
                .replaceFirst("http://", "https://")
            require(url.startsWith("https://www.pgyer.com/app/installUpdate/")) { "蒲公英没有返回可用的安装地址" }
            val notes = data.optString("releaseNote").ifEmpty { "Update" }
            Diag.info("Update", "Pgyer found v$version build=${data.optString("build")} force=${data.optBoolean("needForceUpdate")}")
            return Updater.Info(version, current, data.optBoolean("needForceUpdate", false),
                notes, url, data.optString("sha256", ""), source = "蒲公英")
        } finally {
            connection.disconnect()
        }
    }
}
