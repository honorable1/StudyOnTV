package com.studyontv.tv

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale

/**
 * 法定节假日：从公开的 holiday-cn 数据集读取并缓存在本机。
 * 数据来源：https://github.com/NateScarlet/holiday-cn
 * 只用于「节假日是否按周日安排」这一件事，不发送任何家庭数据。
 */
object HolidayService {
    private fun prefs(context: Context) = context.getSharedPreferences("holidays", Context.MODE_PRIVATE)
    @Volatile private var memo: JSONObject? = null

    fun cached(context: Context): JSONObject {
        memo?.let { return it }
        val data = try { JSONObject(prefs(context).getString("data", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        memo = data
        return data
    }

    fun isHoliday(context: Context, cal: Calendar): Boolean = try {
        val dates = cached(context).optJSONObject("dates") ?: return false
        dates.has(dayKey(cal))
    } catch (_: Exception) { false }

    private fun dayKey(cal: Calendar): String = String.format(Locale.US, "%04d-%02d-%02d",
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))

    fun info(context: Context): JSONObject {
        val data = cached(context)
        val dates = data.optJSONObject("dates") ?: JSONObject()
        return JSONObject().put("year", data.optInt("year", 0)).put("count", dates.length())
            .put("fetchedAt", data.optLong("fetchedAt", 0)).put("source", data.optString("source", "")).put("dates", dates)
    }

    /** 同步某一年；先试国内可访问的接口和 CDN，全部失败时用内置数据。 */
    fun fetch(context: Context, year: Int): JSONObject {
        require(year in 2000..2100) { "年份不正确" }
        val sources = listOf(
            "jiejiari" to "https://api.jiejiariapi.com/v1/holidays/$year",
            "jsDelivr" to "https://fastly.jsdelivr.net/gh/NateScarlet/holiday-cn@master/$year.json",
            "jsDelivr" to "https://gcore.jsdelivr.net/gh/NateScarlet/holiday-cn@master/$year.json",
            "ghproxy" to "https://ghproxy.net/https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/$year.json"
        )
        val deadline = android.os.SystemClock.elapsedRealtime() + 14_000
        var lastError: Exception? = null
        for ((label, url) in sources) {
            if (android.os.SystemClock.elapsedRealtime() > deadline) break
            try {
                val dates = parse(readJson(url))
                if (dates.length() == 0) continue
                return save(context, year, dates, label)
            } catch (e: Exception) { lastError = e }
        }
        return try {
            val text = context.assets.open("holidays/$year.json").use { it.readBytes().toString(Charsets.UTF_8) }
            save(context, year, parse(JSONObject(text)), "内置数据")
        } catch (e: Exception) {
            throw lastError ?: e
        }
    }

    private fun save(context: Context, year: Int, dates: JSONObject, source: String): JSONObject {
        val data = JSONObject().put("year", year).put("dates", dates)
            .put("source", source).put("fetchedAt", System.currentTimeMillis())
        prefs(context).edit().putString("data", data.toString()).commit()
        memo = null
        return info(context)
    }

    /** 同时兼容 holiday-cn 的 days 数组和 jiejiariapi 的按日期对象。 */
    private fun parse(root: JSONObject): JSONObject {
        val dates = JSONObject()
        val days = root.optJSONArray("days")
        if (days != null) {
            for (i in 0 until days.length()) {
                val item = days.optJSONObject(i) ?: continue
                if (!item.optBoolean("isOffDay", false)) continue
                val date = item.optString("date")
                if (date.isNotEmpty()) dates.put(date, item.optString("name").ifEmpty { "节假日" })
            }
            return dates
        }
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = root.optJSONObject(key) ?: continue
            if (!item.optBoolean("isOffDay", false)) continue
            val date = item.optString("date").ifEmpty { key }
            if (date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) dates.put(date, item.optString("name").ifEmpty { "节假日" })
        }
        return dates
    }

    private fun readJson(url: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 5000
            setRequestProperty("User-Agent", "StudyOnTV")
            setRequestProperty("Accept", "application/json")
        }
        try {
            require(connection.responseCode in 200..299) { "节假日服务返回 HTTP ${connection.responseCode}" }
            if (Net.declaredLength(connection) > 1_000_000) throw IllegalStateException("节假日数据过大")
            val text = try { Net.text(connection.inputStream, 1_000_000) } catch (_: Net.TooLarge) { throw IllegalStateException("节假日数据过大") }
            return JSONObject(text)
        } finally { connection.disconnect() }
    }
}
