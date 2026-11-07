package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Calendar
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.min

class LearningEngine(private val store: LocalStore) {
    private fun number(key: String) = store.get(key, "0").toLongOrNull() ?: 0L
    private fun put(key: String, value: Long) = store.set(key, value.toString())

    /**
     * 余额先记在内存里，按「累计 ≥30 秒 / 距上次落盘 ≥10 秒 / 余额用尽 / 进程退出」四个时机写库。
     * 以前每秒一个 SQLite 写事务，在低端电视上是主要的卡顿与耗电来源。
     */
    private var pendingCredit = 0L      // 已扣减、尚未写进 credit_ms
    private var pendingUsed = 0L        // 已增加、尚未写进 used_ms
    private var pendingFree = 0L        // 已消耗、尚未写进 free_quota_ms
    private var pendingUsageMs = 0L     // 尚未写进 events 的用量
    private var lastPersistAt = 0L

    private fun creditMs() = (number("credit_ms") - pendingCredit).coerceAtLeast(0)
    private fun usedMs() = number("used_ms") + pendingUsed
    private fun freeQuotaMs() = (number("free_quota_ms") - pendingFree).coerceAtLeast(0)

    @Synchronized
    private fun persistPending() {
        if (pendingCredit == 0L && pendingUsed == 0L && pendingFree == 0L && pendingUsageMs == 0L) return
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            if (pendingCredit != 0L) {
                val value = (number("credit_ms") - pendingCredit).coerceAtLeast(0); pendingCredit = 0
                store.set("credit_ms", value.toString())
            }
            if (pendingUsed != 0L) {
                val value = number("used_ms") + pendingUsed; pendingUsed = 0
                store.set("used_ms", value.toString())
            }
            if (pendingFree != 0L) {
                val value = (number("free_quota_ms") - pendingFree).coerceAtLeast(0); pendingFree = 0
                store.set("free_quota_ms", value.toString())
            }
            if (pendingUsageMs > 0) {
                val ms = pendingUsageMs; pendingUsageMs = 0
                store.logEvent("usage", JSONObject().put("milliseconds", ms))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        lastPersistAt = System.currentTimeMillis()
    }

    private fun persistIfDue() {
        val now = System.currentTimeMillis()
        if (pendingUsageMs >= 30000 || now - lastPersistAt >= 10000) persistPending()
    }

    private fun day() {
        val current = store.startOfToday()
        if (number("usage_day") != current) {
            persistPending()
            put("usage_day", current)
            put("credit_ms", 0)
            put("used_ms", 0)
            // 每天重置时，把"默认免费时长"存入 free_quota_ms（和答题奖励分开记）
            val freeMin = store.config().optLong("dailyFreeMinutes", 0L)
            put("free_quota_ms", freeMin * 60000L)
        }
    }

    private fun weekSlots(config: JSONObject): List<String>? {
        val raw = config.optJSONArray("weekSlots") ?: return null
        if (raw.length() != 7) return null
        val list = (0 until 7).map { raw.optString(it) }
        return list.takeIf { days -> days.all { it.length == 48 && it.all { ch -> ch == '0' || ch == '1' } } }
    }

    /** 周一 = 0 … 周日 = 6；法定节假日可以按周日安排 */
    private fun dayIndex(config: JSONObject, cal: Calendar): Int {
        if (config.optString("holidayMode", "sunday") == "sunday") {
            val holiday = try { HolidayService.isHoliday(DeviceRuntime.appContext, cal) } catch (_: Throwable) { false }
            if (holiday) return 6
        }
        return (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    }

    private fun allowedAt(config: JSONObject, cal: Calendar): Boolean {
        if (config.optBoolean("allDay")) return true
        val slots = weekSlots(config) ?: return true
        val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return slots[dayIndex(config, cal)].getOrNull(minute / 30) == '1'
    }

    private fun inWindow(config: JSONObject): Boolean = allowedAt(config, Calendar.getInstance())

    /** @return 下一个可用时间戳；规则里一周全部关闭时返回 -1（前端据此提示"请联系家长调整规则"） */
    private fun nextAvailable(config: JSONObject, tomorrow: Boolean): Long {
        val base = Calendar.getInstance()
        if (tomorrow) {
            base.add(Calendar.DAY_OF_YEAR, 1)
            base.set(Calendar.HOUR_OF_DAY, 0); base.set(Calendar.MINUTE, 0)
        }
        base.set(Calendar.SECOND, 0); base.set(Calendar.MILLISECOND, 0)
        if (config.optBoolean("allDay")) return base.timeInMillis
        val slots = weekSlots(config) ?: return base.timeInMillis
        val now = System.currentTimeMillis()
        for (offset in 0..8) {
            val day = (base.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val index = dayIndex(config, day)
            for (slot in 0 until 48) {
                if (slots[index][slot] != '1') continue
                val candidate = (day.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, slot / 2); set(Calendar.MINUTE, (slot % 2) * 30)
                }.timeInMillis
                if (candidate > now) return candidate
            }
        }
        return -1L
    }

    /**
     * 时段有两种用途（Issue #4）：
     *  allow   —— 只在这段时间内可以使用（原有行为，时段外锁定）
     *  control —— 只在这段时间内需要先答题；时段外完全不管控、不计时（例如白天老人看电视）
     */
    private fun scheduleFree(c: JSONObject): Boolean =
        c.optString("scheduleMode", "allow") == "control" && !c.optBoolean("allDay") && !inWindow(c)

    /**
     * 家长遥控（实时生效，GateService 每秒检查一次）：
     *  unlock —— 临时解锁 N 分钟：不答题、不扣时长，到点自动恢复规则（大人自己看电视）
     *  lock   —— 立即锁定：不论余额与时段都显示锁定页，直到家长解除或到达设定的时间
     * 剩余时间同时记录墙上时间和开机计时：同一次开机内以开机计时为准，孩子改系统时间不能延长解锁。
     */
    @Synchronized
    fun override(): JSONObject? {
        val raw = store.get("remote_override").takeIf { it.isNotEmpty() } ?: return null
        val o = try { JSONObject(raw) } catch (_: Exception) { store.set("remote_override", ""); return null }
        val until = o.optLong("until")
        val mode = o.optString("mode")
        if (mode == "indefinite") return o.put("remainingSeconds", -1) // 无限时，永不过期
        if (until == 0L) return o.put("remainingSeconds", -1)          // 锁定且未设结束时间
        val left = if (o.optInt("boot", -2) == bootCount()) o.optLong("untilElapsed") - android.os.SystemClock.elapsedRealtime()
            else until - System.currentTimeMillis()
        if (left <= 0) {
            store.set("remote_override", "")
            store.logEvent("remote", JSONObject().put("action", "expired").put("mode", o.optString("mode")))
            return null
        }
        return o.put("remainingSeconds", (left + 999) / 1000)
    }

    @Synchronized
    fun remote(action: String, minutes: Int, source: String): JSONObject {
        when (action) {
            "cancel" -> store.set("remote_override", "")
            "indefinite" -> {
                // 无限时解锁：管控完全关闭，直到家长手动取消
                store.set("remote_override", JSONObject().put("mode", "indefinite").put("minutes", 0)
                    .put("at", System.currentTimeMillis()).put("source", source).put("boot", bootCount())
                    .put("until", 0L).put("untilElapsed", 0L).toString())
            }
            "unlock", "lock" -> {
                require(action == "lock" || minutes in 1..24 * 60) { "解锁时长需要 1 分钟到 24 小时" }
                require(minutes in 0..7 * 24 * 60) { "锁定时长不正确" }
                val ms = minutes * 60000L
                store.set("remote_override", JSONObject().put("mode", action).put("minutes", minutes)
                    .put("at", System.currentTimeMillis()).put("source", source).put("boot", bootCount())
                    .put("until", if (ms == 0L) 0L else System.currentTimeMillis() + ms)
                    .put("untilElapsed", if (ms == 0L) 0L else android.os.SystemClock.elapsedRealtime() + ms).toString())
            }
            else -> throw IllegalArgumentException("未知操作")
        }
        flushUsage()
        store.logEvent("remote", JSONObject().put("action", action).put("minutes", minutes).put("source", source))
        return status()
    }

    private fun bootCount(): Int = try {
        android.provider.Settings.Global.getInt(DeviceRuntime.appContext.contentResolver, "boot_count", -1)
    } catch (_: Throwable) { -1 }

    private fun freeNow(c: JSONObject): Boolean {
        val o = override()
        if (o != null) return o.optString("mode") in listOf("unlock", "indefinite")
        return scheduleFree(c)
    }

    @Synchronized
    fun status(): JSONObject {
        day()
        val c = store.config()
        val remaining = creditMs()
        val used = usedMs()
        val rounds = store.roundsDoneToday()
        val o = override()
        val free = freeNow(c)
        val freeQuota = freeQuotaMs()
        val inAllowedWindow = c.optBoolean("allDay") || inWindow(c)
        // 顺序很重要：时段判定必须在「免费额度」之前，否则配了每日免费时长就等于取消了全部时段管控
        val reason = when {
            o?.optString("mode") == "lock" -> "locked"
            free -> ""
            c.optString("scheduleMode", "allow") != "control" && !inAllowedWindow -> "schedule"
            freeQuota > 0 -> ""   // 每日默认免费时长还有余额，放行
            used >= c.getLong("dailyMinutes") * 60000 -> "quota"
            remaining <= 0 && rounds >= c.getInt("roundsPerDay") -> "rounds"
            // 已达组数上限：用完最后一组的奖励时间再锁，但不再允许继续答题卷得更多时间
            rounds >= c.getInt("roundsPerDay") -> ""   // 达组但还有剩余时间，用完才锁
            remaining > 0 -> ""
            else -> "timeup"
        }
        val nextAt = if (reason == "locked") o?.optLong("until") ?: 0L
            else if (reason in listOf("quota", "rounds", "schedule")) nextAvailable(c, reason != "schedule") else 0L
        return JSONObject().put("remainingSeconds", remaining / 1000).put("usedMinutes", used / 60000.0)
            .put("extraPool", number("extra_pool")).put("wrongProgress", number("wrong_progress"))
            .put("nextAvailableAt", nextAt).put("free", free)
            .put("freeQuotaSeconds", freeQuota / 1000)
            .put("roundsDoneToday", rounds).put("reason", reason).put("override", o ?: JSONObject.NULL).put("awaitingReview", active()?.has("pendingReview") == true)
    }

    /** 每秒调用一次。用量先累计到内存，按阈值批量落盘 */
    @Synchronized
    fun consume(milliseconds: Long) {
        day()
        val c = store.config()
        if (freeNow(c)) { persistPending(); return }   // 自由时段不扣时长
        val step = milliseconds.coerceIn(0, 5000)
        if (step <= 0) return
        val cap = c.getLong("dailyMinutes") * 60000
        var room = (cap - usedMs()).coerceAtLeast(0)
        if (room <= 0) { persistPending(); return }
        // 每日默认免费时长：先消耗，但同样不能突破家长设定的每日总时长
        val freeQuotaNow = freeQuotaMs()
        if (freeQuotaNow > 0) {
            val freeConsumed = min(step, min(freeQuotaNow, room))
            if (freeConsumed > 0) {
                pendingFree += freeConsumed; pendingUsed += freeConsumed; pendingUsageMs += freeConsumed
            }
            if (freeQuotaNow - freeConsumed <= 0 || room - freeConsumed <= 0) persistPending() else persistIfDue()
            return
        }
        val amount = min(step, min(creditMs(), room))
        if (amount <= 0) { persistPending(); return }
        pendingCredit += amount; pendingUsed += amount; pendingUsageMs += amount
        if (creditMs() <= 0) persistPending() else persistIfDue()
    }

    @Synchronized
    fun flushUsage() { persistPending() }

    private fun active(): JSONObject? = store.get("active_round").takeIf { it.isNotEmpty() }?.let {
        try { JSONObject(it) } catch (_: Exception) { null }
    }

    private fun nextQuestion(round: JSONObject) {
        val config = store.config()
        fun scope(key: String): List<String> {
            val array = config.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }
        }
        // 科目 / 年级的过滤下推到 SQL，避免每出一题都在内存里过滤并反序列化 5000 条题目
        val bank = store.questions(limit = 5000, spaced = config.optBoolean("spacedRepetitionEnabled"),
            subjects = scope("quizSubjects"), levels = scope("quizLevels"))
        require(bank.length() > 0) {
            if (scope("quizSubjects").isNotEmpty() || scope("quizLevels").isNotEmpty())
                "当前答题范围没有题目，请检查题库的科目 / 年级或调整答题范围" else "题库为空，请由家长添加题目"
        }
        val last = round.optJSONObject("question")?.optString("id")
        var question = bank.getJSONObject(0)
        for (i in 0 until bank.length()) {
            if (bank.getJSONObject(i).optString("id") != last) { question = bank.getJSONObject(i); break }
        }
        round.put("question", question).put("nonce", UUID.randomUUID().toString())
        val extraEnabled = round.optJSONObject("rules")?.optBoolean("extraEnabled", true) ?: true
        // 加练题的唯一判定来源：练习模式看加练池，其它模式看本轮是否已完成
        round.put("extra", if (round.optString("mode") == "practice") number("extra_pool") > 0
            else extraEnabled && round.optInt("answered") >= round.getInt("target"))
    }

    private fun publicRound(round: JSONObject): JSONObject {
        val copy = JSONObject(round.toString())
        val question = copy.getJSONObject("question")
        listOf("k", "answers", "correctIndices", "tip", "modelAnswer", "rubric").forEach { question.remove(it) }
        copy.remove("rules")
        return copy.put("extraPool", number("extra_pool")).put("status", status())
    }

    @Synchronized
    fun start(): JSONObject {
        val st = status()
        val c = store.config()
        if (st.getString("reason") == "locked") return JSONObject().put("blocked", "locked").put("status", st)
        if (st.optBoolean("free") && c.getString("playMode") != "practice")
            return JSONObject().put("free", true).put("status", st)
        val feedbackId = store.get("review_feedback")
        val feedback = feedbackId.takeIf { it.isNotEmpty() }?.let { store.review(it) }
        if (feedback?.optString("state") == "graded") return JSONObject().put("reviewed", true).put("status", st)
            .put("reviewId", feedbackId).put("score", feedback.getDouble("score"))
            .put("feedback", feedback.optString("feedback")).put("result", feedback.getJSONObject("result"))
        if (c.getString("playMode") != "practice" && st.getString("reason") in listOf("quota", "rounds", "schedule"))
            return JSONObject().put("blocked", st.getString("reason")).put("status", st)
        if (st.optLong("remainingSeconds") > 0 && c.getString("playMode") != "practice")
            return JSONObject().put("watching", true).put("status", st)
        var round = active()
        if (round?.has("pendingReview") == true) return JSONObject().put("pending", true).put("reviewId", round.getString("pendingReview")).put("status", st)
        if (round != null && round.optInt("answered") == 0 && round.getJSONObject("rules").optInt("version") != c.optInt("version")) round = null
        if (round == null || round.optString("mode") != c.getString("playMode")) {
            round = JSONObject().put("id", UUID.randomUUID().toString()).put("mode", c.getString("playMode"))
                .put("target", c.getInt("questionsPerRound")).put("answered", 0).put("correct", 0)
                .put("streak", 0).put("earnedMs", 0).put("rules", c)
            nextQuestion(round)
            store.set("active_round", round.toString())
        }
        return publicRound(round)
    }

    @Synchronized
    fun acknowledgeReview(id: String): JSONObject {
        if (store.get("review_feedback") == id) store.set("review_feedback", "")
        return JSONObject().put("ok", true).put("status", status())
    }

    /** 孩子不想继续等 AI/家长批改时，放弃这道题的批改并进入下一题。 */
    @Synchronized
    fun skipPending(): JSONObject {
        val round = active() ?: return JSONObject().put("error", "round_missing")
        val id = round.optString("pendingReview")
        if (id.isEmpty()) return JSONObject().put("pending", false).put("status", status())
        store.review(id)?.let {
            it.put("state", "skipped").put("feedback", "孩子选择先跳过，等待家长处理").put("skippedAt", System.currentTimeMillis())
            store.saveReview(it)
        }
        round.remove("pendingReview")
        nextQuestion(round)
        store.set("active_round", round.toString())
        store.logEvent("review_skipped", JSONObject().put("id", id))
        Diag.warn("Quiz", "review skipped id=$id")
        return publicRound(round)
    }

    @Synchronized
    fun grade(reviewId: String, score: Double, feedback: String, source: String): JSONObject {
        require(score.isFinite() && score in 0.0..100.0) { "分数需要在 0–100 之间" }
        val review = store.review(reviewId) ?: throw IllegalArgumentException("待批题目不存在")
        require(review.getString("state") != "skipped") { "这道题已被跳过，不再批改" }
        if (review.getString("state") == "graded") return review
        val settings = JSONObject(store.get("grading_settings", "{}"))
        val threshold = settings.optDouble("passScore", 60.0)
        val correct = score >= threshold
        val quality = when { !correct -> 1; score >= 90 -> 5; score >= 75 -> 4; else -> 3 }
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            // 批改等待期间 round 可能已经变了（改配置重建 / 跳过 / 重启），此时按 review 里的快照结算，
            // 不能因为 nonce 对不上就把已经批出来的结果丢掉
            val round = active()
            val snapshot = review.optJSONObject("round")
            val input = review.getJSONObject("input")
            val sameRound = round != null && snapshot != null &&
                round.optString("id") == snapshot.optString("id") && round.optString("nonce") == input.optString("nonce")
            val direct = if (sameRound) answer(input, correct, quality) else null
            val result = direct?.takeIf { !it.has("error") && !it.has("blocked") } ?: settle(review, correct, quality)
            review.put("state", "graded").put("score", score).put("feedback", feedback.take(5000))
                .put("source", source).put("gradedAt", System.currentTimeMillis()).put("result", result)
            store.saveReview(review)
            store.set("review_feedback", reviewId)
            store.logEvent("review_graded", JSONObject().put("id", reviewId).put("score", score).put("source", source))
            db.setTransactionSuccessful()
            return review
        } finally { db.endTransaction() }
    }

    /** 按 review 里的题目与轮次快照补结算：不依赖当前 active_round 是否还在 */
    private fun settle(review: JSONObject, correct: Boolean, quality: Int): JSONObject {
        val question = review.optJSONObject("question") ?: JSONObject()
        val snapshot = review.optJSONObject("round")
        val rules = snapshot?.optJSONObject("rules")
        val mode = snapshot?.optString("mode") ?: "round"
        val qid = question.optString("id")
        var finished = false
        var passed = false
        var grant = 0L
        persistPending()
        if (qid.isNotEmpty()) store.recordProgress(qid, quality)
        store.logEvent("answer", JSONObject().put("qid", qid).put("correct", correct)
            .put("difficulty", question.optInt("difficulty", 2)).put("extra", false).put("mode", mode).put("late", true))
        if (snapshot != null && rules != null) {
            val target = snapshot.optInt("target", 1).coerceAtLeast(1)
            val answered = snapshot.optInt("answered", 0) + 1
            val correctCount = snapshot.optInt("correct", 0) + if (correct) 1 else 0
            val streak = if (correct) snapshot.optInt("streak", 0) + 1 else 0
            finished = if (mode == "streak") streak >= target || answered >= target else answered >= target
            passed = mode == "streak" || correctCount >= ceil(target * rules.optDouble("passRatio", 1.0)).toInt()
            if (finished && passed) {
                val requested = if (rules.optBoolean("difficultyRewardEnabled")) snapshot.optLong("earnedMs")
                    else rules.optLong("sessionMinutes") * 60000
                val budget = store.config().getLong("dailyMinutes") * 60000 - number("used_ms") - number("credit_ms")
                grant = min(maxOf(requested, 0L), budget.coerceAtLeast(0))
                if (grant > 0) put("credit_ms", number("credit_ms") + grant)
                store.logEvent("quiz_pass", JSONObject().put("roundId", snapshot.optString("id")).put("minutes", grant / 60000.0))
            }
            if (finished) store.logEvent("quiz", JSONObject().put("native", true).put("total", answered)
                .put("correct", correctCount).put("passed", passed))
            // 结算完成：清掉已经过期的活动轮次，避免下次进来又提示"等待批改"
            if (finished && active()?.optString("id") == snapshot.optString("id")) store.set("active_round", "")
        }
        return JSONObject().put("correct", correct).put("correctIndices", JSONArray())
            .put("answerText", question.optJSONArray("answers")?.optString(0) ?: "")
            .put("tip", question.optString("tip")).put("finished", finished).put("passed", passed)
            .put("rewardMinutes", grant / 60000.0).put("late", true)
    }

    private fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
        .lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")

    @Synchronized
    fun answer(input: JSONObject, verdict: Boolean? = null, quality: Int? = null): JSONObject {
        day()
        val last = store.get("last_answer")
        if (verdict == null && last.isNotEmpty()) {
            val cached = try { JSONObject(last) } catch (_: Exception) { JSONObject() }
            if (cached.optString("nonce") == input.optString("nonce")) return cached.getJSONObject("result")
        }
        val round = active() ?: return JSONObject().put("error", "round_missing")
        if (verdict == null && round.has("pendingReview")) return JSONObject().put("pending", true).put("reviewId", round.getString("pendingReview")).put("status", status())
        require(input.getString("roundId") == round.getString("id") && input.getString("nonce") == round.getString("nonce")) { "题目已更新，请重新加载" }
        val st = status()
        if (verdict == null && round.getString("mode") != "practice" && st.getString("reason") in listOf("schedule", "quota", "rounds"))
            return JSONObject().put("blocked", st.getString("reason"))
        val question = round.getJSONObject("question")
        val type = question.optString("type", "single")
        if (verdict == null && (type == "subjective" || input.optString("drawing").isNotEmpty())) {
            require(input.optString("text").isNotBlank() || input.optString("drawing").isNotEmpty()) { "请先作答" }
            val drawing = input.optString("drawing")
            require(drawing.isEmpty() || (drawing.startsWith("data:image/png;base64,") && drawing.length <= 2000000)) { "手写图片格式不正确或过大" }
            val id = UUID.randomUUID().toString()
            // round 快照一并存进 review：批改结果回来时即使活动轮次已经被替换，也能按快照结算
            val review = JSONObject().put("id", id).put("state", "pending").put("ts", System.currentTimeMillis())
                .put("question", question).put("input", input).put("feedback", "等待批改")
                .put("round", JSONObject(round.toString()))
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                store.saveReview(review)
                round.put("pendingReview", id)
                store.set("active_round", round.toString())
                store.logEvent("review_submitted", JSONObject().put("id", id))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            return JSONObject().put("pending", true).put("reviewId", id).put("status", status())
        }
        val expected = if (type == "multiple") question.getJSONArray("correctIndices") else JSONArray().put(question.optInt("k"))
        fun indices(array: JSONArray): Set<Int> = (0 until array.length()).map { array.getInt(it) }.toSet()
        val correct = if (verdict != null) verdict else if (type == "text") {
            val value = normalize(input.optString("text"))
            val accepted = question.getJSONArray("answers")
            value.isNotEmpty() && (0 until accepted.length()).any { normalize(accepted.getString(it)) == value }
        } else {
            val selections = input.getJSONArray("selections")
            require(selections.length() > 0 && (0 until selections.length()).all { selections.getInt(it) in 0 until question.getJSONArray("a").length() }) { "请选择有效答案" }
            indices(expected) == indices(selections)
        }
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            round.remove("pendingReview")
            val rules = round.getJSONObject("rules")
            val wasExtra = round.optBoolean("extra")
            val mode = round.getString("mode")
            if (!correct && rules.optBoolean("extraEnabled")) {
                var wrong = number("wrong_progress") + 1
                if (wrong >= rules.getInt("wrongThreshold")) {
                    val extra = rules.getLong("extraQuestions")
                    put("extra_pool", number("extra_pool") + extra)
                    wrong -= rules.getInt("wrongThreshold")
                    store.logEvent("extra_added", JSONObject().put("count", extra))
                }
                put("wrong_progress", wrong)
            }
            if (correct && wasExtra && number("extra_pool") > 0) put("extra_pool", number("extra_pool") - 1)
            if (mode == "streak" && !correct && !wasExtra) round.put("earnedMs", 0)
            if (!wasExtra || mode == "practice") {
                round.put("answered", round.getInt("answered") + 1)
                if (correct) round.put("correct", round.getInt("correct") + 1)
                round.put("streak", if (correct) round.getInt("streak") + 1 else 0)
                if (correct && rules.optBoolean("difficultyRewardEnabled")) {
                    val minutes = rules.getJSONObject("difficultyRewards").getDouble(question.optInt("difficulty", 2).toString())
                    round.put("earnedMs", round.getLong("earnedMs") + (minutes * 60000).toLong())
                }
            }
            store.recordProgress(question.getString("id"), quality ?: if (correct) 4 else 1)
            store.logEvent("answer", JSONObject().put("qid", question.getString("id")).put("correct", correct)
                .put("difficulty", question.optInt("difficulty", 2)).put("extra", wasExtra).put("mode", mode))
            val baseDone = if (mode == "streak") round.getInt("streak") >= round.getInt("target")
                else round.getInt("answered") >= round.getInt("target")
            val passed = mode == "streak" || round.getInt("correct") >= ceil(round.getInt("target") * rules.getDouble("passRatio")).toInt()
            val extraEnabled = rules.optBoolean("extraEnabled", true)
            val finished = mode != "practice" && baseDone && (!passed || !extraEnabled || number("extra_pool") == 0L)
            val result = JSONObject().put("correct", correct).put("correctIndices", if (type == "text") JSONArray() else expected)
                .put("answerText", if (type == "text") question.getJSONArray("answers").getString(0) else "")
                .put("tip", question.optString("tip")).put("finished", finished).put("passed", finished && passed)
            if (finished) {
                var grant = 0L
                if (passed) {
                    persistPending()
                    val requested = if (rules.optBoolean("difficultyRewardEnabled")) round.getLong("earnedMs") else rules.getLong("sessionMinutes") * 60000
                    val budget = store.config().getLong("dailyMinutes") * 60000 - number("used_ms") - number("credit_ms")
                    grant = min(requested, budget.coerceAtLeast(0))
                    put("credit_ms", number("credit_ms") + grant)
                    store.logEvent("quiz_pass", JSONObject().put("roundId", round.getString("id")).put("minutes", grant / 60000.0))
                }
                store.logEvent("quiz", JSONObject().put("native", true).put("total", round.getInt("answered"))
                    .put("correct", round.getInt("correct")).put("passed", passed))
                store.set("active_round", "")
                result.put("rewardMinutes", grant / 60000.0)
            } else {
                // 加练题是否出现只由 nextQuestion() 统一计算，避免前后两次覆盖导致行为无法推理
                nextQuestion(round)
                store.set("active_round", round.toString())
                result.put("next", publicRound(round))
            }
            result.put("status", status())
            store.set("last_answer", JSONObject().put("nonce", input.getString("nonce")).put("result", result).toString())
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }
}
