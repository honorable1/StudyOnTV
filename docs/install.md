# 安装

## 安装到手机或平板

优先从 [GitHub Release](https://github.com/chadyi/StudyOnTV/releases/latest) 下载对应设备的正式 APK。无法访问 GitHub 时，可使用蒲公英备用入口：[手机 / 平板](https://www.pgyer.com/StudyOnTVMobile)、[TV](https://www.pgyer.com/StudyOnTVTV)。手机和平板直接打开 APK 安装，允许当前下载工具安装应用。手机默认竖屏，平板支持横竖屏；电视支持遥控器。

首次打开先设置管理密码，随后可按需开启权限；不开权限也能进入家长设置或学习。电视无法开启无障碍时，可以使用悬浮窗管理。在另一台家长设备上扫码，输入管理密码连接。受控设备本身也可以通过连续点击标题 5 次，或短按遥控器「上」5 次，再输入密码重新打开连接页。

以下为电视安装方法。

---

## 第一步：开启 ADB 调试

**1. 打开开发者选项**

设置 → 关于 / 关于本机 → 连点「版本号」或「内部版本号」**7 次** → 返回上一级，出现「开发者选项」。

**2. 打开调试开关**

进入开发者选项 → 打开 **USB 调试**；想走无线，再把 **网络调试 / ADB over network** 打开。

**3. 记下电视 IP**

设置 → 网络 / 关于 → 类似 `192.168.1.100` 的地址。

### 三种连接方式

| 方式 | 电视上要开的 | 电脑上执行 |
|---|---|---|
| **无线**（推荐） | USB 调试 + 网络调试 | `adb connect 192.168.1.100:5555` |
| **有线** | USB 调试 | USB 双公头线（A-A）连电脑与电视 → `adb devices` |
| **配对码**（Android 11+） | 开发者选项 → 无线调试 → 使用配对码配对 | `adb pair 192.168.1.100:37861` → 输入电视上的 6 位码 → `adb connect 192.168.1.100:<端口>` |

第一次连接时电视会弹「允许 USB 调试 / 允许网络调试」，**点确定**，否则后面一直是 `unauthorized`。

### 各品牌开 ADB 的位置

| 品牌 | 路径 |
|---|---|
| 小米 / Redmi | 设置 → 关于 → 连点「产品型号」→ 开发者选项 |
| TCL / 雷鸟 | 设置 → 关于 → 本机信息 → 遥控器按「上 下 左 右」→ 底部「更多」→ ADB |
| 索尼 | 设置 → 设备偏好设置 → 关于 → 连点「内部版本号」7 次 |
| 海信 / Vidda | 关于页面开开发者选项，部分型号有专门菜单 |
| 创维 / 酷开 | 关于页面或开发者菜单 |
| 康佳 / 长虹 | 按具体型号找开发者选项 |
| OPPO | 设置 → 系统 → 关于本机 → 设备型号 + 按键组合 |

---

## 第二步：装包

### 方式 a：交给 Agent

复制下面这段发给任意能执行命令的 AI Agent（Claude Code、Cursor、Codex、通义灵码、WorkBuddy 等都行）：

```
帮我在这台电视上安装 StudyOnTV。ADB 调试已经开好了。
电视 IP：<填>

1. 优先从 https://github.com/chadyi/StudyOnTV/releases/latest 下载 TV APK；无法访问时再从 https://www.pgyer.com/StudyOnTVTV 下载
2. adb connect <IP>:5555，然后 adb devices 确认设备出现；
   没出现就等我到电视上点「允许」，还是不行再告诉我怎么处理
3. adb install -r <刚下载的 apk>
4. adb shell monkey -p com.studyontv.tv -c android.intent.category.LAUNCHER 1 打开应用
5. 最后列出可以按需开启的权限：显示在其他应用上层、使用情况访问、无障碍、自启动；不要求全部开启

报错你先自己排查并修复；确实修不了，再把错误原文和你建议我自己动手的解决办法一起告诉我。
```

### 方式 b：手动

```bash
adb connect 192.168.1.100:5555     # 换成电视 IP
adb devices                        # 出现 device 才算成功
adb install -r StudyOnTV.apk       # 看到 Success 就成了
adb shell monkey -p com.studyontv.tv -c android.intent.category.LAUNCHER 1
```

装完把 ADB 调试关掉，减少暴露。

### 方式 c：现成工具（图形界面）

一些把 ADB 图形化的工具，可以可视化地安装 APP。

| 工具 | 跑在哪 | 许可 | 特点 |
|---|---|---|---|
| [tvhelper-docker](https://github.com/wukongdaily/tvhelper-docker) | Docker（电脑 / NAS / 软路由 / OpenWrt） | GPL-3.0 | 网页上传装包、批量装、支持 XAPK / APKM |
| [SmartTV-Toolkit](https://github.com/xiazheteng8/SmartTV-Toolkit) | Windows | 未标注许可（只参考思路，别抄代码） | 国产电视品牌适配、扫已装应用、投屏遥控 |
| [android-tv-toolkit](https://github.com/VictorTran1023/android-tv-toolkit) | ADB 工具箱 | MIT | 各品牌开 ADB 的路径、`adb unauthorized` 重握手、分品牌安装通道 |
| [Android-TV-Desktop-Toolkit](https://github.com/deepsadness/Android-TV-Desktop-Toolkit) | Windows / macOS | AGPL-3.0 | 图形化装包、投屏录屏、电脑当遥控器 |
| [TvToolsPro](https://github.com/deekshith0509/TvToolsPro) | 手机 | 实验性 | 手机无线 ADB 装包，不用电脑 |

### 方式 d：U 盘（不用 ADB）

1. U 盘格式化成 **NTFS**，APK 放根目录
2. 插电视 → 打开文件管理器 / 媒体播放器 → 点 APK 安装
3. 提示「未知来源」→ 去设置里允许，再装一次

⚠️ 部分海信、创维、华为智慧屏**禁止安装第三方应用**，无法使用U 盘安装，只能用 ADB。

---

## 按需开启权限

这些权限都可以稍后设置，未开启时也可以进入学习和家长设置。

| 权限 | 位置 | 作用 |
|---|---|---|
| 显示在其他应用上层 | 设置 → 应用 → 特殊权限 | 到时间后显示学习页，无需强制开启无障碍 |
| 使用情况访问 | 设置 → 应用 → 特殊权限 → 使用情况访问 | 识别应用、准确计时，并配合悬浮窗拦截系统设置；桌面与系统设置不扣时 |
| 无障碍服务 | 设置 → 无障碍 → 已下载的服务 → 题了个题 | 帮助及时识别应用切换，并显示学习页，可选 |
| 自启动 / 后台白名单 | 设置 → 应用 → 自启动管理 | 减少系统清理，适合电视后台运行 |
| 防卸载（可选） | App 权限页 → 防卸载 → 系统设备管理员授权 | 支持的系统可要求先解除管理员再卸载；家长可从设置中解除 |

只开启悬浮窗、未提供应用识别权限时，按离开学习页后的亮屏时间计时，包含桌面停留时间。未开启任何权限时，可以学习和管理题库，暂不拦截其他应用。

部分电视（如创维酷开）在系统设置里精简了「显示在其他应用上层」入口，可以用 ADB 授权：

```bash
adb shell appops set com.studyontv.tv SYSTEM_ALERT_WINDOW allow
```

调试包把包名换成 `com.studyontv.tv.debug`。授权后重新打开一次 App，家长面板「设置 → 设备 → 管控能力」会列出服务、悬浮窗、使用情况和无障碍状态；如果没在计时，先看这里。Android 10+ 上如果悬浮窗被系统拒绝，App 会再尝试打开全屏学习页，并发送一条「到学习时间了」的通知作为兜底。

在家长设备上扫码并输入首次安装时设置的管理密码。权限入口始终保留在家长设置中。

规则中的「管控期间拦截系统设置」默认开启，自由时段不拦截；家长在设备上验证密码后可临时进入。可靠识别与拦截需要无障碍，或悬浮窗配合使用情况访问。只有悬浮窗时无法准确区分系统设置。

**开机启动：** 首次打开并完成设置后，App 接收 Android 开机、部分电视快速开机与覆盖安装广播，尝试恢复前台管理服务。请在厂商设置中允许自启动和后台运行。小米等电视的遥控器待机、关机清后台未必触发 Android 开机广播；系统强制停止或厂商禁用后台启动时，仍需重新打开 App，不能保证自动恢复。

**应用更新：** 家长面板可检查更新，默认优先蒲公英，失败再尝试 GitHub、CDN 与镜像。TV 和手机 / 平板分别更新，下载后核对包名、版本和签名；覆盖安装保留本机数据。需要调整「安装未知应用」权限时，可先由家长验证后进入系统设置。

<img src="./screenshots/v0.3.0/setup-mobile.png" width="260" alt="手机权限页：未授权也可进入设置和学习">

<img src="./screenshots/v0.3.0/setup-tv.png" width="760" alt="电视权限页：只开悬浮窗即可启用简易管理">

---

## 装不上

| 现象 | 原因 | 处理 |
|---|---|---|
| 「应用未安装」/ 解析包错误 | 系统低于 Android 5.0 | 暂不支持，后续支持 |
| 没有「未知来源」开关 | 各家菜单不同 | 设置里搜「安全」「安装」 |
| `adb connect` 拒绝 / 超时 | 没开网络调试 / 端口不同 | 回第一步第 2 步 |
| `adb unauthorized` 且电视不弹窗 | 厂商 adbd 公钥格式特殊 | 用 android-tv-toolkit 的重握手流程 |
| U 盘插上没反应 | 文件管理器不认 | 换 NTFS U 盘，或改用 ADB |
| 装上闪退 | 电视 WebView 版本过老 | 群里反馈型号 |
| 华为鸿蒙 NEXT / 三星 Tizen / LG webOS | 不是 Android 系统 | 后续支持 |

---

## 崩溃了怎么查

应用**不联网上报崩溃**，数据不出设备。崩溃堆栈写在设备里的 `logs/sotv.log`
（单文件上限 1 MB，保留 4 份自动轮转）。

1. 用手机或电脑打开家长面板 `http://设备IP:8099/admin/`
2. 进「诊断」，可按关键字搜（`Crash`、`E/`、`Gate`），也可以点「导出日志」
3. 导出的 zip 里包含：
   - `logs/sotv.log*` —— 运行日志与完整崩溃堆栈
   - `device.json` —— 型号、系统版本、权限状态、更新状态
   - `config.json` —— 当前规则配置

release 包没有开代码混淆，堆栈里的类名和行号直接可读，不需要还原符号表。

> 面板「设置 → 崩溃报告」那个开关在当前版本不起作用（上报功能已移除），
> 保留只是为了兼容旧版面板，点它会提示「本版本不联网上报崩溃」。

---

## 系统版本

| 系统 | 状态 |
|---|---|
| Android 5.0 及以上 | ✅ 支持 |
| Android 4.x 及以下 | ⏳ 待支持 |
| 鸿蒙 NEXT / Tizen / webOS | ⏳ 待支持 |
