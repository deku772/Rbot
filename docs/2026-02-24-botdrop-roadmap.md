# Rbot Roadmap

更新日期：2026-02-24

## 优先级总览

```
P0（当前冲刺）  Shizuku 收尾 + 快速改善（并行推进）
                 │
                 ├─ Shizuku 分支收尾 → 合并 master
                 │   ├─ Root 路径测试
                 │   ├─ APK 包体优化（200MB → <140MB）
                 │   ├─ Shizuku UI 调优
                 │   ├─ UIAutomator 精确度提升
                 │   └─ 集成 sharp 库
                 │
                 ├─ OpenClaw 版本管理（难度低，完成快）
                 │   ├─ 历史版本安装
                 │   └─ 兼容性矩阵
                 │
                 └─ 多 IM 接入 + 配置优化（难度低，完成快）
                     ├─ 飞书/Slack adapter
                     └─ 配置向导优化

P1（下一迭代）  能力边界探索
                 ├─ ADB/Root 自动化能力边界调研与封装
                 └─ Termux 包生态调研（自建源 POC）

P2（中期）      产品体验升级
                 ├─ 应用内聊天界面（类豆包）
                 ├─ 免费 LLM API 试用额度
                 └─ Accessibility Service 自动化

P3（远期）      商业化
                 ├─ API 代理后端
                 ├─ 工作流模板（基础免费 + 定制付费）
                 └─ 高级 skill 订阅服务
```

---

## 一、近期（P0 — 当前冲刺，三线并行）

### A. Shizuku 分支收尾

分支：`feat/shizuku-single-app-merge`，目标：合并 master。

#### 1.1 Root 路径测试

- 状态：**未开始**
- `su -c app_process` 启动 Shizuku 的链路尚未验证。
- 需要在 root 设备上完成端到端测试：启动 → binder 握手 → bridge 可用 → openclaw 调用成功。

#### 1.2 APK 包体优化

- 状态：**未开始**
- 当前约 200MB，目标 < 140MB（先期目标 < 160MB）。
- 动作项：
  - 移除非目标 ABI（`x86`/`x86_64`），仅保留 `armeabi-v7a`/`arm64-v8a`；
  - 清理未使用的资源、语言包、重复矢量/位图；
  - 收敛 R8 / ProGuard keep 规则；
  - 大体积 native 组件改为按需加载（如 `ffmpeg`、终端重资源）；
  - 每轮优化后记录 APK 容量曲线。

#### 1.3 Shizuku UI 调优

- 状态：**未开始**
- 当前界面直接复制自官方 Shizuku Manager，需改为 Rbot 风格。
- 动作项：
  - 重构文案、图标、按钮布局为 Rbot 统一风格；
  - 合并官方 Home 与内置 `ShizukuStatusActivity` 入口逻辑；
  - 状态从弹窗改为 Dashboard 内嵌片段（运行态、权限态、启动方式、最近错误、重试入口）；
  - 增加首屏引导与故障指引卡片。
- 验收：从"启动 Shizuku"到"执行 openclaw 权限动作"不超过 3 次跳转。

#### 1.4 UIAutomator 精确度提升

- 状态：**未开始**
- 问题：截图与控件树不一致，部分机型返回缓存图。
- 动作项：
  - 截图 + 控件树采集加入版本戳校验；
  - 缓存去重与短期失效策略；
  - 二级数据源兜底（`window/contentchange` 后再采集）；
  - 厂商白名单降级策略（小米/华为/三星）。

#### 1.5 集成 sharp 库

- 状态：**未开始**
- 增强 agent 截图分析能力，用于 UI 识别与图片处理。

### B. OpenClaw 版本管理（难度低，完成快）

**问题**：OpenClaw 频繁升级，用户希望能安装历史版本以避免兼容问题。

**方案**：采用最小实现，仅提供“历史版本选择安装”能力，快速落地：

- 在 Dashboard 增加 "OpenClaw 版本管理" 入口；
- 显示当前已安装版本；
- 拉取可安装版本列表（优先从 `npm view openclaw versions --json`）；
- 允许用户选择历史版本并一键安装：`npm install -g openclaw@x.y.z`；
- 安装后刷新版本显示。

#### 2 小时可交付版（最小任务）

1) 视图与入口（约 35 分钟）
- [ ] `DashboardActivity` 新增“OpenClaw 版本管理”按钮（复用现有 `openclaw_version_text` 位置）。
- [ ] 弹出版本选择弹窗（`AlertDialog`/`BottomSheet` 均可），显示：标题、`loading`、`错误重试`、版本列表。
- [ ] 在 `activity_rbot_dashboard.xml` 增加/复用按钮容器（不改现有交互流程）。

2) 版本数据与安装（约 50 分钟）
- [ ] 新建一个小工具方法读取版本列表：
  - 优先执行 `npm view openclaw versions --json`；
  - 解析后按 semver 降序展示；
  - 失败时 fallback 到 `["openclaw@latest", 当前已安装版本]`。
- [ ] 点击版本项时弹出确认文案：`将安装 openclaw@x.y.z`。
- [ ] 使用 `RbotService.updateOpenclaw("openclaw@x.y.z")` 执行安装。
- [ ] 安装过程中复用现有 `dialog_openclaw_update` 进度弹窗显示进度。

3) 收口与防抖（约 20 分钟）
- [ ] 防止重复点击：安装按钮并发保护（点击禁用/状态锁）。
- [ ] 成功后更新 `openclaw_version_text` 与状态提示。
- [ ] 统一错误提示文案（安装失败 / 版本列表失败 / 无可用版本）。
- [ ] 与现有 OpenClaw 更新弹窗冲突检查：避免同时打开两个弹窗（必要时禁用刷新触发）。

- 验收标准（最小）
  - [ ] 用户可打开 Dashboard -> OpenClaw 版本管理 -> 看到历史版本列表；
  - [ ] 用户可点任意版本并触发安装；
  - [ ] 安装成功后版本文本更新为新版本；
  - [ ] 失败时有明确错误提示，不会导致 Dashboard 卡死。

### C. 多 IM 接入 + 配置优化（难度低，完成快）

#### 接入规划

| IM | 状态 | 备注 |
|---|---|---|
| Telegram | 已支持 | 配置流程优化 |
| Discord | 已支持（基础） | 补全交互体验 |
| 飞书（Feishu/Lark） | 待开发 | OpenClaw 侧需增加 channel adapter |
| 微信（企业微信） | 待调研 | API 限制较多，可能需要 webhook 模式 |
| Slack | 待开发 | 标准 Bot API，难度低 |

#### 配置体验优化

- 向导式配置：选 IM → 填 token → 一键测试 → 完成；
- 配置模板预存：切换 IM 不丢失历史配置；
- 多 channel 同时运行：一个 agent 同时连多个 IM；
- 二维码扫描配置（简化长 token 输入）。

---

## 二、中期（P1 — 能力边界探索）

### 2.1 ADB/Root 自动化能力边界

**核心问题**：通过 OpenClaw 调用 ADB/Root 操控手机，天花板在哪里？达到天花板需要做什么？

#### 能力边界

| 层级 | 能力 | 限制 |
|---|---|---|
| **ADB shell（无 root）** | `input tap/swipe/text`、`am start`、`pm install`、`uiautomator dump`、`screencap`、`settings put`、文件读写（公共目录） | 无法访问 app 私有数据、无法修改系统分区、无法绕过权限对话框、部分厂商锁 |
| **Shizuku（ADB 权限）** | 上述 + binder 级 API（`IActivityManager`、`IPackageManager`、`IInputManager`）、隐藏 API 调用、跨进程注入 | 仍受 SELinux 约束、无法修改系统文件、无 root 级文件访问 |
| **Root** | 全部系统权限、Magisk 模块、Xposed/LSPosed hook、系统级 DNS/防火墙/代理 | 硬件级限制（TEE/TrustZone）、部分 DRM 内容、银行/支付 App 的 root 检测 |
| **Accessibility Service** | 无 root 下的 UI 自动化、节点读取/点击/输入、全局手势 | 部分 App 屏蔽节点信息、性能开销、系统安全界面不可操作 |

#### 达到边界需要做的事

1. 完善 ADB 命令封装层（`input`、`am`、`pm`、`content`、`wm`、`dumpsys`）；
2. Shizuku hidden API 调用能力（通过反射 + IPC）；
3. 截图 + OCR / 视觉识别融合（sharp + LLM 多模态分析）；
4. Accessibility Service 作为无 root 补充路径（参考 [design-android-automation.md](design-android-automation.md)）；
5. Root 场景下的 Magisk 模块集成能力；
6. 多方案自动降级：Root → Shizuku → ADB → Accessibility。

### 2.2 Termux 包生态扩展（自建源调研）

**问题**：OpenClaw 的各种 skills 依赖外部工具（`ffmpeg`、`imagemagick`、`pandoc`、`python` 生态等），当前缩小版 Termux 覆盖不足，需要达到接近 Linux 的水平。

#### 需要调研的两个方向

| 方向 | 核心问题 | 调研内容 |
|---|---|---|
| **Termux 能否达到 Linux 水平** | proot 环境的能力边界 | syscall 兼容性、内核限制（无法加载内核模块、无 systemd）、文件系统差异、网络栈限制、多进程/daemon 支持 |
| **自建 Termux 源的难度** | 维护成本与可行性 | Termux 包构建体系（`termux-packages` 仓库）、交叉编译工具链、包签名与分发、CI/CD 搭建、跟上游同步策略 |

#### 可能的路径

- **轻量路径**：fork `termux-packages`，仅补充 OpenClaw skills 需要的包，托管在自有 CDN / GitHub Releases；
- **中等路径**：搭建完整自建源（`apt` 仓库），持续维护 + 自动构建；
- **重度路径**：用 proot-distro 挂载完整 Linux 发行版（Ubuntu/Alpine），绕过 Termux 包限制。

---

## 三、远期（P2/P3）

### 3.1 内置免费体验 + 应用内聊天

**目标**：降低使用门槛，让用户无需自备 API Key、无需外部 IM 即可体验。

| 项 | 说明 |
|---|---|
| **免费 LLM API 额度** | Rbot 提供免费试用额度（代理调用 Claude/GPT），用户无需注册第三方 API Key 即可开始 |
| **应用内聊天窗口** | 不依赖 Telegram/Discord，直接在 App 内与 agent 对话（类似豆包 / ChatGPT App 的界面） |
| **混合模式** | 内置聊天 + 外部 IM 可同时工作；内置聊天用于调试/快速测试，IM 用于日常使用 |

**架构影响**：

- 后端服务：API 代理层（限流、计费、用户管理）；
- OpenClaw 需支持 "local" channel（App 内 WebSocket / 本地 IPC）；
- UI 层：聊天界面（消息列表、输入框、图片/文件发送、历史记录）；
- 商业模式：
  - 免费 LLM API 额度 + 付费升级 / 用户自带 Key；
  - 预置常见工作流模板（定时任务、数据采集、消息转发等），免费提供基础版，定制优化版付费；
  - 高级 skill 订阅服务（如专业级 UI 自动化、多 App 联动、数据分析等），按月/按量订阅。

### 3.2 Accessibility Service 无 Root 自动化

- 已有设计文档：[design-android-automation.md](design-android-automation.md)
- 选择器驱动 API（`/ui/tree`、`/ui/find`、`/ui/action`、`/ui/wait`）；
- 作为 Shizuku/ADB 的补充和降级方案。

---

_本文档将随项目进展持续更新。_
