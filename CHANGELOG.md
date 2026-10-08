# Changelog

## [Unreleased]

### Added
- 新增 "Kimi Code API" 接入面（providerId `kimi_code`）：在 Kimi Code 控制台创建长期有效的 API Key，选择中国站（`https://api.kimi.com`，默认）或国际站（`https://api.kimi.ai`）后应用内输入即可添加账号，App 调 `GET /coding/v1/usages` 采集 5 小时 / 每周 / 每月额度。比例池优先、计数回退不推断周期；key 加密存储，不进日志与诊断。原有 Kimi Cookie 账号不受影响。

## [0.2.3] - 2026-10-07

### Fixed
- 修复 Kimi 内嵌登录页白屏及重定向后登录窗口无法打开的问题。
- 补充 Kimi 网页会话与路径 Cookie 提取，修复登录后提示未找到会话的问题。
- Kimi 短期额度按接口周期显示；周期不明的长期额度改为“周期额度”，不再误标为周额度。保留实际重置时间、额度数值和已有窗口选择。
