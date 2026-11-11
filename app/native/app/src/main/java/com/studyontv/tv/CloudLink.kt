package com.studyontv.tv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 云端联动客户端：把「阅读时长记录」小程序里兑换出来的电视分钟数拉回本机。
 *
 * ## 为什么是电视主动拉
 * 电视在家庭 NAT 后面，云端推不进来；而小程序又请求不了局域网地址（域名白名单 + HTTPS）。
 * 唯一稳定方向就是电视出站拉取 —— 这条路 App 早就在走（AI 批改、更新检查）。
 *
 * ## 开关与默认行为
 * **默认关闭**。关着的时候本对象不发任何网络请求，所有功能与以前一模一样；
 * 家长在面板打开「阅读联动」并填配对码之后才会开始轮询。
 *
 * ## 幂等
 * 服务端给每张券一个**按设备单调递增的 seq**，入账时只接受更大的 seq。
 * 回执丢了、电视重装、同一张券重复下发，都不会重复加时间。
 * 这里刻意不看时间戳：孩子可以改系统时间。
 */
object CloudLink {

    const val DEFAULT_BASE = "https://www.wangziran0828.cn/api/v1"

    private const val KEY_ENABLED = "cloud_enabled"
    private const val KEY_BASE = "cloud_base"
    private const val KEY_TOKEN = "cloud_token"
    private const val KEY_DEVICE = "cloud_device_id"
    private const val KEY_CHILD = "cloud_child_name"
    private const val KEY_SEQ = "cloud_seq"
    private const val KEY_REPORTED = "cloud_reported_ms"
    private const val KEY_LAST_SYNC = "cloud_last_sync"
    private const val KEY_LAST_ERROR = "cloud_last_error"

    private const val POLL_SECONDS = 60L
    /** 攒够 5 分钟「用掉的额度」再上报，避免每分钟一次请求。 */
    private const val REPORT_STEP_MS = 5 * 60 * 1000L

    private var timer: ScheduledExecutorService? = null

    private fun store() = DeviceRuntime.store

    // ---------------------------------------------------------------- 配置读写
    fun base(): String = store().get(KEY_BASE, "").ifEmpty { DEFAULT_BASE }.trimEnd('/')
    fun token(): String = store().get(KEY_TOKEN)
    fun enabled(): Boolean = store().get(KEY_ENABLED) == "1" && token().isNotEmpty()

    fun setEnabled(value: Boolean) {
        store().set(KEY_ENABLED, if (value && token().isNotEmpty()) "1" else "0")
        if (value) start() else stop()
    }

    fun status(): JSONObject {
        val configured = token().isNotEmpty()
        return JSONObject()
            .put("enabled", enabled())
            .put("configured", configured)
            .put("base", base())
            .put("childName", store().get(KEY_CHILD))
            .put("deviceId", store().get(KEY_DEVICE))
            .put("seq", store().get(KEY_SEQ, "0").toLongOrNull() ?: 0L)
            .put("extSeconds", DeviceRuntime.learning.status().optLong("extSeconds"))
            .put("lastSyncAt", store().get(KEY_LAST_SYNC, "0").toLongOrNull() ?: 0L)
            .put("lastError", store().get(KEY_LAST_ERROR))
    }

    // ---------------------------------------------------------------- 配对
    /**
     * 用小程序生成的 6 位配对码换取设备令牌。
     * 令牌只在这一刻拿到明文，之后本机保存，服务端只留 SHA-256。
     */
    @Synchronized
    fun bind(code: String, baseUrl: String? = null): JSONObject {
        val clean = code.trim()
        require(clean.matches(Regex("\\d{6}"))) { "配对码是 6 位数字" }
        if (!baseUrl.isNullOrBlank()) store().set(KEY_BASE, baseUrl.trim().trimEnd('/'))
        val body = request("POST", base() + "/tv/pair/claim", null,
            JSONObject().put("code", clean).put("deviceName", deviceName()))
        val data = body.optJSONObject("data") ?: throw IllegalStateException("配对失败")
        store().set(KEY_TOKEN, data.optString("deviceToken"))
        store().set(KEY_DEVICE, data.optString("deviceId"))
        store().set(KEY_CHILD, data.optString("childName"))
        store().set(KEY_SEQ, "0")
        store().set(KEY_LAST_ERROR, "")
        store().set(KEY_ENABLED, "1")
        Diag.info("Cloud", "配对成功 device=${data.optString("deviceId")} child=${data.optString("childName")}")
        start()
        // 立刻拉一次：家长兑完码通常就等着电视到账
        runCatching { pull() }
        return status()
    }

    @Synchronized
    fun unbind() {
        store().set(KEY_TOKEN, "")
        store().set(KEY_ENABLED, "0")
        store().set(KEY_DEVICE, "")
        store().set(KEY_CHILD, "")
        stop()
        Diag.info("Cloud", "已解除绑定")
    }

    // ---------------------------------------------------------------- 轮询
    @Synchronized
    fun start() {
        if (!enabled()) return
        if (timer != null) return
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "cloud-link").apply { isDaemon = true }
        }
        executor.scheduleWithFixedDelay({
            // 这里面的任何异常都不能逃出去：定时任务抛一次就再也不会被调度
            runCatching { tick() }.onFailure { Diag.warn("Cloud", "轮询失败：" + (it.message ?: it.javaClass.simpleName)) }
        }, 3, POLL_SECONDS, TimeUnit.SECONDS)
        timer = executor
        Diag.info("Cloud", "轮询已启动，每 ${POLL_SECONDS}s 一次")
    }

    @Synchronized
    fun stop() {
        timer?.shutdownNow()
        timer = null
    }

    private fun tick() {
        if (!enabled()) { stop(); return }
        pull()
        report()
    }

    /**
     * 拉取并逐张入账。**先入账、再回执、最后推进 seq**：
     * 中途失败时下次会重拉，而 grant() 按 seq 幂等，不会重复加时间。
     */
    @Synchronized
    fun pull(): JSONObject {
        if (token().isEmpty()) throw IllegalStateException("还没有绑定电视")
        val since = store().get(KEY_SEQ, "0").toLongOrNull() ?: 0L
        val body = request("GET", base() + "/tv/grants?since=" + since, token(), null)
        val data = body.optJSONObject("data") ?: return JSONObject().put("applied", 0)
        val grants = data.optJSONArray("grants") ?: return JSONObject().put("applied", 0)
        var applied = 0
        for (index in 0 until grants.length()) {
            val grant = grants.getJSONObject(index)
            val seq = grant.optLong("seq")
            val id = grant.optString("id")
            DeviceRuntime.learning.grant(grant.optInt("minutes"), seq)
            request("POST", base() + "/tv/grants/ack", token(), JSONObject().put("grantId", id))
            store().set(KEY_SEQ, seq.toString())
            applied++
        }
        store().set(KEY_LAST_SYNC, System.currentTimeMillis().toString())
        store().set(KEY_LAST_ERROR, "")
        if (applied > 0) GateService.refreshNow()
        return JSONObject().put("applied", applied)
    }

    /** 上报「用掉的额度」，服务端据此写一条看电视记录，让小程序统计与实际对得上。 */
    @Synchronized
    fun report(): JSONObject {
        if (token().isEmpty()) return JSONObject().put("reported", 0)
        val used = DeviceRuntime.learning.extUsedMsSnapshot()
        val baseline = store().get(KEY_REPORTED, "0").toLongOrNull() ?: 0L
        var delta = used - baseline
        // 重装 / 清数据 / 池被重置时 used 可能比基线小，按新基线重来，不当成负增长上报
        if (delta < 0) { store().set(KEY_REPORTED, used.toString()); return JSONObject().put("reported", 0) }
        if (delta < REPORT_STEP_MS) return JSONObject().put("reported", 0)
        val seconds = (delta / 1000).toInt()
        if (seconds <= 0) return JSONObject().put("reported", 0)
        request("POST", base() + "/tv/report", token(), JSONObject().put("seconds", seconds))
        store().set(KEY_REPORTED, used.toString())
        return JSONObject().put("reported", seconds)
    }

    /** 面板上的「测试连接」：只读一次券列表，不入账。 */
    fun test(): JSONObject {
        val body = request("GET", base() + "/tv/grants?since=" + (store().get(KEY_SEQ, "0")), token(), null)
        return JSONObject().put("ok", body.optInt("code") == 0)
    }

    private fun deviceName(): String {
        val model = android.os.Build.MODEL ?: "TV"
        return ("客厅电视 " + model).take(32)
    }

    // ---------------------------------------------------------------- HTTP
    /**
     * 统一的请求封装。服务端 HTTP 恒为 200，业务结果在 body 的 code 里（与小程序同一套约定），
     * 所以这里既看连接状态也看 code。
     */
    private fun request(method: String, url: String, bearer: String?, payload: JSONObject?): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 10000
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Client", "studyontv/" + BuildConfig.VERSION_NAME)
            if (!bearer.isNullOrEmpty()) setRequestProperty("Authorization", "Bearer " + bearer)
            if (payload != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (payload != null) {
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = Net.text(stream, 256 * 1024)
            if (code !in 200..299) throw IllegalStateException("服务端返回 HTTP $code")
            val body = try { JSONObject(text) } catch (_: Exception) {
                throw IllegalStateException("返回内容不是合法 JSON")
            }
            val biz = body.optInt("code", -1)
            if (biz != 0) {
                val message = body.optString("message").ifEmpty { "业务失败（$biz）" }
                throw IllegalStateException(message)
            }
            return body
        } finally {
            connection.disconnect()
        }
    }
}
