# 05 · Glance Widget 与低噪通知

## Sub-features

- Glance 响应式 widget：按可用尺寸选择 4 种布局变体。
- Widget 字段容量控制：不同尺寸只取前 N 个字段。
- 常驻状态通知：低 importance、无声音无震动、持续显示当前账号状态。
- 阈值提醒通知：按窗口 threshold 触发 Caution / Warning / Limit。
- 账号错误通知：AuthRequired 或连续刷新失败。
- 通知渠道：`quota_status`、`quota_alerts`、`account_errors`、`app_updates`。

## User POV

- 桌面小部件在窄小尺寸只看最关键一行；拉宽拉高后能看 2-4 个字段。
- 常驻通知不打扰，只是安静常驻；真正告警才走有声渠道。
- 同一窗口在同一重置周期同一阈值不会反复轰炸。
- 换账号后，常驻通知标题会带「Provider·账号」，避免看错对象。

## CLI 驱动命令

- `seed-fixture --scenario antigravity_2x2_depleted`：同时验证 widget 对耗尽窗口/多窗口展示。
- `seed-fixture --scenario multi_account_race`：验证非选中账号刷新也能按自己窗口发 alert，但状态通知仍跟随选中账号。
- `snapshot --json`：模拟器主屏不可直接验 widget 时，改验证 App 内通知设置项与诊断计数。
- `logcat-safe`：确认通知内容不含 token、cookie、原始响应。

## Gotchas

- Widget 尺寸变体是 `ThreeByOne / FourByOne / ThreeByTwo / FourByTwo`；`SizeMode.Responsive` 选的是「能放下的最大参考尺寸」，不是 launcher 标称格数。
- 参考尺寸阈值不是随意值：宽阈值 250dp、高阈值 110dp，避免 3 列实际宽度被误判成 4 列布局。
- 每个变体字段容量固定：1/2/3/4；超出字段直接截断，不做横向滚动。
- `AlertPolicy` 的去重键是 `(providerId, localAccountId, windowId, resetAt, threshold)`；resetAt 变了会重新告警。
- percent/count/balance 三类窗口阈值逻辑不同：percent 看 remainingPercent，count 换算剩余百分比，balance 直接比较金额。
- 主窗口告警有额外闸门：若 `primaryWindowCanAlert=false`，主窗口不告警；非主窗口按启用窗口集合判断。
- 常驻通知 `STATUS_CHANNEL_ID` importance 为 LOW 且显式关声音震动；不要把它和高优先级 alert 渠道混用。
- 通知 ID 用稳定 hash 分桶：quota alert base 20000，account error base 30000，bucket size 10000；改动会影响系统通知替换行为。
