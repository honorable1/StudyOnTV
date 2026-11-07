package com.studyontv.tv

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本检查：只使用蒲公英应用内更新接口（appKey + 当前版本）。
 * 不再访问 GitHub 或第三方镜像；蒲公英失败时由更新页引导用户手动下载。
 */
object Updater {
    const val OWNER_REPO = "chadyi/StudyOnTV"
    private const val GITHUB_API = "https://api.github.com/repos/$OWNER_REPO/releases/latest"
    private const val MANIFEST_PATH = "$OWNER_REPO/main/update.json"
    private val MANIFEST_URLS = listOf(
        "https://raw.githubusercontent.com/$MANIFEST_PATH",
        "https://cdn.jsdelivr.net/gh/$OWNER_REPO@main/update.json",
        "https://fastly.jsdelivr.net/gh/$OWNER_REPO@main/update.json",
        "https://gcore.jsdelivr.net/gh/$OWNER_REPO@main/update.json"
    )
    /** 公益 GitHub 加速镜像，随时可能失效；家长可在管理面板中修改 */
    val DEFAULT_MIRRORS = emptyList<String>()
    /** 只有这些域名才走镜像前缀拼接（蒲公英的安装地址套镜像没有意义） */
    private val MIRRORABLE = listOf("https://github.com/", "https://raw.githubusercontent.com/", "https://objects.githubusercontent.com/")
    private const val CACHE_MS = 6L * 60 * 60 * 1000
    private const val MAX_MANIFEST = 2_000_000

    data class Info(
        val latest: String,
        val current: String,
        val force: Boolean,
        val notes: String,
        val url: String,
        val sha256: String = "",
        val source: String = ""
    )

    fun api(ctx: Context): String = Prefs.updateApi(ctx)

    fun mirrors(ctx: Context): List<String> {
        val saved = Prefs.updateMirrors(ctx) ?: return DEFAULT_MIRRORS
        return saved.split('\n', ',', ' ').map { it.trim() }.filter { it.startsWith("https://") }
            .map { if (it.endsWith("/")) it else "$it/" }.distinct()
    }

    /**
     * 可用的 APK 下载地址：上次成功的线路优先，其次直连，再其次家长配置的镜像。
     * 镜像按「前缀 + 原始地址」拼接（如 https://ghfast.top/https://github.com/...）。
     */
    fun downloadCandidates(ctx: Context, url: String): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        list.add("直连" to url)
        if (MIRRORABLE.any { url.startsWith(it) }) {
            mirrors(ctx).forEach { base -> list.add("镜像" to base + url) }
        }
        val last = Prefs.updateLastSource(ctx)
        return list.sortedByDescending { if (it.first == last) 1 else 0 }
    }

    fun check(ctx: Context, forceRefresh: Boolean = false, onResult: (Info?) -> Unit) {
        val cachedAt = Prefs.updateCheckedAt(ctx)
        val cached = cachedInfo(ctx)
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cachedAt < CACHE_MS) {
            onResult(cached)
            return
        }
        Thread { onResult(checkNow(ctx).first) }.apply { isDaemon = true }.start()
    }

    private val checkingFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    val checking: Boolean get() = checkingFlag.get()

    /** 管理面板“立即检查”：后台执行，面板轮询 status()（不能加锁，否则会等正在进行的检查） */
    fun startCheck(ctx: Context) {
        if (!checkingFlag.compareAndSet(false, true)) return
        Thread { try { checkNow(ctx) } finally { checkingFlag.set(false) } }.apply { isDaemon = true }.start()
    }

    /** 同步检查；返回结果和每个来源的尝试记录（给管理面板显示） */
    @Synchronized
    fun checkNow(ctx: Context): Pair<Info?, JSONArray> {
        val log = JSONArray()
        val current = currentVersion(ctx)
        var info: Info? = null

        /** @return true 表示这一来源已经给出结论（有新版本，或确认已是最新），无需再试下一个 */
        fun manifestAttempt(name: String, endpoint: String): Boolean {
            val started = System.currentTimeMillis()
            return try {
                val data = fetch(endpoint)
                val parsed = if (data == null) null else parse(data, current, name)
                val newer = parsed != null && isNewer(parsed.latest, current)
                log.put(JSONObject().put("source", name).put("ok", parsed != null).put("ms", System.currentTimeMillis() - started)
                    .put("message", when {
                        data == null -> "没有返回数据"
                        parsed == null -> "没有本机可用的版本"
                        newer -> "v" + parsed.latest
                        else -> "当前已是最新版本"
                    }))
                if (newer) { info = parsed; true } else parsed != null
            } catch (e: Exception) {
                Diag.warn("Update", "$name 检查失败：" + (e.message ?: e.javaClass.simpleName))
                log.put(JSONObject().put("source", name).put("ok", false).put("ms", System.currentTimeMillis() - started)
                    .put("message", e.message?.take(160) ?: e.javaClass.simpleName))
                false
            }
        }

        var resolved = false
        // 1) 家长自建的更新地址（面板里配置的 api），显式配置优先
        val custom = api(ctx).trim()
        if (custom.isNotEmpty()) resolved = manifestAttempt("自定义", custom)
        // 2) 蒲公英（国内可直连，是当前的发布渠道）
        if (!resolved) {
            val started = System.currentTimeMillis()
            try {
                val pgyer = Pgyer.info(ctx, current, currentVersionCode(ctx))
                log.put(JSONObject().put("source", "蒲公英").put("ok", pgyer != null).put("ms", System.currentTimeMillis() - started)
                    .put("message", if (pgyer == null) "当前已是最新版本" else "v" + pgyer.latest))
                if (pgyer != null) info = pgyer
                resolved = true      // 能连上就以蒲公英的结论为准，不再增加无谓的超时等待
            } catch (e: Exception) {
                Diag.warn("Update", "蒲公英检查失败：" + (e.message ?: e.javaClass.simpleName))
                log.put(JSONObject().put("source", "蒲公英").put("ok", false).put("ms", System.currentTimeMillis() - started)
                    .put("message", e.message?.take(160) ?: e.javaClass.simpleName))
            }
        }
        // 3) 更新清单（多个 CDN），4) GitHub Release —— 只在前面都不可用时兜底
        if (!resolved) {
            MANIFEST_URLS.forEachIndexed { index, endpoint ->
                if (!resolved) resolved = manifestAttempt("更新清单${index + 1}", endpoint)
            }
        }
        if (!resolved) manifestAttempt("GitHub Release", GITHUB_API)

        Prefs.setUpdateInfo(ctx, info?.let { toJson(it).toString() } ?: "")
        Prefs.setUpdateCheckedAt(ctx, System.currentTimeMillis())
        Prefs.setUpdateLog(ctx, JSONObject().put("at", System.currentTimeMillis()).put("items", log).toString())
        return info to log
    }

    fun toJson(info: Info): JSONObject = JSONObject()
        .put("latest", info.latest).put("current", info.current).put("force", info.force)
        .put("notes", info.notes).put("url", info.url).put("sha256", info.sha256).put("source", info.source)

    fun cachedInfo(ctx: Context): Info? {
        val raw = Prefs.updateInfo(ctx)
        if (raw.isEmpty()) return null
        return try {
            val o = JSONObject(raw)
            Info(o.optString("latest"), o.optString("current"), o.optBoolean("force"),
                o.optString("notes"), o.optString("url"), o.optString("sha256"), o.optString("source"))
        } catch (e: Exception) {
            null
        }
    }

    private fun fetch(endpoint: String): JSONObject? {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 6000
        conn.setRequestProperty("User-Agent", "StudyOnTV-Updater")
        conn.setRequestProperty("Accept", "application/vnd.github+json, application/json")
        return try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException(if (code == 403 || code == 429) "HTTP $code（请求太频繁或被限流）" else "HTTP $code")
            if (Net.declaredLength(conn) > MAX_MANIFEST) throw IllegalStateException("响应过大")
            val text = try { Net.text(conn.inputStream, MAX_MANIFEST) } catch (_: Net.TooLarge) { throw IllegalStateException("响应过大") }
            JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private val flavorMarker get() = if (BuildConfig.FLAVOR == "tv") "-TV-" else "-Mobile-"

    /** 同时支持 GitHub Release JSON 和 update.json 清单 */
    private fun parse(data: JSONObject, current: String, source: String): Info? =
        if (data.has("tag_name")) parseRelease(data, current, source) else parseManifest(data, current, source)

    private fun parseRelease(release: JSONObject, current: String, source: String): Info? {
        val tag = release.optString("tag_name").removePrefix("v").trim()
        if (tag.isEmpty()) return null
        val body = release.optString("body")
        val assets = release.optJSONArray("assets")
        val candidates = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
        val selected = candidates.firstOrNull { it.optString("name").contains(flavorMarker, true) && it.optString("name").endsWith(".apk", true) }
            ?: if (BuildConfig.FLAVOR == "tv") candidates.firstOrNull { it.optString("name").matches(Regex("StudyOnTV-v[0-9.]+\\.apk")) } else null
        val url = selected?.optString("browser_download_url")?.takeIf { it.isNotEmpty() } ?: return null
        // GitHub 会给 Release 附件计算 digest（sha256:...），用于校验镜像下载的文件
        val digest = selected.optString("digest").takeIf { it.startsWith("sha256:", true) }?.substring(7).orEmpty()
        val force = body.contains("[force]", true) || body.contains("force update", true)
        return Info(tag, current, force && isNewer(tag, current), body.trim(), url, digest.lowercase(), source)
    }

    /**
     * update.json 格式（由 scripts/make-update-manifest.mjs 生成）：
     * {"version":"0.2.2","notes":"…","force":false,
     *  "assets":{"tv":{"url":"https://github.com/…/StudyOnTV-TV-v0.2.2.apk","sha256":"…"},"mobile":{…}}}
     */
    private fun parseManifest(manifest: JSONObject, current: String, source: String): Info? {
        val version = manifest.optString("version").removePrefix("v").trim()
        if (version.isEmpty()) return null
        val asset = manifest.optJSONObject("assets")?.optJSONObject(if (BuildConfig.FLAVOR == "tv") "tv" else "mobile") ?: return null
        val url = asset.optString("url").takeIf { it.startsWith("https://") } ?: return null
        val notes = manifest.optString("notes")
        val force = manifest.optBoolean("force") || notes.contains("[force]", true)
        return Info(version, current, force && isNewer(version, current), notes.trim(), url, asset.optString("sha256").lowercase(), source)
    }

    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".", "-").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".", "-").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun currentVersion(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0" } catch (e: Exception) { "0.0.0" }

    fun currentVersionCode(ctx: Context): Int = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    } catch (_: Exception) { 0 }

    fun newerThanCurrent(ctx: Context, info: Info?): Info? =
        if (info != null && isNewer(info.latest, currentVersion(ctx))) info else null

    /** 管理面板用：当前状态 */
    fun status(ctx: Context): JSONObject {
        val info = cachedInfo(ctx)
        val log = try { JSONObject(Prefs.updateLog(ctx)) } catch (_: Exception) { JSONObject() }
        return JSONObject().put("current", currentVersion(ctx))
            .put("latest", info?.latest ?: "").put("hasUpdate", newerThanCurrent(ctx, info) != null)
            .put("notes", info?.notes ?: "").put("source", info?.source ?: "")
            .put("checkedAt", Prefs.updateCheckedAt(ctx)).put("checking", checking)
            .put("logAt", log.optLong("at"))
            .put("api", api(ctx)).put("mirrors", JSONArray(mirrors(ctx))).put("defaultMirrors", JSONArray(DEFAULT_MIRRORS))
            .put("lastDownloadSource", Prefs.updateLastSource(ctx))
            .put("log", log.optJSONArray("items") ?: JSONArray())
    }

    fun saveSettings(ctx: Context, input: JSONObject): JSONObject {
        if (input.has("api")) {
            val api = input.optString("api").trim()
            require(api.isEmpty() || api.startsWith("https://")) { "自定义更新地址需要以 https:// 开头" }
            Prefs.setUpdateApi(ctx, api)
        }
        if (input.has("mirrors")) {
            val raw = input.get("mirrors")
            val list = if (raw is JSONArray) (0 until raw.length()).map { raw.optString(it) } else raw.toString().split('\n', ',')
            val clean = list.map { it.trim() }.filter { it.isNotEmpty() }
            clean.forEach { require(it.startsWith("https://")) { "镜像地址需要以 https:// 开头：$it" } }
            require(clean.size <= 10) { "最多填写 10 个镜像" }
            Prefs.setUpdateMirrors(ctx, if (input.optBoolean("resetMirrors")) null else clean.joinToString("\n"))
        }
        Prefs.setUpdateCheckedAt(ctx, 0)
        return status(ctx)
    }
}
