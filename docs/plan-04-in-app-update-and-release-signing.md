# TypeFree 应用内轻量检查更新与 Release 正式签名配置方案

## 1. 架构与选型说明

本次改动围绕「零新增敏感权限」「零后台静默联网」「纯 Kotlin 逻辑可抽离」三个约束落地：

- **检查时机**：仅在用户主动点击设置页顶栏版本号胶囊（`v${BuildConfig.VERSION_NAME} · BYOK`）或「外观与偏好 -> 关于」卡片中的「检查更新」按钮时发起网络请求；不在输入法服务或应用启动时轮询。
- **下载与安装方式**：不申请 `REQUEST_INSTALL_PACKAGES` 权限，不使用应用内 `DownloadManager` / `FileProvider` 安装器；发现新版本后通过 `Intent(Intent.ACTION_VIEW, Uri.parse(url))` 拉起系统浏览器访问 `.apk` 资产直链（无 `.apk` 资产时回退至 Release 页面 `html_url`）。
- **核心逻辑解耦**：版本解析比对函数 `compareSemanticVersions`、GitHub Releases JSON 解析函数 `parseLatestReleaseJson` 与请求入口 `fetchLatestUpdate` 均集中在 `AppUpdateChecker.kt`，仅依赖 Kotlin 标准库、`Gson`、`OkHttp` 与 `kotlinx.coroutines`，不引入 `android.*` 依赖，后续可直接迁移至独立的 `:core` 模块。
- **私有词库包保护**：当 `BuildConfig.USE_PRIVATE_DICT == true` 且检测到新版本时，`UpdateCheckInfo.requiresPrivateDictWarning` 置为 `true`，更新弹窗顶部展示醒目警告卡片，提示公开 Release APK 仅包含公开词表，直接覆盖安装会替换内置私有小鹤词库。

## 2. Release 正式签名配置说明

### 2.1 读取优先级与回退机制

`app/build.gradle.kts` 按以下优先级读取签名配置：

1. 项目根目录 `keystore.properties`（已加入 `.gitignore`，禁止提交至仓库）
2. 同名环境变量（适用于 CI/CD 或命令行临时注入）

| 配置项 | `keystore.properties` 字段 | 环境变量名 | 说明 |
| --- | --- | --- | --- |
| 密钥库路径 | `storeFile` | `TYPEFREE_KEYSTORE_PATH` | 支持相对项目根目录路径或绝对路径 |
| 密钥库密码 | `storePassword` | `TYPEFREE_KEYSTORE_PASSWORD` | 必填 |
| Key 别名 | `keyAlias` | `TYPEFREE_KEY_ALIAS` | 必填 |
| Key 密码 | `keyPassword` | `TYPEFREE_KEY_PASSWORD` | 必填 |

当上述四项均非空且 `storeFile` 对应文件存在时，Gradle 注册 `signingConfigs.create("release")` 并绑定至 `buildTypes.release`；任一条件不满足时自动回退到 `signingConfigs.getByName("debug")`，保证无本地密钥环境下的单测与构建正常执行。

### 2.2 本地生成与配置步骤

1. 使用 `keytool` 生成签名密钥库（妥善保管至项目外或 `release/` 等被忽略目录）：

   ```bash
   keytool -genkeypair -v \
     -keystore release/typefree-release.jks \
     -alias typefree \
     -keyalg RSA \
     -keysize 2048 \
     -validity 10000
   ```

2. 在项目根目录新建 `keystore.properties`：

   ```properties
   storeFile=release/typefree-release.jks
   storePassword=你的密钥库密码
   keyAlias=typefree
   keyPassword=你的Key密码
   ```

3. 执行正式包构建：

   ```bash
   ./gradlew assembleRelease
   ```

   或通过环境变量构建（无需落盘 `keystore.properties`）：

   ```bash
   TYPEFREE_KEYSTORE_PATH=/abs/path/to/typefree-release.jks \
   TYPEFREE_KEYSTORE_PASSWORD=... \
   TYPEFREE_KEY_ALIAS=typefree \
   TYPEFREE_KEY_PASSWORD=... \
   ./gradlew assembleRelease
   ```

## 3. 代码改动清单

- `.gitignore`：新增忽略 `keystore.properties`。
- `app/build.gradle.kts`：读取 `keystore.properties` 与 `TYPEFREE_*` 环境变量，按条件注册 `release` 签名配置，未配置时平滑回退 `debug`；保留 `versionCode = 28` 与 `versionName = "3.5.1"` 原有配置不变。
- `app/src/main/java/com/typefree/ime/network/AppUpdateChecker.kt`：新增 `UpdateCheckInfo` 数据类与 `AppUpdateChecker` 单例，实现语义化版本比较、`https://api.github.com/repos/dengxuezhao/TypeFree/releases/latest` 响应解析（优先匹配 `.apk` 资产，回退 `html_url`）、私有词库构建警示标记及 10s 超时 `OkHttpClient` 异步请求封装。
- `app/src/main/java/com/typefree/ime/ui/screens/AccountSettingsScreen.kt`：
  - 顶栏右上角版本胶囊支持点击检查更新并显示加载态、禁用重复连点；
  - 「外观与偏好 -> 关于」卡片增加「检查更新」与「GitHub Releases」操作按钮；
  - 新增 `AppUpdateDialog`，覆盖有新版本（含可滚动更新日志、版本跃迁标签、`USE_PRIVATE_DICT` 警告卡片、浏览器下载 APK / 打开发布页 / 暂不更新）、已是最新版本及失败兜底跳转三种状态；
  - 通过 `openExternalUrl` 封装 `Intent.ACTION_VIEW` 并使用 `runCatching` 兜底浏览器拉起异常。
- `app/src/test/java/com/typefree/ime/network/AppUpdateCheckerTest.kt`：新增检查更新核心逻辑单元测试。

## 4. 单元测试覆盖项

`AppUpdateCheckerTest` 覆盖以下场景：

- **语义化版本比对**：`v`/`V` 前缀与空白裁剪、多位数段（如 `3.5.10 > 3.5.2`）、补零对齐（`3.6 == 3.6.0`）、构建元数据忽略（`+build`）、预发布标签优先级（`3.6.0 > 3.6.0-beta.1`、`beta.10 > beta.2`、`alpha < beta < rc`）。
- **GitHub Release JSON 解析与资产筛选**：多资产列表中提取 `.apk` 结尾的 `browser_download_url`、无 `.apk` 资产时回退 `html_url`、空 `body` / 空 `name` 兜底文案。
- **私有词库包告警开关**：`hasUpdate && isPrivateDictBuild` 为 `true` 时触发 `requiresPrivateDictWarning`，已最新或公开版包均不触发。
- **异常防御与 HTTP 状态映射**：空串/非对象/语法错误/缺失 `tag_name` 的 JSON 均抛出明确异常；通过 OkHttp `Interceptor` 验证请求头（`Accept`、`User-Agent`）及 HTTP 403、404、空响应体与 200 成功响应处理。
