# StudyOnTV 代码审查报告

- 审查对象：`honorable1/StudyOnTV`（v0.3.15 / `af1bbf7`）
- 审查范围：`app/native`（Kotlin，45 个源文件）、`app/web`（原生 JS）、`admin`（含打包产物，仅审非压缩源码）、`scripts`、`data`
- 审查重点：鉴权与越权、数据一致性、边界条件、性能热路径、可维护性
- 未覆盖：`admin/assets/index-*.js` 等压缩产物（不可维护，建议改造为源码 + 构建流程）

严重程度定义：
- **P0 严重** — 可导致控制权被夺取、管控完全失效、数据损坏
- **P1 高** — 明确的功能缺陷、可被利用的性能/资源问题
- **P2 中** — 边界情况、局部性能、可维护性缺陷
- **P3 轻** — 代码风格、可读性

---

## P0 严重

### P0-1 局域网明文 HTTP 承载家长凭据，同一 WiFi 下任何人可夺取控制权

**位置**：`LanServer.kt:66-71`、`:114-120`、`:419-436`

```kotlin
// LanServer.kt:67
socket = ServerSocket(p)          // 绑定 0.0.0.0，监听所有网卡
```

```kotlin
// LanServer.kt:114-120
if (path == "/api/auth" && method == "POST") {
    val session = AdminAuth.login(context, input.optString("passphrase"), remember)
```

**问题**
1. `ServerSocket(p)` 未指定 `bindAddr`，服务对整个局域网开放。
2. 协议是裸 HTTP，没有 TLS。`POST /api/auth` 的 `passphrase` 明文传输。
3. 会话令牌通过 `X-Admin-Token` 头明文传输，同样可被嗅探重放。

**影响**：同一 WiFi 下任何设备抓一次包，即可获得家长密码或会话令牌，进而：
- `PUT /api/config` 改掉所有规则（`LanServer.kt:177`）
- `POST /api/password` 改掉管理密码（`:243`）
- `DELETE /api/device/uninstall-guard` 关掉防卸载（`:296`）
- `POST /api/integration` 给自己签发长期集成令牌（`:261`）

对一个"防止孩子绕过管控"的产品，这是最致命的一类缺陷——孩子只要会用手机抓包就能完全接管。

**建议**
- 短期（改动小、收益最大）：`/api/auth` 改为先 `GET /api/auth/challenge` 拿一次性随机数，客户端提交 `HMAC-SHA256(password, challenge)`，密码永不上网；会话令牌仍可明文，但配合下面的绑定策略。
- 中期：在 App 内生成自签证书，面板首次连接时做指纹确认（TOFU）。局域网场景不需要 CA。
- 兜底：至少把 `X-Admin-Key`（见 P0-3）去掉，让密码不能被重放。

---

### P0-2 密码失败计数被清零，锁定形同虚设

**位置**：`AdminAuth.kt:36-49`

```kotlin
val ok = MessageDigest.isEqual(expected, hash(value, salt))
if (ok) p.edit().remove("failures").apply()
else {
    val failures = p.getInt("failures", 0) + 1
    p.edit().putInt("failures", if (failures >= 5) 0 else failures)   // ← 归零
        .putLong("blocked_until", if (failures >= 5) System.currentTimeMillis() + 60000 else 0).commit()
}
```

**问题**：第 5 次失败后计数被重置为 0，只锁 60 秒。60 秒后又有完整 5 次机会。
等价于**恒定 5 次/分钟、无上限、无退避**的暴力破解窗口。

配合 P0-1（局域网内可远程爆破），一个 6 位纯数字密码在约 139 天内可被穷举完；若家长用了常见弱口令，几分钟内即被攻破。

另外 `blocked_until` 生效时函数直接 `return false`，**锁定期间的尝试不计入任何计数**，攻击者无法被"记账"。

**建议**
```kotlin
val failures = p.getInt("failures", 0) + 1
val total    = p.getInt("failures_total", 0) + 1
// 指数退避：1m → 5m → 30m → 2h → 24h
val backoff = when {
    failures >= 20 -> 24L * 3600_000
    failures >= 10 -> 2L * 3600_000
    failures >= 5  -> 30L * 60_000
    else           -> 0L
}
p.edit().putInt("failures", failures)          // 不再归零
    .putInt("failures_total", total)
    .putLong("blocked_until", System.currentTimeMillis() + backoff)
    .commit()
```
并增加按来源 IP 的独立计数（`LanServer` 已有 `c.inetAddress`），避免换设备重置。当日失败超过阈值时给家长推一条通知（`GateService` 已有通知通道）。

---

### P0-3 `X-Admin-Key` 把明文密码当作可重放凭据，且每个请求都跑一次 PBKDF2

**位置**：`LanServer.kt:122-128`、`AdminAuth.kt:21-23`

```kotlin
// LanServer.kt:122-127
val adminKey = headers["x-admin-key"]
val parent = AdminAuth.valid(token) || (adminKey != null && AdminAuth.verify(context, adminKey))
```

**问题**
1. 明文管理密码可以直接塞进请求头，通过**任意** `/api/*` 校验。它不会过期、不受会话有效期约束，等于一个永久凭据。密码一旦泄露（见 P0-1），无法靠"重新登录"补救，只能改密码。
2. `AdminAuth.verify()` 每次都要跑 `PBKDF2WithHmacSHA1 / 60000 轮 / 256 位`，而且是 `@Synchronized` 的**全局对象锁**。面板一次加载会发十几个请求，若走 `X-Admin-Key` 路径，会把 `LanServer` 的 6 个工作线程和整个鉴权锁全部串行堵死。在低端电视 CPU 上单次 PBKDF2 约 100–200ms，10 个请求就是 1–2 秒的全面卡顿。

**建议**
- 删除 `X-Admin-Key` 分支，只保留 `/api/auth` 换取的会话令牌（`AdminAuth.valid`）。
- 若必须保留"脚本调用"场景，改为在 `/api/auth` 时签发一个长期 token（一次 PBKDF2），之后只比对 token 的 SHA-256。
- `valid()` 用 `ConcurrentHashMap` 替代 `@Synchronized`，避免读路径争锁。

---

### P0-4 设备令牌直接交给 WebView，答题接口仅凭它放行

**位置**：`DeviceBridge.kt:26`、`LanServer.kt:336-342`、`app.js:18`

```kotlin
// DeviceBridge.kt:26
@JavascriptInterface fun apiToken(): String = AdminAuth.deviceToken
```
```javascript
// app.js:18
xhr.setRequestHeader('X-Device-Token', nativeCall('apiToken') || '');
```

**问题**：`/api/quiz/answer`、`/api/quiz/start`、`/api/quiz/skip` 只校验 `deviceAccess`（loopback + 这个令牌）。令牌交给 JS 之后，任何在 WebView 里执行的脚本都能直接构造答题请求，**绕过整个答题流程**（直接提交 `selections` 拿奖励）。

当前之所以还没出事，是因为题干/选项都用 `textContent` 渲染（`app.js:104`、`:144`），没有 XSS 入口——**这是单点防御**。一旦将来有人为了排版改成 `innerHTML`，或者第三方 KaTeX 扩展、`qrcode.min.js` 供应链出问题，管控立刻全线失守。

**建议**
- 把 `apiToken()` 从 JS 侧移除，改由原生层代发答题请求（JS 调用 `SOTVNative.submitAnswer(json)`，原生走内部方法）。
- 若必须保留 HTTP 通道，把 `deviceAccess` 收紧为"来源是 loopback **且** 请求头带 WebView 注入的一次性随机 nonce"，该 nonce 每次页面加载刷新、只存内存。
- 补一条回归测试：断言 `SOTVNative` 不暴露任何返回凭据的方法。

---

## P1 高

### P1-1 「免费时长还有余额」提前放行，直接绕过时段管控

**位置**：`LearningEngine.kt:150-159`

```kotlin
val reason = when {
    o?.optString("mode") == "lock" -> "locked"
    free -> ""
    freeQuota > 0 -> ""   // ← 每日默认免费时长还有余额，放行
    used >= c.getLong("dailyMinutes") * 60000 -> "quota"
    remaining <= 0 && rounds >= c.getInt("roundsPerDay") -> "rounds"
    c.optString("scheduleMode", "allow") != "control" && !inWindow(c) -> "schedule"
    ...
}
```

**问题**：`freeQuota > 0` 排在 `schedule` 之前。只要家长配置了 `dailyFreeMinutes > 0`，**任何时段**都会返回空 reason（放行），夜间时段形同不存在。

同一个坑在 `consume()` 也有一份（`LearningEngine.kt:177-183`）：消耗免费额度时只扣 `free_quota_ms`，不检查 `dailyMinutes` 上限，导致 `used_ms` 可以超过家长设定的每日总量。

**建议**：免费额度应当只放宽"余额"这一维度，不应放宽"时段"：
```kotlin
val inAllowedWindow = c.optBoolean("allDay") || inWindow(c)
val reason = when {
    o?.optString("mode") == "lock" -> "locked"
    free -> ""
    c.optString("scheduleMode", "allow") != "control" && !inAllowedWindow -> "schedule"
    freeQuota > 0 -> ""
    used >= c.getLong("dailyMinutes") * 60000 -> "quota"
    remaining <= 0 && rounds >= c.getInt("roundsPerDay") -> "rounds"
    remaining > 0 -> ""
    else -> "timeup"
}
```
`consume()` 里同步加上 `used_ms + freeConsumed <= dailyMinutes * 60000` 的封顶。

---

### P1-2 每秒一次的全量 JSON 解析 + 全表扫描

**位置**：`GateService.kt:48-52`、`LocalStore.kt:131-166`、`:399-424`、`app.js:434`

调用链：
```
GateService.tick（每 1s） → LearningEngine.status()
                          → store.config()          // 全量 JSON 解析 + 3 条正则 + 周表归一化
                          → store.roundsDoneToday() // SQLite COUNT
                          → override()              // 可能写库
```

`/api/state` 走的是另一条更重的路：
```kotlin
// LocalStore.kt:399-418
fun stats(start: Long, end: Long): JSONObject {
    readableDatabase.rawQuery("SELECT type,payload FROM events WHERE ts>=? AND ts<?", ...).use { c ->
        while (c.moveToNext()) {
            val p = try { JSONObject(c.getString(1)) } catch (e: Exception) { JSONObject() }   // ← 逐行 JSON 解析
```
而 `todayStats()` 用 `Long.MAX_VALUE` 作上界（`LocalStore.kt:420`），前端每 2 秒轮询一次（`app.js:434`）。

**影响**：一天下来 events 表即使做了 30 秒聚合也有数千行；每次轮询都要全量反序列化。低端电视/盒子上会表现为面板卡顿、答题响应变慢。

**建议**
1. `config()` 加内存缓存，用 `config.version` 做失效依据（`LocalStore.kt:280` 已经在维护 version）。
2. `stats()` 改 SQL 聚合，避免应用层解析：
   ```sql
   SELECT
     SUM(CASE WHEN type='usage' THEN CAST(json_extract(payload,'$.milliseconds') AS INTEGER) ELSE 0 END),
     SUM(CASE WHEN type='answer' THEN 1 ELSE 0 END),
     ...
   FROM events WHERE ts>=? AND ts<?
   ```
   （SQLite 3.9+ 才有 `json_extract`，Android 5 需 fallback；更稳的做法是单独建 `daily_usage(day, ms, answered, correct, rounds)` 汇总表，写事件时同步累加。）
3. `status()` 结果做 1 秒 TTL 缓存，`GateService` 和 HTTP 接口共享。
4. `todayStats()` 的上界改成「明天 0 点」（见 P2-6）。

---

### P1-3 `exempt()` 每秒多次调用 `resolveActivity`

**位置**：`GateService.kt:211-218`

```kotlin
fun exempt(pkg: String, includeLauncher: Boolean = true): Boolean {
    ...
    val launcher = packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    return pkg == launcher || Foreground.isLauncher(pkg)
}
```

`checkForeground()` 里 `exempt()` 被调用 3~4 次（`:67`、`:68`、`:83`），即每秒 3~4 次 PackageManager IPC。

**建议**：`Foreground` 里已经有 `launcherPackages` 缓存集合（`Foreground.kt:27-33`），直接复用；确实需要动态解析时用 `by lazy` 缓存在 Service 实例上。

---

### P1-4 HTTP 响应无读取上限，先读后校验

**位置**：`GradingService.kt:104-107`、`ApkInstaller.kt:80-95`、`Pgyer.kt:45-47`

```kotlin
// GradingService.kt:105-106
val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)   // ← 先全量读进内存
if (bytes.size > 2_000_000) throw ApiError("响应过大")        // ← 才校验
```

`ApkInstaller.downloadFrom()` 完全没有字节上限，`cacheDir/updates` 可以被写满。

**影响**：一个被劫持/恶意的 AI 服务或镜像可以直接把 App 打 OOM；APK 下载可写满设备存储（对一个装在孩子设备上的管控类 App，等同于让对方"清空"手机）。

**建议**：统一封装一个 `readLimited(stream, maxBytes)`，边读边累计，超限抛异常并断开连接；同时先看 `Content-Length` 做预检。

---

### P1-5 更新源配置项是死配置，面板显示与实际行为不一致

**位置**：`Updater.kt:38-48`、`:71-89`、`:195-211`

`saveSettings()` 允许家长填写 `api`（自定义更新地址）和 `mirrors`（最多 10 个镜像），并做 https 校验；`status()` 也会把它们回显到面板（`:190`）。

但实际检查更新只走蒲公英：
```kotlin
// Updater.kt:77
info = Pgyer.info(ctx, current, currentVersionCode(ctx))
```
```kotlin
// Updater.kt:47-48
fun downloadCandidates(ctx: Context, url: String): List<Pair<String, String>> =
    listOf("蒲公英" to url)
```

`api(ctx)` / `mirrors(ctx)` 只被 `status()` 使用，`fetch()`（`:107`）无人调用，`parse()` / `parseRelease()` / `parseManifest()` / `MANIFEST_URLS` / `GITHUB_API` 全是死代码。

**影响**：家长以为配了镜像就会走镜像，实际不会；一旦蒲公英域名不可达（这在当前网络环境下是常态），更新功能静默失效且无从排查。

**建议**：二选一——要么删掉 `api`/`mirrors` 相关配置与 UI，要么把 `checkNow()` 改成依次尝试 `api` → `MANIFEST_URLS` → `Pgyer`，并复用到 `downloadCandidates`。当前这种"配置存在但不生效"的状态最糟。

---

### P1-6 埋点把未成年人页面行为发往境外服务

**位置**：`app/web/assets/js/analytics.js:13-58`

```javascript
var CONFIG = {
  ga4Id: 'G-561826488',
  clarityId: 'z7790sezw1',
  enabled: true,
```

`docs/analytics.md` 声明"不发题干、不发孩子信息"，事件列表也确实只带 `page`/`title`/`correct`/`total` 这类聚合值。

**但**：Microsoft Clarity **默认开启会话录制与点击热图**，会把页面渲染内容录下来，包括屏幕上的题干、孩子输入的答案。这一点在文档里没有说明，也不受"不发题干"的约束。

叠加因素：这是面向国内儿童的教育类应用，向 GA4 / Clarity 传输数据涉及个人信息出境，属于合规敏感项。

**建议**
- 默认 `enabled: false`，在首次启动时的家长引导页显式征询同意。
- 若保留 Clarity，必须开启文本遮罩：`clarity('set', 'maskText', true)` / CSS `data-clarity-mask="true"`，并关闭 `upload` 之外的录屏能力。
- 更稳妥：改用自建/国内合规的统计，或只保留本地 `js_error` 计数。

---

## P2 中

### P2-1 ETag 全站共用一个值，跨资源会误判 304

**位置**：`LanServer.kt:367`、`:382`

```kotlin
private val etag = "\"" + BuildConfig.VERSION_CODE + "-" + lastUpdateTime + "\""
...
if (ifNoneMatch == etag) return reply(out, 304, ...)
```

`ifNoneMatch` 不跟 `assetPath` 关联。浏览器对 A 资源拿到的 ETag 去校验 B 资源，同样命中 304。多数浏览器按 URL 存 ETag 所以暂时没暴露，但这是"依赖实现巧合"的隐患，且与注释里"页面本身每次校验"的设计相悖。

**建议**：`etag = "\"$version-$lastUpdate-${assetPath.hashCode()}\""`，或直接用 `长度+最后修改时间`。

### P2-2 `config()` 里一行链式调用会连带清空答题范围

**位置**：`LocalStore.kt:163-164`

```kotlin
if (defaults.optString("holidayMode") !in listOf("sunday", "normal")) defaults.put("holidayMode", "sunday")
    .put("quizSubjects", JSONArray()).put("quizLevels", JSONArray())
```

`holidayMode` 非法时会顺带把 `quizSubjects` / `quizLevels` 清空。从上下文看只是调试残留，但它挂在 `if` 上，一旦触发就会静默丢失家长配置的答题范围。

**建议**：拆成独立语句，只修 `holidayMode`；如果确实需要重置范围，单独写并记一条 `Diag.warn`。

### P2-3 `nextQuestion()` 在内存里过滤科目/年级

**位置**：`LearningEngine.kt:215-222`

```kotlin
val all = store.questions(limit = 5000, spaced = ...)   // 拉 5000 条，全部 JSON 化
for (i in 0 until all.length()) { ... if (subjects.isNotEmpty() && ...) continue }
```

而 `LocalStore.questions()` 本来就支持 `subject` / `level` 参数（`LocalStore.kt:289-290`）。当前写法每出一题就反序列化最多 5000 条题目。

**建议**：把范围下推到 SQL。若需要"多科目"，把 `subject IN (?,?,...)` 动态拼占位符（保持参数绑定，不要字符串拼接）。

### P2-4 `readLine()` 无长度上限，配合固定线程池可做局域网 DoS

**位置**：`LanServer.kt:444-457`、`:40`

```kotlin
private fun readLine(input: BufferedInputStream): String? {
    val buf = ByteArrayOutputStream()
    while (true) {
        val b = input.read()
        if (b == -1) ...
        if (b == '\n'.code) ...
        buf.write(b)          // ← 没有上限
```

只要不发换行，这一个连接就能把内存吃满；再配合 `Executors.newFixedThreadPool(6)`，开 6 个慢连接即可让家长面板完全无法访问（`soTimeout=8000` 只能限制"空转"，逐字节发送可以一直续命）。

**建议**：行长度上限 8KB、头部总条数上限 100、整个请求 10 秒硬超时；超限直接关连接。线程池改用 `ThreadPoolExecutor` + 有界队列 + `RejectedExecutionHandler` 快速返回 503。

### P2-5 `consume()` 每秒一个写事务

**位置**：`LearningEngine.kt:172-196`

每秒钟：`beginTransaction` → 2 次 `REPLACE INTO kv` → 可能 1 次 `INSERT INTO events` → `commit`。注释写的是"余额每次都持久化"，但对低端电视来说 SQLite 每秒同步写是主要耗电与卡顿来源。

**建议**：余额在内存累加，按「每 10 秒 / 余额变化 ≥ 30 秒 / 进程退到后台 / 锁屏」四个时机落盘。`onDestroy()` 里已有 `flushUsage()`（`GateService.kt:255`），再补一个 `onPause` 落盘即可保证不丢。

### P2-6 `todayStats()` 用 `Long.MAX_VALUE` 作为时间上界

**位置**：`LocalStore.kt:420`

```kotlin
fun todayStats(): JSONObject = stats(startOfToday(), Long.MAX_VALUE)
```

未来时间戳的事件（如 P2-8 里 `nextAvailable()` 回落到 `now` 之类的时间计算异常、或系统时间被改后写入的记录）都会被算进今天。

**建议**：`stats(startOfToday(), startOfToday() + 86400000)`。

### P2-7 AI/家长批改结果可能因轮次变化而永久丢失

**位置**：`LearningEngine.kt:325-335`、`:298-320`、`GradingService.kt:259-293`

```kotlin
// LearningEngine.kt:335
require(input.getString("roundId") == round.getString("id") && input.getString("nonce") == round.getString("nonce")) { "题目已更新，请重新加载" }
```

`grade()` 会拿 review 里存的**原始 input** 去调 `answer()`。如果等待批改期间 round 变了（改配置后 `start()` 重建、`skipPending()` 推进、应用重启后 round 被替换），`require` 失败 → 整笔批改结果无法落库。`GradingService.enqueue()` 的 catch 只会写一句"AI 暂未完成批改"，结果静默丢失。

**建议**：给 `answer()` 增加"补结算"入口——按 `reviewId` 直接结算（用 review 里存的 question 快照与判分结果），不依赖当前 round 的 nonce；或者把 `round` 快照一并存进 review，结算时以此为准。

### P2-8 找不到可用时段时返回"现在"，前端显示与实际矛盾

**位置**：`LearningEngine.kt:53-78`

`nextAvailable()` 只往后看 8 天（`for (offset in 0..8)`）。如果 `weekSlots` 七天全是 `0`，循环不会命中，最后 `return base.timeInMillis` —— 即"当前时间"。

结果：`/api/lock` 页面会显示"下次可用：现在 10:32"，但实际仍然锁定（`app.js:279-283`）。

**建议**：找不到时返回 `-1`，前端据此显示"暂无可用时段，请联系家长调整规则"。同时在 `saveConfig` 里拦住"七天全 0"的提交。

### P2-9 `answer()` 里重复且互相覆盖的赋值

**位置**：`LearningEngine.kt:423-426`

```kotlin
if (mode == "streak" && !baseDone) round.put("extra", false)
nextQuestion(round)                                   // 内部已经计算过 extra
if (mode == "streak" && !baseDone) round.put("extra", false)
```

`nextQuestion()` 末尾已经写过一次 `round` 里的 `extra`（`:233-234`），前后两次覆盖让"加练题为什么会/不会出现"变得无法推理，也是排查加练池问题时最容易踩坑的地方。

**建议**：删掉这两行，只保留 `nextQuestion()` 内的单一计算源；若 `streak` 有特殊语义，把它作为参数传进去，而不是事后打补丁。

### P2-10 未知 `/api/*` 路径静默返回首页 200

**位置**：`LanServer.kt:144-358`

`when` 没有 `else` 分支，未匹配的 `/api/foo` 会继续走到 `serveStatic()`，最终返回 `web/index.html` + 200 + `text/html`。

**影响**：前端拿到的不是 JSON，`JSON.parse` 失败后统一报"连接设备失败"（`app.js:22`），排查时无法区分"接口不存在"和"设备离线"。

**建议**：`when` 之后加：
```kotlin
if (path.startsWith("/api/")) return json(out, 404, err("not_found"))
```

### P2-11 `ApkInstaller.install()` 吞掉异常且不写 `lastError`

**位置**：`ApkInstaller.kt:112-129`

```kotlin
} catch (e: Exception) {
    false
}
```

下载链路里每个失败点都会写 `lastError`，唯独最后一步"拉起安装器"不写，`UpdateActivity` 只能显示一个笼统的失败。

**建议**：`lastError = "无法打开安装器：" + (e.message ?: e.javaClass.simpleName)`。

---

## P3 可读性 / 可维护性

| 位置 | 问题 | 建议 |
|---|---|---|
| `LanServer.kt:1-31, 73, 102, 356, 362-363, 443, 470` | 大段连续空行，像是批量删代码的残留 | 清理；配 `.editorconfig` |
| `LanServer.kt:444-457` | `var prev = -1` 赋值后从未读取 | 删除 |
| `LanServer.kt:108-111` | 手写 URL decode，键名未解码、异常未处理 | 换 `Uri.parse(url).getQueryParameter(k)` |
| `LanServer.kt:107-359` | `route()` 是 240 行的巨型 `when`，鉴权、业务、静态资源混在一起 | 按域拆 `authRoutes` / `quizRoutes` / `deviceRoutes` / `gradingRoutes`，各建 `Map<Route, Handler>` |
| `LanServer.kt:101-103` | `catch (e: Exception) { }` 完全静默 | 至少 `Diag.warn("HTTP", ..., e)` |
| `app.js:355-384` | `activate()` 用一串独立 `if`，不是 `switch` | 改 `switch`；现在若两个 action 同名会双触发 |
| `GateService.kt:211-218` | `exempt()` 用 `contains("keyboard")` / `contains("inputmethod")` 子串匹配 | 精确包名白名单，子串匹配可被同名应用蹭过去 |
| `AdminAuth.kt:12` | `sessions` 只在 `login()` 时清理过期项 | 定时清理或换带 TTL 的 LRU |
| `LocalStore.kt`（444 行） | 一个类同时负责 schema、迁移、题目 DAO、配置、统计、复习进度 | 拆 `QuestionsDao` / `ConfigStore` / `UsageLog` / `ProgressStore` |
| `GradingService.kt:38`、`LocalStore.kt:38` | `JSONObject(rawString)` 直接构造，非法 JSON 直接抛 | 统一 `runCatching { ... }.getOrDefault(JSONObject())`，已有辅助函数可复用 |
| `LearningEngine.kt:143, 185, 208, 413` | `store.config()` 在同一次调用链里最多解析 4 次 | 由调用方传入 config 参数 |
| `scripts/` | 三个 `.mjs` 直接 `import`，但**没有 `package.json`**，`make-shots.mjs` 依赖 puppeteer 却未声明 | 补 `package.json` + lock，或移到 `tools/` 并在 README 写清安装步骤 |
| `admin/assets/index-*.js` | 交付的是压缩产物，无可读源码 | 补 React 工程源码 + 构建脚本，否则面板无法维护 |

---

## 建议的修复顺序

| 顺序 | 项目 | 理由 | 预估改动面 |
|---|---|---|---|
| 1 | P0-2 失败计数与指数退避 | 单文件、十几行，直接消除暴力破解 | 极小 |
| 2 | P0-3 移除 `X-Admin-Key` | 收益大、风险低 | 小 |
| 3 | P1-1 免费额度绕过时段 | 一行 `when` 顺序调整，影响核心正确性 | 极小 |
| 4 | P2-4 `readLine` 上限 + 线程池有界 | 消除局域网 DoS | 小 |
| 5 | P0-1 局域网明文凭据 | 需引入挑战-响应，改动最大但最根本 | 中 |
| 6 | P1-2 / P1-3 性能热路径 | 电视端体验，需要压测验证 | 中 |
| 7 | P0-4 收回 JS 侧设备令牌 | 需要改 JS 与原生桥接口 | 中 |
| 8 | P1-6 埋点默认关闭 + 文本遮罩 | 合规 | 小 |
| 9 | P1-4 / P1-5 | 健壮性 | 小 |
| 10 | 其余 P2 / P3 | 随迭代清理 | — |

## 验证建议

- **P0-2**：写一个 JVM 单测（`AdminAuth` 无 Android 依赖的部分可抽出），断言"连续 30 次错误后 `blocked_until` 递增且 `failures` 不清零"。
- **P1-1**：单测覆盖 `status()` 在 `{freeQuota>0, 时段外}` 组合下应返回 `"schedule"`。
- **P2-4**：用 `nc` 向 8099 端口发送 1MB 不含换行的数据，断言连接在 8KB 处被断开。
- **P1-2**：在真机上用 `adb shell dumpsys gfxinfo` 对比优化前后的帧率，并把 `Diag` 里 `Gate` 快照的耗时打点加上。
- **P0-1/P0-4**：装一个抓包工具（同一 WiFi）复核 `/api/auth` 请求体是否已不含明文密码。

---

*本报告基于 `feature/dev` 分支 `af1bbf7` 阅读源码得出，未做动态验证。行号以该提交为准。*

---

## 修复状态（2026-11-08 更新）

P0 / P1 / P2 全部 21 项、P3 12/13 项已在 `feature/dev` 上落地，逐项验证记录见提交说明。

| 批次 | 提交 | 内容 |
|---|---|---|
| P0-P2 | `2e84afd` | 21 项全部修复（详见提交说明；含新增 `Net.kt`、`auth-secure.js`） |
| 上游合并 | `1f491fb` | 合并 `v0.3.19`；保留「时段判定前置于免费额度」，采纳上游组数语义与新包 |
| P3 第一批 | `db05c69` | 静默 catch、`activate()` 改 switch、`exempt()` 精确白名单、会话定时清理、`Json.kt`、`config()` 版本号缓存、`scripts/package.json` |
| P3 第二批 | `de8aaf2` | `route()` 240 行 `when` → 9 个按域处理器 + `Req` 参数对象 |
| P3 第三批 | `9689987` | 拆出 `StoreSchema`（建表/迁移）与 `WeekSlots`（7×48 时段） |
| P3 第四批 | `9c2689a` | `LocalStore` 467→106 行，拆为 `ConfigStore` / `QuestionsDao` / `UsageLog` / `ProgressStore`，原方法签名保留为门面 |

**未处理 1 项**：`admin/assets/index-*.js` 无可读源码。上游 v0.3.19 放出了 `parent-ui/src/` 下 3 个 tsx，但缺 `./api`、`./schema`、`./WeekSchedule`、8 个 shadcn 组件与全部构建脚手架，不足以构成可构建工程，故登录逻辑仍以 `admin/assets/auth-secure.js` 垫片形式存在。等上游补齐 `api.ts` 后可把 SHA-256/HMAC 搬进源码并删除垫片。

**验证**：`tv` / `mobile` 两个 flavor 均 `BUILD SUCCESSFUL`，仅剩既存的 `Notification.Builder` 等弃用警告；无 JVM 单测与真机动态验证（本机无测试基础设施）。

---

## 待办（TODO）

### 1. 崩溃上报：等自己的 Bugly 账号下来后重新接入

**现状（`f86e720`）**：已整体移除。原实现上报腾讯 Bugly，但 AppID `9396669eea` 是上游作者的，
数据进不了自己的后台；且 tv（`com.studyontv.tv`）、mobile（`com.studyontv.mobile`）、
两个 `*.debug` 共四个包名共用一个 AppID，崩溃混在一起分不清设备。

现在崩溃只写本机 `logs/sotv.log`（单文件 1 MB，保留 4 份轮转），家长面板「诊断」页
可搜索、可导出 zip（含 `device.json` / `config.json` / 完整堆栈）。
面板「设置 → 崩溃报告」开关保留，但点击会提示"本版本不联网上报崩溃"。

**为什么要等**：得先有一个能登录的 Bugly 后台，否则上报等于把数据送到别人账号里。

**接入步骤**

1. QQ 登录 bugly.qq.com →「新建产品」→ Android，包名填 `com.studyontv.tv`
2. 再建一个产品，包名 `com.studyontv.mobile` —— 两个 flavor 包名不同，必须分开，
   否则崩溃数据混在一起
3. 两个 AppID 按 flavor 选：
   ```kotlin
   private val APP_ID = if (BuildConfig.FLAVOR == "tv") "tv 的 AppID" else "mobile 的 AppID"
   ```
4. debug 包带 `.debug` 后缀，用 `if (BuildConfig.DEBUG) return` 直接不上报，
   免得测试崩溃污染线上统计
5. 恢复的代码：`app/build.gradle.kts` 的 aar 依赖、`BuglyInit.kt`、`DeviceRuntime.ensure()`
   里的初始化；若仍要"崩溃后先征得同意再上传"，同时恢复 `CrashDetector.kt`
   与 `MainActivity` 的同意弹窗
6. 文案与合规：弹窗必须写明采集 **Android ID（设备标识）**，不能只写"型号 + 系统版本"；
   `docs/install.md` 的「崩溃了怎么查」也要改成"本机日志 + 可选上报"

**取舍**：release 没开混淆（`isMinifyEnabled = false`），堆栈里类名行号本来就可读，
不需要 Bugly 的符号还原。如果只是想让家里几台设备崩了能查，本机日志 + 面板导出已经够用；
接入的真正收益是"设备不在身边时能远程看到崩溃"。

### 2. admin 面板源码迁移

**已解锁**：上游在 v0.3.21（`7b49e4c`）放出了 `parent-ui/` 完整源码 —— vite + React + shadcn，
`src/api.ts`、`schema.ts`、`WeekSchedule.tsx`、`components/ui/*` 与 `package-lock.json` 都在，
`BUILD.md` 写了构建方式，`.gitignore` 也从「忽略整个 parent-ui」改成只忽略 `dist/` 与 vendor。

下一步是把 `admin/assets/auth-secure.js` 的挑战-响应搬进源码：

1. 在 `parent-ui/src/api.ts` 的 `api()` 里，遇到 `path === '/api/auth'` 且 body 含 `passphrase` 时，
   先 `GET /api/auth/challenge` 拿 `{challenge, salt}`，算出
   `key = sha256Hex(passphrase + ':' + salt)`、`proof = HMAC-SHA256(key, challenge)`，
   再用 `{challenge, proof}` 替换 body 发出
2. SHA-256 / HMAC 用现成依赖（如 `@noble/hashes`）实现，**别用 `crypto.subtle`**：
   面板跑在 `http://设备IP:8099`，非安全上下文里 `crypto.subtle` 是 undefined
3. `npm run build` 产出到 `admin/`，删掉 `admin/index.html` 里的 `auth-secure.js` 引用与文件本身
4. 回归验证：面板登录、改密码、会话过期跳转三处

之所以还没做：需要 `npm install`（约 400 个包）并改上游 React 代码，风险和验证成本都高于
这次合并本身，单独排一次更稳。


### 3. 真机验证清单

答题额度与时段判定顺序、家长面板「无限时关闭」、题库暂停题是否隐藏、
输入法豁免、无障碍被杀后的通知、更新源切换后的下载安装。暂无设备，待补。

