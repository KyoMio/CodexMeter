# CodexMeter Development Rules

## 1. 文档定位

本文是 `CodexMeter` 的开发规则与长期维护护栏。

所有后续开发、重构、修 bug、生成代码、让 Agent 接手任务前，都必须先读：

1. `docs/PRD.md`
2. `docs/ARCHITECTURE.md`
3. `docs/CODEX_DEVICE_CODE_LOGIN_SPEC.md`，若涉及 Codex 登录、OAuth 会话、账号接入、认证 UI、认证通知或 legacy `auth.json` 迁移
4. `RULES.md`
5. `DESIGN.md`，若涉及 UI / Widget / 通知视觉与交互

本文优先级：

- 产品范围以 `docs/PRD.md` 为准。
- 架构边界以 `docs/ARCHITECTURE.md` 为准。
- 代码质量、可维护性、安全和协作规则以本文为准。
- 若文档冲突，先停止实现，更新文档或请用户拍板，不允许靠猜。

## 2. 核心目标

CodexMeter 的代码必须长期可维护、易于人工接手、便于小步迭代。

开发目标不是“先跑起来再说”，而是：

- 清晰的职责边界。
- 可读的领域模型。
- 可测试的核心逻辑。
- 可诊断但不泄密。
- 可扩展 Provider，但不提前堆抽象。
- 小文件、小函数、小变更。
- 能让人半年后打开仍能看懂。

宁可多花一点时间拆清楚，也不要把认证、网络、存储、UI、通知揉成一团。

## 3. 反屎山铁律

以下行为禁止：

- 把多个职责塞进一个 `Manager` / `Helper` / `Utils` / `Controller`。
- 为了省事让 UI 直接访问 Room、DataStore、OkHttp、Keystore 或 Provider 私有 session。
- 在 Composable 里写业务逻辑、网络请求、数据库查询、token 处理。
- 在 Worker 里拼 UI 状态、直接发通知、直接操作 Provider 私有 token。
- 在 Repository 里塞通知、Widget、UI 文案和权限弹窗逻辑。
- 复制粘贴一段逻辑到首页、Widget、通知三处各算一遍。
- 用布尔参数控制复杂分支，例如 `refresh(force = true, silent = false, notify = true)`。
- 用魔法字符串表示状态、错误和窗口 ID，必须集中定义或用强类型。
- 捕获 `Exception` 后吞掉，不返回结构化错误。
- 写“先这样，后面再说”的临时绕路代码但不留 TODO owner 和原因。
- 没有测试就改 Provider 映射、认证、加密、刷新、告警、脱敏逻辑。
- 为了赶进度绕开 `docs/ARCHITECTURE.md` 的架构红线。

如果一个改动让文件越来越大、分支越来越深、调用链越来越绕，就必须先重构再继续堆功能。

## 4. 项目结构规则

MVP 是单模块 Android app，但包内必须严格分层。

基准包结构以 `docs/ARCHITECTURE.md` 为准：

```text
com.kmnexus.codexmeter
├── app
├── core
├── data
├── domain
├── providers
├── ui
├── widget
├── notification
└── worker
```

### 4.1 依赖方向

允许的依赖方向：

- `ui` → `domain`
- `ui` → ViewModel / UI state
- `widget` → widget state / domain read model
- `notification` → notification state / alert policy
- `worker` → `RefreshCoordinator`
- `data` → Room / DataStore / SecureSessionStore
- `providers` → provider DTO / network / private session payload
- `domain` → 通用模型和接口

禁止的依赖方向：

- `domain` 依赖 `ui`
- `domain` 依赖 Android View / Compose
- `providers` 依赖具体 UI
- `ui` 依赖 Provider 私有 DTO
- `widget` 依赖 Provider 私有 DTO
- `notification` 依赖 Provider 私有 DTO
- 任意层直接跨过 Repository 访问存储细节

### 4.2 包命名

- 包名全部小写。
- Provider 放在 `providers.<providerId>`。
- Codex 私有实现只放在 `providers.codex`。
- 通用模型只放在 `domain` 或 `core`，不得放在 `providers.codex`。
- UI 页面按 feature 拆包，例如 `ui.home`、`ui.settings`、`ui.account`。

## 5. 文件与函数规模

规模限制是维护性护栏，不是绝对法律。超过限制必须能说明原因。

建议软限制：

- 普通 Kotlin 文件：不超过 300 行。
- 单个 class：不超过 250 行。
- 单个函数：不超过 50 行。
- 单个 Composable：不超过 120 行。
- 单个 ViewModel：不超过 250 行。
- 单个 Repository：不超过 300 行。
- 嵌套层级：尽量不超过 3 层。

超过时优先处理方式：

1. 提取领域模型。
2. 提取 use case / policy。
3. 提取 mapper。
4. 提取 UI section composable。
5. 提取测试 fixture。
6. 删除重复逻辑。

禁止用“大文件但我自己看得懂”作为理由。

## 6. 命名规则

命名必须表达业务含义，不用含糊词。

### 6.1 禁止滥用的词

除非确实是通用基础设施，否则不要使用：

- `Manager`
- `Helper`
- `Utils`
- `Common`
- `Base`
- `Data`
- `Info`
- `Temp`
- `New`
- `Old`
- `Stuff`

更好的命名方式：

- `RefreshCoordinator`，而不是 `RefreshManager`
- `AlertPolicy`，而不是 `AlertHelper`
- `CodexUsageMapper`，而不是 `CodexUtils`
- `SecureSessionStore`，而不是 `TokenManager`
- `CurrentQuotaStateFactory`，而不是 `StateHelper`

### 6.2 状态与错误命名

状态和错误必须稳定、可搜索、可测试。

要求：

- 错误用 `QuotaError` 或 provider 私有错误类型表达。
- UI 文案使用 `safeMessageKey` 或资源 ID。
- 窗口 ID 使用常量或类型定义，例如 `five_hour`、`weekly`。
- 不允许到处散落字符串字面量。

## 7. 注释规则

注释必须帮助长期维护，而不是重复代码。

### 7.1 必须写注释的地方

以下场景必须有注释或 KDoc：

- Provider 接口和能力模型。
- `ProviderSessionEnvelope` 加密边界。
- device-code 外部浏览器登录状态机、attemptId 和轮询逻辑。
- 登录通知打开外部验证页、复制 code 和 stale action 校验逻辑。
- legacy raw `auth.json` 内容脱敏逻辑。
- token refresh 串行和 single-flight 并发控制。
- last known good 快照不被失败覆盖的逻辑。
- 告警去重规则。
- redaction / diagnostics 脱敏规则。
- Room migration。
- 任何看起来“不直觉但必要”的平台 workaround。

### 7.2 注释应该解释什么

好注释解释：

- 为什么这样做。
- 这个边界保护什么。
- 哪些输入是不可信的。
- 哪些字段不能落日志。
- 哪个平台限制导致当前写法。
- 未来扩展时不能破坏的 invariant。

坏注释示例：

```kotlin
// Set loading to true
isLoading = true
```

好注释示例：

```kotlin
// Keep the last successful quota visible on refresh failure.
// A failed network call must not erase the user's last known usable quota.
```

### 7.3 注释维护规则

- 改代码时必须同步更新相关注释。
- 过时注释比没注释更危险，发现必须立即修。
- 禁止写无法验证的注释，例如“这里以后会优化”。
- TODO 必须写清楚原因、范围和触发条件。

TODO 格式：

```kotlin
// TODO(codexmeter): Replace with provider-specific migration when Claude provider is added.
// Reason: MVP only has Codex, but the envelope schema already carries providerId.
```

## 8. Kotlin 规则

### 8.1 基本规范

- 使用 Kotlin official style。
- 优先 `val`，必要时才用 `var`。
- 禁止非必要的 `!!`。
- 禁止无意义的 nullable。
- 禁止吞异常。
- 禁止在主线程执行网络、文件、数据库、加密、JSON 解析。
- 所有 suspend API 必须 main-safe。

### 8.2 Result 与错误

- Provider 私有错误必须映射为通用 `QuotaError`。
- UI 不展示原始异常 message。
- 用户可见错误必须走资源和 `safeMessageKey`。
- 日志和诊断只记录脱敏摘要。

### 8.3 数据模型

- 服务端 DTO 字段默认 nullable。
- DTO 不直接进入 UI。
- DTO 必须经过 mapper 转成 domain model。
- Room entity 不直接作为 UI state。
- UI state 不直接作为 Room entity。

### 8.4 Magic Number / Magic String

禁止散落：

- URL。
- providerId。
- windowId。
- notification channel id。
- WorkManager unique work name。
- DataStore key。
- Room table name。
- 错误码字符串。

必须集中定义在合适位置。

## 9. Compose 与 UI 规则

### 9.1 Composable 职责

Composable 只负责：

- 渲染 `UiState`。
- 发出用户意图 `UiIntent` / callback。
- 组合小组件。

Composable 禁止：

- 发网络请求。
- 读写数据库。
- 读写 DataStore。
- 解析 legacy raw auth JSON 或任何凭据原文。
- 解密 session。
- 拼接安全敏感诊断。
- 判断 Provider 私有错误。

### 9.2 ViewModel 职责

ViewModel 负责：

- 暴露 `StateFlow<UiState>`。
- 接收 UI intent。
- 调用 use case / repository。
- 将领域状态映射为 UI state。

ViewModel 禁止：

- 持有 token 明文。
- 直接操作 OkHttp。
- 直接操作 Room DAO，除非通过 Repository。
- 包含大量 UI 拼装逻辑。

### 9.3 UI 状态

- 首页、Widget、通知、告警必须共享 `CurrentQuotaState` 或其裁剪版本。
- 不允许三处各自计算 freshness、状态色、主额度、错误状态。
- 所有用户可见文案必须走 string resources。
- i18n 覆盖 Compose、Widget、Notification、错误、诊断。

### 9.4 可访问性

- 可点击元素至少 48dp。
- 交互元素必须有可理解的 contentDescription。
- 状态色不能是唯一信息来源，必须有文字状态。
- 深色模式必须可用。
- 文本对比度必须符合基本可读性。

## 10. Provider 规则

Provider 是扩展点，但不是随便插代码的后门。

### 10.1 新增 Provider 必须包含

- `ProviderCapabilities`
- `ProviderAuthConnector`
- `QuotaProvider`
- session payload schema
- DTO
- mapper
- 私有错误类型
- 私有错误到 `QuotaError` 的映射
- 核心单元测试
- 脱敏诊断规则

### 10.2 Provider 禁止事项

Provider 不得：

- 直接调用 UI。
- 直接发通知。
- 直接更新 Widget。
- 直接写 DataStore 当前账号。
- 直接把 token 暴露给公共层。
- 直接保存原始 API response body。
- 绕过 `SecureSessionStore` 保存 session。

### 10.3 Codex Provider 特殊规则

Codex Provider 必须遵守：

- Codex 新登录使用 `docs/CODEX_DEVICE_CODE_LOGIN_SPEC.md` 定义的 Hermes 对齐 device-code 外部浏览器流程。
- Android 端不启动本地 server；只允许打开 OpenAI/Codex verification URI 的外部浏览器 handoff，不使用内嵌 WebView 或自建 OAuth callback server。
- 新账号接入不得提供 `auth.json` 文件选择、粘贴 JSON、token 手填或 Cookie 输入路径。
- 既有已保存 OAuth session，包括历史上从 `auth.json` 导入后保存的 session，不得被自动删除，仍按统一 refresh 逻辑保活。
- legacy raw `auth.json` 内容只作为高敏感内容整体脱敏，不保存原文。
- OAuth code、code verifier、device auth id、token 不得进入日志和诊断。
- usage API 字段缺失时显示不可用，不估算。
- 401 / 403 / refresh token 失效必须映射为需要重新登录。

## 11. 存储规则

### 11.1 Room

Room 只保存非敏感业务数据：

- ProviderAccount。
- QuotaSnapshot。
- RefreshAttempt。
- AlertState。

Room 禁止保存：

- access token。
- refresh token。
- id token。
- auth code。
- Cookie。
- 原始 auth.json。
- 原始 OAuth callback URL。
- 原始 API response body。

### 11.2 DataStore

DataStore 只保存轻量设置：

- 当前 provider / account。
- 主额度窗口。
- AppWidget 级展示账号 / 紧凑主额度窗口配置。
- 阈值。
- 历史保留期。
- 通知开关。

DataStore 禁止保存任何 session 明文。

### 11.3 SecureSessionStore

- 只保存 encrypted payload 和必要 envelope metadata。
- 加密使用 Android Keystore + AES-GCM。
- key 不导出、不备份。
- 卸载后 session 不可恢复。
- 不启用每次读取生物识别 gating，以保证后台刷新可用。

## 12. 网络规则

- 统一通过 `ProviderHttpClient`。
- 不在 UI、Widget、Notification、Worker 里直接 new OkHttp client。
- 默认 timeout 必须明确。
- User-Agent 使用 `CodexMeter/<version>`。
- Authorization、Cookie、token 类 header 必须脱敏。
- Release build 不打印 request / response body。
- DTO 解析失败必须返回结构化错误。
- 字段缺失不得估算。
- 网络错误不得清空 last known good 快照。

## 13. 刷新与并发规则

- 所有刷新入口必须进入 `RefreshCoordinator`。
- 同一 `(providerId, accountId)` 必须 single-flight。
- WorkManager 使用 unique work 防止任务堆积。
- token refresh 必须串行。
- token refresh 成功后必须立即保存新的 encrypted session payload。
- 刷新成功写 `QuotaSnapshot`。
- 刷新失败写 `RefreshAttempt`，不得覆盖成功快照。
- 401 / 403 / session revoked 不无限重试，必须进入 reauthRequired。
- 告警判断只在统一状态更新后执行。

## 14. Widget 规则

- Widget 使用 Jetpack Glance。
- MVP 只实现中号 Widget。
- Widget 只消费 `WidgetQuotaState`；AppWidget 配置入口只读写 `WidgetQuotaConfiguration` 并重新投影状态。
- Widget 不直接联网。
- Widget 不直接查 Provider。
- Widget 不直接解密 session。
- Widget 内部不做账号切换。
- Widget 不做内部按钮。
- Widget 点击只打开 App 对应入口。
- 配置指定的非当前账号只能读取本地最新快照，不得启动多账号并行后台刷新。

## 15. 通知与告警规则

- 通知由 `NotificationOrchestrator` 统一管理。
- 告警由 `AlertPolicy` 判断。
- 去重由 `AlertStateStore` 负责。
- Worker 和 Repository 不直接发通知。
- 剩余 30% 只改变状态色与文案，不发通知。
- 剩余 10% / 0% 发通知。
- 同一账号、窗口、resetAt、阈值只提醒一次。
- reset 后清理旧告警状态。
- 通知权限未授予时不得阻塞首页和 Widget。

## 16. 安全与隐私红线

以下内容绝不能进入日志、诊断、截图、Room 明文、DataStore 明文、通知、Widget、崩溃信息：

- access token。
- refresh token。
- id token。
- auth code。
- Cookie。
- 完整 auth.json。
- 完整 OAuth callback URL query。
- 原始 usage API response body。

其他红线：

- 不做 token 手填。
- 不做 Cookie Header 手填。
- 不做网页解析。
- 不做 token / cost 本地估算。
- 不做导出。
- 不做云同步。
- 禁用 Android Auto Backup。
- 不申请无关高敏权限。

## 17. 诊断规则

诊断目标是“能排错但不泄密”。

允许记录：

- App 版本。
- Android 版本。
- providerId。
- accountId 脱敏后缀。
- session 状态。
- 最近成功刷新时间。
- 最近失败刷新时间。
- 错误类型。
- HTTP status。
- WorkManager 状态。
- 通知权限状态。
- Widget 最近更新时间。

禁止记录：

- token 原文。
- auth code。
- Cookie。
- 完整 URL query。
- 原始 auth.json。
- 原始 response body。

所有诊断输出必须经过 redaction 工具。

## 18. 测试规则

### 18.1 TDD 原则

核心逻辑必须测试先行。

必须 test-first 的范围：

- Provider DTO mapper。
- device-code client / login use case。
- device-code attempt 取消、过期和 stale result 丢弃。
- token exchange / refresh 错误映射。
- session envelope 迁移。
- `QuotaError` 映射。
- `RefreshCoordinator` 降级逻辑。
- last known good 保护。
- `AlertPolicy`。
- redaction / diagnostics。
- Room migration。

流程：

1. 写失败测试。
2. 运行并确认因目标行为缺失而失败。
3. 写最小实现。
4. 运行并确认通过。
5. 必要时重构。
6. 运行相关测试和全量可用测试。

### 18.2 可以先人工验收的范围

以下范围 MVP 可先以人工验收清单为主，但不能违反架构边界：

- Compose 视觉布局。
- Widget 视觉细节。
- 通知展示样式。
- 真机外部浏览器 device-code 登录体验。

### 18.3 必测场景

至少覆盖：

- device-code response 缺字段。
- device-code request 成功后进入等待授权状态。
- 新 attempt 取消旧 attempt，旧结果不能写入 session。
- 授权过期后停止轮询。
- token exchange 成功但 usage 校验失败时不保存账号。
- legacy raw auth JSON 整体脱敏。
- usage API 字段缺失。
- 401 / 403 映射为 reauthRequired。
- 网络失败不覆盖成功快照。
- 剩余额度 30 / 10 / 0 阈值行为。
- reset 后告警状态清理。
- token / auth code / Cookie 脱敏。

## 19. 构建与验证规则

项目初始化后，任何功能开发前必须先保证：

```bash
./gradlew assembleDebug
```

能够成功。

常用验证：

```bash
./gradlew test
./gradlew :app:testDebugUnitTest
./gradlew assembleDebug
```

涉及 release 包时再验证：

```bash
./gradlew assembleRelease
```

规则：

- 不允许提交无法构建的代码。
- 不允许忽略新增编译警告。
- 不允许为了通过构建临时删除测试。
- 不允许把 keystore、密码、local.properties 提交到仓库。
- 没有 Android SDK / JDK 时，必须在结果里明确说明验证受限。

## 20. 依赖规则

新增依赖必须满足：

- 有明确用途。
- 不与既定架构冲突。
- 不引入明显过重框架。
- 不引入不必要权限。
- 不引入远程日志、统计、广告 SDK。
- 与 Kotlin / Compose / AGP 版本兼容。

MVP 默认不引入：

- Hilt。
- Retrofit。
- Ktor。
- 远程 analytics。
- crash 上传服务。
- 广告 SDK。

新增依赖时必须在 PR / 变更说明中写清原因。

## 21. 文档同步规则

以下情况必须同步更新文档：

- 改变产品范围：更新 `docs/PRD.md`。
- 改变架构边界：更新 `docs/ARCHITECTURE.md`。
- 改变开发规范：更新 `RULES.md`。
- 改变 UI / 交互 / 视觉：更新 `DESIGN.md`。
- 新增 Provider：更新 `docs/ARCHITECTURE.md` 和 `RULES.md`。
- 新增高风险认证或存储逻辑：更新安全相关章节。

不允许代码和文档长期不一致。

## 22. Git 与变更规则

每次变更应尽量小而完整。

要求：

- 一次提交只做一类事情。
- 重构和功能尽量分开。
- 格式化和逻辑改动尽量分开。
- 不提交生成的临时文件。
- 不提交本地签名文件和敏感配置。
- 不提交无关 IDE 噪音文件。
- 大范围重命名前先确认影响。

提交前自检：

- 是否读过相关文档。
- 是否破坏架构边界。
- 是否有必要测试。
- 是否跑过可用验证命令。
- 是否新增敏感日志。
- 是否需要更新文档。

## 23. Agent / AI 开发规则

后续如果由 Agent 继续开发，必须遵守：

- 开工前读取 `PRD.md`、`ARCHITECTURE.md`、`RULES.md`，涉及 UI 时读取 `DESIGN.md`。
- 不得凭记忆改架构。
- 不得大段重写无关文件。
- 不得为了省事跨层调用。
- 不得把 token 或 auth.json 原文贴到回复、日志或测试 fixture。
- 先做可逆的小补丁。
- 每轮完成后运行能运行的最小验证。
- 不能验证时明确说明原因。
- 发现文档与代码冲突时先停下，不许硬写。

Agent 输出原则：

- 产物落盘。
- 汇报只给结果、路径、验证状态、剩余风险。
- 不在聊天里甩大段代码。

## 24. Code Review Checklist

合并或交付前必须检查：

- 是否符合 PRD 范围。
- 是否符合 Architecture 边界。
- 是否符合本 RULES。
- 是否没有明文 token / auth.json / Cookie。
- 是否没有 UI 直连网络 / 数据库 / session。
- 是否没有 Widget 直连网络。
- 是否没有 Worker 直接发通知。
- 是否失败刷新不覆盖 last known good。
- 是否错误全部映射为 `QuotaError`。
- 是否用户文案走 string resources。
- 是否核心逻辑有测试。
- 是否注释解释了关键原因和 invariant。
- 是否没有明显超大文件、超长函数、重复逻辑。
- 是否运行过对应验证命令。

## 25. Definition of Done

一个开发任务完成必须满足：

- 功能符合 PRD 或明确的用户指令。
- 架构符合 `docs/ARCHITECTURE.md`。
- 代码符合本文规则。
- 敏感信息没有泄漏路径。
- 核心逻辑有测试或明确说明为什么暂不自动化。
- 能运行的验证已运行并通过。
- 相关文档已同步。
- 变更范围可解释、可回滚、可人工接手。

## 26. 最重要的维护原则

如果某段代码需要靠“当时写的人记得”才能维护，它就是坏代码。

好代码应该做到：

- 文件名说明职责。
- 类型名说明业务概念。
- 函数名说明行为。
- 测试说明边界。
- 注释说明原因。
- 错误说明下一步。
- 文档说明决策。

CodexMeter 是小工具，但不能用小工具当借口写一次性屎山。
