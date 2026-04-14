# Rbot 用户指引：Shizuku 配对与使用（单 App）

> 适用版本：`feat/shizuku-single-app-merge`

本指南面向用户，说明在 Rbot 单 App 内完成 Shizuku 配对、启动和使用 OpenClaw 的流程。

## 1. 你需要先确认的前置条件

- 设备已安装 Rbot（含内嵌 Shizuku 集成）。
- 如使用 ADB 模式，请确保系统允许开发者模式并打开相关 adb 开关（不同系统不同）。
- 若手机有 root，建议开启 root；否则走 ADB 方式。
- 首次启动前请允许 Rbot 的安装和权限申请弹窗。

## 2. 一键上手（从 Dashboard 开始）

1. 打开 Rbot。
2. 进入 Dashboard，确认能看到 Shizuku 区域（`Open Shizuku` / `Request Shizuku Permission`）。
3. 点击 `Open Shizuku`。
4. 在弹出的 Shizuku 状态页中，先查看：
   - Shizuku 服务是否已就绪（Binder）
   - 当前权限是否已授权
5. 点击 `Bootstrap Shizuku`（可选，或等待自动重试）。
6. 点击 `Request Shizuku Permission`，在弹窗中授予权限。

## 3. 什么是“配对”

在本仓库的单 App 形态中，配对指：

- Rbot 能够拿到 Shizuku 的 Binder；
- 本应用在 Shizuku 权限里被授权；
- openclaw 的桥接能力可向 Rbot 发起提权执行请求。

## 4. 启动流程（你要看到的状态）

### 4.1 自动启动

Rbot 启动时会尝试初始化 Shizuku runtime，流程包括：

- 请求 Provider binder
- 检查 Binder 是否 ready
- 失败时尝试启动（先 root，后 ADB）

### 4.2 手动启动

如自动启动失败，可回到 Dashboard → `Open Shizuku` → `Bootstrap Shizuku`。

### 4.3 何时算启动成功

- Shizuku Binder 可达；
- 权限请求成功后 `Permission` 状态显示已授权；
- 可正常读写 `~/.openclaw/shizuku-bridge.json`。

## 5. 开始使用（openclaw）

在已配对后，你可以：

- 启动 Gateway；
- 使用 OpenClaw 相关命令；
- 让 openclaw 在需要系统能力时走本地 bridge（127.0.0.1:18790）。

注意：

- openclaw 常规 CLI 命令通常由 Termux 本地执行；
- 需要系统能力的命令会通过内部 bridge 转给 Shizuku 执行；
- 系统能力执行失败时会返回明确错误，不会静默 fallback。

### 5.1 给 OpenClaw 的提示词：如何正确调用 Shizuku

如果你要让 OpenClaw 自动决定是否走 Shizuku 权限执行，建议在 OpenClaw 的系统提示词（System Prompt）里加这段规则：

> 你可以在执行可能需要系统权限的操作前，优先走本地 `Shizuku bridge`。  
> 
> 1. 读取 `~/.openclaw/shizuku-bridge.json`，取 `host`、`port`、`token`。  
> 2. 对于需要系统级执行的命令，调用 `POST http://{host}:{port}/shizuku/exec`。  
> 3. 必须带 `Authorization: Bearer {token}`，并在请求体里放 JSON `{"command":"...","timeoutMs":30000}`。  
> 4. 只把结果（`exitCode/stdout/stderr/mode/fallbackReason`）用于决策，不要在未授权或不可达时重试无意义本地路径。  

#### 示例模板（可直接贴给 OpenClaw）

```text
你在当前设备上只能通过本地 Shizuku bridge 执行系统级命令。
流程：
1) 先检查 ~/.openclaw/shizuku-bridge.json：
   {"host":"127.0.0.1","port":18790,"token":"<bridge-token>"}
2) 调用：
   curl -sS -X POST http://127.0.0.1:18790/shizuku/exec \
     -H "Authorization: Bearer <bridge-token>" \
     -H "Content-Type: application/json" \
     -d '{"command":"<要执行的 shell 命令>","timeoutMs":30000}'
3) 若响应 ok=false 或 stderr 提示 shizuku unavailable，就返回失败给用户，不做本地强行降级。
4) 仅当 openclaw 的标准 CLI 足够时，使用本地命令，不强制走 shizuku。
```

#### 什么时候强制走 Shizuku

以下场景建议走 bridge：
- 你在做设备设置/权限/系统信息读取，且普通 shell 可能受限；
- 你要执行 `pm`、`settings`、`dumpsys`、`service call` 等命令；
- 你需要在其它应用上下文执行“触控/输入/启动组件”类操作。

以下场景不必走 Shizuku：
- 读写 `~/.openclaw`、网关启动脚本、下载模型、普通应用日志、与 OpenClaw 自身配置相关命令。

## 6. 常见问题与排查

### 情况：`Shizuku already granted, precheck failed`

通常表示绑定/权限状态已 OK，但预检命令执行失败。优先检查 openclaw 命令本身是否可执行（wrapper、路径、依赖）。

### 情况：`Shizuku permission not granted`

表示未授权或授权失效。重新进入 `Shizuku` 状态页，执行权限请求。

### 情况：`shizuku execution unavailable`

表示桥接通道不可用（binder/权限/配置任一异常）。建议按顺序检查：

1. Shizuku binder 状态；
2. 是否已授权；
3. 配置文件 `~/.openclaw/shizuku-bridge.json` 是否存在且 token 有效；
4. 重启 Shizuku 与 Gateway。

## 7. 一次性快速验收清单

1. 重新启动 Rbot 后，打开 `Open Shizuku`；
2. 完成权限请求；
3. 检查状态页显示已授权；
4. 启动 Gateway；
5. 打开或执行一次 openclaw 预检命令，确认返回正常。

都通过后，即可认定 Shizuku 配对-启动-使用链路已打通。

## 8. 附：日志建议

如需提 bug，可附上：

```bash
adb logcat -s ShizukuBootstrap ShizukuStatus ShizukuBridgeService ShizukuBridgeServer ShizukuShellService RbotService:V
```

并补充：

- 是否是 root 启动还是 ADB 启动；
- 是否在 Dashboard 里点击过 `Request Shizuku Permission`；
- `~/.openclaw/shizuku-bridge.json` 的时间戳与内容（可脱敏后）。
