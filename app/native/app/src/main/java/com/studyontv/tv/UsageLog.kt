package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * events 表的写入与统计。
 *
 * 这里有两层缓存，都是为同一个热路径服务的 —— 家长面板每 2 秒轮询一次 `/api/state`，
 * 而看板上每个数字都要从 events 表算出来：
 * - `stats()` 用 SQL 的 `SUM(CASE WHEN ...)` 一次算完，不再把每行 payload 反序列化一遍；
 *   老系统没有 json1 扩展时回落到逐行解析（结果一致，只是慢）。
 * - `todayStats()` 在 2 秒内复用上一次的结果，`logEvent()` 写入时立即失效。
 */
class UsageLog(private val store: LocalStore) {

    fun logEvent(type: String, payload: JSONObject) {
        store.writableDatabase.execSQL("INSERT INTO events(type,payload,ts) VALUES(?,?,?)", arrayOf<Any?>(type, payload.toString(), System.currentTimeMillis()))
        todayCached = null      // 当天统计缓存立即失效
    }

    fun recentEvents(limit: Int = 50): JSONArray {
        val out = JSONArray()
        store.readableDatabase.rawQuery("SELECT type,payload,ts FROM events WHERE type!='usage' ORDER BY id DESC LIMIT ${limit.coerceIn(1, 500)}", null).use { c ->
            while (c.moveToNext()) out.put(JSONObject().put("type", c.getString(0)).put("payload", JSONObject(c.getString(1))).put("ts", c.getLong(2)))
        }
        return out
    }

    /** SQLite 3.9+ 自带 json1 扩展；老系统上没有时回落到逐行解析 */
    private val json1Available: Boolean by lazy {
        try {
            store.readableDatabase.rawQuery("""SELECT json_extract('{"a":1}','$.a')""", null).use { it.moveToFirst() }
            true
        } catch (_: Exception) { false }
    }

    fun stats(start: Long, end: Long): JSONObject {
        val args = arrayOf(start.toString(), end.toString())
        if (json1Available) {
            try { return statsSql(args) } catch (e: Exception) { Diag.warn("Store", "SQL 统计失败，回落到逐行解析：" + (e.message ?: "")) }
        }
        return statsLoop(args)
    }

    private fun statsSql(args: Array<String>): JSONObject {
        val sql = """
            SELECT
              COALESCE(SUM(CASE WHEN type='usage' THEN CAST(json_extract(payload,'$.milliseconds') AS INTEGER) ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN type='answer' THEN 1 ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN type='answer' AND json_extract(payload,'$.correct')=1 THEN 1 ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN type='quiz' AND COALESCE(json_extract(payload,'$.native'),0)=0 THEN CAST(json_extract(payload,'$.total') AS INTEGER) ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN type='quiz' AND COALESCE(json_extract(payload,'$.native'),0)=0 THEN CAST(json_extract(payload,'$.correct') AS INTEGER) ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN type='quiz_pass' THEN 1 ELSE 0 END),0)
            FROM events WHERE ts>=? AND ts<? AND json_valid(payload)
        """.trimIndent()
        var usedMs = 0L; var answered = 0; var correct = 0; var rounds = 0
        store.readableDatabase.rawQuery(sql, args).use { c ->
            if (c.moveToFirst()) {
                usedMs = c.getLong(0)
                answered = c.getInt(1) + c.getInt(3)
                correct = c.getInt(2) + c.getInt(4)
                rounds = c.getInt(5)
            }
        }
        return summary(usedMs, answered, correct, rounds)
    }

    private fun statsLoop(args: Array<String>): JSONObject {
        var usedMs = 0L
        var answered = 0
        var correct = 0
        var rounds = 0
        store.readableDatabase.rawQuery("SELECT type,payload FROM events WHERE ts>=? AND ts<?", args).use { c ->
            while (c.moveToNext()) {
                val p = jsonObject(c.getString(1))
                when (c.getString(0)) {
                    "usage" -> usedMs += p.optLong("milliseconds")
                    "answer" -> { answered++; if (p.optBoolean("correct")) correct++ }
                    "quiz" -> if (!p.optBoolean("native")) { answered += p.optInt("total"); correct += p.optInt("correct") }
                    "quiz_pass" -> rounds++
                }
            }
        }
        return summary(usedMs, answered, correct, rounds)
    }

    private fun summary(usedMs: Long, answered: Int, correct: Int, rounds: Int): JSONObject =
        JSONObject().put("usedMinutes", usedMs / 60000.0).put("watch", usedMs / 60000.0)
            .put("answered", answered).put("correct", correct).put("rounds", rounds)
            .put("accuracy", if (answered > 0) correct * 100 / answered else 0)

    @Volatile private var todayCachedAt = 0L
    @Volatile private var todayCached: String? = null

    /** 上界取「明天 0 点」：用 Long.MAX_VALUE 会把系统时间异常写入的未来事件也算进今天 */
    fun todayStats(): JSONObject {
        val now = System.currentTimeMillis()
        val cached = todayCached
        if (cached != null && now - todayCachedAt in 0..2000) return JSONObject(cached)
        val value = stats(startOfToday(), startOfTomorrow())
        todayCached = value.toString(); todayCachedAt = now
        return value
    }

    // status() 每秒都会调用，这里直接计数，不再解析今天的全部事件
    fun roundsDoneToday(): Int = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE type='quiz_pass' AND ts>=?", arrayOf(startOfToday().toString())).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun historyDays(days: Int = 7): JSONArray {
        val out = JSONArray()
        for (d in 0 until days.coerceIn(1, 90)) {
            val cal = Calendar.getInstance()
            cal.timeInMillis = startOfToday()
            cal.add(Calendar.DAY_OF_YEAR, -d)
            val start = cal.timeInMillis
            val label = if (d == 0) "今天" else if (d == 1) "昨天" else "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}"
            cal.add(Calendar.DAY_OF_YEAR, 1)
            out.put(stats(start, cal.timeInMillis).put("label", label))
        }
        return out
    }

    fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun startOfTomorrow(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
}
