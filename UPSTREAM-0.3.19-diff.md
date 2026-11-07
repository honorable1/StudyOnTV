# 上游 v0.3.15 → v0.3.19 差异与合并分析

> 生成于 2026-11-07。对比基线：本地 `feature/dev`（HEAD = `af1bbf7 Release v0.3.15`）与上游 tag `v0.3.19`。

## 0. 前置说明

本地克隆的 `honorable1/StudyOnTV` 是 `chadyi/StudyOnTV` 的**快照/分叉**：`main` 停在 `af1bbf7`（v0.3.15），且**没有任何 tag**。真正在发版的是 `chadyi`，已添加远端：

```
upstream  https://ghfast.top/https://github.com/chadyi/StudyOnTV.git
```

8 次提交，22 个文件，`+29383 / −354` 行——其中 28915 行是 `admin/assets/index-CKbAr4Li.js` 打包产物重建，实际手写改动约 470 行。

## 1. 提交列表

| 提交 | 说明 |
|---|---|
| `e555d12` | fix: 修复「每日免费时长」无法保存（PR #53） |
| `6926fee` | Release v0.3.16 |
| `b6e6a11` | Merge PR #53 |
| `a291d4c` | fix: 剪贴板复制在 HTTP 环境下的降级方案 |
| `ec5193a` | fix: 复制为空 + 题库隐藏已暂停的题 |
| `5a3717e` | Release v0.3.17 |
| `d4ce1f5` | Release v0.3.18 |
| `df9e656` | Release v0.3.19 |

## 2. 逐文件对照

### 2.1 双方都改过的文件（7 个，需合并）

| 文件 | 上游改了什么 | 我们改了什么 | 行区间重叠 | 冲突风险 |
|---|---|---|---|---|
| **LearningEngine.kt** | ① 新增 `indefinite`（无限时关闭管控）override 模式；② `freeNow()` 放行 `unlock`+`indefinite`；③ 重排 `reason` 判定，新增「已达组数但仍有剩余时间→放行」 | P1-1 把 `schedule` 判定提到 `freeQuota` 之前；P2-5 内存累加+定期落盘；P2-7 review 存 `round` 快照 + `settle()`；P2-8 `nextAvailable` 返回 -1 | **是**（上游 `-156` vs 我们 `-152/-156/-169`） | 🔴 1 处 |
| **LocalStore.kt** | ① `config()` 默认值补 `dailyFreeMinutes=0`；② `saveConfig` 校验范围加 `dailyFreeMinutes 0..120` | P1-2 `config()` 缓存 + `stats()` SQL 聚合 + `todayStats()` TTL；P2-2 拆分链式调用；P2-3 `questions()` 下推 SQL；P2-6 `startOfTomorrow()`；`saveConfig` 拒绝七天全 0 | **是**（上游 `-132/-203` vs 我们 `-130/-131/-202`） | 🔴 2 处 |
| **ApkInstaller.kt** | 下载段重写：超时放宽到 12s/60s、手动跟随 301-303 重定向、加 `Accept: */*` | P1-4 加 `MAX_APK_BYTES` 200MB 边下边检；P2-11 安装异常写 `lastError` | **相邻**（上游 `-77/-80` vs 我们 `-78`） | 🟠 1 处 |
| **GateService.kt** | ① 无悬浮窗时改走无障碍覆盖层；② 两处 `BuglyInit.reportPermissionLost()` | P1-3 `launcherPackage` 改 `by lazy`；`onTaskRemoved` 补 `flushUsage()` | 否（上游 `-88/-91/-151/-187`，我们 `-210/-216/-249`） | 🟢 自动合并 |
| **DeviceBridge.kt** | `exitToLauncher` 逻辑重写：主场景改 `moveTaskToBack` 让原 App 回前台，仅 `GateActivity` 跳桌面 | P0-4 删除 `apiToken()`，`api()` 改进程内路由 | 否（上游 `-61/-70`，我们 `-24/-26`） | 🟢 自动合并 |
| **app/web/assets/js/app.js** | 顶部状态条文案：支持小时显示、`indefinite` 显示「管控已关闭」 | P0-4 `request()` 走 `SOTVNative.api()`；P2-8 `nextAvailableAt<0` 提示 | 否（上游 `-46/-48`，我们 `-11/-12/-18/-279/-283`） | 🟢 自动合并 |
| **admin/index.html** | 换了打包产物的 hash（`index-CKbAr4Li.js` / `charts-6Zd_DBmv.js` / `index-BmzgaB_P.css`） | 在 `<head>` 插入 `<script src="./assets/auth-secure.js">` | **是**（同一行，文件仅 4 行） | 🔴 1 处 |

### 2.2 只有上游改的文件（不影响我们的修复）

| 文件 | 改动 |
|---|---|
| `AndroidManifest.xml` | `MainActivity` 加 `excludeFromRecents`；`BootReceiver` 新增 SCREEN_ON / USER_PRESENT / CONNECTIVITY_CHANGE / LOCKED_BOOT_COMPLETED / TCL / 海信 广播 |
| `BootReceiver.kt` | 配合上面广播：进程被杀后由亮屏/解锁/网络变化兜底拉起管控服务（仅 `Prefs.onboarded()` 后生效） |
| `BuglyInit.kt` | 新增 `reportPermissionLost()`，上报权限丢失（不采集个人数据） |
| `OnboardingActivity.kt` | 按品牌给自启动引导（TCL / 海信 / 创维 / 小米 / 长虹） |
| `build.gradle.kts` | `versionCode 20→24`，`versionName 0.3.15→0.3.19` |
| `parent-ui/src/*.tsx` | **新增** `DevicePanel.tsx` / `QuestionBank.tsx` / `RulesPage.tsx`——管理面板终于有源码了（此前只有打包产物） |
| `admin/assets/*` | 打包产物整体重建 |

### 2.3 只有我们改的文件（上游没动，直接保留）

`AdminAuth.kt`、`DeviceRuntime.kt`、`GateOverlay.kt`、`GradingService.kt`、`HolidayService.kt`、`LanServer.kt`、`Pgyer.kt`、`Updater.kt`、`Net.kt`（新增）、`analytics.js` ×2、`auth-secure.js`（新增）、`docs/*`、`.gitignore`

## 3. 三个必须人工决策的点

### 3.1 上游**没有**修 P1-1，我们的修复仍然必要

`LearningEngine` 的 `reason` 判定，上游 v0.3.19 仍然是：

```kotlin
free -> ""
freeQuota > 0 -> ""                    // ← 免费额度仍在时段判定之前
used >= c.getLong("dailyMinutes") * 60000 -> "quota"
c.optString("scheduleMode", "allow") != "control" && !inWindow(c) -> "schedule"
```

我们的是：

```kotlin
free -> ""
c.optString("scheduleMode", "allow") != "control" && !inAllowedWindow -> "schedule"   // ← 前置
freeQuota > 0 -> ""
used >= ... -> "quota"
```

**结论：合并时这一块必须保留我们的顺序**，否则「免费额度绕过时段管控」的漏洞会回来。

### 3.2 上游的 `indefinite` 模式必须并进来

上游新增了「无限时关闭管控」：`remote("indefinite")` 写入 `mode=indefinite`，`freeNow()` 把它当作自由状态，`app.js` 状态条显示「管控已关闭」。

我们的 `override()` 里 `until == 0L` 会走「锁定且未设结束时间」分支，`freeNow()` 只认 `mode == "unlock"`——**如果只合代码不并这段逻辑，家长点「无限时关闭」会变成「永久锁定」**。这是本次合并里最容易出事的地方。

### 3.3 admin 包重建，`auth-secure.js` 垫片要重新挂

新版 `admin/index.html` 指向 `index-CKbAr4Li.js`。我确认过新包里**仍然**是 `passphrase` 明文 POST + `Bearer ${token}`，全文 `challenge` 出现 **0 次**——说明上游没做挑战-响应。我们的 `auth-secure.js` 劫持 `window.fetch` 的方案对新包依然有效，但**必须把 `<script>` 标签重新插进新的 `admin/index.html`**，否则 P0-1 在管理面板上等于没修。

## 4. 建议的合并步骤

```bash
# 1. 先把当前工作（P0-P2 修复）提交成一个 commit，保住现状
git add -A && git commit -m "fix(security+perf): 修复 P0-P2 审查问题"

# 2. 合并上游
git merge v0.3.19

# 3. 按上面 3 个决策点解决 7 处冲突
#    LearningEngine.kt  → 保留我们的 schedule 顺序 + 并入 indefinite
#    LocalStore.kt      → 保留我们的缓存结构 + 并入 dailyFreeMinutes
#    ApkInstaller.kt    → 取上游的重定向逻辑 + 保留我们的 200MB 限制
#    admin/index.html   → 取新版 hash + 重新插入 auth-secure.js

# 4. 重新构建验证
cd app/native && ./gradlew :app:assembleTvDebug :app:assembleMobileDebug
```

## 5. 顺带解决的历史问题

上游新增 `parent-ui/src/*.tsx`，意味着之前 CODE_REVIEW 里 P3 列的「admin 无源码、只能改打包产物」这条**已经不成立了**——以后管理面板可以改源码重新构建，不用再靠 `auth-secure.js` 这种垫片。合并后可以考虑把那套挑战-响应登录直接写进源码。
