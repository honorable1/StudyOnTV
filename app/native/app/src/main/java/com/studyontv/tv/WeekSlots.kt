package com.studyontv.tv

import org.json.JSONArray

/**
 * 一周可用时段（7 天 × 48 个半小时槽）的表示与校验。
 *
 * 存储格式是一天一个 48 字符的 "0"/"1" 串，星期从周一开始。
 * 这类纯数据转换和数据库无关，单独放一处，便于对照前端 `WeekSchedule` 的同一套规则。
 */
object WeekSlots {
    const val SLOTS_PER_DAY = 48
    const val DAYS = 7

    /** 由「开始-结束时间 + 全开」生成一周；起始时间晚于结束视为跨零点 */
    fun fromWindow(allDay: Boolean, start: String, end: String): JSONArray {
        fun minutes(value: String): Int? = try {
            value.split(":").let { it[0].toInt() * 60 + it[1].toInt() }
        } catch (_: Exception) { null }
        val from = minutes(start) ?: 18 * 60 + 30
        val to = minutes(end) ?: 20 * 60
        val chars = CharArray(SLOTS_PER_DAY) { index ->
            val minute = index * 30
            val on = allDay || if (from < to) minute in from until to else minute >= from || minute < to
            if (on) '1' else '0'
        }
        val day = String(chars)
        return JSONArray().apply { repeat(DAYS) { put(day) } }
    }

    fun fullWeek(): JSONArray = JSONArray().apply { repeat(DAYS) { put("1".repeat(SLOTS_PER_DAY)) } }

    /** 校验并复制：长度不对或出现 0/1 以外的字符就返回 null（不要静默接受脏数据） */
    fun normalize(raw: JSONArray?): JSONArray? {
        if (raw == null || raw.length() != DAYS) return null
        val out = JSONArray()
        for (i in 0 until DAYS) {
            val day = raw.optString(i)
            if (day.length != SLOTS_PER_DAY || day.any { it != '0' && it != '1' }) return null
            out.put(day)
        }
        return out
    }
}
