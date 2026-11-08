package com.studyontv.tv

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 题库的读写。
 *
 * 抽取 / 导入时逐条校验，靠 `require` 抛出可读的中文提示 —— LanServer 会把
 * IllegalArgumentException 的 message 直接回给家长面板，所以这里的文案就是给用户看的。
 */
class QuestionsDao(private val store: LocalStore) {

    fun questions(subject: String? = null, level: String? = null, limit: Int = 200, spaced: Boolean = false,
                   enabledOnly: Boolean = true, sortBy: String = "random",
                   subjects: List<String> = emptyList(), levels: List<String> = emptyList()): JSONArray {
        val where = StringBuilder(if (enabledOnly) "enabled=1" else "1=1")
        val args = mutableListOf<String>()
        if (!subject.isNullOrEmpty()) { where.append(" AND subject=?"); args.add(subject) }
        if (!level.isNullOrEmpty()) { where.append(" AND level=?"); args.add(level) }
        // 多科目 / 多年级：仍然用参数绑定，只动态拼占位符
        if (subjects.isNotEmpty()) {
            where.append(" AND subject IN (").append(subjects.joinToString(",") { "?" }).append(")")
            args.addAll(subjects)
        }
        if (levels.isNotEmpty()) {
            where.append(" AND level IN (").append(levels.joinToString(",") { "?" }).append(")")
            args.addAll(levels)
        }
        val out = JSONArray()
        val source = if (spaced) "questions LEFT JOIN question_progress progress ON questions.id=progress.qid" else "questions"
        val order = if (spaced) "CASE WHEN progress.due_at<=${System.currentTimeMillis()} THEN 0 WHEN progress.qid IS NULL THEN 1 ELSE 2 END, progress.due_at ASC, RANDOM()"
            else when (sortBy) { "newest" -> "questions.created_at DESC, questions.rowid DESC"; "oldest" -> "questions.created_at ASC, questions.rowid ASC"; else -> "RANDOM()" }
        store.readableDatabase.rawQuery("SELECT questions.* FROM $source WHERE $where ORDER BY $order LIMIT ${limit.coerceIn(1, 5000)}", args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val details = c.getString(c.getColumnIndexOrThrow("details"))
                val item = if (details.isNullOrEmpty()) JSONObject() else JSONObject(details)
                item.put("id", c.getString(c.getColumnIndexOrThrow("id")))
                    .put("q", c.getString(c.getColumnIndexOrThrow("stem")))
                    .put("a", JSONArray(c.getString(c.getColumnIndexOrThrow("options"))))
                    .put("k", c.getInt(c.getColumnIndexOrThrow("answer")))
                    .put("difficulty", c.getInt(c.getColumnIndexOrThrow("difficulty")))
                    .put("tip", c.getString(c.getColumnIndexOrThrow("tip")) ?: "")
                    .put("subject", c.getString(c.getColumnIndexOrThrow("subject")) ?: "")
                    .put("level", c.getString(c.getColumnIndexOrThrow("level")) ?: "")
                    // 必须回传 enabled：管理端靠它区分「启用 / 已暂停」，
                    // 否则暂停的题在页面上永远看不到，用户会以为题目丢了。
                    .put("enabled", c.getInt(c.getColumnIndexOrThrow("enabled")) != 0)
                if (!item.has("type")) item.put("type", "single")
                out.put(item)
            }
        }
        return out
    }

    fun countQuestions(): Int = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM questions WHERE enabled=1", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun upsertQuestion(item: JSONObject): String {
        val stem = item.optString("q").trim()
        require(stem.isNotEmpty() && stem.length <= 5000) { "请填写题干" }
        val type = item.optString("type", "single")
        require(type in listOf("single", "multiple", "boolean", "text", "subjective")) { "不支持此题型" }
        val options = if (type == "boolean") JSONArray(listOf("正确", "错误")) else item.optJSONArray("a") ?: JSONArray()
        if (type != "text" && type != "subjective") {
            require(options.length() in 2..8) { "需要 2–8 个选项" }
            for (i in 0 until options.length()) require(options.getString(i).isNotBlank()) { "选项不能为空" }
        }
        val indices = if (type == "multiple") item.optJSONArray("correctIndices") ?: JSONArray()
            else JSONArray().put(item.optInt("k", -1))
        if (type != "text" && type != "subjective") {
            require(indices.length() > 0) { "请选择正确答案" }
            for (i in 0 until indices.length()) require(indices.getInt(i) in 0 until options.length()) { "答案序号超出选项范围" }
        } else if (type == "text") {
            val answers = item.optJSONArray("answers") ?: JSONArray()
            require(answers.length() > 0) { "填空题需要至少一个可接受的答案" }
            for (i in 0 until answers.length()) require(answers.getString(i).isNotBlank()) { "答案不能为空" }
        }
        val difficulty = item.optInt("difficulty", 2)
        require(difficulty in 1..5) { "难度需要为 1–5 星" }
        val id = item.optString("id").ifEmpty { "q_" + UUID.randomUUID().toString() }
        val details = JSONObject(item.toString()).put("type", type)
        store.writableDatabase.insertWithOnConflict("questions", null, ContentValues().apply {
            put("id", id); put("subject", item.optString("subject", "常识"))
            put("level", item.optString("level", "")); put("difficulty", difficulty)
            put("stem", stem); put("options", options.toString()); put("answer", if (type == "text" || type == "subjective") 0 else indices.getInt(0))
            put("tip", item.optString("tip")); put("enabled", 1); put("details", details.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return id
    }

    fun importQuestions(items: JSONArray): Int {
        require(items.length() in 1..5000) { "每次导入 1–5000 道题" }
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            for (i in 0 until items.length()) upsertQuestion(items.getJSONObject(i))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return items.length()
    }

    fun deleteQuestions(ids: List<String>): Int {
        val db = store.writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            ids.forEach { count += db.delete("questions", "id=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return count
    }

    fun deleteQuestion(id: String): Boolean = store.writableDatabase.delete("questions", "id=?", arrayOf(id)) > 0

    fun setEnabled(ids: List<String>, enabled: Boolean): Int {
        val db = store.writableDatabase; var count = 0
        db.beginTransaction()
        try {
            ids.forEach { id ->
                count += db.update("questions", ContentValues().apply { put("enabled", if (enabled) 1 else 0) }, "id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return count
    }
}
