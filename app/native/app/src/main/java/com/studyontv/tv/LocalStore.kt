package com.studyontv.tv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

class LocalStore(context: Context) : SQLiteOpenHelper(context, "studyontv.db", null, 5) {
    companion object {
        val DEFAULT_PRAISE = listOf("太棒了！", "答对啦！", "完全正确！", "真厉害！", "好样的，继续保持！", "漂亮！你越来越熟练了")
        val DEFAULT_ENCOURAGE = listOf("没关系，记住正确答案就好", "差一点点，下一题加油！", "错误也是学习的一部分", "别灰心，再接再厉！")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE questions(id TEXT PRIMARY KEY, subject TEXT, level TEXT, difficulty INTEGER, stem TEXT NOT NULL, options TEXT NOT NULL, answer INTEGER NOT NULL, tip TEXT, enabled INTEGER DEFAULT 1, created_at INTEGER, details TEXT)")
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, payload TEXT, ts INTEGER)")
        db.execSQL("CREATE INDEX idx_events_ts ON events(ts)")
        createReviews(db)
        createProgress(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE questions ADD COLUMN details TEXT")
        if (oldVersion < 3) createReviews(db)
        if (oldVersion < 4) createProgress(db)
        if (oldVersion < 5) compactUsage(db)
    }

    /**
     * 旧版本每秒写一条 usage 事件，一天可达上万行，导致 /api/state、成长记录越来越慢（Issue #2：后台加载卡）。
     * 升级时按小时合并成一条。
     */
    private fun compactUsage(db: SQLiteDatabase) {
        val buckets = LinkedHashMap<Long, Long>()
        db.rawQuery("SELECT payload,ts FROM events WHERE type='usage'", null).use { c ->
            while (c.moveToNext()) {
                val ms = try { JSONObject(c.getString(0)).optLong("milliseconds") } catch (_: Exception) { 0L }
                val hour = c.getLong(1) / 3600000 * 3600000
                buckets[hour] = (buckets[hour] ?: 0L) + ms
            }
        }
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM events WHERE type='usage'")
            buckets.forEach { (hour, ms) ->
                if (ms > 0) db.execSQL("INSERT INTO events(type,payload,ts) VALUES(?,?,?)", arrayOf<Any?>("usage", JSONObject().put("milliseconds", ms).toString(), hour))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun createProgress(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE question_progress(qid TEXT PRIMARY KEY, repetitions INTEGER NOT NULL, interval_days INTEGER NOT NULL, ease REAL NOT NULL, due_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_question_due ON question_progress(due_at)")
    }

    fun recordProgress(id: String, quality: Int) {
        var repetitions = 0
        var interval = 0L
        var ease = 2.5
        readableDatabase.rawQuery("SELECT repetitions,interval_days,ease FROM question_progress WHERE qid=?", arrayOf(id)).use {
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
        writableDatabase.insertWithOnConflict("question_progress", null, ContentValues().apply {
            put("qid", id); put("repetitions", repetitions); put("interval_days", interval); put("ease", ease)
            put("due_at", next); put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun repetitionStats(): JSONObject = readableDatabase.rawQuery(
        "SELECT COUNT(*),SUM(CASE WHEN due_at<=? THEN 1 ELSE 0 END) FROM question_progress JOIN questions ON questions.id=question_progress.qid WHERE questions.enabled=1", arrayOf(System.currentTimeMillis().toString())
    ).use { if (it.moveToFirst()) JSONObject().put("studied", it.getInt(0)).put("due", it.getInt(1)) else JSONObject().put("studied", 0).put("due", 0) }

    private fun createReviews(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE reviews(id TEXT PRIMARY KEY, nonce TEXT UNIQUE, state TEXT NOT NULL, payload TEXT NOT NULL, ts INTEGER NOT NULL)")
    }

    fun saveReview(value: JSONObject) {
        writableDatabase.insertWithOnConflict("reviews", null, ContentValues().apply {
            put("id", value.getString("id")); put("nonce", value.getJSONObject("input").getString("nonce"))
            put("state", value.optString("state", "pending")); put("payload", value.toString()); put("ts", value.getLong("ts"))
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun noteReview(value: JSONObject) {
        writableDatabase.update("reviews", ContentValues().apply { put("payload", value.toString()) },
            "id=? AND state='pending'", arrayOf(value.getString("id")))
    }

    fun review(id: String): JSONObject? = readableDatabase.rawQuery("SELECT payload FROM reviews WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }

    fun reviews(state: String): JSONArray {
        val out = JSONArray()
        val filter = if (state == "all") "" else "WHERE state=?"
        readableDatabase.rawQuery("SELECT payload FROM reviews $filter ORDER BY ts DESC LIMIT 200", if (state == "all") null else arrayOf(state)).use {
            while (it.moveToNext()) out.put(JSONObject(it.getString(0)))
        }
        return out
    }

    fun get(key: String, default: String = ""): String =
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) ?: default else default
        }

    fun set(key: String, value: String) {
        writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply {
            put("k", key); put("v", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun adminToken(): String = get("admin_token").ifEmpty {
        UUID.randomUUID().toString().also { set("admin_token", it) }
    }

    /**
     * 配置的解析结果会缓存。GateService 每秒调用多次 config()，每次都重新解析 JSON、
     * 跑 3 条正则、归一化周表，在电视上是明显的浪费；配置只在 saveConfig() 时变化，
     * 所以用 kv 里的原始字符串作为缓存键即可正确失效。
     * 注意：返回的对象是共享实例，调用方不得修改（需要改请先复制）。
     */
    @Volatile private var cachedConfigRaw: String? = null
    @Volatile private var cachedConfig: JSONObject? = null

    fun config(): JSONObject {
        val raw = get("config", "")
        val cached = cachedConfig
        if (cached != null && cachedConfigRaw == raw) return cached
        val value = buildConfig(raw)
        cachedConfig = value
        cachedConfigRaw = raw
        return value
    }

    private fun buildConfig(raw: String): JSONObject {
        val defaults = JSONObject().put("dailyMinutes", 40).put("dailyFreeMinutes", 0).put("sessionMinutes", 10)
            .put("questionsPerRound", 5).put("roundsPerDay", 3).put("passRatio", 0.6)
            .put("windowStart", "18:30").put("windowEnd", "20:00").put("allDay", true).put("spacedRepetitionEnabled", false)
            .put("playMode", "round").put("extraEnabled", true)
            .put("wrongThreshold", 3).put("extraQuestions", 2)
            .put("difficultyRewardEnabled", false)
            .put("difficultyRewards", JSONObject().put("1", 1).put("2", 2).put("3", 3).put("4", 4).put("5", 5))
            .put("praisePhrases", JSONArray(DEFAULT_PRAISE)).put("encouragePhrases", JSONArray(DEFAULT_ENCOURAGE))
            .put("autoNextSeconds", 0).put("scheduleMode", "allow").put("blockSettingsDuringControl", true).put("theme", if (BuildConfig.FLAVOR == "tv") "dark" else "system")
            .put("holidayMode", "sunday")
            .put("quizSubjects", JSONArray()).put("quizLevels", JSONArray())
            .put("version", 1)
        val saved = try { JSONObject(raw.ifEmpty { "{}" }) } catch (e: Exception) { JSONObject() }
        saved.keys().forEach { defaults.put(it, saved.get(it)) }
        // 旧版的四种配色主题合并为深色 / 浅色
        defaults.put("theme", when (defaults.optString("theme")) { "light", "daylight" -> "light"; "system" -> "system"; "dark", "midnight", "nord", "amber" -> "dark"; else -> if (BuildConfig.FLAVOR == "tv") "dark" else "system" })
        val timePattern = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")
        val validStart = timePattern.matches(defaults.optString("windowStart"))
        val validEnd = timePattern.matches(defaults.optString("windowEnd"))
        if (!validStart) defaults.put("windowStart", "18:30")
        if (!validEnd) defaults.put("windowEnd", "20:00")
        if (!saved.has("allDay") && validStart && validEnd && saved.optString("windowStart") == saved.optString("windowEnd")) defaults.put("allDay", true)
        // 旧版的单个时段自动迁移成一周七天；新版按 30 分钟格子保存
        val migrated = normalizeWeek(saved.optJSONArray("weekSlots"))
            ?: if (saved.has("allDay") || saved.has("windowStart") || saved.has("windowEnd")) {
                val oldDefault = !defaults.optBoolean("allDay") &&
                    defaults.optString("windowStart") == "18:30" && defaults.optString("windowEnd") == "20:00"
                if (oldDefault) { defaults.put("allDay", true); fullWeek() }
                else weekFromWindow(defaults.optBoolean("allDay"), defaults.optString("windowStart"), defaults.optString("windowEnd"))
            } else fullWeek()
        defaults.put("weekSlots", migrated)
        // 只修正非法值，不要顺带清空家长配置的答题范围（历史上这里是一行链式调用，会误清空）
        if (defaults.optString("holidayMode") !in listOf("sunday", "normal")) defaults.put("holidayMode", "sunday")
        return defaults
    }

    private fun weekFromWindow(allDay: Boolean, start: String, end: String): JSONArray {
        fun minutes(value: String): Int? = try { value.split(":").let { it[0].toInt() * 60 + it[1].toInt() } } catch (_: Exception) { null }
        val from = minutes(start) ?: 18 * 60 + 30
        val to = minutes(end) ?: 20 * 60
        val chars = CharArray(48) { index ->
            val minute = index * 30
            val on = allDay || if (from < to) minute in from until to else minute >= from || minute < to
            if (on) '1' else '0'
        }
        val day = String(chars)
        return JSONArray().apply { repeat(7) { put(day) } }
    }

    private fun fullWeek(): JSONArray = JSONArray().apply { repeat(7) { put("1".repeat(48)) } }

    /** 旧版默认周末时段，仅用于迁移判断 */
    private fun weekendDefault(): JSONArray {
        val empty = "0".repeat(48)
        val weekend = weekFromWindow(false, "09:00", "20:00").optString(0)
        return JSONArray().apply { repeat(5) { put(empty) }; put(weekend); put(weekend) }
    }

    private fun normalizeWeek(raw: JSONArray?): JSONArray? {
        if (raw == null || raw.length() != 7) return null
        val out = JSONArray()
        for (i in 0 until 7) {
            val day = raw.optString(i)
            if (day.length != 48 || day.any { it != '0' && it != '1' }) return null
            out.put(day)
        }
        return out
    }

    fun saveConfig(input: JSONObject): JSONObject {
        // config() 返回的是缓存实例，这里要复制一份再改，避免污染缓存
        val next = JSONObject(config().toString())
        val ranges = mapOf("dailyMinutes" to 1..480, "dailyFreeMinutes" to 0..120, "sessionMinutes" to 1..120,
            "questionsPerRound" to 1..100, "roundsPerDay" to 1..100,
            "wrongThreshold" to 1..100, "extraQuestions" to 1..100)
        ranges.forEach { (key, range) ->
            if (input.has(key)) {
                val value = input.getDouble(key)
                require(value.isFinite() && value == value.toInt().toDouble() && value.toInt() in range) { "$key 超出范围" }
                next.put(key, value.toInt())
            }
        }
        val wantsAllDay = if (input.has("allDay")) input.getBoolean("allDay") else next.optBoolean("allDay")
        listOf("windowStart", "windowEnd").forEach { key ->
            if (input.has(key)) {
                val valid = Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(input.optString(key))
                require(wantsAllDay || valid) { "请输入有效的开始和结束时间" }
                if (valid) next.put(key, input.getString(key))
            }
        }
        if (input.has("passRatio")) {
            val ratio = input.getDouble("passRatio")
            require(ratio.isFinite() && ratio > 0 && ratio <= 1) { "答对比例需要大于 0 且不超过 100%" }
            next.put("passRatio", ratio)
        }
        if (input.has("playMode")) {
            require(input.getString("playMode") in listOf("round", "streak", "practice")) { "未知玩法" }
            next.put("playMode", input.getString("playMode"))
        }
        listOf("extraEnabled", "difficultyRewardEnabled", "allDay", "spacedRepetitionEnabled", "blockSettingsDuringControl").forEach {
            if (input.has(it)) next.put(it, input.getBoolean(it))
        }
        for (key in listOf("praisePhrases", "encouragePhrases")) if (input.has(key)) {
            val raw = input.getJSONArray(key)
            val clean = JSONArray()
            for (i in 0 until raw.length()) raw.optString(i).trim().takeIf { it.isNotEmpty() }?.let {
                require(it.length <= 40) { "每句鼓励语不超过 40 个字" }
                clean.put(it)
            }
            require(clean.length() <= 30) { "鼓励语最多 30 句" }
            next.put(key, if (clean.length() == 0) JSONArray(if (key == "praisePhrases") DEFAULT_PRAISE else DEFAULT_ENCOURAGE) else clean)
        }
        if (input.has("scheduleMode")) {
            require(input.getString("scheduleMode") in listOf("allow", "control")) { "未知的时段用途" }
            next.put("scheduleMode", input.getString("scheduleMode"))
        }
        for (key in listOf("quizSubjects", "quizLevels")) if (input.has(key)) {
            val raw = input.optJSONArray(key) ?: JSONArray()
            val clean = JSONArray()
            for (i in 0 until minOf(raw.length(), 50)) raw.optString(i).trim().takeIf { it.isNotEmpty() && it.length <= 32 }?.let { value ->
                if (!(0 until clean.length()).any { clean.optString(it) == value }) clean.put(value)
            }
            next.put(key, clean)
        }
        if (input.has("weekSlots")) {
            val clean = normalizeWeek(input.optJSONArray("weekSlots")) ?: throw IllegalArgumentException("可用时段格式不正确")
            // 七天全关等于设备永久不可用，前端会显示"暂无可用时段"，几乎总是误操作
            require(next.optBoolean("allDay") || (0 until 7).any { clean.optString(it).contains('1') }) {
                "可用时段不能全部关闭；需要临时停用请用「暂停使用」"
            }
            next.put("weekSlots", clean)
        }
        if (input.has("holidayMode")) {
            require(input.getString("holidayMode") in listOf("sunday", "normal")) { "未知的节假日规则" }
            next.put("holidayMode", input.getString("holidayMode"))
        }
        if (input.has("theme")) {
            require(input.getString("theme") in listOf("dark", "light", "system")) { "外观需要为深色、浅色或跟随系统" }
            next.put("theme", input.getString("theme"))
        }
        if (input.has("autoNextSeconds")) {
            val value = input.getInt("autoNextSeconds")
            require(value in 0..30) { "自动下一题需要为 0–30 秒，0 表示手动" }
            next.put("autoNextSeconds", value)
        }
        if (input.has("difficultyRewards")) {
            val rewards = input.getJSONObject("difficultyRewards")
            for (star in 1..5) {
                val minutes = rewards.getDouble(star.toString())
                require(minutes.isFinite() && minutes in 0.1..120.0) { "每题奖励需要为 0.1–120 分钟" }
            }
            next.put("difficultyRewards", rewards)
        }
        next.put("version", next.optInt("version", 1) + 1)
        set("config", next.toString())
        return next
    }

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
        readableDatabase.rawQuery("SELECT questions.* FROM $source WHERE $where ORDER BY $order LIMIT ${limit.coerceIn(1, 5000)}", args.toTypedArray()).use { c ->
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
                if (!item.has("type")) item.put("type", "single")
                out.put(item)
            }
        }
        return out
    }

    fun countQuestions(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM questions WHERE enabled=1", null).use {
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
        writableDatabase.insertWithOnConflict("questions", null, ContentValues().apply {
            put("id", id); put("subject", item.optString("subject", "常识"))
            put("level", item.optString("level", "")); put("difficulty", difficulty)
            put("stem", stem); put("options", options.toString()); put("answer", if (type == "text" || type == "subjective") 0 else indices.getInt(0))
            put("tip", item.optString("tip")); put("enabled", 1); put("details", details.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        return id
    }

    fun importQuestions(items: JSONArray): Int {
        require(items.length() in 1..5000) { "每次导入 1–5000 道题" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (i in 0 until items.length()) upsertQuestion(items.getJSONObject(i))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return items.length()
    }

    fun deleteQuestions(ids: List<String>): Int {
        val db = writableDatabase
        var count = 0
        db.beginTransaction()
        try {
            ids.forEach { count += db.delete("questions", "id=?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return count
    }

    fun deleteQuestion(id: String): Boolean = writableDatabase.delete("questions", "id=?", arrayOf(id)) > 0

    fun setEnabled(ids: List<String>, enabled: Boolean): Int {
        val db = writableDatabase; var count = 0
        db.beginTransaction()
        try {
            ids.forEach { id ->
                count += db.update("questions", android.content.ContentValues().apply { put("enabled", if (enabled) 1 else 0) }, "id=?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return count
    }

    fun logEvent(type: String, payload: JSONObject) {
        writableDatabase.execSQL("INSERT INTO events(type,payload,ts) VALUES(?,?,?)", arrayOf<Any?>(type, payload.toString(), System.currentTimeMillis()))
        todayCached = null      // 当天统计缓存立即失效
    }

    fun recentEvents(limit: Int = 50): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery("SELECT type,payload,ts FROM events WHERE type!='usage' ORDER BY id DESC LIMIT ${limit.coerceIn(1, 500)}", null).use { c ->
            while (c.moveToNext()) out.put(JSONObject().put("type", c.getString(0)).put("payload", JSONObject(c.getString(1))).put("ts", c.getLong(2)))
        }
        return out
    }

    /** SQLite 3.9+ 自带 json1 扩展；老系统上没有时回落到逐行解析 */
    private val json1Available: Boolean by lazy {
        try {
            readableDatabase.rawQuery("""SELECT json_extract('{"a":1}','$.a')""", null).use { it.moveToFirst() }
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

    /** 用 SQL 聚合代替把当天每一行事件反序列化一遍（面板每 2 秒轮询一次 /api/state） */
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
        readableDatabase.rawQuery(sql, args).use { c ->
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
        readableDatabase.rawQuery("SELECT type,payload FROM events WHERE ts>=? AND ts<?", args).use { c ->
            while (c.moveToNext()) {
                val p = try { JSONObject(c.getString(1)) } catch (e: Exception) { JSONObject() }
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
    fun roundsDoneToday(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE type='quiz_pass' AND ts>=?", arrayOf(startOfToday().toString())).use {
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
