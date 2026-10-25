# Changelog

## [0.2.5] - 2026-10-25

### Fixed
- Antigravity：所有 Gemini 模型（含 `gemini-4-argon` 等新代号）合并进单一「Gemini」窗口，不再因命名不含 pro/flash 而丢失；100% 耗尽的模型（proto3 响应省略 0.0 值）显示为已用尽而非整桶丢弃；未知模型归入「其他模型」窗口；同一模型多行数据取最低剩余。
- Claude 登录：拒绝授权、会话过期、页面加载失败（如 QUIC 错误）分别给出具体提示，不再无声白屏；回调先校验再拦截，失败不再卡死页面；新增「在浏览器中打开 + 手动粘贴授权码」兜底通道（仅 Claude 显示）。
- Codex 设备码登录：识别 auth.openai.com 的 Cloudflare 挑战页并立即提示「登录请求被 OpenAI 的防护拦截，请更换网络后重试」，不再被当作等待授权而静默轮询至超时。

## [0.2.4] - 2026-10-08

### Added
- 新增 "Kimi Code API" 接入面（providerId `kimi_code`）：在 Kimi Code 控制台创建长期有效的 API Key，选择中国站（`https://api.kimi.com`，默认）或国际站（`https://api.kimi.ai`）后应用内输入即可添加账号，App 调 `GET /coding/v1/usages` 采集 5 小时 / 每周 / 每月额度。比例池优先、计数回退不推断周期；key 加密存储，不进日志与诊断。原有 Kimi Cookie 账号不受影响。

## [0.2.3] - 2026-10-07

### Fixed
- 修复 Kimi 内嵌登录页白屏及重定向后登录窗口无法打开的问题。
- 补充 Kimi 网页会话与路径 Cookie 提取，修复登录后提示未找到会话的问题。
- Kimi 短期额度按接口周期显示；周期不明的长期额度改为“周期额度”，不再误标为周额度。保留实际重置时间、额度数值和已有窗口选择。
