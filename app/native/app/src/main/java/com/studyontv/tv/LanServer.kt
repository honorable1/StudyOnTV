package com.studyontv.tv

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class LanServer(
    private val context: Context,
    private val store: LocalStore,
    private val assets: AssetManager,
    private val port: Int = 8099
) {
    private var socket: ServerSocket? = null

    // 浏览器会并发 6 个连接加载面板；线程太少会排队，太多又会在低端盒子上抢 CPU。
    // 线程池必须有界：无界队列 + 慢连接会让整台设备的内存被吃满（局域网 DoS）。
    private val workers = if (BuildConfig.FLAVOR == "tv") 3 else 4
    private val pool = ThreadPoolExecutor(
        workers, workers, 60L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(32),
        ThreadFactory { runnable -> Thread(runnable, "sotv-http").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    @Volatile private var running = false
    /** accept() 连续失败次数：只在第 1 次和每 50 次记录，避免端口被占用时刷爆日志 */
    private var acceptFailures = 0
    var boundPort: Int = port
        private set

    fun start() {
        if (running) return
        boundPort = tryBind(port) ?: tryBind(8090) ?: return
        running = true
        Thread {
            while (running) {
                try {
                    val client = socket!!.accept()
                    acceptFailures = 0
                    try {
                        pool.execute { handle(client) }
                    } catch (_: RejectedExecutionException) {
                        try {
                            client.getOutputStream().use { it.write("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()) }
                        } catch (_: Exception) { }
                        try { client.close() } catch (_: Exception) { }
                    }
                } catch (e: Exception) {
                    if (running) {
                        acceptFailures++
                        if (acceptFailures == 1 || acceptFailures % 50 == 0)
                            Diag.warn("HTTP", "accept 已连续失败 $acceptFailures 次：" + (e.message ?: e.javaClass.simpleName))
                        Thread.sleep(50)
                    }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (e: Exception) { }
    }

    private fun tryBind(p: Int): Int? = try {
        socket = ServerSocket(p)
        p
    } catch (e: Exception) {
        null
    }

    private fun handle(client: Socket) {
        client.use { c ->
            c.soTimeout = 8000
            val started = System.currentTimeMillis()
            try {
                val input = BufferedInputStream(c.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                val query = parts[1].substringAfter('?', "")

                val headers = HashMap<String, String>()
                var headerCount = 0
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    if (++headerCount > MAX_HEADERS) return reply(c.getOutputStream(), 431, "text/plain; charset=utf-8", "请求头过多".toByteArray())
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
                // 逐字节慢速发送也能一直续命，这里给整个请求一个硬上限
                if (System.currentTimeMillis() - started > REQUEST_TIMEOUT_MS) return reply(c.getOutputStream(), 408, "text/plain; charset=utf-8", "请求超时".toByteArray())
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length !in 0..MAX_BODY) return reply(c.getOutputStream(), 413, "text/plain; charset=utf-8", "Request too large".toByteArray())
                val body = if (length > 0) readBody(input, length) else ""

                val out = c.getOutputStream()
                if (method == "OPTIONS") return reply(out, 204, null, null)
                val local = c.inetAddress?.isLoopbackAddress ?: false
                route(method, path, query, headers, body, out, local)
            } catch (e: LineTooLongException) {
                try { reply(c.getOutputStream(), 431, "text/plain; charset=utf-8", "请求头过长".toByteArray()) } catch (_: Exception) { }
            } catch (e: Exception) {
                Diag.warn("HTTP", "请求处理失败：" + (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** 页面只允许调用这些设备侧接口；改规则、改密码、签发令牌必须走家长登录 */
    private val devicePaths = setOf(
        "/api/state", "/api/hello", "/api/event",
        "/api/quiz/start", "/api/quiz/answer", "/api/quiz/skip", "/api/quiz/review"
    )

    /**
     * WebView 内部调用：直接走路由，不经过 TCP，也不把任何凭据交给 JS。
     * 返回 JSON 字符串（HTTP 头已剥离），异常同样以 JSON 返回。
     */
    fun localJson(method: String, path: String, body: String): String {
        val clean = path.substringBefore('?')
        if (clean !in devicePaths)
            return JSONObject().put("error", "forbidden").put("message", "这个接口不能从页面调用").toString()
        val out = ResponseBuffer()
        return try {
            route(method, clean, path.substringAfter('?', ""), emptyMap(), body, out, true, "127.0.0.1", true)
            out.body().ifEmpty { JSONObject().put("error", "empty_response").toString() }
        } catch (e: Exception) {
            Diag.warn("HTTP", "本地调用 $method $clean 失败：" + (e.message ?: e.javaClass.simpleName))
            JSONObject().put("error", "bad_request").put("message", e.message ?: "请求失败").toString()
        }
    }

    private fun route(method: String, path: String, query: String, headers: Map<String, String>, body: String,
                      out: OutputStream, local: Boolean, ip: String = "", trusted: Boolean = false) {
        val q = if (query.isEmpty()) emptyMap() else query.split("&").associate {
            val kv = it.split("=", limit = 2)
            // 键和值都要解码，非法转义不能让整个请求炸掉
            val name = runCatching { java.net.URLDecoder.decode(kv[0], "UTF-8") }.getOrDefault(kv[0])
            val value = runCatching { java.net.URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8") }.getOrDefault("")
            name to value
        }
        val deviceAccess = trusted || (local && headers["x-device-token"] == AdminAuth.deviceToken)
        val token = headers["authorization"]?.removePrefix("Bearer ") ?: headers["x-admin-token"]

        // ---------- 登录：不需要令牌 ----------
        if (path == "/api/auth/challenge" && method == "GET") {
            // 一次性随机数：客户端用 HMAC 回应，密码本身不上网
            val challenge = AdminAuth.challenge(context, ip)
                ?: return json(out, 503, err("challenge_unavailable").put("message", "请使用管理密码登录"))
            return json(out, 200, challenge)
        }
        if (path == "/api/auth" && method == "POST") {
            val input = jsonObject(body)
            val remember = input.optBoolean("remember")
            val session = AdminAuth.login(context,
                input.optString("passphrase").takeIf { it.isNotEmpty() },
                input.optString("challenge").takeIf { it.isNotEmpty() },
                input.optString("proof").takeIf { it.isNotEmpty() }, remember, ip)
            if (session == null) {
                val wait = AdminAuth.blockedSeconds(context, ip)
                val message = if (wait > 0) "密码错误次数过多，请 $wait 秒后再试" else "管理密码错误"
                return json(out, 401, err("wrong_password").put("message", message).put("retryAfter", wait))
            }
            return json(out, 200, JSONObject().put("token", session).put("expiresIn", if (remember) 30 * 86400 else 43200))
        }

        // ---------- 其余 /api/* 统一鉴权 ----------
        var parent = false
        var integration: IntegrationAuth.Scope? = null
        if (path.startsWith("/api/") && path != "/api/hello") {
            val integrationToken = token?.takeIf { it.startsWith("sotv_") }
            if (integrationToken != null && !IntegrationAuth.takeBudget())
                return json(out, 429, err("too_many_requests").put("message", "请求太频繁，请稍后再试"))
            integration = IntegrationAuth.verify(context, integrationToken)
            // 只有会话令牌（/api/auth 换取，且与来源地址绑定）；明文密码不再作为凭据使用
            parent = AdminAuth.valid(token, ip)
            if (!deviceAccess && !parent && integration == null) return json(out, 401, err("unauthorized"))
            // 集成令牌只能访问明确列出的接口；不能改规则、题库、密码或权限本身。
            val grant = integration
            if (grant != null && !deviceAccess && !parent) {
                val scope = when {
                    path == "/api/state" && method == "GET" -> "read"
                    path == "/api/device" && method == "GET" -> "read"
                    path == "/api/remote" && method == "POST" -> "control"
                    else -> null
                } ?: return json(out, 403, err("forbidden").put("message", "这个接口需要家长登录"))
                if ((scope == "read" && !grant.read) || (scope == "control" && !grant.control))
                    return json(out, 403, err("forbidden").put("message", "集成令牌没有这个权限"))
            }
        }

        if (method != "GET") Diag.info("HTTP", "$method $path")
        val req = Req(method, path, q, body, out, deviceAccess, parent, integration, ip, token)
        try {
            // 按域分发：每个 handler 只处理自己那组路径，命中即返回 true
            val handled = infoRoutes(req) || adminRoutes(req) || questionRoutes(req) ||
                gradingRoutes(req) || updateRoutes(req) || integrationRoutes(req) ||
                deviceRoutes(req) || quizRoutes(req) || diagnosticsRoutes(req)
            if (handled) return
        } catch (e: Exception) {
            Diag.error("HTTP", "$method $path failed", e)
            return json(out, 400, JSONObject().put("error", "bad_request").put("message", e.message ?: "请求格式错误"))
        }

        // 未匹配的 /api/*：明确返回 404，而不是把首页当 JSON 返回（否则前端只会报「连接设备失败」）
        if (path.startsWith("/api/")) return json(out, 404, err("not_found"))
        if (method != "GET") return json(out, 405, err("method_not_allowed"))
        serveStatic(path, out, headers["accept-encoding"]?.contains("gzip") == true, headers["if-none-match"])
    }

    /** 一次请求的上下文：处理器只依赖它，不必各自展开一长串参数 */
    private class Req(
        val method: String,
        val path: String,
        val q: Map<String, String>,
        val body: String,
        val out: OutputStream,
        val deviceAccess: Boolean,
        val parent: Boolean,
        val integration: IntegrationAuth.Scope?,
        val ip: String,
        val token: String?
    )

    /** 概览 / 历史 / 节假日：家长面板首屏与统计页 */
    private fun infoRoutes(r: Req): Boolean {
        when {
            r.path == "/api/hello" && r.method == "GET" -> {
                json(r.out, 200, JSONObject()
                    .put("app", "StudyOnTV")
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("name", Prefs.deviceName(context))
                    .put("ip", localIp() ?: "")
                    .put("port", boundPort)
                    .put("questions", store.countQuestions())
                    .put("needsToken", true)
                    .put("passwordRequired", true))
                return true
            }
            r.path == "/api/state" && r.method == "GET" -> {
                DeviceRuntime.learning.flushUsage()
                json(r.out, 200, JSONObject()
                    .put("config", store.config())
                    .put("today", store.todayStats())
                    .put("questions", store.countQuestions())
                    .put("roundsDoneToday", store.roundsDoneToday())
                    .put("repetition", store.repetitionStats())
                    .put("learning", DeviceRuntime.learning.status()))
                return true
            }
            r.path == "/api/history" && r.method == "GET" -> {
                DeviceRuntime.learning.flushUsage()
                val days = r.q["days"]?.toIntOrNull() ?: 7
                json(r.out, 200, JSONObject().put("items", store.historyDays(days)))
                return true
            }
            r.path == "/api/events" && r.method == "GET" -> {
                json(r.out, 200, JSONObject().put("items", store.recentEvents(r.q["limit"]?.toIntOrNull() ?: 50)))
                return true
            }
            r.path == "/api/holidays" && r.method == "GET" -> {
                json(r.out, 200, HolidayService.info(context))
                return true
            }
            r.path == "/api/holidays/sync" && r.method == "POST" -> {
                val year = r.q["year"]?.toIntOrNull() ?: java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                json(r.out, 200, try {
                    HolidayService.fetch(context, year)
                } catch (e: Exception) {
                    err("holiday_failed").put("message", e.message ?: "节假日同步失败")
                })
                return true
            }
        }
        return false
    }

    /** 规则 / 密码 / 遥控 / 集成令牌：只有家长会话可用 */
    private fun adminRoutes(r: Req): Boolean {
        when {
            r.path == "/api/config" && r.method == "PUT" -> {
                val input = jsonObjectOrNull(r.body) ?: run { json(r.out, 200, err("bad_json")); return true }
                json(r.out, 200, JSONObject().put("config", store.saveConfig(input)))
                return true
            }
            r.path == "/api/password" && r.method == "POST" -> {
                val input = jsonObject(r.body)
                if (!AdminAuth.verify(context, input.optString("current"), r.ip)) {
                    val wait = AdminAuth.blockedSeconds(context, r.ip)
                    val message = if (wait > 0) "密码错误次数过多，请 $wait 秒后再试" else "当前密码不正确"
                    json(r.out, 403, err("wrong_password").put("message", message).put("retryAfter", wait))
                    return true
                }
                AdminAuth.setPassword(context, input.optString("password"))
                json(r.out, 200, JSONObject().put("ok", true))
                return true
            }
            r.path == "/api/logout" && r.method == "POST" -> {
                AdminAuth.forget(context, r.token)
                json(r.out, 200, JSONObject().put("ok", true))
                return true
            }
            r.path == "/api/remote" && r.method == "POST" -> {
                // 家长会话，或带「控制」权限的集成令牌；学习页的设备令牌不能调用
                if (!r.parent && r.integration?.control != true) {
                    json(r.out, 403, err("forbidden").put("message", "请先用管理密码登录"))
                    return true
                }
                val input = jsonObject(r.body)
                val status = DeviceRuntime.learning.remote(input.optString("action"), input.optInt("minutes"),
                    if (r.parent) "panel" else "integration")
                GateService.refreshNow()
                json(r.out, 200, JSONObject().put("learning", status))
                return true
            }
        }
        return false
    }

    /** 题库：列表 / 新增 / 批量暂停 / 删除 */
    private fun questionRoutes(r: Req): Boolean {
        when {
            r.path == "/api/questions" && r.method == "GET" -> {
                val all = r.q["all"] == "1"
                val items = store.questions(r.q["subject"], r.q["level"], r.q["limit"]?.toIntOrNull() ?: 500,
                    enabledOnly = !all, sortBy = r.q["sort"] ?: "random")
                json(r.out, 200, JSONObject().put("items", items))
                return true
            }
            r.path == "/api/questions" && r.method == "POST" -> {
                val payload = jsonObjectOrNull(r.body) ?: run { json(r.out, 200, err("bad_json")); return true }
                if (payload.has("items")) {
                    val added = store.importQuestions(payload.getJSONArray("items"))
                    json(r.out, 200, JSONObject().put("ok", true).put("added", added))
                    return true
                }
                json(r.out, 200, try {
                    val id = store.upsertQuestion(payload)
                    store.logEvent("question_added", JSONObject().put("id", id))
                    JSONObject().put("ok", true).put("id", id)
                } catch (e: IllegalArgumentException) {
                    JSONObject().put("error", "bad_question").put("message", e.message ?: "")
                })
                return true
            }
            r.path == "/api/questions/pause" && r.method == "POST" -> {
                val input = jsonObject(r.body)
                val ids = input.getJSONArray("ids")
                require(ids.length() in 1..5000) { "请选择题目" }
                json(r.out, 200, JSONObject().put("ok", true).put("updated",
                    store.setEnabled((0 until ids.length()).map { ids.getString(it) }, input.optBoolean("enabled", false))))
                return true
            }
            r.path == "/api/questions/delete" && r.method == "POST" -> {
                val ids = jsonObject(r.body).getJSONArray("ids")
                require(ids.length() in 1..5000) { "请选择要删除的题目" }
                json(r.out, 200, JSONObject().put("ok", true)
                    .put("deleted", store.deleteQuestions((0 until ids.length()).map { ids.getString(it) })))
                return true
            }
            r.path.startsWith("/api/questions/") && r.method == "DELETE" -> {
                val id = r.path.removePrefix("/api/questions/")
                json(r.out, 200, JSONObject().put("ok", store.deleteQuestion(id)))
                return true
            }
        }
        return false
    }

    /** 批改：模型设置 / 连通性测试 / 待批列表与人工裁决 */
    private fun gradingRoutes(r: Req): Boolean {
        when {
            r.path == "/api/grading" && r.method == "GET" -> {
                json(r.out, 200, GradingService.settings(store).put("providers", GradingService.providers()))
                return true
            }
            r.path == "/api/grading" && r.method == "PUT" -> {
                json(r.out, 200, GradingService.save(store, jsonObject(r.body)))
                return true
            }
            r.path == "/api/grading/test" && r.method == "POST" -> {
                json(r.out, 200, GradingService.test(store, jsonObject(r.body)))
                return true
            }
            r.path == "/api/grading/models" && r.method == "POST" -> {
                json(r.out, 200, GradingService.models(store, jsonObject(r.body)))
                return true
            }
            r.path == "/api/reviews" && r.method == "GET" -> {
                json(r.out, 200, JSONObject().put("items", store.reviews(r.q["state"] ?: "pending")))
                return true
            }
            r.path.startsWith("/api/reviews/") && r.method == "POST" -> {
                val id = r.path.removePrefix("/api/reviews/").substringBefore('/')
                if (r.path.endsWith("/retry")) {
                    require(store.review(id) != null) { "题目不存在" }
                    GradingService.enqueue(store, DeviceRuntime.learning, id)
                    json(r.out, 200, JSONObject().put("ok", true))
                    return true
                }
                val decision = jsonObject(r.body)
                json(r.out, 200, DeviceRuntime.learning.grade(id, decision.getDouble("score"),
                    decision.optString("feedback"), "parent_or_agent"))
                return true
            }
        }
        return false
    }

    /** 应用内更新：状态 / 设置 / 触发检查（检查在后台跑，面板轮询 GET） */
    private fun updateRoutes(r: Req): Boolean {
        when {
            r.path == "/api/update" && r.method == "GET" -> {
                json(r.out, 200, Updater.status(context))
                return true
            }
            r.path == "/api/update" && r.method == "PUT" -> {
                json(r.out, 200, Updater.saveSettings(context, jsonObject(r.body)))
                return true
            }
            r.path == "/api/update/check" && r.method == "POST" -> {
                // 检查可能要依次尝试多个来源（最长约 1 分钟），放到后台，面板轮询 GET /api/update
                Updater.startCheck(context)
                json(r.out, 200, Updater.status(context))
                return true
            }
        }
        return false
    }

    /** 集成令牌（Home Assistant 等外部调用方） */
    private fun integrationRoutes(r: Req): Boolean {
        when {
            r.path == "/api/integration" && r.method == "GET" -> {
                json(r.out, 200, IntegrationAuth.state(context))
                return true
            }
            r.path == "/api/integration" && r.method == "POST" -> {
                val input = jsonObject(r.body)
                val read = input.optBoolean("read", true)
                val control = input.optBoolean("control", false)
                if (!read && !control) {
                    json(r.out, 200, err("bad_scope").put("message", "至少选择只读或控制中的一种权限"))
                    return true
                }
                val issued = IntegrationAuth.issue(context, read, control)
                json(r.out, 200, JSONObject().put("token", issued).put("integration", IntegrationAuth.state(context)))
                return true
            }
            r.path == "/api/integration" && r.method == "PUT" -> {
                val input = jsonObject(r.body)
                IntegrationAuth.update(context,
                    if (input.has("enabled")) input.optBoolean("enabled") else null,
                    if (input.has("read")) input.optBoolean("read") else null,
                    if (input.has("control")) input.optBoolean("control") else null)
                json(r.out, 200, IntegrationAuth.state(context))
                return true
            }
            r.path == "/api/integration" && r.method == "DELETE" -> {
                IntegrationAuth.revoke(context)
                json(r.out, 200, IntegrationAuth.state(context))
                return true
            }
        }
        return false
    }

    /** 设备信息 / 上报开关 / 防卸载 / 埋点事件 */
    private fun deviceRoutes(r: Req): Boolean {
        when {
            r.path == "/api/device" && r.method == "GET" -> {
                json(r.out, 200, JSONObject().put("name", Prefs.deviceName(context))
                    .put("version", BuildConfig.VERSION_NAME).put("flavor", BuildConfig.FLAVOR)
                    .put("ip", localIp() ?: "").put("port", boundPort)
                    .put("uninstallProtected", UninstallGuard.active(context))
                    .put("autostart", Watchdog.report(context))
                    .put("monitor", GateService.diagnostics(context))
                    // 崩溃上报已移除，恒为 false；面板据此把开关渲染成关闭
                    .put("buglyConsent", false))
                return true
            }
            r.path == "/api/device" && r.method == "PUT" -> {
                val name = jsonObject(r.body).optString("name").trim()
                require(name.length in 1..32) { "设备名称需要 1–32 个字" }
                Prefs.setDeviceName(context, name)
                json(r.out, 200, JSONObject().put("ok", true).put("name", name))
                return true
            }
            // 崩溃上报已移除：堆栈只写本机日志。保留该路由是为了让旧面板点了有明确回应，
            // 而不是静默 404 —— 面板的开关按 buglyConsent 渲染，GET 恒返回 false。
            r.path == "/api/device/bugly" && r.method == "PUT" -> {
                json(r.out, 409, JSONObject().put("error", "not_supported")
                    .put("message", "本版本不联网上报崩溃。崩溃日志保存在设备里，可在「诊断 → 导出日志」下载"))
                return true
            }
            r.path == "/api/device/uninstall-guard" && r.method == "DELETE" -> {
                // 家长在网页端可以关闭防卸载（安全：需要家长登录 token）
                json(r.out, 200, try {
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                    dpm.removeActiveAdmin(UninstallGuard.component(context))
                    JSONObject().put("ok", true).put("uninstallProtected", false)
                } catch (e: Exception) {
                    err(e.message ?: "failed")
                })
                return true
            }
            r.path == "/api/event" && r.method == "POST" -> {
                val payload = jsonObject(r.body)
                val type = payload.optString("type")
                if (type != "study") {
                    json(r.out, 403, err("event_managed_by_device"))
                    return true
                }
                store.logEvent(type, payload)
                json(r.out, 200, JSONObject().put("ok", true))
                return true
            }
        }
        return false
    }

    /** 答题流程：只有设备端（学习页）能调用，家长面板与集成令牌都不行 */
    private fun quizRoutes(r: Req): Boolean {
        when {
            r.path == "/api/quiz/start" && r.method == "POST" -> {
                if (!r.deviceAccess) { json(r.out, 403, err("device_only")); return true }
                json(r.out, 200, DeviceRuntime.learning.start())
                return true
            }
            r.path == "/api/quiz/review" && r.method == "POST" -> {
                if (!r.deviceAccess) { json(r.out, 403, err("device_only")); return true }
                json(r.out, 200, DeviceRuntime.learning.acknowledgeReview(jsonObject(r.body).getString("reviewId")))
                return true
            }
            r.path == "/api/quiz/skip" && r.method == "POST" -> {
                if (!r.deviceAccess) { json(r.out, 403, err("device_only")); return true }
                json(r.out, 200, DeviceRuntime.learning.skipPending())
                return true
            }
            r.path == "/api/quiz/answer" && r.method == "POST" -> {
                if (!r.deviceAccess) { json(r.out, 403, err("device_only")); return true }
                val result = DeviceRuntime.learning.answer(jsonObject(r.body))
                Diag.info("Quiz", "answer correct=${result.optBoolean("correct")} finished=${result.optBoolean("finished")} pending=${result.optBoolean("pending")} reward=${result.optDouble("rewardMinutes", 0.0)}")
                if (result.optBoolean("pending")) GradingService.enqueue(store, DeviceRuntime.learning, result.getString("reviewId"))
                json(r.out, 200, result)
                return true
            }
        }
        return false
    }

    /** 诊断日志：查看 / 清空 / 导出 */
    private fun diagnosticsRoutes(r: Req): Boolean {
        when {
            r.path == "/api/logs" && r.method == "GET" -> {
                json(r.out, 200, JSONObject()
                    .put("lines", Diag.read(context, r.q["limit"]?.toIntOrNull()?.coerceIn(50, 3000) ?: 800, r.q["q"] ?: "")))
                return true
            }
            r.path == "/api/logs" && r.method == "DELETE" -> {
                Diag.clear(context)
                json(r.out, 200, JSONObject().put("ok", true))
                return true
            }
            r.path == "/api/logs/export" && r.method == "GET" -> {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
                reply(r.out, 200, "application/zip", Diag.export(context),
                    mapOf("Content-Disposition" to "attachment; filename=\"studyontv-logs-$stamp.zip\""))
                return true
            }
        }
        return false
    }

    /** gzip 后的静态文件缓存（资源随 APK 固定不变，压缩一次即可） */
    private val gzipCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    // 包含安装时间：同版本号的重新安装 / 调试构建也会让浏览器拿到新页面
    private val buildStamp = try { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime } catch (_: Exception) { 0L }

    /** ETag 必须与具体资源绑定，否则 A 资源的校验结果会被误用到 B 资源上（304 误判） */
    private fun etagFor(assetPath: String): String =
        "\"" + BuildConfig.VERSION_CODE + "-" + buildStamp + "-" + Integer.toHexString(assetPath.hashCode()) + "\""

    private fun serveStatic(path: String, out: OutputStream, gzip: Boolean, ifNoneMatch: String?) {
        val (assetPath, contentType) = when {
            path == "/" || path == "/index.html" -> "web/index.html" to "text/html; charset=utf-8"
            path.startsWith("/admin/assets/") -> "admin" + path.removePrefix("/admin") to mime(path)
            path == "/admin" -> return reply(out, 301, null, null, mapOf("Location" to "/admin/"))
            path.startsWith("/admin") -> "admin/index.html" to "text/html; charset=utf-8"
            path.startsWith("/assets/") -> "web" + path to mime(path)
            else -> "web/index.html" to "text/html; charset=utf-8"
        }
        if (assetPath.contains("..")) return reply(out, 404, "text/plain; charset=utf-8", "404".toByteArray())
        // 带哈希的打包文件和字体可以长期缓存；页面本身每次校验（ETag = 版本号 + 资源路径，升级后自动失效）
        val hashed = Regex("-[A-Za-z0-9_-]{8}\\.(js|css)$").containsMatchIn(assetPath) || assetPath.contains("/vendor/")
        val cache = if (hashed) "public, max-age=31536000, immutable" else "no-cache"
        val etag = etagFor(assetPath)
        if (ifNoneMatch == etag) return reply(out, 304, null, null, mapOf("ETag" to etag, "Cache-Control" to cache))
        try {
            val compressible = contentType.startsWith("text/") || contentType.contains("javascript") || contentType.contains("json") || contentType.contains("svg")
            val extra = mutableMapOf("ETag" to etag, "Cache-Control" to cache)
            val bytes = if (gzip && compressible) {
                extra["Content-Encoding"] = "gzip"; extra["Vary"] = "Accept-Encoding"
                gzipCache.getOrPut(assetPath) {
                    val raw = assets.open(assetPath).use { it.readBytes() }
                    ByteArrayOutputStream().also { buf -> java.util.zip.GZIPOutputStream(buf).use { it.write(raw) } }.toByteArray()
                }
            } else assets.open(assetPath).use { it.readBytes() }
            reply(out, 200, contentType, bytes, extra)
        } catch (e: Exception) {
            reply(out, 404, "text/plain; charset=utf-8", "404".toByteArray())
        }
    }

    private fun mime(path: String): String = when {
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".js") -> "application/javascript; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") -> "image/jpeg"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".woff") -> "font/woff"
        path.endsWith(".ttf") -> "font/ttf"
        path.endsWith(".txt") -> "text/plain; charset=utf-8"
        else -> "application/octet-stream"
    }

    private fun json(out: OutputStream, code: Int, obj: JSONObject) =
        reply(out, code, "application/json; charset=utf-8", obj.toString().toByteArray())

    private fun err(code: String) = JSONObject().put("error", code)

    private fun reply(out: OutputStream, code: Int, contentType: String?, bytes: ByteArray?, extra: Map<String, String> = emptyMap()) {
        val body = bytes ?: ByteArray(0)
        val head = StringBuilder()
        head.append("HTTP/1.1 ").append(code).append(" ").append(reason(code)).append("\r\n")
        if (contentType != null) head.append("Content-Type: ").append(contentType).append("\r\n")
        head.append("Content-Length: ").append(body.size).append("\r\n")
        head.append("Connection: close\r\n")
        if (!extra.containsKey("Cache-Control")) head.append("Cache-Control: no-store\r\n")
        extra.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        head.append("Access-Control-Allow-Origin: *\r\n")
        head.append("Access-Control-Allow-Methods: GET,POST,PUT,DELETE,OPTIONS\r\n")
        head.append("Access-Control-Allow-Headers: Content-Type,Authorization,X-Admin-Token\r\n")
        head.append("Access-Control-Allow-Private-Network: true\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray())
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 204 -> "No Content"; 301 -> "Moved Permanently"; 304 -> "Not Modified"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 408 -> "Request Timeout"; 413 -> "Payload Too Large"
        429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"; 503 -> "Service Unavailable"
        else -> "OK"
    }

    private class LineTooLongException : Exception("请求头过长")

    private fun readLine(input: BufferedInputStream, limit: Int = MAX_LINE): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) {
                val bytes = buf.toByteArray()
                return String(bytes, 0, if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) bytes.size - 1 else bytes.size, Charsets.UTF_8)
            }
            if (buf.size() >= limit) throw LineTooLongException()
            buf.write(b)
        }
    }

    private fun readBody(input: BufferedInputStream, length: Int): String {
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    /** 只保留 body 的响应缓冲：给 WebView 内部调用复用同一套路由逻辑 */
    private class ResponseBuffer : OutputStream() {
        private val buf = ByteArrayOutputStream()
        override fun write(b: Int) { buf.write(b) }
        override fun write(b: ByteArray) { buf.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { buf.write(b, off, len) }
        fun body(): String {
            val bytes = buf.toByteArray()
            var i = 0
            while (i + 3 < bytes.size) {
                if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte() &&
                    bytes[i + 2] == '\r'.code.toByte() && bytes[i + 3] == '\n'.code.toByte())
                    return String(bytes, i + 4, bytes.size - i - 4, Charsets.UTF_8)
                i++
            }
            return ""
        }
    }

    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val MAX_LINE = 8 * 1024
        const val MAX_HEADERS = 100
        const val MAX_BODY = 4_000_000
        const val REQUEST_TIMEOUT_MS = 15_000L
    }
}
