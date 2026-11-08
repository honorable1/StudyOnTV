# 构建与静态管理面板

## 两个 Android 包

| 版本 | 包名 | 构建任务 |
|---|---|---|
| TV | `com.studyontv.tv` | `assembleTvRelease` |
| 手机 / 平板 | `com.studyontv.mobile` | `assembleMobileRelease` |

两者均要求 Android 5.0+。TV 包保留原包名和签名，可以覆盖升级旧 TV 版本。手机和平板版是独立应用，题库与记录各自保存在所在设备，不自动跨包迁移。

```bash
cd app/native
gradle assembleTvRelease assembleMobileRelease
```

产物在 `app/build/outputs/apk/tv/release/` 与 `app/build/outputs/apk/mobile/release/`。正式签名读取本机 `local.properties`，密钥不入库。

TV 包不编译语音、手写作答活动；普通学习页不加载公式库，不运行家长面板的 React。公式遇到时才加载，学习页退到后台时释放 WebView。家长面板文件由 TV 提供给家长设备浏览器运行。

## 管理面板

采用 React、shadcn/ui、Radix、React Hook Form、Zod、TanStack Query 和 Recharts，参考 [shadcn-admin](https://github.com/satnaing/shadcn-admin) 的组件与页面结构。

React 源码在 `parent-ui/`，构建后的静态文件在 `admin/`，两者均纳入版本管理。依赖目录、临时文件和自动复制的公共资源不入库，构建时由 `prepare-assets.mjs` 从 `shared/` 和已安装依赖生成。Android 构建直接使用 `admin/` 中的预构建文件。

本机修改面板后：

```bash
cd parent-ui
npm ci
npm run test
npm run build
```

构建将静态页面导出到 `admin/`。随后构建 APK，Gradle 会同步 `admin/`、`app/web/`、`shared/` 与内置题库到 assets。所有必要的页面组件、公式脚本、字体均随包提供。

「全天可用」是独立布尔开关。关闭时必须选择有效且不同的开始、结束时间，支持跨午夜；开启时由开关决定全天可用，不再使用空字符串推断。前后端都校验规则。

## 更新附件

正式发布附件使用：

- `StudyOnTV-TV-v<版本>.apk`
- `StudyOnTV-Mobile-v<版本>.apk`

每个客户端只选择对应附件。TV 附件在 Release 中先上传，兼容旧版更新器读取首个 APK 的行为。

## 检查

```bash
cd app/native
gradle lintTvDebug lintMobileDebug
```

客户端学习页与公式库保持 ES5 语法，适配旧电视 WebView。第三方组件声明见 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。
