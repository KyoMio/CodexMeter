# Grok（xAI）供应商接入调研：登录方式选型

- 调研日期：2026-09-10
- 调研目标：为 CodexMeter 新增 Grok 供应商确定合适的登录/认证方式。
- 结论状态：已完成选型结论，未开始实现。
- 信息来源：xAI 官方文档、开源客户端实现（gptme / OpenClaw / Kilo Code / Hermes / pi-supergrok-usage）、社区文档；全部链接见文末。

## 1. 结论

**选用 SuperGrok 订阅的 OAuth 2.0 设备码（device code）登录，不采用 API Key。**

核心依据：CodexMeter 的产品价值是展示官方配额/用量数据，而 xAI 目前**只有 SuperGrok OAuth 会话能查到配额**；API Key 路线没有任何官方用量或余额查询接口。

| | SuperGrok OAuth（订阅） | API Key（console.x.ai） |
|---|---|---|
| 认证方式 | OAuth 2.0：设备码流程 或 PKCE + loopback | 手动创建 `xai-...` key，`Authorization: Bearer` |
| 配额/用量接口 | `GET https://cli-chat-proxy.grok.com/v1/billing?format=credits`（周窗口用量百分比、重置时间、预付余额） | 无。`GET /v1/api-key` 仅返回 key 名称/状态 |
| 适合用户 | SuperGrok / X Premium+ 订阅者 | 按 credits 预充值的 API 用户 |
| 对本 App 的意义 | 与 Codex / z.ai Coding Plan 同类：可展示周额度 + credits | 无配额数据可展示，违背「官方数据源、不估算」原则 |

OpenClaw 文档明确指出：**xAI Console API credits 与 SuperGrok 订阅配额是两个独立的计费桶**，纯 API key 接入刻意不显示用量。做 Grok 配额显示的社区工具（pi-supergrok-usage 等）全部走 OAuth billing 接口，并明确注明 `XAI_API_KEY` 不足以获取周配额。

## 2. SuperGrok OAuth 路线细节

### 2.1 授权端点与 client（来自 gptme 开源实现，注释标注与 grok CLI 相同）

- Issuer：`https://auth.x.ai`
- Authorize：`https://auth.x.ai/oauth2/authorize`
- Token：`https://auth.x.ai/oauth2/token`
- client_id：`b1a00492-073a-47ea-816f-4c329264a828`（grok CLI 共享的公开 OAuth client；OpenClaw 文档说明 consent 页可能显示 Grok Build 名称，属正常现象）
- scope：`openid profile email offline_access grok-cli:access api:access`

### 2.2 两种流程形态

- **设备码流程**（OpenClaw / Hermes / Kilo 无头模式）：打印 verification URL + 短码，客户端轮询换取 token。不需要 localhost 回调、不需要 WebView——与 CodexMeter 现有 Codex device-code 外部浏览器模式完全同构，最适合 Android。
- **Authorization Code + PKCE（S256）+ loopback 回调**（gptme / Kilo 桌面模式）：适合桌面端，Android 上不如设备码契合（Antigravity 已有 loopback 先例，但外部浏览器 + loopback 短连接在手机上体验差）。

**选型：设备码流程，复用 Codex 的 device-code 模式（外部浏览器 handoff + 轮询 + usage 校验后才落库）。**

### 2.3 配额接口（billing）

```
GET https://cli-chat-proxy.grok.com/v1/billing?format=credits
Authorization: Bearer <OAuth access token>
```

- 与 grok 官方 CLI（Grok Build）同源的上游 billing 接口；推理代理 `https://cli-chat-proxy.grok.com/v1` 需要 `x-grok-client-version` 头（最低版本 `0.1.202`），billing 请求是否同样要求待实现时验证。
- 脱敏样本（pi-supergrok-usage 仓库 `samples/grok-billing.json`，2026-09 拉取）：

```json
{
  "config": {
    "currentPeriod": {
      "type": "USAGE_PERIOD_TYPE_WEEKLY",
      "start": "2026-08-17T17:33:48.278812+00:00",
      "end": "2026-08-24T17:33:48.278812+00:00"
    },
    "creditUsagePercent": 1.0,
    "onDemandCap": { "val": 0 },
    "onDemandUsed": { "val": 0 },
    "productUsage": [{ "product": "GrokBuild", "usagePercent": 1.0 }],
    "isUnifiedBillingUser": true,
    "prepaidBalance": { "val": 0 },
    "topUpMethod": "TOP_UP_METHOD_SAVED_PAYMENT_METHOD",
    "billingPeriodStart": "2026-08-17T17:33:48.278812+00:00",
    "billingPeriodEnd": "2026-08-24T17:33:48.278812+00:00"
  }
}
```

字段与现有 QuotaSnapshot 模型的映射：

| billing 字段 | CodexMeter 展示 |
|---|---|
| `currentPeriod.end` | 周额度 reset 时间 / 倒计时 |
| `creditUsagePercent` | 周额度已用百分比（剩余 = 100 − 已用） |
| `productUsage[].usagePercent` | 分产品用量（GrokBuild），可类比 Antigravity 多模型分桶 |
| `prepaidBalance.val` | credits 余额 |
| `currentPeriod.type` | 窗口类型（周/月），注意存在 `USAGE_PERIOD_TYPE_WEEKLY` 之外的取值可能 |

另：推理调用的响应头 `after_provider_response` 含 RPM 窗口（`{remaining}/{limit} RPM`），本 App 不做推理调用，用不上。

### 2.4 Token 刷新与轮换

- access token 有效期约 6 小时（`expires_in` 缺省 21600s）。
- 刷新：`POST /oauth2/token`，`grant_type=refresh_token` + client_id + refresh_token；gptme 在到期前 300s 主动刷新，401 时反应式刷新。
- **xAI 每次刷新会轮换 refresh token**（Kilo 文档警告多进程并发会互相踢掉登录）。CodexMeter 已有 Codex 的 single-flight + 轮换回写 + terminal 错误码判定（`CodexRefreshProvider` / `NeedsReauthPolicy`）经验，可直接复用同一套策略。
- 刷新失败（`invalid_grant` 等）→ 判定 needs-reauth，引导重新登录。

## 3. API Key 路线为何不选

- 唯一确认的查询端点 `GET https://api.x.ai/v1/api-key` 只返回 key 名称与状态（docs.x.ai 与 Swagger 列表确认；无 usage/credits/balance/spend 字段，openapi 规范未公开可抓取）。
- rate limit（RPS/TPM、tier 0–4 由累计消费决定、2026-01-01 起终身不降级）只能在 xAI Console 网页查看，无 API。
- docs.x.ai 导航中不存在 usage / billing / credits / spend 任何查询类 API 页面。
- 因此 API Key 接入后主界面无任何配额数据可展示，违背 PRD「官方数据源、不估算」原则。
- 保留可能性：若 xAI 未来为 API key 开放 credits 查询接口，可按 z.ai 先例拆为第二个 provider（如「Grok API」）补充接入，无需现在预留架构。

## 4. 风险与未知点

1. **billing 端点未公开文档化**：docs.x.ai 不收录，属 grok CLI 自用的官方内部接口；字段与路径可能随版本变动。DTO 解析需要 defensive（参照 z.ai 的 defensive JSON 解析先例），并在文档中标注「非公开契约」。
2. **OAuth 资格由 xAI 侧决定**（allowlist）：OpenClaw 明示「xAI decides which accounts can receive OAuth API tokens」；推荐 SuperGrok / X Premium+ 账号。个别订阅层级可能对部分模型 403（Hermes issue #26847）。登录失败原因需要覆盖此类 403。
3. ~~设备码端点路径未最终确认~~ **已解决（2026-09-10 补充）**：从 OpenClaw `extensions/xai/xai-oauth.ts` 源码确认——不做硬编码，走 OIDC discovery：`GET https://auth.x.ai/.well-known/openid-configuration` 取 `device_authorization_endpoint` + `token_endpoint`；旧端点 `/oauth/token` 已退役（当前疑似 `/oauth2/token`，以 discovery 返回为准）。轮询为标准 RFC 8628 语义（`authorization_pending` / `slow_down` +5s / `access_denied` / `expired_token`，默认间隔 5s 下限 1s，deadline = `expires_in` 缺省 5 分钟）。刷新请求**不做传输层重试**（xAI 轮换 refresh token，响应丢失即烧掉旧 token）。
4. **`x-grok-client-version` 头要求**：推理代理已确认需要；billing 是否需要待实测。
5. 样本中 `creditUsagePercent: 1.0` 是低用量样本（1%），字段类型在不同账号下可能不稳定（浮点/整数），参照 z.ai 经验做防御。

## 5. 与现有架构的映射（实现清单备忘）

按 `docs/plans/2026-05-30-provider-expansion-plan.md` 的接入模式：

1. `providers/grok/` 六件套：`GrokRefreshProvider`、`auth/GrokSessionImporter`、`network/GrokBillingClient`、`dto/GrokBillingResponseDto`、`mapper/GrokMapper`、`session/GrokSessionPayload`（OAuth 类：accessToken / refreshToken / tokenExpiresAtEpochSeconds）。
2. `ProviderRegistry.kt`：新增 `ProviderId` 常量 + `ProviderConfig`。注意 `ProviderAuthKind` 目前无独立 DeviceCode 类型（Codex 用历史遗留 `OAuthWebView` + `LoginToCodex` 特殊路由），需决定新增 auth kind 还是沿用 Codex 式特判。
3. 设备码流程复用 Codex 模式：外部浏览器打开 verification 页 + App 内轮询 + billing 校验成功后才落库（对应 `CODEX_DEVICE_CODE_LOGIN_SPEC.md` 的两段式）。
4. `AppContainer.kt` 两处装配（CompositeRefreshProvider map + SessionImportRouter map）。
5. `CodexMeterNavHost.kt` 路由；`ApiKeyAuthScreen` 不适用。
6. UI：品牌图标 drawable、`auth_method_*` 文案、Provider 选择列表排序。
7. 测试（TDD 强制）：billing DTO mapper、设备码 attempt 取消/过期/stale 处理（复用 Codex 模式）、token 刷新错误映射、`QuotaError` 映射、降级行为。
8. 文档同步：PRD §5.1/§7.1 provider 列表、ARCHITECTURE、SPEC、DESIGN。

## 6. 信息来源

- xAI 官方：[Rate Limits](https://docs.x.ai/developers/rate-limits) · [docs.x.ai 总览](https://docs.x.ai/overview) · [api.x.ai Swagger](https://api.x.ai/docs) · [mTLS（/v1/api-key 用法）](https://docs.x.ai/developers/advanced-api-usage/mtls) · [Grok Build CLI 公告](https://x.ai/news/grok-build-cli) · [Grok Build 文档](https://docs.x.ai/build/overview)
- 开源实现：[gptme grok 订阅实现（端点/client_id/scope/刷新）](https://github.com/gptme/gptme/blob/master/gptme/llm/llm_grok_subscription.py) · [OpenClaw xAI provider（设备码、双计费桶、资格说明）](https://docs.openclaw.ai/providers/xai) · [OpenClaw xai-oauth.ts 源码（discovery/RFC 8628 轮询/刷新不重试，2026-09-10 提取）](https://github.com/openclaw/openclaw/blob/main/extensions/xai/xai-oauth.ts)
- 社区工具/文档：[pi-supergrok-usage（billing 端点、脱敏样本）](https://pi.dev/packages/pi-supergrok-usage) · [Hermes Agent xAI OAuth](https://hermes-agent.nousresearch.com/docs/guides/xai-grok-oauth) · [Kilo Code xAI 接入（OAuth 两种模式、refresh token 轮换警告）](https://kilo.ai/docs/ai-providers/xai) · [Warp SuperGrok 订阅接入](https://docs.warp.dev/agents/inference/grok-subscription/)
