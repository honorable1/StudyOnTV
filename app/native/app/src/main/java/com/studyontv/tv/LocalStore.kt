package com.studyontv.tv

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * 数据库持有者，同时充当门面。
 *
 * 这个类原来有 467 行，把四件事混在一起：建表迁移、kv 配置、题库 DAO、事件统计、
 * 复习进度。改动其中任何一处都要在几百行里翻找，而且看不出哪些是每秒都会跑的热路径。
 * 现在按职责拆成四个协作类，本类只保留两件职责：
 *
 * 1. 作为 `SQLiteOpenHelper` 持有连接，并把建表/迁移转发给 [StoreSchema]
 * 2. 提供与拆分前完全一致的方法签名，原有调用点一行都不用改
 *
 * 新代码可以直接用 [configs] / [questionsDao] / [usage] / [progress]；
 * 老代码继续走转发方法也不会多出开销（只是一次值转发）。
 *
 * 拆分后各部分的职责边界：
 * | 类 | 表 | 负责 |
 * |---|---|---|
 * | [ConfigStore] | kv | 配置读写、默认值合并、保存校验、热路径缓存 |
 * | [QuestionsDao] | questions | 题库查询、增删改、导入 |
 * | [UsageLog] | events | 事件写入、按日统计、历史趋势 |
 * | [ProgressStore] | question_progress / reviews | SM-2 复习计划、待批改答卷 |
 */
class LocalStore(context: Context) : SQLiteOpenHelper(context, "studyontv.db", null, StoreSchema.VERSION) {

    // 表结构与迁移都在 StoreSchema 里，这里只做转发
    override fun onCreate(db: SQLiteDatabase) = StoreSchema.onCreate(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        StoreSchema.onUpgrade(db, oldVersion, newVersion)

    val configs: ConfigStore by lazy { ConfigStore(this) }
    val questionsDao: QuestionsDao by lazy { QuestionsDao(this) }
    val usage: UsageLog by lazy { UsageLog(this) }
    val progress: ProgressStore by lazy { ProgressStore(this) }

    // ---------------------------------------------------------------- 配置

    fun get(key: String, default: String = ""): String = configs.get(key, default)

    fun set(key: String, value: String) = configs.set(key, value)

    fun adminToken(): String = configs.adminToken()

    /** 返回共享缓存实例，调用方不得修改。 */
    fun config(): JSONObject = configs.config()

    fun saveConfig(input: JSONObject): JSONObject = configs.saveConfig(input)

    // ---------------------------------------------------------------- 题库

    fun questions(subject: String? = null, level: String? = null, limit: Int = 200, spaced: Boolean = false,
                  enabledOnly: Boolean = true, sortBy: String = "random",
                  subjects: List<String> = emptyList(), levels: List<String> = emptyList()): JSONArray =
        questionsDao.questions(subject, level, limit, spaced, enabledOnly, sortBy, subjects, levels)

    fun countQuestions(): Int = questionsDao.countQuestions()

    fun upsertQuestion(item: JSONObject): String = questionsDao.upsertQuestion(item)

    fun importQuestions(items: JSONArray): Int = questionsDao.importQuestions(items)

    fun deleteQuestions(ids: List<String>): Int = questionsDao.deleteQuestions(ids)

    fun deleteQuestion(id: String): Boolean = questionsDao.deleteQuestion(id)

    fun setEnabled(ids: List<String>, enabled: Boolean): Int = questionsDao.setEnabled(ids, enabled)

    // ---------------------------------------------------------------- 事件与统计

    fun logEvent(type: String, payload: JSONObject) = usage.logEvent(type, payload)

    fun recentEvents(limit: Int = 50): JSONArray = usage.recentEvents(limit)

    fun stats(start: Long, end: Long): JSONObject = usage.stats(start, end)

    fun todayStats(): JSONObject = usage.todayStats()

    fun roundsDoneToday(): Int = usage.roundsDoneToday()

    fun historyDays(days: Int = 7): JSONArray = usage.historyDays(days)

    fun startOfToday(): Long = usage.startOfToday()

    fun startOfTomorrow(): Long = usage.startOfTomorrow()

    // ---------------------------------------------------------------- 复习进度

    fun recordProgress(id: String, quality: Int) = progress.recordProgress(id, quality)

    fun repetitionStats(): JSONObject = progress.repetitionStats()

    fun saveReview(value: JSONObject) = progress.saveReview(value)

    fun noteReview(value: JSONObject) = progress.noteReview(value)

    fun review(id: String): JSONObject? = progress.review(id)

    fun reviews(state: String): JSONArray = progress.reviews(state)
}
