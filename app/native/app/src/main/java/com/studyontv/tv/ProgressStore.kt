package com.studyontv.tv

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * 间隔重复进度与待批改答卷。
 *
 * 两张表都跟「一次答题的结果」相关，所以放在一起：
 * - question_progress：SM-2 简化实现的复习计划（repetitions / interval / ease / due_at）
 * - reviews：AI 批改的中间态，pending 表示还没结算
 */
class ProgressStore(private val store: LocalStore) {

    fun recordProgress(id: String, quality: Int) {
        var repetitions = 0
        var interval = 0L
        var ease = 2.5
        store.readableDatabase.rawQuery("SELECT repetitions,interval_days,ease FROM question_progress WHERE qid=?", arrayOf(id)).use {
            if (it.moveToFirst()) { repetitions = it.getInt(0); interval = it.getLong(1); ease = it.getDouble(2) }
        }
        val q = quality.coerceIn(0, 5)
        val now = System.currentTimeMillis()
        val next = if (q < 3) { repetitions = 0; interval = 0; now + 10 * 60 * 1000 } else {
            repetitions++
            interval = when (repetitions) { 1 -> 1; 2 -> 6; else -> kotlin.math.round(interval * ease).toLong().coerceIn(1, 36500) }
            now + interval * 86400000L
        }
        ease = (ease + 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)).coerceAtLeast(1.3)
        store.writableDatabase.insertWithOnConflict("question_progress", null, ContentValues().apply {
            put("qid", id); put("repetitions", repetitions); put("interval_days", interval); put("ease", ease)
            put("due_at", next); put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun repetitionStats(): JSONObject = store.readableDatabase.rawQuery(
        "SELECT COUNT(*),SUM(CASE WHEN due_at<=? THEN 1 ELSE 0 END) FROM question_progress JOIN questions ON questions.id=question_progress.qid WHERE questions.enabled=1", arrayOf(System.currentTimeMillis().toString())
    ).use { if (it.moveToFirst()) JSONObject().put("studied", it.getInt(0)).put("due", it.getInt(1)) else JSONObject().put("studied", 0).put("due", 0) }

    fun saveReview(value: JSONObject) {
        store.writableDatabase.insertWithOnConflict("reviews", null, ContentValues().apply {
            put("id", value.getString("id")); put("nonce", value.getJSONObject("input").getString("nonce"))
            put("state", value.optString("state", "pending")); put("payload", value.toString()); put("ts", value.getLong("ts"))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun noteReview(value: JSONObject) {
        store.writableDatabase.update("reviews", ContentValues().apply { put("payload", value.toString()) },
            "id=? AND state='pending'", arrayOf(value.getString("id")))
    }

    /** payload 损坏时按"查无此卷"处理，而不是让整个请求 500 */
    fun review(id: String): JSONObject? = store.readableDatabase.rawQuery("SELECT payload FROM reviews WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) jsonObjectOrNull(it.getString(0)) else null
    }

    fun reviews(state: String): JSONArray {
        val out = JSONArray()
        val filter = if (state == "all") "" else "WHERE state=?"
        store.readableDatabase.rawQuery("SELECT payload FROM reviews $filter ORDER BY ts DESC LIMIT 200", if (state == "all") null else arrayOf(state)).use {
            // 单条损坏就跳过，不要把整页试卷列表带崩
            while (it.moveToNext()) jsonObjectOrNull(it.getString(0))?.let(out::put)
        }
        return out
    }
}
