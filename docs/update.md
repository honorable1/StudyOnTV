# 版本检查与更新提示

客户端回到学习页时检查最新 Release，**有新版才提示**，结果缓存 6 小时。GitHub 不稳定时会自动换用其他来源（见下文「镜像与备用来源」）。

```
GET https://api.github.com/repos/chadyi/StudyOnTV/releases/latest
```

| 字段 | 用途 |
|---|---|
| `tag_name` | 版本号，写成 `v1.2.3` |
| `body` | 更新说明，直接显示在提示里；**含 `[force]` 就强制更新** |
| `assets[].browser_download_url` | 有 `.apk` 就点「立即更新」直接下载，没有就打开 Release 页 |

## 两种提示

| 类型 | 触发 | 表现 |
|---|---|---|
| 强制更新 | `body` 里有 `[force]` | 全屏页，返回键无效，只能点「立即更新」 |
| 普通更新 | 版本号更高但没有 `[force]` | 右上角小卡片，可关闭 |

## 发版流程

```bash
# 1. 改版本号（app/native/app/build.gradle.kts 里的 versionCode / versionName）
# 2. 打包
cd app/native && ./gradlew assembleDebug      # 或 assembleRelease（需签名）
# 3. 发布
gh release create v0.2.0 app/native/app/build/outputs/apk/debug/app-debug.apk \
  --title "v0.2.0" \
  --notes "· 新增题库导入导出
· 修复锁屏偶发不弹的问题
· 支持 100 以内加减法题库"
```

要强制更新时，在说明里加一行：

```
[force]
本次必须更新
```

发布后客户端最长 6 小时内（或下次启动）就会看到提示。

## 镜像与备用来源

部分网络访问 GitHub 不稳定，客户端按顺序尝试，第一个给出结论的来源为准：

1. 自定义更新地址（`PUT /api/update` 里的 `api`，支持 GitHub Release JSON 或 update.json 格式）。
   **配了就以它为准**：只要它返回了合法清单，不再询问后面的来源。
2. 蒲公英（`Pgyer.info`，当前官方发布渠道）
3. 仓库 main 分支的 `update.json`：raw.githubusercontent.com → cdn / fastly / gcore.jsdelivr.net
4. GitHub Releases API

APK 下载依次尝试：上次成功的线路 → 直连 → 加速镜像（只对 GitHub 域名拼接前缀，默认不预置镜像，可在面板配置）。
下载后会校验 SHA-256（GitHub API 的 `digest` 或 update.json 中的 `sha256`），
并要求「包名一致 + versionCode 更高 + 签名与已安装版本有交集」，否则拒绝安装。

### 发版时生成 update.json

```bash
node scripts/make-update-manifest.mjs release-artifacts --notes "· 修复……"
git add update.json && git commit -m "update.json v0.2.2"
```

jsDelivr 对分支文件最多缓存约 12 小时；GitHub API 能访问时不受影响。

---

## 用当前分支的包做更新源（自建源）

开发分支装的是 `com.studyontv.mobile.debug` + debug 签名，而官方发的是
`com.studyontv.mobile` + 正式签名。`ApkInstaller.validUpdate` 校验三件事：

```
archive.packageName == ctx.packageName      // 包名必须相同（不会跨 debug / release）
archive.versionCode  >  ctx 的 versionCode  // 只改 versionName 不够
签名交集非空                                 // 同一台机器构建 → 同一个 debug keystore
```

官方包三条都过不了，所以设备上点「更新」必然失败。想让本分支的包也能被更新到，
就自己发一份：包名和签名天然一致（同一台机器、同一个 debug keystore），只要把
`versionCode` 抬上去即可。

### 一条命令

```bash
node scripts/publish-local.mjs --flavor mobile --notes "修复了 XX"
```

脚本会依次做四件事：

1. 用 `-PsotvVersionName` / `-PsotvVersionCode` 覆盖版本号后构建
   （默认 `<当前版本>-dev.1`、`versionCode + 1`）
2. 把 APK 复制到 `dist/apk/`，用 aapt2 读回 APK 里的真实版本，不一致时以 APK 为准并告警
3. 生成 `dist/update.json`，下载地址按 `--base` 拼接（默认 `http://局域网IP:端口`）
4. 在本机 `8787` 端口起静态服务，打印要填进设备的清单地址

参数：

| 参数 | 说明 |
|---|---|
| `--flavor mobile\|tv\|both` | 默认 both |
| `--version x.y.z[-tag]` | 默认 `<仓库版本>-dev.1` |
| `--code N` | 默认 `versionCode + 1`，**必须比设备上的大** |
| `--notes "..."` | 更新说明；含 `[force]` 等同 `--force` |
| `--force` | 全屏强制更新，返回键无效 |
| `--port N` | 静态服务端口，默认 8787 |
| `--base URL` | 清单里写的对外基地址，如 `http://xxx.xiaomiqiu.com`；默认 `http://局域网IP:端口` |
| `--no-build` | 跳过构建，复用 `build/outputs` 里现有 APK |
| `--push URL --token sotv_xxx` | 构建完直接把清单地址写进设备 |

### 让设备从这里更新

两种方式，任选其一：

**A. 脚本直接写进去**（推荐，少一步手工）

```bash
node scripts/publish-local.mjs --flavor mobile \
  --push http://192.168.31.174:8099 --password '家长密码'
```

`--password` 会让脚本自己走挑战-响应登录换会话令牌（密码不上网），然后
`PUT /api/update` 写地址、`POST /api/update/check` 触发检查，最后打印
「设备当前版本 / 来源 / 最新版本 / 有更新：是」。

> 不能用「集成」页的 `sotv_` 令牌：`LanServer` 给集成令牌的白名单只放行
> `/api/state`、`/api/device`、`/api/remote`，访问 `/api/update` 会 403。
> 这是故意的——一个只读/遥控用的令牌不该有权改更新源。

已经有会话令牌（比如从浏览器 devtools 里复制）就换 `--token <64 位 hex>`。

**B. 清掉自建源，回到官方**

```bash
node scripts/publish-local.mjs --push http://192.168.31.174:8099 \
  --password '家长密码' --clear --no-build --no-serve
```

之后设备重新按「蒲公英 → update.json → GitHub Release」的顺序检查。

### 为什么允许 http

`Updater.isAllowedSource` 的规则是：**https 与 http 都允许，只校验协议和主机名**
（早先 http 只放行 `127.x`、`10.x`、`192.168.x`、`172.16–31.x`、`localhost`、`*.local`）。

放开的原因是家长常用内网穿透域名托管清单（如 `http://xxxx.xiaomiqiu.com`）——
它既不是私有网段也没有证书，按 IP 白名单判断会把这条路直接堵死。
面板自己跑在 `http://192.168.x.x:8099`，给内网机器配证书同样不现实。
http 明文被中间人替换的风险，由下载后的「包名 + versionCode + 签名交集」三重校验兜住，
清单里带 `sha256` 时还会再核一次摘要 —— 换掉的包装不上。

### 用穿透域名托管（xiaomiqiu 等）

设备不在同一个 WiFi 时，局域网地址就失效了。做法是把本机的 `dist/` 通过穿透服务
暴露出去，让清单里写公网域名：

```bash
# 1. 构建 + 生成清单（地址写成穿透域名），同时在本机 8788 起静态服务
node scripts/publish-local.mjs --flavor both --port 8788 \
  --base http://55egrv9vqmkr.xiaomiqiu.com --notes "本分支验证包"

# 2. 穿透客户端把 55egrv9vqmkr.xiaomiqiu.com 指向本机 8788，验证：
curl -s http://55egrv9vqmkr.xiaomiqiu.com/update.json
```

清单里的下载地址会变成 `http://55egrv9vqmkr.xiaomiqiu.com/apk/StudyOnTV-Mobile-vX.apk`，
所以穿透必须映射到 **`dist/` 目录本身**（不是父目录），否则 `/apk/...` 会 404。

打包时若想让设备**开箱即用**这个源，改 `Prefs.DEFAULT_UPDATE_API` 的默认值即可
（当前为 `http://55egrv9vqmkr.xiaomiqiu.com/update.json`）。家长在面板里清空更新地址
仍会回落到「蒲公英 → 官方 update.json → GitHub Release」链路。

### 两个容易踩的坑

- **只改 versionName 不改 versionCode**：设备会提示"有新版本"，点下去却报
  「安装包版本不比当前新」。`Updater.isNewer` 比的是版本名，`ApkInstaller` 比的是
  versionCode，两者必须一起抬。
- **换机器构建**：debug keystore 在 `~/.android/debug.keystore`，不同机器的签名不同，
  交叉更新会报「安装包签名与当前版本不一致」。要么始终用同一台机器构建，
  要么配 `local.properties` 里的 `KEYSTORE_PATH` 走 release 签名（此时包名也要一致）。

### 和面板提示的关系

- 设备学习页右上角的横幅：受 `localStorage['sotv_update_dismissed']` 控制，点过 ×
  之后同一版本不再提示（`force` 除外），并且检查结果缓存 6 小时。
- 管理面板：打开任意页面（含 `#/remote`）都会 `POST /api/update/check` 强制刷新，
  轮询到 `hasUpdate` 就弹 toast，**不看 dismissed**。

所以「面板一直提示、设备不提示」是两套策略的正常差异，不是 bug。把自建源配好之后，
两边看到的就是同一个版本了。
