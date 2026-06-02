# Codex Device Code 登录设计规格

状态：Implemented，已切换为外部浏览器 device-code 登录
日期：2026-05-25

## 1. 背景

当前 CodexMeter 已废弃 WebView OAuth 与新的 `auth.json` 导入入口。后续登录设计调整为：

- 对齐 Hermes Agent 的 OpenAI Codex device code 登录模型。
- 移除新的 `auth.json` 导入入口与导入能力。
- 用户点击添加账号后进入 device-code 登录页，并通过外部浏览器打开 OpenAI/Codex verification 页面。
- App 生成 device code，并通过页面顶部与通知栏同时提示。
- 通知正文点击打开同一个外部 verification 页面；通知操作按钮负责复制 device code。
- 授权成功后，登录通知短暂更新为账号连接成功，再由常驻额度通知接管。

本文是 Codex 登录实现规格；`docs/SPEC.md`、`docs/PRD.md`、`docs/ARCHITECTURE.md`、`RULES.md`、`DESIGN.md` 与本文保持同步。

## 2. 已确认决策

### 2.1 登录入口

- 添加账号只保留 `登录 Codex` 主入口。
- 不再提供 `导入 auth.json` 文件选择入口。
- 不再提供粘贴完整 `auth.json` JSON 的入口。
- 已经保存到本地的历史账号与会话不主动删除；它们已归一为 OAuth session，继续使用现有 refresh 机制保活。

### 2.2 页面布局

添加账号后进入 App 内 device-code 登录页，页面结构自上而下：

1. 顶部紧凑 device code 行。
2. 打开 Codex verification 页的外部浏览器主操作。
3. 底部只保留必要状态/错误提示，不做复杂表单。

顶部 device code 行要求：

- code 与复制按钮共占一行。
- 行高克制，不挤压打开浏览器主操作和状态提示。
- code 使用等宽数字/字母样式，便于核对。
- 复制按钮是轻量按钮，不做大 CTA。
- 通知权限被拒绝时，页面内 code 行仍可复制，登录流程不能因此中断。

### 2.3 外部浏览器授权地址

- 外部浏览器默认打开普通 verification 页面，不打开带 code 的完整自动填充链接。
- 对齐 Hermes 当前 Codex device code 方式：用户在 Web 页面手动输入/粘贴 device code。
- 如果上游接口未来返回 `verification_uri_complete`，MVP 不自动使用；除非后续重新设计并验证第三方页面兼容性。

### 2.4 通知交互

登录中通知：

- 使用账号/错误类通知通道，不与额度阈值告警混用。
- 标题表达 `Codex 登录等待授权`。
- 正文不得包含完整 device code、token、authorization code、callback URL 或完整响应体；仅提示用户打开验证页并使用复制动作。
- 通知正文点击：打开同一个外部 Codex verification 页面。
- 通知操作按钮：`复制授权码`，点击后只复制当前最新 attempt 的 user-facing device code，并给出轻量反馈。

授权成功后：

- 同一条登录通知更新为 `Codex 账号连接成功`。
- 成功通知短暂显示后自动消失。
- 随后由现有常驻额度状态通知接管。
- 不额外留下第二条长期成功通知。

授权过期或失败后：

- 通知更新为 `Codex 登录已过期` 或 `Codex 登录失败`。
- 正文提示回到 App 重试。
- 不继续轮询。

### 2.5 重复登录尝试

- 开始新的登录尝试时，取消旧登录尝试。
- 只保留最新一次 device code、通知和轮询任务。
- 旧尝试的通知必须被取消或更新为已取消，避免多个 code 同时可见。
- 旧尝试返回的异步结果必须被 attemptId 丢弃，不能写入账号或 session。

### 2.6 轮询与过期

- 按服务端返回的 `interval` 轮询授权结果。
- 到服务端返回的 `expires_in` 后停止轮询。
- 过期后页面显示 `授权码已过期，请重新生成`，通知更新为过期态。
- 不做过期后的低频续轮询。
- 不为了登录流程引入前台服务。

### 2.7 后台行为

- 登录尝试绑定当前 App 进程与登录页。
- 短暂切后台时可以继续轮询。
- App 进程被系统杀掉后，本次登录尝试取消；用户下次进入重新生成 code。
- 不做持久化后台登录任务。

### 2.8 成功前校验

授权轮询成功并换取 token 后，仍需调用官方 usage API 做一次校验：

- usage 校验成功后，才保存账号、session 和初始额度快照。
- usage 校验失败时，不静默保存不可用账号。
- 页面提示 `授权已完成，但额度校验失败`，提供 `重试校验` 与 `重新登录`。
- 通知不得显示连接成功，最多显示校验失败。

### 2.9 重新登录账号不匹配

从某个已有账号的 `重新登录` 入口进入时：

- 如果最终授权账号与原账号一致，更新原账号 session。
- 如果最终授权账号不同，弹窗提醒用户：`授权的是另一个 Codex 账号，是否保存为新账号？`
- 用户选择保存：保存为新账号，并可切换为当前账号。
- 用户选择不保存：终止登录和授权结果处理，返回账号管理页面，不写入新 session。

## 3. Hermes 对齐点

CodexMeter 需要对齐 Hermes Agent 的以下行为模式：

- 使用 OpenAI Codex device code 登录，而不是导入本机 `~/.codex/auth.json` 作为主路径。
- 先请求 device/user code，再让用户在 Web 端输入 code。
- 轮询授权结果，pending 状态继续等待，异常状态映射为安全错误。
- 授权完成后用返回的 authorization code + code verifier 换取 OAuth token。
- refresh token 后续可能轮换，也可能不返回；若 refresh 响应缺少新 refresh token，必须保留旧 refresh token。
- 运行时 access token 即将过期或请求 401 后，应触发 refresh 并重试一次，避免短时间内误判登录失效。

Hermes 当前 Codex device code 参考路径：

- `hermes_cli/auth.py::_codex_device_code_login`
- issuer：`https://auth.openai.com`
- Web 授权页：`https://auth.openai.com/codex/device`
- device code 请求：`/api/accounts/deviceauth/usercode`
- 授权轮询：`/api/accounts/deviceauth/token`
- token 交换：`https://auth.openai.com/oauth/token`

上述 endpoint 属于实现参考；实际代码中应集中定义在 Codex provider 私有配置里，禁止散落在 UI / Worker / Notification 层。

## 4. 目标用户流程

### 4.1 添加新账号

1. 用户在首页未登录态或账号页点击 `登录 Codex` / `添加账号`。
2. App 取消旧登录尝试并创建新的 login attempt。
3. App 请求 device code。
4. App 显示 device-code 登录页，并提供打开 Codex verification 页的外部浏览器按钮。
5. 页面顶部显示一行 device code + 复制按钮。
6. App 发出登录中通知，通知正文可打开外部 verification 页，通知按钮可复制 code。
7. 用户在外部浏览器页面内粘贴/输入 code 并完成 OpenAI/Codex 授权。
8. App 按 interval 轮询授权结果。
9. 轮询成功后，App 换取 OAuth token。
10. App 调官方 usage API 校验 session。
11. 校验成功后保存账号/session/snapshot。
12. 登录通知短暂更新为连接成功。
13. 页面跳转回账号页或首页，并触发常驻额度通知刷新。

### 4.2 重新登录已有账号

1. 用户在账号页对某账号点击 `重新登录`。
2. App 创建带 `expectedAccountId` 的 login attempt。
3. 授权与校验成功后，对比最终 accountId。
4. accountId 一致：更新原账号 session。
5. accountId 不一致：弹窗让用户选择是否保存为新账号。
6. 用户拒绝保存：丢弃 token/session，返回账号管理页面。

### 4.3 用户取消

- 用户点击返回或关闭登录页时，取消当前登录尝试。
- 取消轮询任务。
- 取消登录中通知。
- 页面立即隐藏授权码、验证链接、过期时间和轮询间隔；下一次点击开始登录并成功创建后台轮询后，才显示新的授权码。
- 不写入账号/session。

## 5. 状态机

建议引入显式登录状态，避免 UI、通知、网络轮询各自维护布尔值。

```text
Idle
  -> RequestingDeviceCode
  -> AwaitingUserAuthorization
  -> PollingAuthorization
  -> ExchangingToken
  -> ValidatingUsage
  -> Connected

AwaitingUserAuthorization / PollingAuthorization
  -> Expired
  -> Cancelled
  -> Failed

ValidatingUsage
  -> ValidationFailed

Connected
  -> AccountMismatchDecision
  -> Saved
```

关键约束：

- 每个状态必须绑定 `attemptId`。
- 只有最新 `attemptId` 可以更新 UI、通知、账号、session。
- 所有失败态都只能暴露 safe message，不暴露原始响应体。

## 6. 数据模型建议

### 6.1 DeviceCodeLoginAttempt

建议 provider 私有层使用内存模型：

```text
DeviceCodeLoginAttempt
- attemptId
- mode: AddAccount | Relogin(expectedAccountId)
- userCode
- deviceAuthId / deviceCode
- verificationUri
- expiresAt
- pollIntervalSeconds
- createdAt
```

MVP 不要求持久化此模型。

### 6.2 CodexSessionPayload

最终成功后仍复用现有 session payload 方向：

- access token
- refresh token
- id token，如果存在
- account id（优先使用 token 响应顶层字段；device-code 响应缺失时，从 `id_token` 的 `https://api.openai.com/auth.chatgpt_account_id` claim 派生，用于后续 `ChatGPT-Account-Id` usage 校验）
- token expiry / last refresh metadata

要求：

- refresh token 是后续保活必需字段。
- refresh 响应返回新 refresh token 时原子替换。
- refresh 响应不返回新 refresh token 时保留旧值。

## 7. 通知设计

### 7.1 通知 ID

建议新增固定登录通知 ID，例如：

```text
AUTH_LOGIN_NOTIFICATION_ID
```

原因：

- 保证同一时刻只有一个登录通知。
- 新登录尝试可以覆盖旧通知。
- 成功/失败/过期可以在同一条通知上更新。

### 7.2 PendingIntent

- 内容点击 PendingIntent：打开外部 Codex verification 页面。
- `复制 code` Action PendingIntent：发给专用 BroadcastReceiver，只复制当前最新 attempt 的 userCode。
- BroadcastReceiver 必须校验 attemptId，旧通知 action 不得复制旧 code。

### 7.3 通知权限

- Android 13+ 未授权通知权限时，不阻塞登录。
- 页面顶部 code 行是主路径兜底。
- 如果通知权限未开启，页面可提示：`通知未开启，授权码仍可在本页复制。`

## 8. 安全与隐私

禁止记录或展示：

- access token
- refresh token
- id token
- authorization code
- code verifier
- deviceAuthId / deviceCode 原始值
- OAuth callback URL
- 原始 token exchange response
- 原始 usage API response

允许展示：

- user-facing device code，即用户需要输入到 Web 页的短码。
- 脱敏 account label。
- safe error key / safe message。

剪贴板注意：

- 复制的是 user-facing device code，不是 token。
- 复制后可给 Toast / Snackbar：`已复制授权码`。
- 不把 code 写入诊断、日志或数据库。

## 9. 与现有 `auth.json` 能力的关系

本设计要求移除新的 `auth.json` 导入入口：

- 删除/隐藏添加账号页的 `导入 auth.json` 按钮。
- 删除/隐藏粘贴 JSON 输入区。
- 删除新增导入的导航路径。
- 后续实现时可分阶段移除未使用代码，但 UI 与产品路径必须先收敛。

兼容要求：

- 已经通过旧导入方式保存的账号不自动删除。
- 已保存 session 继续按统一 OAuth refresh 处理。
- 删除账号仍按现有账号删除流程执行。

## 10. 错误映射

需要覆盖并测试以下错误：

- 请求 device code 失败：网络错误 / 非 200 / 缺字段。
- 用户未完成授权：轮询 pending，继续等待。
- 轮询返回未授权或未完成：继续等待，直到过期。
- 轮询返回异常状态：显示安全失败。
- 授权码过期：停止轮询，允许重新生成。
- token 交换失败：显示安全失败，要求重新登录。
- token 响应缺 access token：失败。
- token 响应缺 refresh token：登录成功阶段应视为失败；refresh 阶段缺新 refresh token 则保留旧值。
- usage 校验失败：不保存账号，允许重试校验或重新登录。
- 重新登录账号不匹配：弹窗决策。

## 11. 实现分层建议

### 11.1 Provider 私有层

建议新增或改造：

- `CodexDeviceCodeClient`
  - request user code
  - poll authorization result
  - exchange token
- `CodexDeviceCodeLoginUseCase`
  - 管理 attemptId
  - 取消旧 attempt
  - 驱动状态机
  - 调用 usage 校验与 session 保存
- `CodexDeviceCodeLoginState`
  - 供 ViewModel / UI 观察

### 11.2 UI 层

- 添加账号页改为 device code 外部浏览器登录页面。
- 顶部一行显示 code + 复制按钮。
- 页面提供打开普通 Codex verification 页的外部浏览器按钮。
- 返回/关闭时取消登录尝试。
- 错误态展示重试，不展示底层响应体。

### 11.3 Notification 层

- `NotificationOrchestrator` 统一管理登录通知。
- Worker、Repository、Provider 不直接发通知。
- 通知文案进入 string resources，覆盖简中与英文。

## 12. 测试要求

严格 TDD，至少补以下本地单测：

- device code response 缺字段时失败。
- request 成功后生成 AwaitingUserAuthorization 状态。
- 新 attempt 会取消旧 attempt，旧 attempt 结果不能写入 session。
- pending/403/404 轮询继续等待。
- expires 后停止轮询并进入 Expired。
- token exchange 成功但 usage 校验失败时不保存账号。
- refresh 响应缺新 refresh token 时保留旧值。
- 重新登录 accountId 不匹配时进入用户决策态。
- 用户选择保存不匹配账号时新增账号。
- 用户选择不保存时丢弃 session 并返回账号管理。
- 复制通知 action 只复制最新 attempt 的 userCode。
- 诊断/日志红线字段脱敏测试。

人工验收：

- 未授权通知权限时，页面 code 行仍能复制，外部浏览器按钮仍可用。
- 通知权限开启时，通知正文点击打开外部 verification 页，按钮复制 code。
- 授权成功后，成功通知短暂显示并消失，常驻额度通知接管。
- 添加账号页不再出现 `导入 auth.json`。

## 13. SPEC 后续引用方式

后续更新 `docs/SPEC.md` 时建议只加入：

- `M3/M4 Auth` 阶段引用本文。
- 把旧 `auth.json` import 任务标记为废弃/迁移。
- 新增 device code 登录任务拆分。
- 保留 refresh-token optional rotation 修复任务。

建议引用文案：

```markdown
Codex 登录方式以 `docs/CODEX_DEVICE_CODE_LOGIN_SPEC.md` 为准；旧 `auth.json` 导入不再作为 MVP 登录入口。
```

## 14. 未决事项

当前已通过 grill-me 确认的关键分支已写入本文。实现前仍需在代码层验证：

- 外部浏览器对 `https://auth.openai.com/codex/device` 的实际兼容性。
- OpenAI device auth endpoint 返回字段与 Hermes 当前实现是否完全一致。
- 通知 action 复制剪贴板在 Android 12/13/14/16 模拟器上的行为差异。
- 成功通知 `timeoutAfter` 在不同 Android 版本上的表现。
