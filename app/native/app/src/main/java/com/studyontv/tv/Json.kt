package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject

/**
 * 宽松 JSON 解析。
 *
 * kv 表里存的字符串可能被旧版本写法、断电或人为改动破坏，`JSONObject(raw)` 遇到非法内容
 * 会直接抛 `JSONException`，让整条调用链失败 —— 典型症状是「评分设置坏了以后整个统计页
 * 都加载不出来」。所有读持久化字符串的地方统一走这里。
 */
fun jsonObject(raw: String?): JSONObject =
    try {
        if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
    } catch (_: Exception) {
        JSONObject()
    }

fun jsonArray(raw: String?): JSONArray =
    try {
        if (raw.isNullOrBlank()) JSONArray() else JSONArray(raw)
    } catch (_: Exception) {
        JSONArray()
    }

/** 需要区分「没有配置」和「配置损坏」时用这个：解析失败返回 null */
fun jsonObjectOrNull(raw: String?): JSONObject? =
    try {
        if (raw.isNullOrBlank()) null else JSONObject(raw)
    } catch (_: Exception) {
        null
    }
