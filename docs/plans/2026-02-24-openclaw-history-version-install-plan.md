# OpenClaw 历史版本安装 Implementation Plan

**Goal:** 在设置页的 OpenClaw 卡片中支持历史版本安装与切换，避免每次点击都请求网络，满足“安装历史版本、优先打开已安装版本”的需求。

**Architecture:**
- 采用 `AgentSelectionFragment` + `AlertDialog` 列表弹窗 + `RbotService` 既有更新链路。
- 安装/更新动作统一走 `RbotService.updateOpenclaw(targetVersion)`。
- 版本来源通过 shell 调用 `npm view openclaw versions --json`，优先读取本地缓存（TTL 1小时），缓存过期才重新请求。
- 列表按稳定版过滤（排除 `latest`/含 `-` `/`+`）并取最近 20 条。

**Tech Stack:**
- Java (Android)
- `AgentSelectionFragment`、`RbotService`
- SharedPreferences（仅用于保存最近一次手动选择结果，可选）
- 缓存：SharedPreferences（版本列表与时间戳）

## 当前交付状态（更新）

- [x] 版本入口已从 Dashboard 移至 `AgentSelectionFragment` OpenClaw 卡片，作为次要操作入口。
- [x] 版本列表弹窗实现（最近 20 个稳定版本）。
- [x] 点击版本行为完成：
  - 已安装版本：直接打开 Dashboard。
  - 未安装版本：确认后进行安装。
  - 已安装但不同版本：确认后进行 in-place 更新。
- [x] 版本列表增加本地缓存：
  - 缓存键：版本列表 JSON + 缓存时间戳。
  - TTL：1 小时。
  - 请求失败时回退到本地缓存（若存在）。
- [x] 版本管理按钮改为卡片右上角“管理”位（更低优先级、减少干扰）。
- [x] 版本按钮尺寸与布局进行了缩放（变更中/低占位）。

## 设计与实现范围（排除项）
- 不做自动回退，不做兼容性矩阵。
- 不改动安装脚本与 OpenClaw 镜像源策略。
- 不新增复杂权限与后台调度逻辑。

## 任务清单

### Task 1：入口与交互（AgentSelection）

**Files:**
- `app/src/main/res/layout/fragment_rbot_agent_select.xml`
- `app/src/main/java/app/rbot/AgentSelectionFragment.java`

- [x] 将“版本管理”入口从正文按钮区下沉为卡片右上角次要入口（更不抢眼）。
- [x] 版本按钮缩小（非主操作尺寸）。
- [x] 已安装与未安装状态下的点击分流：
  - 已安装当前版本：直接打开。
  - 已安装其他版本：弹确认执行更新。
  - 未安装：弹确认执行安装。

### Task 2：版本列表能力

**Files:**
- `app/src/main/java/app/rbot/AgentSelectionFragment.java`

- [x] `fetchOpenclawVersions()` 调用 `npm view openclaw versions --json`。
- [x] 支持 JSON 数组和文本兜底解析，过滤非稳定版（`-`/`+`）并去重。
- [x] 语义排序后取最近 20 个版本。

### Task 3：版本列表缓存（加速）

**Files:**
- `app/src/main/java/app/rbot/AgentSelectionFragment.java`

- [x] 新增 SharedPreferences 版本缓存：
  - `KEY_OPENCLAW_VERSION_CACHE`
  - `KEY_OPENCLAW_VERSION_CACHE_TIME`
- [x] TTL 机制：1 小时内命中缓存直接返回。
- [x] 网络失败回退到缓存（有缓存时）。

### Task 4：验收

- [x] `./gradlew installDebug` 成功安装并在手机上启动验证。
- [x] 版本入口行为与按钮视觉调整已同步部署。
- [ ] 记录 UX 优化项（可选）：`Versions` 文案改为 `管理` 或 icon-only，继续降低干扰。

## 变更清单（本轮）

- [x] `app/src/main/res/layout/fragment_rbot_agent_select.xml`
  - 入口位置从同行按钮改为卡片右上角。
  - 版本按钮尺寸缩小。
- [x] `app/src/main/java/app/rbot/AgentSelectionFragment.java`
  - 版本列表逻辑迁移并完成缓存、排序、过滤。
  - 点击后分流到打开/安装/更新。
