---
version: alpha
name: CodexMeter Widget-first Liquid Glass
description: Light premium Android quota dashboard with widget-first chromatic liquid glass surfaces.
colors:
  primary: "#17181C"
  secondary: "#6A7280"
  tertiary: "#8A94A6"
  neutral: "#F5F7FB"
  neutralAlt: "#EEF2F8"
  surface: "#FFFFFF"
  surfaceSoft: "#FAFCFF"
  border: "#E5EAF2"
  accent: "#0071E3"
  accentSoft: "#F8FBFF"
  glassBase: "#EAF3FF"
  glassInk: "#151821"
  glassTintBlue: "#5DB8FF"
  glassTintCyan: "#7EF4E8"
  glassTintViolet: "#9D8CFF"
  glassStrokeLight: "#FFFFFF"
  glassStrokeCool: "#B9D8FF"
  glassShadow: "#8AA9D6"
  success: "#18A058"
  successSoft: "#EAF7F0"
  warning: "#D97706"
  warningSoft: "#FFF4E4"
  danger: "#DC2626"
  dangerSoft: "#FEEDEE"
typography:
  display:
    fontFamily: Geist Mono
    fontSize: 31px
    fontWeight: 800
    lineHeight: 1.05
    letterSpacing: "-0.03em"
  title:
    fontFamily: Geist Mono
    fontSize: 20px
    fontWeight: 760
    lineHeight: 1.2
    letterSpacing: "-0.02em"
  body:
    fontFamily: Geist Mono
    fontSize: 14px
    fontWeight: 500
    lineHeight: 1.5
    letterSpacing: "0em"
  label:
    fontFamily: Geist Mono
    fontSize: 12px
    fontWeight: 720
    lineHeight: 1.35
    letterSpacing: "0.01em"
  number:
    fontFamily: Geist Mono
    fontSize: 39px
    fontWeight: 850
    lineHeight: 0.96
    letterSpacing: "-0.05em"
rounded:
  xs: 8px
  sm: 13px
  md: 16px
  lg: 22px
  xl: 28px
  screen: 39px
  pill: 999px
spacing:
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  xl: 20px
  xxl: 24px
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "#FFFFFF"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    height: 44px
    padding: 15px
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.primary}"
    typography: "{typography.body}"
    rounded: "{rounded.md}"
    height: 44px
    padding: 15px
  card-surface:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.primary}"
    rounded: "{rounded.xl}"
    padding: 17px
  liquid-glass-widget-tile:
    backgroundColor: "{colors.glassBase}"
    textColor: "{colors.glassInk}"
    rounded: "{rounded.xl}"
    padding: 17px
  liquid-glass-hero-card:
    backgroundColor: "{colors.glassBase}"
    textColor: "{colors.glassInk}"
    rounded: "{rounded.xl}"
    padding: 18px
  tab-active:
    backgroundColor: "{colors.accentSoft}"
    textColor: "{colors.accent}"
    rounded: "{rounded.lg}"
    height: 58px
  badge-success:
    backgroundColor: "{colors.successSoft}"
    textColor: "#137A43"
    rounded: "{rounded.pill}"
    height: 27px
    padding: 10px
  badge-warning:
    backgroundColor: "{colors.warningSoft}"
    textColor: "#A15C02"
    rounded: "{rounded.pill}"
    height: 27px
    padding: 10px
  badge-danger:
    backgroundColor: "{colors.dangerSoft}"
    textColor: "#B91C1C"
    rounded: "{rounded.pill}"
    height: 27px
    padding: 10px
  app-background:
    backgroundColor: "{colors.neutral}"
    textColor: "{colors.primary}"
  app-background-alt:
    backgroundColor: "{colors.neutralAlt}"
    textColor: "{colors.primary}"
  screen-background:
    backgroundColor: "{colors.surfaceSoft}"
    textColor: "{colors.primary}"
  muted-label:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.secondary}"
    typography: "{typography.label}"
  subtle-tile:
    backgroundColor: "{colors.tertiary}"
    textColor: "{colors.primary}"
    rounded: "{rounded.md}"
  divider-line:
    backgroundColor: "{colors.border}"
    textColor: "{colors.primary}"
    height: 1px
  status-dot-success:
    backgroundColor: "{colors.success}"
    textColor: "{colors.primary}"
    rounded: "{rounded.pill}"
    height: 9px
    width: 9px
  status-dot-warning:
    backgroundColor: "{colors.warning}"
    textColor: "{colors.primary}"
    rounded: "{rounded.pill}"
    height: 9px
    width: 9px
  status-dot-danger:
    backgroundColor: "{colors.danger}"
    textColor: "#FFFFFF"
    rounded: "{rounded.pill}"
    height: 9px
    width: 9px
---

# CodexMeter Design

## Overview

`CodexMeter` 的当前视觉方向是 **Widget-first Chromatic Liquid Glass**：保留浅色高级工具风和蓝色主交互，但把桌面 Widget 做成最强玻璃表面，首页主视觉次之，底部导航轻玻璃化，其余页面保持克制可读。

本文是 UI / UX / Widget / 通知视觉与交互的执行规范，根目录 `DESIGN.md` 为后续实现和 Agent 接手 UI 任务的依据。

相关文件：

- PRD：`docs/PRD.md`
- 架构：`docs/ARCHITECTURE.md`
- Codex 登录迁移：`docs/CODEX_DEVICE_CODE_LOGIN_SPEC.md`
- 开发规则：`RULES.md`
- Liquid Glass 升级计划：`docs/plans/2026-05-25-liquid-glass-ui-upgrade.md`
- Liquid Glass 轮子验证：`docs/plans/2026-05-26-liquid-glass-library-spike-report.md`
- 已确认交互原型：`sketches/prototype-air-glass/index.html`
- 原型说明：`sketches/prototype-air-glass/README.md`

边界说明：

- PRD 负责产品范围，Architecture 负责技术边界，Rules 负责开发护栏。
- 本文负责视觉、页面信息架构、组件规则和交互细节。
- 若 PRD 中较早的 UI 入口描述与本文冲突，以本文已确认的 UI 信息架构为准；产品能力范围不变。
- 不因视觉设计引入新权限、新数据源、新外部服务或新的敏感信息展示路径。

核心设计目标：

- Widget 是第一优先体验，必须一眼看出是 Liquid Glass，而不是普通白卡。
- 一眼判断 Codex 还能不能继续用。
- 首页只展示对决策有用的信息，不做数据堆砌。
- 桌面 Widget、首页、通知的状态语义保持一致。
- 自用工具也要长期可维护，组件规则必须清晰。

## Colors

### 基础色

- `primary #17181C`：主文字、关键标题、主数值。
- `secondary #6A7280`：说明文字、次级标签、辅助信息。
- `tertiary #8A94A6`：弱提示、时间轴、非关键元信息。
- `neutral #F5F7FB`：页面主背景。
- `neutralAlt #EEF2F8`：浅蓝灰背景渐变辅助色。
- `surface #FFFFFF`：卡片、弹层、按钮底色。
- `surfaceSoft #FAFCFF`：屏幕内层底色。
- `border #E5EAF2`：弱边界线、分隔线。
- `accent #0071E3`：唯一主交互色。
- `accentSoft #F8FBFF`：选中态、主交互弱背景。

### 状态色

状态色只做语义提示，不允许大面积铺满页面。

- `success #18A058`：正常、新鲜、已连接。
- `warning #D97706`：注意、接近注意阈值。
- `danger #DC2626`：紧张、耗尽、删除、认证失效等破坏性或严重状态。

额度状态映射按剩余额度百分比判断：

- 正常：剩余 31–100%，主界面仍以蓝色交互为主，绿色只作为小圆点或 badge。
- 注意：剩余 11–30%，warning 局部强调，不弹通知。
- 紧张：剩余 1–10%，danger 局部强调，并触发告警通知。
- 耗尽：剩余 0%，danger 局部强调，并触发告警通知。
- 过期 / 离线 / 失效：使用灰阶弱化，同时保留文字解释。
- 用户配置的注意 / 紧张阈值必须同步影响首页与微件的状态文案和重点色。

禁止：

- 用红橙绿替代信息层级。
- 只靠颜色表达状态，必须有状态文字。
- 把整张卡片铺成高饱和状态色。
- 非玻璃折射目的的大面积彩虹渐变；允许受控冷蓝 / 青绿 / 紫色 caustic 光斑，仅用于玻璃体积感，不承载状态语义。

## Typography

### 字体

Android 实现固定使用 Mono Focus 字体方案：

- Compose：`CodexMeterTheme` 固定为 `CodexMeterFontScheme.MonoFocusGeistMono`。
- UI、标题、标签和数字优先使用本地 Geist Mono；中文走系统 CJK fallback 保持可读。
- 设置页不展示字体方案入口，也不让历史 DataStore 字体偏好覆盖当前主题。
- 不引入远程字体；除已打包字体资源外，不增加字体包依赖。

### 字阶

建议 Compose 对应关系：

- App 标题：31sp 左右，ExtraBold / 800，紧凑负字距。
- 页面标题：28–31sp，Bold / ExtraBold。
- 分组标题：13sp，SemiBold，灰色，轻微字距。
- 卡片标题：15–17sp，Bold。
- 正文：14sp。
- 辅助文字：12–13sp。
- 大号额度数字：39–44sp，ExtraBold，`FontFeatureSettings` 尽量启用 tabular numbers。
- Widget 主数字：42–48sp，ExtraBold。

数字规则：

- 百分比数字必须使用等宽数字或 tabular numbers，避免刷新时跳动。
- 百分比保留整数，例如 `62%`。
- 不展示官方未返回的估算字段。

## Layout

### 设计基准

- 目标平台：Android 12+ 手机端。
- 原型基准宽度：390px，模拟现代手机。
- Compose 实现以 `dp` 为单位，遵循 8dp 栅格。
- 页面左右主内边距：20dp。
- 卡片内部常规内边距：16dp。
- 卡片间距：12–16dp。
- 分组标题与上一卡片间距：20–24dp。

### App Shell

MVP 使用三 Tab 底部导航：

1. 首页
2. 账号
3. 设置

设计结论：

- 首页不放右上角设置跳转按钮。
- 设置入口只通过底部 Tab 进入，避免顶部出现不明动作。
- 账号能力独立为账号 Tab；设置页不再重复放账号卡片。
- 若未来首页右上角需要动作，必须明确含义，例如“刷新状态 / 诊断状态”，不得放无语义图标。

### 底部安全区

固定底部 Tabbar 时，滚动区域必须为底栏和系统手势区预留空间。

要求：

- 最后一项内容必须能完整滚到 Tabbar 上方。
- 最后一项的关键按钮不得被 Tabbar 遮挡。
- 不能只靠视觉上“差不多”的大 padding，要用布局结构明确预留底部区域。
- 原型已验证账号页滚动到底时 `旧账号` 操作按钮能完整位于 Tabbar 上方。

### 页面滚动

- 首页内容少，保持仪表盘一屏优先；小屏允许滚动。
- 账号页和设置页按垂直滚动列表组织。
- 滚动列表底部必须保留 24dp 以上额外呼吸空间。

## Elevation & Depth

Liquid Glass 的深度来自“玻璃厚度 + 彩色折射 + 可读内容层”，不是简单白卡半透明。

层级规则：

- **Widget：最高优先但轻薄表达**。必须有低白度冷蓝灰透明底、贴边白色高光、极细冷色边缘、轻微蓝 / 青绿折射光斑、窄幅语义状态光效和贴边底部阴影；重点是轻透、悬浮、边缘发光，避免做成厚重实体灰卡。
- **首页主视觉：中高强度**。当前账号摘要可用 QmDeve 真折射或项目封装的液态玻璃表面，并优先展示 plan 类型与 credits 余量；两张额度卡保持清晰数字层级。
- **底部导航：轻玻璃**。使用浮动胶囊、半透明冷色底、细描边和选中态蓝色 tint，不抢首页主视觉。
- **账号 / 设置页：克制玻璃**。分组卡片只使用轻玻璃/浅表面，优先阅读效率，不做满屏炫技。
- **弹层 / Bottom Sheet：更实**。玻璃效果降级，保证文字、按钮和危险操作确认足够清晰。

材质规则：

- 玻璃边缘使用贴边白色高光和极细冷蓝描边表达厚度；高光要沿上边缘/侧边贴住主体，不能像单独漂浮的厚阴影层。
- 玻璃底色不能纯白，默认使用低白度冷蓝灰透明基底。
- 高光来自上边缘和局部 lens，不靠整卡泛白。
- 阴影偏蓝灰，必须很浅、很贴边，只提示悬浮，不制造厚卡片感。
- 文字层必须压在稳定 scrim 上，主文字对比不可低于现有规范。
- Widget 左侧状态条使用预渲染 bitmap 光效：中心 3dp 清晰语义色，外侧只保留 1–2dp 级别柔和高斯光晕；不得做成宽光柱或大面积霓虹。

禁止：

- 把 Liquid Glass 做成普通 `#FFFFFF` 半透明白卡。
- 全页面到处使用强折射，导致像 demo 而不是工具。
- 大面积毛玻璃导致文字对比不足。
- 多层强玻璃互相叠太多，制造噪音。
- 暗色终端风作为主视觉（深色主题亦不例外，须保持 Air Glass 质感）。

## Shapes

圆角是 CodexMeter 的主要亲和力来源之一，但要保持工具感。

建议：

- 屏幕容器：36–40dp。
- 主卡片：24–28dp。
- 小卡片 / 行项目：16–22dp。
- 按钮：14–16dp。
- Segmented control / badge / chip：999dp 胶囊。
- 账号头像：圆形。

账号头像规则：

- 多供应商扩展后，头像优先使用圆形浅色底 + 该供应商品牌图标（`ic_brand_*`，单色描边图标，accent 着色），用于区分来源。
- 当 provider 未知（如历史数据）时回退到圆形纯色底 + 账号首字。
- 不使用渐变头像作为默认样式。
- 品牌图标 / 首字需视觉居中，避免上下错位。
- 首字回退色可按账号稳定 hash 选择，但必须在低饱和、专业色域内。

## Components

### 1. Bottom Tabbar

Tabbar 是主导航，升级后使用轻量浮动玻璃胶囊，不做强折射。

结构：

- 顺序固定为：首页 / 账号 / 设置。
- 每项包含 24dp 线性 SVG / Vector 图标 + 文字标签。
- 当前项使用蓝色图标、蓝色文字和浅蓝玻璃选中胶囊。
- 未选中项使用灰色图标和灰色文字。
- Tabbar 背景使用半透明冷蓝灰底、1dp 亮描边和柔和蓝灰阴影。

图标规则：

- 使用 Compose `ImageVector` 或 Android VectorDrawable。
- 禁止用 CSS / Box / Canvas 拼出来的伪图标作为实现参考。
- 图标风格统一为 iOS-like / SF Symbols-like 的线性轮廓、圆角端点、约 2dp stroke。
- 首页图标：home。
- 账号图标：person / account circle。
- 设置图标：settings gear。

可访问性：

- 每个 Tab 必须有明确 contentDescription：`首页`、`账号`、`设置`。
- 最小触控区域不小于 48dp。

### 2. Home Dashboard

首页是仪表盘优先，不是完整数据分析页。

已登录态结构：

1. 顶部标题：`CodexMeter`。
2. 副标题：当前数据状态说明。
3. 标题行右侧刷新图标按钮。
4. 当前账号 Liquid Glass 摘要卡，显示账号头像、账号名、数据状态、plan 和 credits。
5. 5小时额度卡。
6. 1周额度卡。
7. 最近 24 小时趋势卡。
8. 刷新状态卡。

不做：

- 不做复杂分析型主额度大卡；允许一个克制的 Liquid Glass summary / hero 区承载当前账号与主额度摘要。
- 不做卡片点击展开详情。
- 不做独立详情页入口。
- 不做模型 / 场景拆分。
- 不做完整历史列表。
- 不在首页右上角放无明确含义的按钮。

视觉：

- 首页主视觉使用中高强度玻璃，不得强过 Widget。
- 5h / Weekly 两张额度卡仍是决策核心，数字可读性优先于折射效果。
- 趋势卡、刷新卡使用轻玻璃 / 浅表面，不加入彩色光斑。

### 3. Home Account Summary

首页当前账号摘要只展示状态，不承担账号切换。

要求：

- 左侧为圆形纯色首字头像。
- 中间显示账号名、`当前账号` 辅助说明和当前数据状态。
- 右侧显示 plan 与 credits。
- 使用首页中高强度 Liquid Glass 表面，但不能强过 Widget。
- 点击摘要卡不打开账号切换弹层；账号切换统一在 Account Tab 完成。

### 4. Account Switching

当前实现不提供首页账号切换 Bottom Sheet。

账号切换入口：

- Account Tab 的账号卡片。
- 账号卡片展开后通过 `设为当前` 操作切换当前账号。
- 切换后首页和未单独配置的 Widget 跟随新账号；常驻通知只有在选择“跟随当前账号”时跟随新账号。

### 5. Quota Cards

首页始终同时显示 5h 和 Weekly 两张额度卡。

内容：

- 额度名称。
- 剩余百分比大数字。
- reset 倒计时 / reset 时间。
- 状态文字。
- 小型进度条。
- 小面积状态点。

视觉：

- 两卡并排，窄屏仍保持可读。
- 剩余百分比是第一视觉层级。
- 5h 与 Weekly 不因主额度设置而隐藏。
- 状态色只影响状态点、少量文字或进度条，不改变整个卡片底色。

### 6. Trend Card

趋势只做最近 24 小时极简圆顶柱状图，避免曲线 / 折线在稀疏数据下产生“追点”“过平滑”或定位漂移的观感。

内容：

- 标题：`最近 24 小时`。
- Segmented control：`5h` / `Weekly`。
- 圆顶柱状图：固定 24 个小时槽位，每小时最多一根柱，柱顶为圆形/胶囊式圆角，底部贴近统一基线。
- 同一小时内如果存在多条历史采样，先取剩余百分比平均值，再绘制该小时柱高。
- 不做平滑、不做折线、不做面积曲线；高度直接表达该小时平均剩余百分比，视觉重点是离散小时分布。
- 起止时间标签：`24h 前`、`12h`、`现在`。
- 数据点保留时间排序和窗口边界；首尾柱需要留出半个柱宽，避免贴边裁切。

交互：

- 仅支持 5h / Weekly 切换。
- 不支持缩放。
- 不支持 tooltip。
- 不支持复杂图表交互。
- 数据不足时显示 `数据积累中`。

### 7. Refresh Card

刷新卡用于解释当前数据可信度。

内容：

- 状态标题，例如 `数据新鲜`、`正在刷新`、`可能已过期`、`需要重新登录`。
- 状态说明，例如 `刚刚刷新 · 自动刷新已开启`。
- 最近成功刷新时间。
- 最近一次刷新尝试时间。

手动刷新入口位于首页标题行右上角，使用单个刷新 SVG / Vector 图标按钮。

刷新交互：

- 点击顶部刷新图标后，图标进入轻量旋转动画。
- loading 状态不要夸张，不阻塞页面浏览。
- 成功后更新最近刷新文案。
- 失败时保留 last known good 数据，并显示失败 / 过期状态。

### 8. Account Tab

账号页是账号管理主入口。

结构：

1. 页面标题：`账号管理`。
2. 说明：全局只有一个当前账号。
3. 右上角添加账号按钮，语义明确为 `添加账号`。
4. 已保存账号列表。

不再额外展示 `添加 Codex 账号` 卡片，避免与右上角添加账号按钮重复。

账号卡片内容：

- 默认折叠，只露出圆形首字头像、账号名、最近刷新 / 采集状态、当前账号 / 备用 / 失效 badge 和右侧 chevron 展开按钮。
- 点击账号卡片或 chevron 后，详情区以轻量抽屉动效向下展开，显示 plan 类型、credits 余量、5h / Weekly 摘要、5h / Weekly 告警开关；收起时 chevron 反向并向上收合。
- 展开态显示操作按钮：设为当前、重命名、重新登录、删除。

删除规则：

- 删除账号必须二次确认。
- 删除文案必须说明会删除会话和历史数据。
- 删除按钮使用 danger 色，但不大面积铺红。

### 9. Settings Tab

设置页只放全局配置，不重复账号管理。

分组：

- 常驻通知：常驻通知开关、通知账号、通知显示额度。
- 通知与告警：账号与错误提醒、注意阈值、紧张阈值。
- 刷新：后台刷新、最近结果。
- 数据：历史保留、清空历史。
- 诊断：脱敏诊断信息、复制诊断、重新检测。
- 关于：版本、GitHub 仓库链接、数据来源、隐私说明；关于卡片下方放一个检查更新按钮。

关于卡片交互：

- GitHub 仓库链接使用 GitHub logo + 文案按钮，在外部浏览器打开 `https://github.com/KyoMio/CodexMeter`。
- 仓库链接是信息入口，不引入账号管理、远程同步或应用内 WebView。

检查更新交互：

- 按钮前置刷新 SVG / Vector 图标，检查中使用与首页刷新按钮一致的轻量旋转动画，完成后停止。
- 发现更高版本且 Release 含 APK 资产时弹窗提示，按钮为 `下载更新` 和 `取消更新`。
- `下载更新` 只启动系统下载器；`取消更新` 关闭弹窗返回当前设置页。

外观卡片：

- 独立卡片，不与通知或账号卡片合并。
- 标题：`外观 / Appearance`。
- 提供 3 选 1 的 999dp 胶囊形 Segmented Control，选项依次为：`浅色` / `深色` / `跟随系统`。
- 默认选中 `跟随系统`；选择立即生效，仅作用于 App。桌面微件不随此设置变化，始终跟随系统深浅色（见下文「深色 / 浅色主题适配」）。
- `浅色`：始终使用浅色主题，忽略系统深色模式。
- `深色`：始终使用深色主题，不受系统模式影响；深色须保持 Air Glass 质感，不得做成深色终端风。
- `跟随系统`：随系统深色模式自动切换，冷启动状态栏通过 `values-night` 资源处理。
- 偏好独立持久化，不与账号数据耦合。

不放：

- 不放显示分组。
- 不放主额度设置。
- 不放语言切换。
- 不放字体方案切换。
- 不放当前账号卡片。
- 不放添加账号入口。
- 不放账号管理入口。

原因：账号已有独立 Tab，设置页再放账号区会重复导航层级。

### 10. Add Account Flow

添加账号入口在账号 Tab（右上角圆形 `+` 图标按钮）。

#### Provider 选择底部 Sheet

点击 `+` 后弹出 `ProviderSelectionSheet`，而非全屏选择页：

- Sheet 从底部 tab bar 向上展开并遮住 tab bar，关闭时折叠回去。
- Sheet 内按 `ProviderRegistry.all` 顺序列出所有 Provider，每行显示品牌图标和名称。
- 选择 Provider 后导航到对应认证页；取消点击背景关闭 Sheet。

#### 认证页公共 Chrome（AuthScaffold）

所有认证页使用 `AuthScaffold`：

- 固定高度居中 top bar，左侧返回箭头，右侧可选操作图标槽。
- WebView 认证页（Kimi Cookie、Claude/Antigravity OAuth）首次打开时弹提示对话框，说明操作步骤并展示对应 top bar 图标，让用户知道操作入口；之后不再重复弹出。
- WebView 操作图标：清除会话（`ic_action_clear`）、确认登录（`ic_action_done`）、重新加载（OAuth 页专用）。

#### Codex 登录页（device-code）

- 页面展示紧凑的一行 device code + 复制按钮，并提供打开 Codex verification 页的主操作。
- Codex verification 页面通过外部浏览器打开，不内嵌 WebView。
- 通知权限关闭时，页面内 code 行仍是可用兜底。

#### API Key 登录页（DeepSeek / z.ai / MiniMax）

- `ApiKeyAuthScreen` 提供单行 API Key 输入框和提交按钮。
- 不展示 token、不展示 Cookie 或任何敏感凭据。

#### WebView 认证页（Cursor / Kimi Cookie；Claude / Antigravity OAuth）

- 内嵌 WebView 承载 Provider 登录页，登录完成后自动提取 cookie 或拦截 OAuth code。
- Kimi 等 Cookie 页使用软件层渲染；Claude 等 OAuth 拦截页使用硬件层（防止 Google 登录页被压缩）。
- 不展示浏览器地址栏 URL，不要求用户手动粘贴 callback URL。

兼容说明：

- 已保存的 OAuth 会话，包括历史上从 `auth.json` 导入后保存的会话，不在 UI 迁移中自动删除。
- 失效账号通过对应认证页重新连接。

安全文案：

- 不展示 token。
- 不展示 Cookie。
- 不展示 auth.json 原文。
- 原型和实现都不得把真实凭据写入日志、截图或诊断。

### 11. Widget

MVP 做一个可调整大小并自适应布局的 Widget。

视觉方向：Widget-first Liquid Glass。Widget 是本次升级的第一优先表面，必须比 App 普通卡片更像液态玻璃，不能停留在白色半透明卡。

材质要求：

- 使用低白度冷蓝灰透明玻璃底，不使用纯白卡底。
- 卡片边缘使用贴边白色高光 + 极细冷蓝描边，表现轻薄玻璃边缘；边缘高光必须可见但不能压过文字，也不能堆成厚边框。
- 背景加入受控蓝 / 青绿折射光斑或 lens，透明度低，不影响读数。
- 顶部高光贴住主体上沿，底部只保留极轻蓝灰接触阴影，形成轻悬浮感而不是厚重实体卡。
- `initialLayout` / loading 背景也要同步同一方向的高光、冷蓝描边和底部阴影，避免首次放置时退化成普通卡。
- 内容文字必须位于稳定 scrim 上，主数字和 reset 文案在浅 / 深壁纸上都可读。
- Widget 实现受 Glance / RemoteViews 限制时，可采用 bitmap-backed glass renderer；App 内真玻璃 View 不得直接假定可用于 Widget。

深色 / 浅色主题适配：

Widget **始终跟随系统主题**（与 App 的浅/深/跟随系统设置无关——后者只作用于 App）。支持两种材质变体：

- **深色玻璃**（系统深色）：沿用上述低白度冷蓝灰透明底、双描边、折射光斑、接触阴影规则；左侧语义状态条保留。
- **浅色液态玻璃**（系统浅色）：冷白磨砂玻璃底（非纯白卡），深色墨水文字，顶部细薄白色高光，发丝级冷蓝边框，轻冷色接触阴影；左侧语义状态条同样保留。两种变体均不得退化为普通白卡或纯色卡。

实现：玻璃背景为 `drawable` / `drawable-night` XML layer-list 资源（`ImageProvider(R.drawable.widget_glass_xml)`），文字色为 Glance `ColorProvider(day, night)`。系统夜间模式切换时由桌面 host **资源级即时自动切换**，无需 App 进程参与或重绘（API 31+ `setColorInt(notNight, night)` / `setImageViewResource`）。左侧状态条为 tone-only 预渲染 bitmap（与明暗无关，不需切换）。

Widget 支持四种尺寸布局（`WidgetLayoutVariant`），均带头部行（Provider 图标 + 账号名 + 状态）：

**3×1（1 字段）**

- 紧凑横条，展示 1 个配置的窗口字段。
- 字段显示：标签、主值（百分比 / 余额 / 次数）、重置/刷新时间。
- 正常态主值用主文字色，注意 / 紧张 / 耗尽才用语义重点色。

**4×1（2 字段）**

- 双列横条，展示 2 个配置的窗口字段，中间有竖向分隔线。
- 每列同 3×1 字段规则。

**3×2（3 字段）**

- 2×2 格子网格中填 3 个字段（按行优先，右下角为空白占位或留空）。
- 格子间有横向和纵向分隔线。
- 头部行在格子区域上方。

**4×2（4 字段）**

- 2×2 格子网格，填满 4 个字段。
- 格子间有横向和纵向分隔线。

字段来源：用户在配置页最多选 4 个窗口，按选择顺序取前 `fieldCapacity` 个。未配置时按 Provider 天然窗口顺序取默认字段。Provider 图标显示于头部左侧，不再只显示 `Codex`。

交互：

- 已登录点击打开首页。
- 未登录点击进入添加账号流程。
- 长按微件进入系统配置入口，可选择展示账号（最多显示已保存账号列表）和最多 4 个窗口字段（多选）。
- 配置入口使用系统 Activity 弹出浮层，保持克制浅表面风格，不做全屏复杂管理页。
- 不做 Widget 内按钮，不做 Widget 内账号切换。
- 未配置窗口时显示引导提示（告知用户长按进行配置）。

验收：

- 截图中必须明显区别于普通白卡，有玻璃厚度、折射色和悬浮感。
- 正常 / 注意 / 紧张 / 耗尽状态均要保留状态文字，不能只靠颜色。
- 3×1、4×1、3×2、4×2 在 emulator 桌面上都要截图验收。
- Provider 图标在 Widget 头部正确显示（不同 Provider 账号对应不同图标）。
- 配置页支持多窗口选择（最多 4 个），账号列表可滚动。

### 12. Notification

常驻通知是辅助表面，不是主体验。

设置：

- 常驻通知开关。
- 通知账号：跟随当前账号或指定已保存账号。
- 通知显示额度：5小时额度 / 1周额度。
- 常驻通知设置不影响 Widget 默认值或 Widget 独立配置。

内容：

- 标题：`Codex 62%`。
- 正文按状态展示 reset / 过期 / 需要重新登录。
- 点击打开首页。

不做：

- 不做通知内按钮。
- 不做通知内直接刷新。
- 不做锁屏额度展示。
- 不做多账号并排显示。

## Do's and Don'ts

### Do

- 使用浅色、低噪声、工具型视觉语言。
- 使用蓝色作为唯一主交互色。
- 使用绿色 / 橙色 / 红色表达状态，但必须配文字。
- 首页优先展示 5h、Weekly、24h 趋势、刷新状态。
- Bottom Tab 固定为：首页 / 账号 / 设置。
- 底部 Tab 切换保留轻量 PageCascade 入场，但固定背景遮罩必须按全屏坐标绘制，内容层再消费系统栏 `innerPadding`，避免状态栏出现背景分界线或露出前一页残影。
- Tabbar 使用真实 SVG / Vector 图标。
- 账号头像使用圆形纯色首字，并做视觉居中。
- 设置页只放全局设置。
- 账号管理集中在账号 Tab。
- 诊断信息必须脱敏。
- 所有用户可见文案必须资源化，覆盖简中和 English。

### Don't

- 不要做深色终端风作为 MVP 主视觉。（深色为可选备选外观，默认仍为浅色主视觉；深色须保持 Air Glass 质感，不得做成深色终端风。）
- 不要用过度渐变、彩虹色、营销感插画。
- 不要把页面做成复杂分析系统。
- 不要在首页右上角放无明确含义的图标按钮。
- 不要用 CSS 伪图形替代正式图标资源。
- 不要在设置页重复账号管理卡片。
- 不要只靠颜色表达正常 / 紧张 / 耗尽。
- 不要展示 token、Cookie、auth.json 原文、完整 OAuth query。
- 不要为了玻璃感牺牲可读性。

## Screen Specifications

### Home

默认已登录首页：

- 顶部展示 `CodexMeter` 和当前账号刷新状态。
- 当前账号玻璃摘要展示头像、账号名、状态、plan 和 credits；不提供首页账号切换。
- 5h / Weekly 两张额度卡并排。
- 最近 24 小时趋势卡支持 5h / Weekly 切换。
- 标题行右侧刷新图标支持手动刷新；刷新卡只解释数据可信度。

未登录首页：

- 展示登录引导卡。
- 主按钮：`登录 Codex`。
- 说明登录后可在 Widget 和通知中查看额度。

错误 / 失效首页：

- 保留 last known good 数据，除非从未成功获取。
- 明确显示 `可能已过期`、`已过期`、`需要重新登录` 等状态。
- 提供重新登录入口，不提供文件导入或粘贴 JSON 兜底。

### Accounts

账号页是独立 Tab。

- 顶部右侧 `+` 必须是添加账号，不做其他用途。
- 不展示额外添加账号卡片；添加入口只保留顶部添加账号按钮和空态按钮。
- 账号卡片默认折叠，右侧用 chevron 图标按钮提示展开状态；点击卡片或图标展开后再进行告警开关和账号管理操作。
- 当前账号使用 success badge。
- 失效账号使用 warning / danger 语义，提示重新登录。
- 删除账号必须弹确认。

### Settings

设置页按卡片分组。

- 外观：浅色 / 深色 / 跟随系统三选一 Segmented Control，立即生效。
- 常驻通知：开关、通知账号、通知显示额度。
- 通知与告警：账号与错误提醒、阈值。
- 刷新：后台刷新、最近结果。
- 数据：历史保留、清空历史。
- 诊断：折叠卡。
- 关于：版本和隐私说明。

### Dialogs

- 对话框用于添加账号、选项选择和破坏性确认。
- 破坏性操作使用确认弹层。
- 弹层背景更实，保证文字对比。
- 弹层内主次按钮层级必须清楚。

## Interaction Rules

### Navigation

- 主导航只有：首页、账号、设置。
- 首页不再提供顶部设置按钮。
- 账号切换和账号管理只在 Account Tab 完成。
- 设置页不承担账号管理入口。

### Account Switching

- 首页不提供账号切换弹层。
- Account Tab 展开账号卡片后通过 `设为当前` 切换账号。
- 切换当前账号后：首页和未配置 Widget 跟随新账号；常驻通知只有在选择“跟随当前账号”时跟随新账号。
- 切换后触发一次刷新或读取该账号 last known good 状态。

### Refresh

- 手动刷新按钮必须有 loading 状态。
- 首页手动刷新入口使用标题行右上角的单个刷新图标按钮。
- 底部刷新卡只解释数据可信度和最近刷新时间，不再重复展示刷新按钮。
- 刷新失败不清空旧数据。
- 正在刷新时不阻塞页面导航。
- 设置页展示最近一次周期后台刷新结果，不显示实现占位文案。

### Settings

- 常驻通知账号 / 显示额度设置只影响常驻通知。
- Widget 默认值固定为当前账号 + 5小时额度，Widget 独立配置只影响该 Widget。
- 额度预警阈值影响 Home 状态色 / 文案、Widget tone 和通知告警。
- 不影响首页同时展示 5h 和 Weekly。
- 历史保留选项为 7 / 30 / 90 / 永久。
- 清空全部历史必须二次确认。

### Diagnostics

- 诊断卡默认折叠。
- 异常时可提示用户查看。
- 复制诊断只复制脱敏摘要。
- 诊断中不得包含 token、Cookie、完整 auth.json、完整 OAuth query 或原始 response body。

## Accessibility & i18n

### Accessibility

- 所有可点击目标至少 48dp。
- Tab、按钮、账号摘要、刷新按钮、删除按钮必须有 contentDescription。
- 状态色必须配文字，不允许只靠颜色。
- 百分比、reset 时间、刷新状态必须可被读屏理解。
- 破坏性操作必须二次确认。
- 动画只用于状态连续性，不可阻碍操作。

### Contrast

- 主文字对浅色 surface 至少满足 4.5:1。
- 大数字至少满足 3:1，实际应接近主文字对比。
- 浅色玻璃层上不得使用过浅灰色小字。
- Widget 需要考虑不同桌面壁纸，必要时增加更实的底色或描边。

### i18n

MVP 支持：

- 简体中文。
- English。

规则：

- 所有用户可见文案走 string resources。
- Compose、Widget、Notification、错误、诊断、权限说明全部资源化。
- App 内不提供语言切换入口，默认跟随系统；系统语言不支持时显示英文。
- 旧版本残留的 App 专属语言覆盖不得继续影响新版本显示。
- `Codex`、`OpenAI`、`auth.json` 等专名不翻译。
- 文字长度变化时，按钮和 badge 不得错位；长文本应省略或换行。

## Implementation Notes

### Compose

- 使用 Jetpack Compose + Material 3。
- 组件应拆分到 `ui.components`，页面放在 `ui.home`、`ui.account`、`ui.settings` 等包。
- Composable 只消费 UI state，不直接访问 Provider、Room、DataStore、OkHttp 或 Keystore。
- 首页、Widget、通知状态从 `CurrentQuotaState` / 裁剪状态派生，避免重复计算。

### Icons

- 使用 Compose `ImageVector` 或 Android VectorDrawable。
- 图标资源命名使用语义前缀，例如 `ic_tab_home`、`ic_tab_account`、`ic_tab_settings`。
- 避免使用 Android 保留名，例如 `icon`、`logo`、`background`。
- 线性图标保持统一 stroke、圆角端点和 24dp 画布。

### Theme Tokens

建议在 `ui.theme` 中沉淀：

- `CodexMeterColors`
- `CodexMeterTypography`
- `CodexMeterShapes`
- `CodexMeterSpacing`

MVP 可以先用 Material 3 theme + 项目内 token object，不需要引入复杂设计系统框架。

### Liquid Glass Libraries

- App 端优先用 QmDeve LiquidGlass 1.0.4 承载首页主视觉和必要玻璃组件。
- `compileSdk 37` / `buildToolsVersion 37.0.0` 是 QmDeve 1.0.4 的实现前提。
- 不要为所有普通卡片强行套真折射；只在 Widget、首页 hero、底栏容器 / 选中态等关键层级使用。
- Widget 端优先按 DESIGN.md 材质规则实现高保真 bitmap / drawable 背景；如果平台限制导致真玻璃不可用，不能退回普通白卡，必须保留折射光斑、双描边、高光和阴影。

### Widget / Notification

- Widget 不直接联网，不直接读 Provider。
- Notification 不直接拼复杂状态，使用统一派生状态。
- Widget 和 Notification 的文案也必须资源化。

## Acceptance Checklist

实现 UI 时至少检查：

- [ ] 底部 Tab 顺序为：首页 / 账号 / 设置。
- [ ] Tabbar 使用真实 SVG / Vector 图标。
- [ ] 首页右上角没有无意义跳转按钮。
- [ ] 首页显示 5h 和 Weekly 两张额度卡。
- [ ] 首页趋势支持 5h / Weekly 切换。
- [ ] 首页刷新按钮有 loading / 成功 / 失败状态。
- [ ] 首页账号摘要使用圆形纯色首字头像。
- [ ] 首页账号摘要不打开账号切换弹层。
- [ ] 账号 Tab 支持添加、设为当前、重命名、重新登录、删除确认。
- [ ] 账号卡片默认折叠，展开后显示 plan、credits、5h 告警开关和 Weekly 告警开关。
- [ ] 设置页不出现账号管理分组。
- [ ] 设置页包含外观、常驻通知、通知与告警、刷新、数据、诊断、关于。
- [ ] 外观卡片包含浅色 / 深色 / 跟随系统三选一 Segmented Control，默认跟随系统，选择立即生效。
- [ ] 常驻通知卡片包含开关、通知账号和通知显示额度。
- [ ] 最后一项列表内容能完整滚到 Tabbar 上方。
- [ ] 所有触控目标不小于 48dp。
- [ ] 状态色均有文字说明。
- [ ] 所有用户可见文案资源化。
- [ ] 诊断和 UI 不展示 token、Cookie、auth.json 原文或 OAuth query。
- [ ] Widget 与 App 视觉一致，但优先保证桌面可读性。
- [ ] Widget 有低白度玻璃底、双描边、高光、冷色阴影和蓝 / 青绿 / 紫色折射光斑，不像普通白卡。
- [ ] Widget 深色模式保持 Air Glass 深色玻璃材质；浅色模式使用冷白磨砂液态玻璃（非普通白卡），均保留语义状态条。
- [ ] Widget 头部显示 Provider 品牌图标（非纯文字 `Codex`）。
- [ ] App 首页主视觉使用中高强度 Liquid Glass，但不强过 Widget。
- [ ] Bottom Tabbar 是轻玻璃浮动胶囊。
- [ ] emulator 上完成 App 首页、3×1 Widget、4×1 Widget、3×2 Widget、4×2 Widget 截图验收。
- [ ] Widget 配置页支持多窗口字段选择（最多 4 个），账号列表可滚动。
- [ ] 通知只作为辅助表面，不承担主体验。
