package com.studyontv.tv

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * kv 表上的读写与家长配置的校验。
 *
 * 从 LocalStore 拆出来，因为它同时承担了三类职责：kv 通用读写、配置解析与默认值、
 * 保存时的范围校验，合计三百多行，混在数据库持有者里很难看出哪些是热路径。
 *
 * 并发：`config()` 的结果会被缓存成共享实例。GateService 每秒多次调用，而配置只在
 * `saveConfig()` 时变化，所以用递增版本号做失效判断 —— 命中缓存时只有一次字段读取，
 * 不需要回读 kv（回读本身也是一次 SQL 查询，占了这个热路径的大头）。
 */
class ConfigStore(private val store: LocalStore) {

    companion object {
        val DEFAULT_PRAISE = listOf("太棒了！", "答对啦！", "完全正确！", "真厉害！", "好样的，继续保持！", "漂亮！你越来越熟练了")
        val DEFAULT_ENCOURAGE = listOf("没关系，记住正确答案就好", "差一点点，下一题加油！", "错误也是学习的一部分", "别灰心，再接再厉！")
    }

    fun get(key: String, default: String = ""): String =
        store.readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) ?: default else default
        }

    fun set(key: String, value: String) {
        store.writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply {
            put("k", key); put("v", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        // 写配置就让缓存失效；用版本号而不是回读原始串，避免每次 config() 都查一次库
        if (key == "config") configVersion.incrementAndGet()
    }

    fun adminToken(): String = get("admin_token").ifEmpty {
        UUID.randomUUID().toString().also { set("admin_token", it) }
    }

    private val configVersion = AtomicInteger(0)
    @Volatile private var cachedConfigVersion = -1
    @Volatile private var cachedConfig: JSONObject? = null

    /** 注意：返回的对象是共享实例，调用方不得修改（需要改请先复制）。 */
    fun config(): JSONObject {
        val version = configVersion.get()
        val cached = cachedConfig
        if (cached != null && cachedConfigVersion == version) return cached
        val value = buildConfig(get("config", ""))
        cachedConfig = value
        cachedConfigVersion = version
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
        val saved = jsonObject(raw)
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
        val migrated = WeekSlots.normalize(saved.optJSONArray("weekSlots"))
            ?: if (saved.has("allDay") || saved.has("windowStart") || saved.has("windowEnd")) {
                val oldDefault = !defaults.optBoolean("allDay") &&
                    defaults.optString("windowStart") == "18:30" && defaults.optString("windowEnd") == "20:00"
                if (oldDefault) { defaults.put("allDay", true); WeekSlots.fullWeek() }
                else WeekSlots.fromWindow(defaults.optBoolean("allDay"), defaults.optString("windowStart"), defaults.optString("windowEnd"))
            } else WeekSlots.fullWeek()
        defaults.put("weekSlots", migrated)
        // 只修正非法值，不要顺带清空家长配置的答题范围（历史上这里是一行链式调用，会误清空）
        if (defaults.optString("holidayMode") !in listOf("sunday", "normal")) defaults.put("holidayMode", "sunday")
        return defaults
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
            val clean = WeekSlots.normalize(input.optJSONArray("weekSlots")) ?: throw IllegalArgumentException("可用时段格式不正确")
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
}
