---
name: AgentHub
description: 企业级 Agent 中台控制台 —— 中控室式的配置、运行与观测界面
colors:
  primary: "#635bff"
  primary-hover: "#5146f5"
  primary-active: "#4338ca"
  primary-tint: "#f4f3ff"
  primary-tint-soft: "#f4f4ff"
  scope-cyan: "#00d4ff"
  page-bg: "#f5f7fa"
  surface: "#ffffff"
  surface-muted: "#fafafa"
  text: "#1f2937"
  text-regular: "#374151"
  text-secondary: "#6b7280"
  text-faint: "#9ca3af"
  border: "#ebeef5"
  border-soft: "#f2f6fc"
  success: "#047857"
  warning: "#b45309"
  danger: "#b91c1c"
  success-tint: "#ecfdf5"
  warning-tint: "#fffbeb"
  neutral-tint: "#f3f4f6"
  signin-bg: "#0b0f19"
  signin-glass: "rgba(255, 255, 255, 0.08)"
typography:
  display:
    fontFamily: '"Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif'
    fontSize: "clamp(42px, 5.5vw, 64px)"
    fontWeight: 800
    lineHeight: 1.1
    letterSpacing: "0.02em"
  title:
    fontFamily: '"Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif'
    fontSize: "16px"
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: "0em"
  subtitle:
    fontFamily: '"Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif'
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.4
    letterSpacing: "0em"
  body:
    fontFamily: '"Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif'
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "0em"
  label:
    fontFamily: '"Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif'
    fontSize: "12px"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "0.02em"
  mono:
    fontFamily: 'ui-monospace, Consolas, monospace'
    fontSize: "12px"
    fontWeight: 400
    lineHeight: 1.4
rounded:
  xs: "4px"
  md: "6px"
  lg: "8px"
  bubble: "12px"
  glass: "20px"
  pill: "999px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"
  page: "36px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "#ffffff"
    rounded: "{rounded.lg}"
    padding: "0 14px"
    height: "32px"
    typography: "{typography.body}"
  button-primary-hover:
    backgroundColor: "{colors.primary-hover}"
    textColor: "#ffffff"
    rounded: "{rounded.lg}"
  button-primary-active:
    backgroundColor: "{colors.primary-active}"
    textColor: "#ffffff"
    rounded: "{rounded.lg}"
  button-reset:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    rounded: "{rounded.lg}"
    padding: "0 14px"
    height: "32px"
  input:
    backgroundColor: "{colors.page-bg}"
    textColor: "{colors.text}"
    rounded: "{rounded.lg}"
    padding: "0 12px"
    height: "32px"
  nav-item:
    backgroundColor: "transparent"
    textColor: "#4b5563"
    rounded: "{rounded.md}"
    padding: "0 12px"
    height: "40px"
  nav-item-active:
    backgroundColor: "{colors.primary}"
    textColor: "#ffffff"
    rounded: "{rounded.md}"
    height: "40px"
  tab-active:
    backgroundColor: "{colors.primary-tint}"
    textColor: "{colors.primary}"
    rounded: "{rounded.md}"
    padding: "0 8px"
    height: "24px"
  card-config:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    rounded: "{rounded.lg}"
    height: "160px"
  card-config-footer:
    backgroundColor: "{colors.surface-muted}"
    textColor: "{colors.text-secondary}"
    height: "36px"
    padding: "0 10px"
  tag-success:
    backgroundColor: "{colors.success-tint}"
    textColor: "{colors.success}"
    rounded: "{rounded.xs}"
    padding: "0 6px"
    height: "20px"
---

# Design System: AgentHub

## Overview

**Creative North Star: "The Control Room"（中控室）**

这是一个值班员的中控室，不是展厅。浅灰的墙、白色的仪表盘、几盏语义色的小灯；值班的人一眼要看懂三件事：哪些通道在跑、哪一路失败、这条记录属于谁。所以界面几乎不用装饰性线条，卡片靠底色与一层极轻的阴影分层，纵向节奏由 8px 圆角与 12/16px 的间距撑起来，靠字重与颜色而不是边框来建立层级。

紫色的用法像中控台上那一排唯一的亮灯：主操作、当前页签、被选中的那张卡。它稀缺，所以一眼就能定位「现在在哪、下一步按什么」。青色只出现在两类地方 —— 登录页的光晕与 logo 的节点 —— 作为设备的指示灯，不进业务页面。

密度是克制的：表格去掉竖线只留极细横线，筛选条与页签条都压在 40px 上下，正文 14px / 说明 13px / 标签 12px，三级字号就够。动效只发生在状态变化上：卡片 hover 上浮 2px、详情从网格飞入 280ms、侧栏折叠 300ms；静止状态不动，且全部尊重 `prefers-reduced-motion`。

**Key Characteristics:**
- 浅灰页底（`#F5F7FA`）+ 白色卡片 + 一层 5% 黑的静止阴影，卡片无描边。
- 单一强调色：信号紫只给主操作、当前态与选中；语义色（绿/琥珀/红）只给状态。
- 系统字体栈，不加载 Web 字体；字号只有 16 / 14 / 13 / 12 四级。
- 左侧通高导航（240px / 64px）+ 右侧 40px 页签条 + 独立滚动主区，没有顶栏。
- 登录页是唯一的暗色「入场券」，玻璃拟态与光晕不向控制台扩散。

## Colors

冷调的工业灰底，配一个电光紫的强调色；颜色承担语义，不承担装饰。

### Primary
- **Signal Violet 信号紫** (`#635BFF`): 主按钮、当前页签、选中卡片、侧栏当前项、聚焦描边。单屏覆盖面积通常低于 10%。
- **Signal Violet Deep 深信号紫** (`#5146F5` / `#4338CA`): 只做 hover 与 active 两个瞬态，不用于静态填充。
- **Signal Violet Wash 紫雾底** (`#F4F3FF` / `#F4F4FF`): 当前页签底、选中卡片底。两级几乎相同的浅紫分别来自页签条与卡片，不要合并成一个值，也不要再新造第三个。

### Secondary
- **Scope Cyan 示波器青** (`#00D4FF`): 只出现在登录页光晕与 logo `agent` / `loop` 变体的节点。控制台业务页不使用；它是一种「设备通电」的提示，不是第二品牌色。

### Neutral
- **Console Paper 操作台纸白** (`#FFFFFF`): 卡片、侧栏、筛选条的底。
- **Room Gray 房间灰** (`#F5F7FA`): 页面底、主区底、页签条底、输入框填充。
- **Quiet Gray 静灰** (`#FAFAFA`): 表格表头、卡片底栏。
- **Ink 墨色** (`#1F2937`): 主文本、标题、表格正文。
- **Ink Regular 常墨** (`#374151`): 表单标签、次级标题、导航项文本。
- **Readable Gray 可读灰** (`#6B7280`): 说明文字、时间、编号、表单元信息 —— 承载信息的灰不得浅于此。
- **Decoration Gray 装饰灰** (`#9CA3AF`): 分隔符号、占位图标、禁用态、目录小标题；不承载正文。
- **Hairline 发丝线** (`#EBEEF5` / `#F2F6FC`): 侧栏右边框、卡片底栏上边线、表格横线。两级线宽都是 1px，靠明度区分层级。

### Semantic
- **Clear Green 通过绿** (`#047857`, 底 `#ECFDF5`): 启用、成功、工具调用完成。
- **Attention Amber 待办琥珀** (`#B45309`, 底 `#FFFBEB`): Router 类型、需要人工确认、待办。
- **Fault Red 故障红** (`#B91C1C`): 失败、删除、危险操作。
- **Idle Gray 停用灰** (`#6B7280`, 底 `#F3F4F6`): 停用、未启用、不可用。

### Named Rules
**The One Violet Rule.** 紫色是当前态与主操作的专有色；一屏里它出现的地方应能用一句话数完。面积越大，权威越弱。
**The Readable Gray Rule.** 承载信息的文字最深到 `#6B7280`；`#9CA3AF` 及更浅只做装饰，不写说明与数据。
**The Retired Blue Rule.** 查询按钮的 `#409EFF` 与左栏当前卡的 `#3B82F6` 环是遗留蓝，只存在于既有代码里，不得成为新控件的第三种强调色。

## Typography

**Display Font:** 系统 UI 栈（`"Segoe UI"`, `"PingFang SC"`, `"Microsoft YaHei"`, sans-serif）
**Body Font:** 同上
**Label/Mono Font:** 编码、ID、耗时用 `ui-monospace, Consolas, monospace`

**Character:** 没有品牌字体，也不加载 Web 字体。中文在 macOS 落到苹方、Windows 落到雅黑，字形差异由系统决定；系统的统一感来自字号、字重与行高的固定搭配，而不是字体本身。

### Hierarchy
- **Display** (800, `clamp(42px, 5.5vw, 64px)`, 1.1): 只用于登录页的 `AgentHub` 字标。控制台内不出现 Display。
- **Title** (600, 16px, 1.4): 页头标题、卡片标题、弹窗标题。页头标题不长于一行。
- **Subtitle** (400, 13px, 1.4): 页头说明、表单提示、表单元信息。页头说明单行省略，不换行撑高工具栏。
- **Body** (400, 14px, 1.5): 表格正文、卡片描述、对话正文。卡片描述严格两行截断。
- **Label** (600, 12px, 0.02em): 侧栏目录标题、页签文字、状态标签、时间戳。目录标题用 12px/700 加 `letter-spacing: 0.02em`，全大写策略不适用于中文。
- **Mono** (400, 12px): 编码、traceId、耗时数字，紧贴说明灰。

### Named Rules
**The No-Webfont Rule.** 不引入 Web 字体、不在 `<head>` 里加字体链接；新增字重只能从系统已有的 400 / 500 / 600 / 700 / 800 中取。
**The 16/13 Rule.** 页头永远只有 16px 的标题与 13px 的说明，没有副标题、没有面包屑堆叠。要表达更多信息，放进下方的卡片或筛选条。

## Layout

壳层是左右两列、左列通高：`aside` 240px（折叠 64px，`transition: width 0.3s ease`）+ 右侧内容列。内容列自上而下是 40px 页签条与主区；主区内边距 `16px 16px 20px`，独立滚动并预留 `scrollbar-gutter: stable`，避免出现滚动条时卡片换行错位。**不存在顶栏**：品牌、用户、收起按钮都在左栏（品牌区 60px，底部账户行 64px）。

业务页纵向节奏固定：`PageHeader`（标题 + 说明 + 右侧操作，下边距 12px）→ 筛选条（白色，最小高 56px，内边距 `12px 16px`）→ 内容卡片 → 分页（右对齐，上边距 12～16px）。多区块页用 `.panel`：白底、8px 圆角、轻阴影、内边距 16px、块间距 16px。

横向网格：`repeat(auto-fill, minmax(280px, 1fr))`，间距 16px。网格 ↔ 主从切换时，左栏固定 186px，右侧为详情；左栏卡片间距 10px，可滚动，最大高度 `calc(100vh - 220px)`。

表格：满宽、无竖线、无外框；表头 `#FAFAFA` 底、`#5F6673` 600 字重；行分隔用 `#F2F6FC` 1px；行悬浮 `#FAFAFA`。分页统一右对齐。

响应式是逐页判断的，没有全局断点表；实际出现过的断点是 1440 / 1280 / 1200 / 1100 / 1024 / 1000 / 960 / 900 / 768 / 720 / 640px。两条明确的：页头在 900px 以下允许说明换行；登录页在 1023px 以下隐藏左侧品牌栏。

滚动条全站统一：宽 5px、轨道透明、默认隐藏，容器 hover 时滑块以次级灰 35% 出现。

### Named Rules
**The Flush Workspace Rule.** 整屏页（对话、工作流画布、运行）用 `is-flush` 去掉主区留白与滚动，把高度与滚动交给页面内部；禁止出现外层与内层双滚动条。
**The No-Top-Bar Rule.** 不新增顶部 Header。全局动作放左栏底部账户行，页面动作放页头右侧。

## Elevation & Depth

平铺是默认状态。静止的卡片只有一层 `0 1px 3px rgba(0, 0, 0, 0.05)`，作用是从浅灰底上托起白面，而不是制造「浮起的卡片」。深度只在三种情况下变化：可点卡片 hover 上浮 2px 并加深到 `0 4px 12px rgba(0, 0, 0, 0.08)`；选中态换一层极浅紫底与紫调阴影 `0 1px 3px rgba(99, 91, 255, 0.12)`；输入聚焦出现 2px 的紫光 `0 0 0 2px rgba(99, 91, 255, 0.2)`。

分区不靠阴影，靠 1px 发丝线与底色差：侧栏右边框、页签条底边线、卡片底栏上边线。全站不使用多层阴影、不使用描边卡片。唯一的例外是登录页的玻璃卡：`blur(24px)` + `0 24px 64px rgba(0, 0, 0, 0.45)` + 内高光，那是一个独立世界。

### Shadow Vocabulary
- **Rest 静止** (`0 1px 3px rgba(0, 0, 0, 0.05)`): 所有白卡、筛选条、页签条以外的容器。
- **Hover Lift 悬浮** (`0 4px 12px rgba(0, 0, 0, 0.08)`): 仅限可点卡片（`ConfigCard`、`.quick-card`、左栏卡、网格卡）。
- **Selected 选中** (`0 1px 3px rgba(99, 91, 255, 0.12)`): 被选中的业务卡。
- **Focus Glow 聚焦** (`0 0 0 2px rgba(99, 91, 255, 0.2)`): 输入框聚焦，以及按钮的 `:focus-visible`。
- **Glass 玻璃** (`0 24px 64px rgba(0, 0, 0, 0.45)`): 仅登录卡片。

### Named Rules
**The Flat-At-Rest Rule.** 静止不位移、不发光。位移只发生在 hover，且幅度是 2px；禁止给静态容器（表格卡、面板、页签条）加上浮。

## Shapes

圆角分四档：8px 是卡片与按钮的主圆角，6px 是导航项、页签、小控件，4px 是标签与状态块，999px 是胶囊与进度。项目里的变量名与数值有意错位 —— `--ah-radius-lg: 8px`、`--ah-radius-md: 6px` —— 沿用现名，不要按通用 Material 顺序理解。12px 只给对话气泡与快捷卡，20px 只给登录玻璃卡。

轮廓语言是「无描边」：卡片、筛选条、输入框在静止时都没有可见边框，边界由底色差与阴影给出；只有输入框在聚焦时出现 1px 紫色描边加光晕。头像分两种形：用户头像是正圆的 32px（消息里 36px），Agent 形象是 9px 圆角的方座加 1px 内描边（`inset 0 0 0 1px #E5E7EB`），因为形象图形自带白底，正圆裁切会切掉构图。Logo 角标是 8px 圆角的 32px 方块。

### Named Rules
**The 8/6/4 Rule.** 新控件只从 8px / 6px / 4px 里选圆角；12px 与 20px 属于对话与登录两个已划定区域，不进入普通业务卡片。

## Components

### Buttons
- **Shape:** 8px 圆角，高 32px，内边距 `0 14px`，14px/400 文字。
- **Primary:** 紫底白字，用于「新建 / 保存 / 发送」等页面唯一主操作；hover `#5146F5`，active `#4338CA`。
- **Reset / Secondary:** 白底 + `#EBEEF5` 1px 描边 + 深色文字，用于「重置 / 取消」；hover 底色 `#F5F7FA`、描边转 `#C0C4CC`。
- **Query（遗留）:** 筛选条里的「查询」是淡蓝底 + `#409EFF` 文字，属于 Element Plus 默认语义的残留；不要把它扩散到其它按钮，新页面把查询也用 primary 或 reset 表达。
- **Focus:** 2px 紫光，不使用浏览器默认 outline。

### Tabs
- **Style:** 40px 定高页签条，底色与主区同为 `#F5F7FA`，只有底边 1px 发丝线；标签本身 24px 高、6px 圆角、12px 文字。
- **State:** 未选中透明底 `#6B7280`；hover 半透明白底；当前页为 `#F4F3FF` 底 + `#635BFF` 600 字重。关闭按钮 11px，悬停出现 8% 黑圆底。
- **Behavior:** 标签横向滚动，滚动条隐藏；标签按 `cacheName` 归并，换 query 不新增标签。

### Cards / Containers
- **Corner Style:** 8px。
- **Background:** 白；选中态 `#F4F4FF`。
- **Shadow Strategy:** 静止一层 5% 黑影，hover 上浮到 8% 黑影（见 Elevation）。
- **Border:** 无。卡片内部的分区只用 `#F2F6FC` 1px。
- **Internal Padding:** `ConfigCard` 头部 `12px 14px 0`、正文 `8px 14px`、底栏高 36px、底栏底 `#FAFAFA`；`.panel` 统一 16px。
- **Heights:** 业务卡锁死 160px（`normal`）/ 120px（`small`），min/max/height 同值，禁止被文案撑高。

### Inputs / Fields
- **Style:** 高 32px（登录页 44px），8px 圆角，浅灰填充 `#F5F7FA`，无描边无内阴影。
- **Focus:** 底转白，1px 紫描边 + `0 0 0 2px rgba(99, 91, 255, 0.2)` 光晕。
- **Placeholder / Disabled:** `#9CA3AF`；禁用态保留形状、降低对比，不加斜纹或删除线。
- **Dark variant:** 登录页输入框为 35% 黑填充 + 白字，聚焦换 45% 黑 + 55% 紫描边；这是唯一允许反色的输入形态。

### Navigation
- **Structure:** 左侧通高；品牌区 60px，导航可滚，底部账户行 64px。
- **Item:** 40px 高、左右外边距 12px、内边距 12px、6px 圆角、10px 图标-文字间距；默认 `#4B5563`，hover `#F3F4F6`，当前项紫底白字。
- **Directory heading:** 12px/700、`#9CA3AF`、不可点击；嵌套分组再缩进 16px。
- **Collapsed:** 宽度 64px，只出居中的图标；有子项的分组 hover 出右侧飞出层（200px 宽，当前项紫底白字）。

### Chat Surface (signature)
对话页是产品的脸，但仍是控制室逻辑：用户消息右对齐、淡紫气泡（12px 圆角、`10px 12px` 内边距、最大 78% 宽）；Agent 回答左对齐、白卡承载、36px 方头像在上；回答正文允许表格横向滚动并带 `is-clamped` 折叠。空态用三列快捷卡（12px 圆角、白底、hover 上浮）。工具步骤、分发卡、追踪时间线共用同一套发丝线与语义色。

### Sign-in (signature)
暗色 `#0B0F19` 全屏，左 50% 品牌区、右 50% 玻璃卡（420px 宽、20px 圆角、8% 白底、24px 模糊）。背景只有两团 120px 模糊的光晕（紫 / 青，30% 不透明度）与 40px 网格（白 3%）。主按钮是 `135deg` 的紫→青渐变加紫投影。它不参与控制台的任何布局规则，也不可被业务页复用。

## Do's and Don'ts

### Do:
- **Do** 所有业务页用 `<PageHeader>`：标题 16px + 说明 13px 同行，铺在灰底上，不加白卡。
- **Do** 单表页把主按钮放进 `#extra`；多区块页把新建按钮放在对应区块标题行，页头只留标题与说明。
- **Do** 卡片 8px 圆角 + `0 1px 3px rgba(0, 0, 0, 0.05)`，无描边；只有可点卡允许 hover 上浮 2px。
- **Do** 表格去竖线，只留 `#F2F6FC` 横线；表头 `#FAFAFA` + `#5F6673` 600。
- **Do** 正文辅助信息最深用 `#6B7280`；状态一律走语义色（绿通/琥珀待/红错/灰停），不新造颜色。
- **Do** 过渡只写 `box-shadow` / `transform` / `background-color`，150–200ms；长动画（飞卡 280ms、侧栏 300ms）保持现节奏。
- **Do** 尊重 `prefers-reduced-motion`：飞卡、详情转场、左栏位移在该媒体查询下直接取消。
- **Do** 界面文案用「运行」；「对话 / 单兵 / 方案 / 入口 Router / 工作流 / 人工待办」是固定术语。

### Don't:
- **Don't** 新增顶栏或在侧栏顶部加 workspace / 租户切换 —— 产品没有这些概念。
- **Don't** 把紫色用在静态装饰、图标底色、大面积标题条上；它只属于主操作与当前态。
- **Don't** 新加蓝色控件。查询按钮的 `#409EFF` 与左栏当前卡的 `#3B82F6` 环是遗留漂移，不要照抄进新页面。
- **Don't** 把登录页的玻璃拟态、渐变、光晕带进控制台；暗色只属于登录。
- **Don't** 给静态容器加 hover 上浮或把 `transition: all` 写进卡片（会让网格换列时逐帧重排）。
- **Don't** 用 `#909399` 或更浅的灰承载正文与说明。
- **Don't** 把页头包进白卡，或在内容区重复放一个「新建」。
- **Don't** 在普通业务卡上使用 12px 以上圆角；12px 属于对话，20px 属于登录。
