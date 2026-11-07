package com.studyontv.tv

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 管理密码与会话。
 *
 * 密码本身永远不上网：面板先用 GET /api/auth/challenge 取一次性随机数，再提交
 * proof = HMAC-SHA256(sha256("密码:盐"), challenge)。服务端只保存 auth_key = sha256("密码:盐")，
 * 校验时用 auth_key 重算 HMAC，所以抓包只能拿到一次性的 challenge 与 proof，无法重放。
 * 旧客户端仍可提交明文 passphrase（兼容），登录成功后会自动补写 auth_key 供下次使用。
 */
object AdminAuth {
    private val random = SecureRandom()
    private val sessions = ConcurrentHashMap<String, Long>()
    private val sessionIp = ConcurrentHashMap<String, String>()

    /** 一次性挑战：challenge -> 过期时间 */
    private val challenges = ConcurrentHashMap<String, Long>()

    /** 按来源 IP 的失败计数（换设备也无法把计数刷掉） */
    private val ipFailures = ConcurrentHashMap<String, Int>()
    private val ipBlockedUntil = ConcurrentHashMap<String, Long>()

    val deviceToken: String = randomHex(32)

    private const val CHALLENGE_MS = 120_000L
    private const val REMEMBER_MS = 30L * 24 * 60 * 60 * 1000
    private const val SESSION_MS = 12L * 60 * 60 * 1000

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun hmacHex(key: String, value: String): String =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
            .doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun prefs(context: Context) = context.getSharedPreferences("parent_access", Context.MODE_PRIVATE)
    fun configured(context: Context): Boolean = prefs(context).contains("hash")

    private fun hash(value: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(value.toCharArray(), salt, 60000, 256)).encoded

    private fun authSaltOf(context: Context): String = prefs(context).getString("auth_salt", "") ?: ""
    private fun authKeyOf(context: Context): String = prefs(context).getString("auth_key", "") ?: ""

    /** 记住密码本的「快速校验值」：只用于挑战-响应，不能代替 PBKDF2 校验 */
    private fun storeAuthKey(context: Context, value: String) {
        val salt = authSaltOf(context).ifEmpty { randomHex(16) }
        prefs(context).edit().putString("auth_salt", salt).putString("auth_key", sha256Hex("$value:$salt")).apply()
    }

    @Synchronized
    fun setPassword(context: Context, value: String) {
        require(value.length in 6..64) { "管理密码需要 6–64 位" }
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val authSalt = randomHex(16)
        prefs(context).edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(value, salt), Base64.NO_WRAP))
            .putString("auth_salt", authSalt)
            .putString("auth_key", sha256Hex("$value:$authSalt"))
            .remove("failures").remove("failures_total").remove("blocked_until").remove("remembered").commit()
        sessions.clear(); sessionIp.clear(); challenges.clear()
        ipFailures.clear(); ipBlockedUntil.clear()
    }

    // ---------- 失败计数与退避 ----------

    /**
     * 全局限流上限刻意压在 30 分钟内：孩子故意乱试不能把家长永久锁在门外；
     * 单个来源 IP 则一路升级到 24 小时，让爆破的一方彻底失效。
     */
    private fun globalBackoff(failures: Int): Long = when {
        failures >= 10 -> 30L * 60_000
        failures >= 5 -> 5L * 60_000
        failures >= 3 -> 60_000
        else -> 0L
    }

    private fun ipBackoff(failures: Int): Long = when {
        failures >= 20 -> 24L * 3600_000
        failures >= 10 -> 2L * 3600_000
        failures >= 5 -> 30L * 60_000
        failures >= 3 -> 60_000
        else -> 0L
    }

    /** 剩余锁定秒数（0 = 未锁定）。用于登录失败时给出提示，也用于拒绝新尝试 */
    fun blockedSeconds(context: Context, source: String = ""): Long {
        val now = System.currentTimeMillis()
        var left = (prefs(context).getLong("blocked_until", 0) - now).coerceAtLeast(0)
        if (source.isNotEmpty()) left = maxOf(left, ((ipBlockedUntil[source] ?: 0L) - now).coerceAtLeast(0))
        return (left + 999) / 1000
    }

    /**
     * @param countGlobal 锁定期内的尝试只计入来源 IP（避免孩子乱试把家长一起锁在门外），
     *                    但 IP 计数器照常升级，爆破方会被彻底挡住
     */
    private fun recordFailure(context: Context, source: String, countGlobal: Boolean = true) {
        val p = prefs(context)
        if (countGlobal) {
            val failures = p.getInt("failures", 0) + 1
            val total = p.getInt("failures_total", 0) + 1
            val wait = globalBackoff(failures)
            p.edit().putInt("failures", failures).putInt("failures_total", total)
                .putLong("blocked_until", if (wait > 0) System.currentTimeMillis() + wait else 0L).commit()
            if (failures == 3 || failures == 5 || failures == 10 || failures % 20 == 0) {
                Diag.warn("Auth", "管理密码连续错误 $failures 次（累计 $total），来源=$source")
                notifyParent(context, failures, source)
            }
        }
        if (source.isNotEmpty()) {
            if (ipFailures.size > 500) { ipFailures.clear(); ipBlockedUntil.clear() }
            val count = (ipFailures[source] ?: 0) + 1
            ipFailures[source] = count
            ipBlockedUntil[source] = if (ipBackoff(count) > 0) System.currentTimeMillis() + ipBackoff(count) else 0L
            if (count == 20) Diag.warn("Auth", "来源 $source 连续输错 20 次，已锁定 24 小时")
        }
    }

    private fun resetFailures(context: Context, source: String) {
        prefs(context).edit().remove("failures").remove("blocked_until").apply()
        if (source.isNotEmpty()) { ipFailures.remove(source); ipBlockedUntil.remove(source) }
    }

    /** 家长应该知道有人在试管理密码；权限被拒或渠道不可用时静默失败 */
    private fun notifyParent(context: Context, failures: Int, source: String) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val channelId = "sotv_gate_alert"
            if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
                android.app.NotificationChannel(channelId, "学习提醒", android.app.NotificationManager.IMPORTANCE_HIGH))
            val pi = android.app.PendingIntent.getActivity(context, 9, android.content.Intent(context, ParentActivity::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val builder = if (android.os.Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(context, channelId)
                else android.app.Notification.Builder(context)
            manager.notify(1006, builder.setContentTitle("有人在尝试管理密码")
                .setContentText("已连续输错 $failures 次" + (if (source.isNotEmpty()) "（来源 $source）" else "") + "，请确认是否是家人操作")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentIntent(pi)
                .setAutoCancel(true).setOnlyAlertOnce(true).build())
        } catch (_: Throwable) { }
    }

    // ---------- 登录 ----------

    /** 旧协议：直接比对明文密码。仅用于改密码和兼容旧面板，成功后补写挑战-响应用的 auth_key */
    @Synchronized
    fun verify(context: Context, value: String, source: String = ""): Boolean {
        val p = prefs(context)
        if (!configured(context)) return false
        if (blockedSeconds(context, source) > 0) { recordFailure(context, source, countGlobal = false); return false }
        if (value.length !in 6..64) { recordFailure(context, source); return false }
        val salt = Base64.decode(p.getString("salt", ""), Base64.NO_WRAP)
        val expected = Base64.decode(p.getString("hash", ""), Base64.NO_WRAP)
        val ok = try { MessageDigest.isEqual(expected, hash(value, salt)) } catch (_: Exception) { false }
        if (ok) { resetFailures(context, source); if (authKeyOf(context).isEmpty()) storeAuthKey(context, value) }
        else recordFailure(context, source)
        return ok
    }

    /** GET /api/auth/challenge：返回一次性随机数；未配置密码或正在锁定时返回 null */
    fun challenge(context: Context, source: String): JSONObject? {
        if (!configured(context)) return null
        if (blockedSeconds(context, source) > 0) return null
        val key = authKeyOf(context)
        val salt = authSaltOf(context)
        if (key.isEmpty() || salt.isEmpty()) return null   // 老密码还没补写 auth_key，先用旧协议登录一次
        val now = System.currentTimeMillis()
        if (challenges.size > 500) challenges.clear()
        challenges.entries.removeAll { it.value < now }
        val value = randomHex(24)
        challenges[value] = now + CHALLENGE_MS
        return JSONObject().put("challenge", value).put("salt", salt)
            .put("algorithm", "hmac-sha256").put("expiresIn", CHALLENGE_MS / 1000)
    }

    @Synchronized
    private fun verifyProof(context: Context, challenge: String, proof: String, source: String): Boolean {
        // 一次性：无论成功失败都作废，避免被反复重放
        val expires = challenges.remove(challenge) ?: return false
        if (expires < System.currentTimeMillis()) return false
        if (blockedSeconds(context, source) > 0) { recordFailure(context, source, countGlobal = false); return false }
        val key = authKeyOf(context)
        if (key.isEmpty() || proof.length != 64) { recordFailure(context, source); return false }
        val expected = hmacHex(key, challenge)
        val ok = MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), proof.lowercase().toByteArray(Charsets.UTF_8))
        if (ok) resetFailures(context, source) else recordFailure(context, source)
        return ok
    }

    /**
     * 登录。优先使用挑战-响应；没有 challenge/proof 时回落到明文密码（旧面板）。
     * @return 会话令牌；失败返回 null
     */
    @Synchronized
    fun login(context: Context, passphrase: String?, challenge: String?, proof: String?, remember: Boolean, source: String): String? {
        val hasProof = !challenge.isNullOrEmpty() && !proof.isNullOrEmpty()
        if (!hasProof && passphrase.isNullOrEmpty()) return null      // 空请求不计入失败次数
        val ok = if (hasProof) verifyProof(context, challenge.orEmpty(), proof.orEmpty(), source)
            else verify(context, passphrase ?: "", source)
        if (!ok) return null
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { it.value < now }
        val token = randomHex(32)
        val expires = now + if (remember) REMEMBER_MS else SESSION_MS
        sessions[token] = expires
        sessionIp[token] = source
        if (!remember) return token
        // 「记住这台手机」：30 天内免密码，设备重启后仍有效（只保存令牌的 SHA-256 与来源地址）；修改管理密码后全部失效
        val saved = remembered(context)
        saved.put(digest(token), JSONObject().put("exp", expires).put("ip", source))
        val keys = saved.keys().asSequence().toList().sortedBy { saved.optJSONObject(it)?.optLong("exp") ?: 0L }
        keys.take((keys.size - 8).coerceAtLeast(0)).forEach { saved.remove(it) }   // 最多记住 8 台
        prefs(context).edit().putString("remembered", saved.toString()).commit()
        return token
    }

    private fun digest(token: String) = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun remembered(context: Context): JSONObject {
        val all = try { JSONObject(prefs(context).getString("remembered", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val now = System.currentTimeMillis()
        all.keys().asSequence().toList().forEach {
            val entry = all.optJSONObject(it)
            if (entry == null || entry.optLong("exp") < now) all.remove(it)
        }
        return all
    }

    @Synchronized
    fun forget(context: Context, token: String?) {
        if (token == null) return
        sessions.remove(token); sessionIp.remove(token)
        val saved = remembered(context)
        if (saved.remove(digest(token)) != null) prefs(context).edit().putString("remembered", saved.toString()).commit()
    }

    @Volatile private var verifiedAt = 0L

    /** 刚在本机设置或验证过密码（2 分钟内）：从权限引导页进入家长设置时不必再输一次 */
    fun markVerified() { verifiedAt = android.os.SystemClock.elapsedRealtime() }
    fun recentlyVerified(): Boolean = verifiedAt > 0 && android.os.SystemClock.elapsedRealtime() - verifiedAt < 120000

    /**
     * 会话校验。令牌同时与来源地址绑定：同一 WiFi 下即使被嗅探到令牌，换一台设备也无法重放。
     * @param source 客户端地址；null 表示不校验来源（仅用于本机内部调用）
     */
    fun valid(token: String?, source: String? = null): Boolean {
        if (token == null || token.length != 64) return false
        val now = System.currentTimeMillis()
        val expires = sessions[token]
        if (expires != null && expires > now) {
            val bound = sessionIp[token]
            if (bound.isNullOrEmpty() || source == null || bound == source) return true
        }
        return try {
            val entry = remembered(DeviceRuntime.appContext).optJSONObject(digest(token)) ?: return false
            if (entry.optLong("exp") <= now) return false
            val ip = entry.optString("ip")
            ip.isEmpty() || source == null || ip == source
        } catch (_: Throwable) { false }
    }
}
