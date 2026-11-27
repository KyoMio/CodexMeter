# 02 · Provider DTO 映射与窗口归一

## Sub-features

- 11 个 Provider 的用量/配额响应统一映射成 `QuotaSnapshot` + `QuotaWindow`。
- Antigravity 双路径映射：优先 `:retrieveUserQuotaSummary`；无有效 summary 时回退 per-model `:fetchAvailableModels`。
- resetTime/过期时间解析：ISO-8601 优先，兼容 OffsetDateTime；空值不丢窗口。
- 重复行/重复窗口去重：Antigravity 同模型取最紧约束，同窗口取使用量最高行。
- 错误窗口可用性归一：`Available / Depleted / Missing / DecodeFailed / Unsupported`。

## User POV

- 用户不关心上游字段差异，只看到统一卡片：5h、weekly、balance、次数、家庭窗口。
- Antigravity 用户看到 Gemini / Claude-GPT 分 family 的卡；无 Gemini 时主卡回退 Claude-GPT。
- 耗尽不是「没数据」：剩余 0% 仍显示为耗尽卡，而不是消失。
- Provider 返回字段缺失时，尽量保留窗口但去掉 reset/percent，而不是整卡消失。

## CLI 驱动命令

- `seed-fixture --scenario antigravity_2x2_depleted`：覆盖 proto3 `remainingFraction` 缺失（即耗尽）与 weekly/5h 双窗口。
- `seed-fixture --scenario kimi_active`：验证 Kimi rate/cycle 映射与 resetTime 展示。
- `snapshot --json`：断言家庭窗口 title、耗尽标识与账号页模型明细存在。
- `logcat-safe`：确认无原始响应体或 token 泄漏，只剩脱敏 digest。

## Gotchas

- proto3 JSON 会省略默认值：`remainingFraction=0.0` 在响应里经常整个字段消失，因此 **缺省 = 完全耗尽**；Antigravity mapper 明确按 `?: 0.0` 处理。
- `remaining` 可能是嵌套对象：`remainingFractionOrNull()` 先取顶层，再取 `remaining.remainingFraction`。
- `hasKnownQuotaSummary()` 不看 `remainingFraction` 是否存在就够，还看 resetTime/window/bucketId/id；空 `{}` 不算已知配额。
- lane 不是只靠名称：`window`、bucketId、displayName 先归一化匹配 session/weekly，匹配不到再用 reset 距抓取时间的 horizon 回推 5h/weekly/cycle。
- resetTime 解析失败不会使窗口消失，只会 `resetAt=null`；因此 UI 必须容忍无重置时间。
- MiniMax 的模型 family 规则与 Antigravity 不同；账号页 `modelFamilyOf()` 按 provider 分派，不可混用。
- Kimi/Zai/Cursor/Claude 等 mapper 各有窗口语义：balance、usage count、percent 不要强行统一成百分比。
