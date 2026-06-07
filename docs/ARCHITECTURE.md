# CodexMeter Architecture

## 1. 文档定位

本文是 `CodexMeter` MVP 的可执行开发蓝图，用于固化 Android 架构、数据模型、认证链路、刷新链路、安全边界和后续扩展约束。

本文不展开完整代码实现，不描述 UI 视觉细节。视觉与交互细节放在根目录 `DESIGN.md`，开发规范放在根目录 `RULES.md`。

## 2. 已确认产品边界

- 项目目录：`codexbar-apk`
- 产品展示名：`CodexMeter`
- 正式包名：`com.kmnexus.codexmeter`
- debug 包名：`com.kmnexus.codexmeter.debug`
- 目标平台：Android 12+
- 分发方式：自用 / 小范围侧载 APK
- MVP 首个 Provider：Codex
- 未来预留 Provider：Gemini、Claude 等额度监控来源
- 核心表面：App 首页、桌面微件、常驻通知
- 刷新频率：约 15 分钟，后台有界刷新所有 Active 账号，非实时监控
- 数据源：Provider 官方会话和官方 usage / quota API
- 不做网页解析
- 不做 token / cost 本地估算
- 不做 Cookie Header 或 token 手填
- 不做自建浏览器 / 本地回调 OAuth 登录；Codex 验证页只通过 device-code 外部浏览器 handoff 打开
- 不做云同步、导出、跨设备迁移

## 3. 总体架构决策

CodexMeter MVP 采用单模块 Android 应用架构。

- 只有一个 `app` module。
- 不引入多模块拆分。
- 包内按层次与职责严格分区。
- 后续如 Provider 增多或功能膨胀，再迁移为多模块架构。

### 3.1 技术栈

- 语言：Kotlin
- UI：Jetpack Compose
- 设计系统：Material 3
- 状态流：StateFlow + ViewModel
- 后台调度：WorkManager
- 本地数据库：Room
- 设置存储：DataStore
- 会话加密：Android Keystore + AES-GCM
- 网络：OkHttp + kotlinx.serialization
- 桌面微件：Jetpack Glance
- 依赖组装：手写 `AppContainer`

### 3.2 不采用的技术

MVP 不采用：

- Hilt
- Retrofit
- Ktor Client
- Foreground Service
- 动态插件系统
- 多模块 Gradle 架构
- 系统自动备份
- 远程日志上传

## 4. 包结构

当前实现包结构：

```text
com.kmnexus.codexmeter
├── app
├── core
│   ├── i18n
│   └── network
├── data
│   ├── local
│   │   ├── dao
│   │   ├── db
│   │   └── entity
│   ├── preferences
│   ├── repository
│   └── secure
├── domain
│   ├── account
│   ├── auth          (ApiKeyLoginUseCase, SessionLoginUseCase, DeviceCodeLogin facade)
│   ├── currency      (BalanceConversion, CurrencyPreferences, ExchangeRates)
│   ├── diagnostics
│   ├── model
│   ├── quota
│   ├── refresh
│   ├── settings
│   └── update
├── notification
├── providers
│   ├── ProviderRegistry.kt   (所有 Provider 注册与 ProviderConfig)
│   ├── SessionImporter.kt    (通用接口)
│   ├── SessionImportRouter.kt
│   ├── codex
│   │   ├── auth
│   │   ├── dto
│   │   ├── mapper
│   │   ├── network
│   │   └── session
│   ├── deepseek / auth, dto, mapper, network, session
│   ├── zai      / auth, dto, mapper, network, session
│   ├── minimax  / auth, dto, mapper, network, session
│   ├── cursor   / auth, dto, mapper, network, session
│   ├── kimi     / auth, dto, mapper, network, session
│   ├── claude   / auth, dto, mapper, network, session
│   ├── antigravity / auth, dto, mapper, network, session
│   └── common
│       └── auth  (LoopbackCallbackServer, OAuthTokenClient)
├── refresh
├── ui
│   ├── account
│   ├── auth
│   ├── components
│   ├── home
│   ├── motion
│   ├── navigation
│   ├── settings
│   ├── spike
│   └── theme
└── widget
```

约束：

- `ui` 不直接访问 Room、DataStore、OkHttp、Keystore。
- `widget` 不直接访问网络和 Provider。
- `refresh` 中的 WorkManager glue 不直接创建通知，不直接拼 UI 状态。
- Provider 私有 token、DTO、endpoint 不外泄到公共层。
- 公共层只消费标准化后的领域模型。

## 5. 分层职责

### 5.1 UI 层

负责：

- Compose 页面渲染。
- 用户交互转为 UI Intent。
- 观察 ViewModel 暴露的 `StateFlow<UiState>`。

禁止：

- 直接读取数据库。
- 直接读取 DataStore。
- 直接访问 token / session payload。
- 直接发网络请求。
- 直接触发 Provider 私有逻辑。

### 5.2 Domain 层

负责：

- 通用 Provider 抽象。
- 通用账号模型。
- 通用 quota / window 模型。
- 刷新协调。
- 告警策略。
- 错误归一。
- 展示状态派生。

### 5.3 Data 层

负责：

- Room 数据库。
- DataStore 设置。
- 加密 session 存储。
- Repository 实现。
- 数据清理与迁移。

### 5.4 Provider 层

负责：

- Provider 私有认证。
- Provider 私有 session payload。
- token refresh。
- 官方 usage API 请求。
- Provider DTO 解析。
- Provider 私有错误映射为通用 `QuotaError`。

Codex 是第一个 Provider，但公共架构不得写死 Codex。

## 6. Provider 接入现状

当前已通过内置 `ProviderRegistry` 实现 8 个 Provider。`ProviderConfig` 声明每个 Provider 的 displayName、iconResId、认证类型（`ProviderAuthKind`）和能力标志。

### 6.1 认证类型（ProviderAuthKind）

| 认证类型 | Provider |
|---|---|
| `OAuthWebView`（Codex 特有 device-code 外部浏览器流程） | Codex |
| `ApiKeyImport`（应用内 API Key 输入框） | DeepSeek、z.ai、MiniMax |
| `CookieAuth`（内嵌 WebView 提取 Cookie） | Cursor、Kimi |
| `OAuthPkceLogin`（Claude=WebView 拦截 code；Antigravity=loopback server） | Claude、Antigravity |

### 6.2 已接入 Provider 一览

| providerId | displayName | 认证类型 | 支持余额 |
|---|---|---|---|
| `codex` | Codex | OAuthWebView（device-code） | 否 |
| `deepseek` | DeepSeek | ApiKeyImport | 是 |
| `zai` | z.ai | ApiKeyImport | 否 |
| `minimax` | MiniMax | ApiKeyImport | 否 |
| `cursor` | Cursor | CookieAuth | 否 |
| `kimi` | Kimi | CookieAuth | 否 |
| `claude` | Claude | OAuthPkceLogin（WebView 拦截） | 否 |
| `antigravity` | Antigravity | OAuthPkceLogin（loopback server） | 否 |

### 6.3 组装方式

- `AppContainer` 手写组装所有 Provider 依赖。
- 每个 Provider 的 `<Name>RefreshProvider` 实现 `RefreshProvider`，供 `RefreshCoordinator` 调用。
- 每个 Provider 的 `<Name>SessionImporter` 实现通用 `SessionImporter` 接口，并通过 `SessionImportRouter` 按 `ProviderAuthKind` 路由。
- Codex 特有：`CodexDeviceCodeLoginUseCase` 负责 device-code 登录状态机；`CodexDeviceCodeLoginController` 暴露 domain facade。
- 通用 domain 层：`ApiKeyLoginUseCase` 和 `SessionLoginUseCase` 供 API Key 和 WebView/OAuth 登录使用。
- `providers/common/auth` 包含 `LoopbackCallbackServer`（Antigravity loopback）和 `OAuthTokenClient`（通用 token 换取）。

不引入动态插件或用户自定义 Provider；新 Provider 通过手写实现注册进 `ProviderRegistry`。

## 7. 通用领域模型

### 7.1 ProviderAccount

账号使用跨 Provider 通用模型。

字段建议：

- `providerId`
- `accountId`
- `displayName`
- `avatarUrl?`
- `status`
- `createdAt`
- `updatedAt`
- `lastUsedAt`

唯一键：

- `(providerId, accountId)`

MVP UI 只开放 Codex，但底层允许未来切换到 Gemini / Claude 账号。

### 7.2 ProviderSessionEnvelope

Session 采用通用 envelope + Provider 私有加密 payload。

公共 envelope 只包含：

- `providerId`
- `accountId`
- `schemaVersion`
- `createdAt`
- `updatedAt`
- `lastValidatedAt`
- `sessionStatus`
- `encryptedPayload`

公共层不得保存或读取：

- access token
- refresh token
- id token
- auth code
- Cookie
- Provider 私有 OAuth endpoint

Codex 私有 payload 由 Codex Provider 自己定义和迁移。

### 7.3 QuotaSnapshot

`QuotaSnapshot` 只代表成功获取并标准化后的额度状态。

建议字段：

- `id`
- `providerId`
- `accountId`
- `fetchedAt`
- `freshnessStatus`
- `providerStatus`
- `windows`
- `primaryWindowId`
- `diagnosticsDigest?`

失败刷新不得覆盖 last known good `QuotaSnapshot`。

### 7.4 QuotaWindow

通用窗口模型（含多 Provider 扩展字段）：

- `windowId`
- `titleKey`：string resource key
- `usedPercent?`
- `remainingPercent`：派生自 `100 - usedPercent.coerceIn(0,100)`
- `resetAt?`
- `limitWindowSeconds?`
- `isPrimaryCandidate`
- `availability`：Available / Depleted / Missing / DecodeFailed / Unsupported
- `displayKind`：`Percent`（默认）/ `Balance`（余额型 Provider）/ `UsageCount`（次数型）/ `MultiModelFraction`（多模型分桶）
- `balanceAmount?` / `balanceCurrency?`：余额型 Provider 的原始余额
- `originalBalanceAmount?` / `originalBalanceCurrency?`：货币转换前的原始余额（仅展示层，不持久化）
- `grantedBalance?` / `toppedUpBalance?`：余额明细（如 DeepSeek 赠送 / 充值拆分）
- `usedCount?` / `limitCount?`：次数型窗口
- `subLabel?`：附加说明文字
- `modelBuckets: List<QuotaModelBucket>`：多模型分桶列表
- `usesModelBucketSum`：为 true 时首页趋势图按模型桶 used-fraction delta 求和而非 diff usedPercent（用于 Antigravity 等报告单一模型最差值的 Provider）

`QuotaModelBucket` 包含 `modelId`、`displayName`、`remainingFraction`、`resetAt?`。

已知 Provider 窗口：

| Provider | 窗口 id | displayKind |
|---|---|---|
| Codex | `five_hour`、`weekly` | Percent |
| DeepSeek | `balance` | Balance |
| z.ai | `quota`（按 Provider 实际返回） | Percent / Balance |
| MiniMax | `tokens`（按 Provider 实际返回） | Balance |
| Cursor | `usage`（按 Provider 实际返回） | UsageCount |
| Kimi | `quota` | Percent |
| Claude | `usage` | UsageCount / Percent |
| Antigravity | 多模型分桶 | MultiModelFraction |

UI 只消费 `displayKind` 分支渲染，不识别 Provider 私有窗口 id。

### 7.5 RefreshAttempt

每次刷新尝试记录一条脱敏摘要。

字段建议：

- `id`
- `providerId`
- `accountId`
- `startedAt`
- `finishedAt`
- `success`
- `errorType?`
- `httpStatus?`
- `safeMessageKey?`
- `diagnosticsDigest?`

`RefreshAttempt` 不参与趋势图，不覆盖成功快照。

### 7.6 CurrentQuotaState

`CurrentQuotaState` 是展示层唯一读模型。

由以下事实源派生：

- `ProviderAccount`
- `QuotaSnapshot`
- `RefreshAttempt`
- `UserPreferences`
- 当前 Codex 窗口定义与通知 / Widget 配置

消费方：

- 首页
- 微件
- 常驻通知
- 告警策略

目标：

- 首页、微件、通知状态一致。
- 避免多处重复计算过期、错误、告警颜色。
- 未来 Provider 扩展时减少 UI 改动。

## 8. 本地存储

CodexMeter 将本地数据分为三类。

### 8.1 敏感会话

- 存储 OAuth session、refresh token 等敏感信息。
- 使用 Android Keystore 保护的 AES-GCM 密钥加密。
- 每个账号一份 encrypted payload。
- 不进 Room 明文字段。
- 不进 DataStore 明文字段。
- 不进日志和诊断。

### 8.2 业务历史

使用 Room 保存：

- 账号元信息。
- 成功 quota 快照。
- 刷新尝试记录。
- 告警状态。

### 8.3 轻量设置

使用 DataStore 保存：

- 当前 `providerId`
- 当前 `accountId`
- legacy 主额度窗口，仅用于迁移旧设置；新功能不再把它作为全局默认
- 常驻通知配置：通知账号选择、通知显示额度窗口
- 账号额度告警配置：每个账号的每个额度窗口是否开启告警
- 每个 AppWidget 的轻量配置：展示账号、紧凑主额度窗口
- 阈值设置
- 历史保留天数
- 通知开关
- 状态通知开关
- 外观主题偏好：浅色 / 深色 / 跟随系统，持久化在独立 DataStore 文件 `appearance.preferences_pb`，与账号数据解耦；启动时同步预热首值（`runBlocking { first() }`）以避免首帧主题闪烁

历史版本残留的语言 / 字体偏好不再驱动当前 UI：App 启动时清理平台 App 专属语言覆盖，字体方案固定为 Mono Focus；明暗主题由外观偏好驱动，仅作用于 App（桌面微件始终跟随系统深浅色）。

### 8.4 应用更新检查

应用更新检查不写入本地数据库或 DataStore：

- `GitHubReleaseAppUpdateChecker` 通过 OkHttp + kotlinx.serialization 读取 `KyoMio/CodexMeter` 最新 GitHub Release。
- 仅比较语义版本号；debug / build suffix 不参与新旧判断。
- 仅选择 Release assets 中的 `.apk` 下载链接。
- 下载交给 Android `DownloadManager`，App 不自行安装 APK，不申请存储权限，不上传诊断或设备信息。

不使用 SharedPreferences。

## 9. Room Schema

第一版 schema 即采用 Provider 通用字段，避免 Codex-only 污染。

### 9.1 provider_accounts

建议字段：

- `providerId`
- `accountId`
- `displayName`
- `avatarUrl`
- `status`
- `createdAt`
- `updatedAt`
- `lastUsedAt`

主键：

- `providerId + accountId`

### 9.2 quota_snapshots

建议字段：

- `id`
- `providerId`
- `accountId`
- `fetchedAt`
- `freshnessStatus`
- `providerStatus`
- `windowsJson`
- `primaryWindowId`
- `diagnosticsDigest`

索引：

- `providerId + accountId + fetchedAt`

`windowsJson` 存通用窗口列表，不创建 `fiveHourPercent`、`weeklyPercent` 这类 Codex-only 列。

### 9.3 refresh_attempts

建议字段：

- `attempt_id`
- `provider_id`
- `local_account_id`
- `trigger`
- `started_at`
- `finished_at`
- `status`
- `error_code`
- `http_status`
- `retryable`
- `user_action_required`
- `diagnostics_digest`

索引：

- `provider_id + local_account_id + started_at`

### 9.4 alert_states

建议字段：

- `alert_state_id`
- `provider_id`
- `local_account_id`
- `window_id`
- `threshold`
- `reset_at`
- `last_notified_at`

主键：

- `alert_state_id`

唯一索引：

- `provider_id + local_account_id + window_id + reset_at + threshold`

用于避免同一窗口同一阈值重复告警。

### 9.5 Session 存储

Session 不以明文字段进入 Room。

如使用文件保存 encrypted payload，文件名应由 `(providerId, accountId)` 派生且不可包含敏感原文。

如使用表保存，也只能保存：

- envelope metadata
- encrypted blob

不得保存 token 明文。

## 10. 会话安全

### 10.1 加密方式

MVP 使用：

- Android Keystore 生成不可导出的 AES-GCM key。
- Provider session payload 使用 AES-GCM 加密。
- key 不导出、不备份。
- App 卸载后 key 消失，会话不可恢复。

### 10.2 不使用生物识别强制读取

MVP 不启用 per-use biometric / user-auth gating。

原因：

- WorkManager 后台刷新需要自动读取 session。
- 每次读取要求指纹 / 面容会破坏后台刷新。

后续可增加“打开 App 时要求系统认证”的隐私锁，但不能影响后台刷新。

### 10.3 敏感信息禁区

以下内容禁止进入日志、诊断、Room 明文、DataStore 明文、UI 文案：

- access token
- refresh token
- id token
- auth code
- Cookie
- 完整 `auth.json`
- 完整 OAuth callback URL query
- 原始 API response body

## 11. 认证架构

### 11.1 当前认证组件

认证通过 `ProviderAuthKind` 路由到各自流程，不同认证类型的组件组成如下：

**Codex（OAuthWebView / device-code）**

- UI：`AddAccountScreen` / `DeviceCodeLoginViewModel`。
- Domain facade：`CodexDeviceCodeLoginController`、`DeviceCodeLoginResult`、`DeviceCodeLoginNotifier`。
- Provider use case：`CodexDeviceCodeLoginUseCase`。
- Provider client：`CodexDeviceCodeClient`、`CodexOAuthTokenExchanger`。
- 会话校验与保存：`CodexSessionImporter`。

**API Key（DeepSeek / z.ai / MiniMax）**

- UI：`ApiKeyAuthScreen`（共用，AuthScaffold top bar）。
- Domain use case：`ApiKeyLoginUseCase`。
- 会话保存：`<Name>SessionImporter`。

**Cookie（Cursor / Kimi）**

- UI：`WebViewAuthScreen`（内嵌 WebView，AuthScaffold top bar，含登录提示对话框）。
- Domain use case：`SessionLoginUseCase`。
- 会话保存：`<Name>SessionImporter`（CookieManager 提取目标 cookie）。

**OAuth PKCE WebView 拦截（Claude）**

- UI：`WebViewAuthScreen`（`shouldOverrideUrlLoading` 拦截 callback code）。
- 共用 `OAuthTokenClient`（`providers/common/auth`）换取 token。
- 会话保存：`ClaudeSessionImporter`。

**OAuth PKCE loopback（Antigravity）**

- UI：`WebViewAuthScreen` + `LoopbackCallbackServer`。
- `LoopbackCallbackServer` 绑定 `127.0.0.1` 随机端口，单次请求后关闭；仅对 `127.0.0.1` 放行明文。
- 会话保存：`AntigravitySessionImporter`。

**路由**

- `ProviderSelectionSheet`（底部 Sheet，从 tab bar 升起）展示所有已注册 Provider，用户选择后导航到对应认证页。
- `SessionImportRouter` 按 `ProviderAuthKind` 将校验成功的候选 session 路由到对应 `SessionImporter`。

所有认证流程的共同约束：新连接必须在 official usage API 校验成功后，才保存账号、加密 session 和初始 `QuotaSnapshot`；UI 不直接处理 token、cookie 或私有 OAuth 细节。

### 11.2 Codex device-code 外部浏览器登录

Codex 登录使用 Hermes 对齐的 device-code 外部浏览器流程，完整状态机以 `docs/CODEX_DEVICE_CODE_LOGIN_SPEC.md` 为准。

已知 Codex device-code 形态：

- issuer: `https://auth.openai.com`
- verification page: `https://auth.openai.com/codex/device`
- device code request: `/api/accounts/deviceauth/usercode`
- authorization polling: `/api/accounts/deviceauth/token`
- token endpoint: `https://auth.openai.com/oauth/token`

Android 流程：

1. `CodexDeviceCodeLoginUseCase` 取消旧 attempt，并创建带 `attemptId` 的新登录尝试。
2. 请求 user-facing device code、verification URI、poll interval 和 expiry。
3. App 显示登录页内的 device code 行、复制按钮和打开 verification 页的主操作。
4. App 通过外部浏览器打开普通 verification 页面；通知正文点击也打开同一个外部 verification URI，通知 action 复制最新 code。
5. 按服务端 interval 轮询授权结果，过期、取消或失败后停止。
6. 授权成功后用返回的 authorization code + code verifier 换取 OAuth token。
7. 使用 access token 调 official usage API 校验。
8. 校验成功后保存账号、加密 session 和初始 quota snapshot。

约束（Codex device-code 专属）：

- Codex 登录不启动本地 HTTP server。
- 不做 authorization code 手填。
- Codex 登录不使用内嵌 WebView 或本地 callback server；外部浏览器只承载 OpenAI/Codex verification 页面。
- 浏览器 Cookie 不作为 App 持久会话。
- 不自动打开带 code 的 verification 完整链接，MVP 让用户在外部浏览器页面手动输入 / 粘贴 device code。
- 新账号连接不提供 `auth.json` 文件选择、粘贴 JSON、token 手填或 Cookie 输入路径。

> **多 Provider 扩展豁免（非 Codex）**：上述「不内嵌 WebView / 不启动本地 server」约束只适用于 Codex。扩展 Provider 的认证按类型实现：
> - **API Key**（DeepSeek / z.ai / MiniMax）：应用内输入框，无浏览器。
> - **Cookie**（Kimi / Cursor）：内嵌 `WebView` 承载 Provider 登录页，登录完成后用 `CookieManager` 自动提取目标 cookie；不展示、不持久化浏览器地址栏 URL。
> - **OAuth PKCE — WebView 拦截**（Claude）：内嵌 `WebView` 在 `shouldOverrideUrlLoading` 拦截 `console.anthropic.com` 回调提取 `code`；**禁止**让用户手动粘贴 callback URL。
> - **OAuth PKCE — loopback**（Antigravity）：Google 禁止在 WebView 内完成 OAuth，故使用外部浏览器 + 短暂的 loopback HTTP server（仅绑定 `127.0.0.1`、随机端口、单次请求、校验 `state`、收到 code 或离开页面即关闭）。`network_security_config` 仅对 `127.0.0.1` 放行明文，全局明文仍关闭。
>
> 所有非 Codex 凭证（API Key / Cookie / OAuth token）经 AES-GCM（Android Keystore）加密后落库，与 Codex session 同等保护；诊断与日志不输出凭证、cookie、token 或完整 callback URL。

### 11.3 外部浏览器 handoff 边界

App 不嵌入登录网页，不读取浏览器 Cookie，也不持有 OpenAI 登录页面状态。App 只保存 device-code 协议最终换得并通过 usage API 校验的 OAuth session。

外部浏览器 handoff 要求：

- 只打开 provider 返回或配置中的普通 verification URI。
- 不携带 token、authorization code、code verifier、device auth id 或完整 callback URL。
- 不自动打开带 code 的 verification 完整链接，除非后续重新验证并修订规格。
- 通知正文点击与页面按钮使用同一个 verification URI。
- 页面和通知 action 只复制 user-facing device code；通知正文不展示完整 code。

### 11.4 旧 `auth.json` 会话兼容与脱敏

`auth.json` 导入不再是用户可见的 MVP 认证方式。迁移后的架构只保留兼容与脱敏边界：

- 不提供新的 `auth.json` 文件选择入口。
- 不提供粘贴完整 JSON 的入口。
- 不申请或使用外部存储权限来导入凭据。
- 不持久化文件 URI 权限。
- 不复制 legacy 原文件到 App 私有目录。
- 不新增 access token / refresh token 单字段表单。
- 不新增 Cookie Header 输入。

兼容要求：

- 迁移前已经保存的账号和 encrypted OAuth session 不自动删除。
- 历史上从 `auth.json` 导入后已保存的 session 视为普通 Codex OAuth session。
- 现有 session 继续走统一 token refresh、official usage API 校验和 quota fetch。
- 如果实现分阶段保留旧 parser / import 内部代码，它必须无法从 UI、导航或 Provider capabilities 触达。

安全要求：

- legacy raw `auth.json` 内容必须视为高敏感输入。
- legacy raw `auth.json` 内容不得保存、缓存、进入 Room / DataStore / encrypted payload、日志、诊断或截图。
- redaction / diagnostics 必须继续识别并整体遮蔽 legacy raw `auth.json` 内容。

## 11.5 货币转换

`domain/currency` 包提供余额型 Provider 的货币转换能力：

- `CurrencyPreferences`：用户选择的目标货币（默认跟随系统 locale）。
- `ExchangeRates`：内置汇率表（静态，随版本更新）。
- `BalanceConversion`：将 `QuotaWindow.balanceAmount`（原始货币）转换为目标货币，结果写入 `originalBalanceAmount` / `originalBalanceCurrency` 字段（仅展示层，不持久化）。

DeepSeek 支持余额明细展示：`grantedBalance`（赠送额度）和 `toppedUpBalance`（充值额度）从 Provider DTO 持久化到 `windowsJson`。

## 12. 网络层

### 12.1 ProviderHttpClient

网络层采用 OkHttp 的轻量封装 `ProviderHttpClient`。

职责：

- 统一 timeout。
- 统一 User-Agent：`CodexMeter/<version>`。
- 统一 HTTP 响应封装。
- 统一基础脱敏。
- 统一网络错误归一。

Provider 自己负责：

- URL。
- header。
- request body。
- DTO。
- token refresh。
- 字段映射。
- Provider 私有错误识别。

### 12.2 DTO 解析

使用 `kotlinx.serialization`。

要求：

- 服务端字段默认 nullable。
- 未知字段忽略。
- 字段缺失时不估算。
- 字段解析失败时映射为 `providerResponseInvalid`。

## 13. 刷新架构

### 13.1 RefreshCoordinator

所有刷新入口统一进入 `RefreshCoordinator`。

刷新入口包括：

- WorkManager 周期刷新。
- 手动刷新（首页下拉刷新 / 右上角刷新按钮）。
- 登录 / 重新登录成功后刷新。
- 切换当前账号后刷新。

打开 App、从通知或微件进入首页只读取已持久化的 last known good 快照，不再触发网络刷新；新鲜度由手动刷新与周期后台刷新保证，以避免频繁切页造成的高频 API 调用。`RefreshTrigger.AppOpen` 仅保留作为历史快照来源标记与旧记录反序列化兼容，不再有任何入口产生它。

`RefreshCoordinator` 负责：

- 读取当前账号。
- 根据 `providerId` 找 Provider。
- 解密 session。
- 调用 Provider refresh / fetch。
- 保存新 encrypted session payload。
- 写入 `QuotaSnapshot`。
- 写入 `RefreshAttempt`。
- 派生 `CurrentQuotaState`。
- 触发 Widget / Notification 状态更新。

### 13.2 并发控制

刷新必须按 `(providerId, accountId)` 执行 single-flight。

要求：

- 同一账号同一时间只允许一个刷新任务。
- 后续刷新请求复用进行中的结果，或在完成后补一次。
- token refresh 必须串行。
- refresh token 轮换后立刻覆盖旧 encrypted payload。
- WorkManager 使用 unique work 防止任务堆积。

### 13.3 WorkManager

MVP 使用 WorkManager，不做 Foreground Service。

任务：

- 周期任务：`quota_periodic_refresh`，约 15 分钟。
- 一次性任务：`quota_refresh_once`。

周期任务刷新所有 Active 账号，并使用有界并发避免账号过多时堆积。

约束：

- 同一账号仍通过 `RefreshCoordinator` 保持 `(providerId, accountId)` single-flight。
- 非 Active / 需要重新登录 / 已禁用账号不参与周期刷新。
- 任一账号刷新成功后发布该账号的 `CurrentQuotaState` 以评估额度告警。
- 常驻通知状态不直接使用“刚刷新完成的账号”，而是按常驻通知配置重新加载展示账号和展示窗口。

常驻通知只是展示最近状态，不代表前台采集服务。

### 13.4 成功与失败分离

成功刷新：

- 写入 `QuotaSnapshot`。
- 写入成功 `RefreshAttempt`。
- 更新 `CurrentQuotaState`。
- 更新 Widget。
- 更新状态通知。
- 执行告警判断。

失败刷新：

- 写入失败 `RefreshAttempt`。
- 不覆盖 last known good `QuotaSnapshot`。
- 根据错误更新 session 状态。
- 保留旧额度展示，但标明过期 / 失败 / 需要重新登录。

401 / 403 / refresh token expired / revoked：

- 标记 session 为 `reauthRequired`。
- 不无限重试。
- 引导用户通过 device-code 外部浏览器流程重新登录。

## 14. 历史与清理

MVP 每次成功刷新都保存原始成功快照。

理由：

- 15 分钟刷新频率下，30 天约 2880 条 / 账号，Room 可轻松承载。
- 原始快照有利于趋势、诊断和后续扩展。

规则：

- 趋势图读取最近 24 小时成功快照。
- MVP 直接使用已有成功快照点，并按采集时间在 24 小时窗口内定位；不按点数均分铺满图表。
- 失败 attempt 不进入趋势。
- 根据用户设置清理旧历史：7 / 30 / 90 / 永久。
- 默认保留 30 天。
- 删除账号时级联删除该账号全部历史。

暂不做：

- 写入降采样。
- hourly / daily rollup 表。
- 独立历史页。
- 数据导出。

## 15. Widget 架构

桌面微件采用 Jetpack Glance。

MVP 实现一个可调整大小并自适应布局的微件，支持以下四种尺寸（`WidgetLayoutVariant`）：

| 变体 | 参考尺寸（宽×高 dp） | 字段容量 |
|---|---|---|
| `ThreeByOne` | 180×60 | 1 |
| `FourByOne` | 300×60 | 2 |
| `ThreeByTwo` | 180×150 | 3 |
| `FourByTwo` | 300×150 | 4 |

布局由 `SizeMode.Responsive` 驱动；Glance 选择不超过可用空间的最大参考尺寸对应的变体。

字段列表（`fieldList`）由用户在配置页最多选 4 个窗口 id 决定，按选择顺序取前 `fieldCapacity` 个展示。未配置时按 Provider 天然顺序取前 N 个默认窗口。

### 15.1 WidgetQuotaState

Widget 不直接消费完整 `CurrentQuotaState`，而是消费裁剪后的 `WidgetQuotaState`。

`WidgetQuotaState` 包含：

- 有序字段列表（`List<WidgetQuotaField>`），对应用户配置或默认窗口的展示值。
- Provider 图标资源 id 和账号展示名。
- 状态等级（用于 tone / 颜色）。
- 数据新鲜度。
- onboarding 标志（未配置时显示引导提示）。
- 点击目标。

来源：

- 由 `CurrentQuotaState` 派生并持久化到 `DataStore`（通过 `WidgetQuotaStatePreferences`）。
- 未配置微件默认跟随当前账号，取 Provider 默认窗口列表。
- 已配置微件通过 `WidgetQuotaConfiguration`（含 `selectedWindowIds` 多选和 `fieldList`）指定展示账号与字段顺序；展示账号读取 Room 中该账号最新本地快照。
- 微件默认和微件配置不读取常驻通知账号 / 显示额度配置。

### 15.2 Widget 约束

Widget 不做：

- 直接联网。
- 微件内部账号切换。
- 内部刷新按钮。
- Provider 私有逻辑。

允许：

- 系统 AppWidget 配置 Activity 读写该 widget 的 `WidgetQuotaConfiguration`，并重新投影 `WidgetQuotaState`。
- 账号删除时清理引用该账号的 widget 状态与配置。

点击行为：

- 已登录：打开 App 首页。
- 未配置 / 未登录：同样打开 App 首页，由首页无账号态承载添加账号引导。微件不再深链单一 Provider 的添加账号页（已支持多 Provider，退役了单 Provider 直达入口）。

刷新触发：

- 刷新完成后更新。
- 切换当前账号后更新。
- 保存单个 widget 配置后更新该 widget。
- 登录成功后更新。
- App 冷启动状态恢复后可更新。

## 16. 通知与告警

### 16.1 NotificationOrchestrator

通知由 `NotificationOrchestrator` 统一管理。

负责：

- 创建通知渠道。
- 按常驻通知配置更新状态通知。
- 根据 `AlertPolicy` 判断是否发额度告警。
- 处理账号失效 / 错误通知。

Worker 和 Repository 不直接创建通知。

### 16.2 通知渠道

MVP 使用三个通知渠道：

- 状态通知：常驻通知，默认静默，低打扰。
- 额度告警：剩余额度 10% / 0% 阈值提醒。
- 账号与错误：会话失效、认证失败等。

Android 13+ 通知权限未授予时：

- 不阻塞首页。
- 不阻塞微件。
- 静默跳过通知。

### 16.3 AlertPolicy

告警规则：

- 针对每个账号中已开启告警的额度窗口。
- 默认阈值按剩余额度：30 / 10 / 0。
- 剩余 30%：只改变状态色 / 文案，不弹通知。
- 剩余 10%：发通知。
- 剩余 0%：发通知。
- 用户配置的剩余额度阈值同时影响 Home 状态、Widget tone 和通知告警。
- 同一账号、同一窗口、同一 resetAt、同一阈值只提醒一次。
- reset 后清理旧告警状态。
- 额度刷新成功、阈值调整、开启账号窗口告警时都必须触发重新评估。
- Android 通知权限未授予时不得写入告警去重状态。

`AlertStateStore` 使用以下 key 去重：

- `providerId`
- `accountId`
- `windowId`
- `resetAt`
- `threshold`

常驻通知额外约束：

- 常驻通知有自己的账号选择和显示额度窗口。
- 默认跟随当前账号，默认显示 5小时额度。
- 指定账号删除后回退到跟随当前账号。
- 其它账号刷新完成不得覆盖常驻通知配置展示的账号状态。

## 17. 错误模型

错误模型分两层。

### 17.1 Provider 私有错误

例如 Codex 内部可以有：

- refresh token expired
- refresh token revoked
- state mismatch
- usage response invalid
- token exchange failed

这些错误不得直接进入 UI。

### 17.2 通用 QuotaError

Provider 私有错误必须映射为通用 `QuotaError`。

建议类型：

- `network`
- `unauthorized`
- `reauthRequired`
- `providerRateLimited`
- `providerUnavailable`
- `providerResponseInvalid`
- `sessionInvalid`
- `importInvalid`
- `oauthCancelled`
- `oauthFailed`
- `unknown`

字段：

- `providerId`
- `severity`
- `isUserActionRequired`
- `retryable`
- `httpStatus?`
- `safeMessageKey`
- `diagnosticsDigest`

UI、Widget、通知只消费 `QuotaError`，不得判断 Provider 私有异常。

## 18. 诊断与日志

CodexMeter 只提供可复制脱敏诊断摘要。

### 18.1 诊断摘要包含

诊断按分组段落输出（`## GENERATED / ENVIRONMENT / WORKMANAGER / CONFIG / ACCOUNTS / CURRENT ACCOUNT`）；时间戳同时给出可读 UTC 时间和相对时长，并以导出时刻 `generatedAt` 作为相对时长基准。

环境（ENVIRONMENT）：

- App 版本、build type、Android SDK 与版本号。
- 设备型号、locale。
- 电池优化白名单状态、后台限制状态、网络类型、Data Saver 状态。
- App 首次安装与最近更新时间。

后台调度（WORKMANAGER）：

- WorkManager 周期任务状态、运行尝试次数、下次调度时间、停止原因。
- 一次性刷新任务状态。
- 通知权限状态。

应用配置（CONFIG）：

- Room schema 版本、历史保留天数、刷新间隔。
- 状态通知开关、额度告警开关、告警阈值。
- 各账号已开启告警的额度窗口（带 providerId 与脱敏账号哈希）。
- 已放置的 Widget 数量。

全部账号摘要（ACCOUNTS）：

- 每个账号一行：providerId、脱敏账号哈希、状态、最近一次刷新结果（状态 / 错误码 / 时长）、最近成功刷新时长、最近快照时长。

当前账号（CURRENT ACCOUNT）：

- providerId、脱敏 accountId 哈希、账号选择状态、账号状态、账号数量、连续失败次数。
- session 状态、session providerAccountId 状态、当前展示状态、数据新鲜度。
- 最近快照来源 / 时间 / 摘要状态、最近成功刷新时间、最近一次刷新尝试（触发源 / 起止时间 / 状态）。
- 安全错误类型、HTTP status、是否可重试、是否需要用户操作、诊断摘要。
- device-code 登录状态、脱敏 attempt id、verification URI 分类和轮询信息。

### 18.2 诊断摘要禁止包含

- access token。
- refresh token。
- id token。
- auth code。
- Cookie。
- 完整 `auth.json`。
- 完整 URL query。
- 原始 API response body。

### 18.3 日志策略

- Debug build 可打印更多结构化日志，但必须脱敏。
- Release build 默认只保留必要错误摘要。
- 不导出完整日志文件。
- 不上传远程日志。

所有日志与诊断必须经过统一 redaction。

## 19. 权限策略

MVP 采用最小权限策略。

需要权限：

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `POST_NOTIFICATIONS`，Android 13+ 运行时询问

可选权限：

- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：仅用于在设置页「提升后台刷新可靠性」入口引导用户把 App 加入电池优化白名单，使 Doze 不那么激进地推迟周期刷新。从不强制，仅在后台刷新开启且尚未豁免时显示；不通过它绕过 Doze，也不拉起前台服务。

不申请：

- 外部存储权限。
- 后台定位。
- 通讯录。
- 日历。
- 短信。
- `SCHEDULE_EXACT_ALARM`。
- 前台服务权限。
- 悬浮窗。
- 无障碍。
- VPN。

认证流程不需要外部存储权限，也不提供 `auth.json` 文件选择或粘贴 JSON 路径。

## 20. 冷启动、离线与进程恢复

核心展示不得依赖内存状态。

### 20.1 App 冷启动

流程：

1. 从 DataStore 读取当前 `(providerId, accountId)`。
2. 从 Room 读取 last known good `QuotaSnapshot`。
3. 从 `RefreshAttempt` 读取最近错误。
4. 派生 `CurrentQuotaState`。
5. 根据新鲜度决定是否触发一次刷新。

### 20.2 Widget 恢复

Widget 从持久化 `WidgetQuotaState` 或 last known good 派生。

不依赖 App 进程仍存活。

### 20.3 通知恢复

App 启动或 Worker 执行后可重建常驻通知。

如果用户关闭状态通知或未授权通知权限，则静默跳过。

### 20.4 离线状态

有旧快照：

- 展示旧额度。
- 标明刷新失败 / 可能过期 / 已过期。

无旧快照：

- 首页显示错误引导。
- Widget 显示需要登录或刷新失败状态。

网络失败不得清空主界面。

## 21. 备份、迁移与删除

### 21.1 备份

MVP 禁用 Android Auto Backup。

不做：

- 云同步。
- 数据导出。
- 跨设备迁移。
- token/session/history 导出。

原因：

- OAuth session 敏感。
- Keystore key 不可导出。
- 备份恢复后 encrypted payload 可能不可读。
- 自用场景重新登录成本更低。

### 21.2 Room migration

- Room 从第一版启用 schema version。
- Release 后只做显式 migration。
- 不做破坏性静默迁移。
- Debug 开发期可允许清库重建。

### 21.3 删除账号

删除账号必须二次确认。

删除时级联删除：

- `ProviderAccount`
- encrypted session
- `QuotaSnapshot`
- `RefreshAttempt`
- `AlertState`

如果删除的是当前账号：

- 有其他账号：切换到下一个账号或要求用户选择。
- 无其他账号：回到未登录态。

### 21.4 清空历史

设置页支持：

- 清空当前账号历史。
- 清空全部账号历史。

清空历史不删除账号和 session。

## 22. 构建与签名

### 22.1 applicationId

- Release：`com.kmnexus.codexmeter`
- Debug：`com.kmnexus.codexmeter.debug`

### 22.2 App 名称

- Release：`CodexMeter`
- Debug：`CodexMeter Dev`

Debug 与 release 可并存安装。

### 22.3 Build Types

MVP 只维护：

- `debug`
- `release`

不引入 product flavors。

### 22.4 签名

- Debug 使用默认 debug keystore。
- Release 使用本地 release keystore。
- keystore、密码、签名配置不得提交到仓库。
- 签名信息通过 `local.properties` 或环境变量读取。

### 22.5 产物

- MVP 输出 APK。
- 暂不做 Play Store / AAB。
- `versionCode` 递增。
- `versionName` 使用语义版本，例如 `0.1.0`。

## 23. 测试策略

MVP 测试以本地单元测试为主。

重点覆盖：

- device-code response 缺字段与格式错误。
- device-code attempt 取消、过期和 stale result 丢弃。
- 授权轮询 pending / failure / expiry 映射。
- token exchange 错误映射。
- legacy raw `auth.json` redaction。
- usage response 到 `QuotaSnapshot` 映射。
- 字段缺失时不估算、不告警。
- 刷新成功写 snapshot。
- 刷新失败不覆盖 last known good。
- 401 / 403 映射为 reauthRequired。
- 剩余额度 30 / 10 / 0 告警与状态策略。
- 同一 reset 窗口告警去重。
- token / auth code / Cookie 脱敏。

暂不强求：

- 完整 Compose UI 自动化。
- 真机 device-code 外部浏览器登录自动化。
- 通知栏 UI 自动化。
- Widget UI 自动化。
- 多设备矩阵。

UI、Widget、通知先使用人工验收清单。

## 24. MVP 实现顺序建议

建议按以下顺序实现：

1. 初始化 Android 项目、Gradle、包名、debug/release。
2. 建立基础包结构、`AppContainer`、Compose 壳。
3. 建立 Room、DataStore、Keystore 加密存储。
4. 定义 Provider 通用模型和手写 `AppContainer` 依赖组装。
5. 实现 Codex usage API 拉取、映射与 OAuth session refresh。
6. 实现 Codex device-code 外部浏览器登录与 usage API 校验。
7. 移除用户可见 `auth.json` 导入 UI / 路由，同时保留既有 encrypted OAuth session 兼容。
8. 实现 `RefreshCoordinator`、WorkManager、single-flight。
9. 实现首页 `CurrentQuotaState` 展示。
10. 实现 Jetpack Glance 中号 Widget。
11. 实现常驻通知与告警策略。
12. 补齐设置页、账号管理页、诊断卡。
13. 补核心单元测试和人工验收清单。
14. 打 debug APK 真机侧载验证。
15. 配置 release 签名并打首个侧载 APK。

## 25. 后续扩展原则

后续增加 Gemini / Claude Provider 时：

- 只能新增 Provider 实现和能力声明。
- 不应改动公共账号主键设计。
- 不应改动 session envelope 结构。
- 不应让 UI 直接识别 Provider 私有 token。
- 新 Provider 的窗口通过 `QuotaWindow` 暴露。
- 新 Provider 的错误必须映射到 `QuotaError`。
- 新 Provider 的认证方式先通过 provider 私有 use case + domain facade 暴露；Provider 增多后再抽象 registry / connector。

如果 Provider 数量、页面复杂度或团队协作复杂度上升，再考虑：

- 多模块拆分。
- Hilt。
- 更完整 UI 自动化。
- 更复杂历史聚合表。

## 26. 架构红线

以下红线不得突破：

- 不保存明文 token。
- 不保存原始 `auth.json`。
- 不新增 `auth.json` 文件选择、粘贴 JSON 或手动凭据导入路径。
- 不把浏览器 Cookie 当 App 持久会话。
- 不做网页解析。
- 不做 token / cost 本地估算。
- 不让失败刷新覆盖 last known good。
- 不让 Widget 直接联网。
- 不让 UI 直接访问 Provider 私有 session。
- 不导出账号数据和历史数据。
- 不申请与功能无关的高敏权限。
- 不在诊断和日志中泄漏 token、auth code、Cookie、原始响应。
