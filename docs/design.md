# Rbot Android GUI 设计文档

> 目标：用户从安装到运行 OpenClaw，全程 **零命令行**
> 核心哲学：**App GUI 只负责点火，配置交给 AI 自己完成**

## 核心理念

App 做最少的事：装好 OpenClaw → 填 API Key → 连一个频道 → 启动。
之后所有进一步配置（加频道、调模型、装 skill 等）用户直接跟 **Rbot 的 TG bot / DC bot 聊天**完成。

不做自己的 Web UI，不复制 OpenClaw control-ui。

## 当前流程（需要终端）

```
安装 APK → 打开 → Termux 终端 → 自动跑 first-run.sh
→ 手动 `openclaw onboard` → CLI 向导
→ 手动 `openclaw gateway start`
```

## 目标流程（纯 GUI）

```
安装 APK → 打开 → 自动安装（进度条，无终端）
→ 选 Provider + Auth → 用 @RbotSetupBot 连频道（或手动）→ 启动
→ 之后跟自己的 bot 聊天做后续配置
```

---

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│  App 启动 (RbotLauncherActivity)                            │
│  ↓                                                          │
│  检查状态:                                                    │
│  - bootstrap 解压完？ → 否 → 等 TermuxInstaller               │
│  - openclaw 装好了？  → 否 → SetupActivity (自动安装)         │
│  - 配好 API Key？     → 否 → SetupActivity (填 key)          │
│  - 配好频道？         → 否 → SetupActivity (连频道)           │
│  - 全部就绪          → DashboardActivity                     │
└─────────────────────────────────────────────────────────────┘
```

### 组件清单

| 组件 | 类型 | 职责 |
|------|------|------|
| `RbotLauncherActivity` | Activity | 启动路由 |
| `SetupActivity` | Activity + ViewPager2 | 3 步向导 |
| `DashboardActivity` | Activity | 极简状态面板 |
| `RbotService` | Foreground Service | 后台运行 gateway + 命令执行 |
| `TermuxActivity` | (保留) | 高级入口，从 Dashboard 进 |

---

## 向导页面 (SetupActivity) — 只有 3 步

### Step 1: Welcome + 自动安装

```
┌──────────────────────────────┐
│                              │
│         🦉                   │
│      Rbot                 │
│                              │
│  Your AI assistant,          │
│  running on your phone.      │
│                              │
│  ━━━━━━━━━━━━━━━━━━━━━━━━   │
│                              │
│  ✓ Environment ready         │
│  ● Installing OpenClaw...    │
│                              │
│  This takes about a minute   │
│                              │
└──────────────────────────────┘
```

- **Welcome + 安装合并为一页**
- 打开即自动开始（不需要点 "Get Started"，减少一次点击）
- 后台静默执行：chmod → 验证 node/npm → npm install openclaw
- 进度步骤用 ✓/●/○ 显示
- 安装完成自动滑到下一步
- 失败 → 显示 "Retry" 按钮 + 可展开的错误详情

### Step 2: AI Provider + Auth

OpenClaw 支持多种认证方式，App 需要对应处理：

| 认证类型 | Provider 举例 | App 处理方式 |
|---------|-------------|------------|
| **API Key**（粘贴） | Anthropic, OpenAI, OpenRouter, Gemini, Kimi, Venice, MiniMax | 文本输入框 |
| **Setup Token**（粘贴） | Anthropic `claude setup-token` | 文本输入框（不同提示文案） |
| **OAuth（浏览器）** | OpenAI Codex, Google, Qwen, Chutes | 跳浏览器 → 回调 deep link |
| **Device Flow** | GitHub Copilot | 显示 code + URL，用户手动验证 |

**UI 设计 — 选择 Provider：**

```
┌──────────────────────────────┐
│  ← Step 2/3                  │
│                              │
│  Choose your AI              │
│                              │
│  Popular                     │
│  ┌────────────────────────┐  │
│  │ ◉ Anthropic (Claude)   │  │
│  │   Setup Token or Key   │  │
│  └────────────────────────┘  │
│  ┌────────────────────────┐  │
│  │ ○ OpenAI               │  │
│  │   ChatGPT login or Key │  │
│  └────────────────────────┘  │
│  ┌────────────────────────┐  │
│  │ ○ Google (Gemini)      │  │
│  │   API key or OAuth     │  │
│  └────────────────────────┘  │
│  ┌────────────────────────┐  │
│  │ ○ OpenRouter           │  │
│  │   API key (any model)  │  │
│  └────────────────────────┘  │
│                              │
│  More providers ▼            │
│  (Kimi, MiniMax, Venice...) │
│                              │
└──────────────────────────────┘
```

**选 Anthropic → 选认证方式：**

```
┌──────────────────────────────┐
│  ← Anthropic                 │
│                              │
│  How to connect?             │
│                              │
│  ┌────────────────────────┐  │
│  │ ⭐ Setup Token          │  │
│  │   Easiest — paste from  │  │
│  │   claude.ai/settings    │  │
│  └────────────────────────┘  │
│  ┌────────────────────────┐  │
│  │    API Key              │  │
│  │   From console.         │  │
│  │   anthropic.com         │  │
│  └────────────────────────┘  │
│                              │
└──────────────────────────────┘
```

**Setup Token 输入：**
```
┌──────────────────────────────┐
│  ← Anthropic Setup Token     │
│                              │
│  1. Open claude.ai/settings  │
│     → "Setup Token"          │
│  2. Copy the token           │
│  3. Paste below              │
│                              │
│  Token                       │
│  ┌────────────────────────┐  │
│  │ stp_••••••••••••••     │👁│
│  └────────────────────────┘  │
│                              │
│  [  Verify & Continue  ]     │
│                              │
│  ✓ Connected!                │
│    Model: claude-sonnet-4-5  │
└──────────────────────────────┘
```

**选 OpenAI → OAuth 流程：**
```
┌──────────────────────────────┐
│  ← OpenAI                    │
│                              │
│  ┌────────────────────────┐  │
│  │ ⭐ Sign in with ChatGPT │  │
│  │   Use your OpenAI       │  │
│  │   account (OAuth)       │  │
│  └────────────────────────┘  │
│  ┌────────────────────────┐  │
│  │    API Key              │  │
│  │   From platform.        │  │
│  │   openai.com            │  │
│  └────────────────────────┘  │
│                              │
└──────────────────────────────┘

点 "Sign in with ChatGPT" →
┌──────────────────────────────┐
│                              │
│  Opening browser for         │
│  OpenAI sign-in...           │
│                              │
│  ●●● Waiting for auth        │
│                              │
│  Complete the login in your  │
│  browser, then come back.    │
│                              │
└──────────────────────────────┘
→ 打开系统浏览器 → OAuth 回调 → 自动返回 App
```

**OAuth 回调技术要点：**
- 注册 deep link scheme: `rbot://oauth-callback`
- OpenClaw OAuth flow 会启动 localhost server 监听回调
- Android 上需要拦截 `http://127.0.0.1:1455/oauth-callback` 或 `http://127.0.0.1:1456/oauth-callback`
- 或者让 openclaw 直接处理（在 proot 环境里跑 OAuth flow，打开浏览器用 `termux-open-url`）

**最简方案：调用 `openclaw auth` CLI**
- 部分 auth 流程（尤其 OAuth）在 CLI 里已经实现完整
- App 可以在后台启动一个 headless terminal session 跑 `openclaw auth set`
- 需要浏览器时调用 `termux-open-url`
- 这样不需要在 Java 侧重新实现每种 auth 逻辑

### Step 3: Connect a Channel — @RbotSetupBot 辅助

**核心思路**：运维一个官方 **@RbotSetupBot**（TG + DC），帮用户完成最卡人的步骤。

**为什么需要 Helper Bot：**
- 创建 bot、拿 token、找 user ID 这三步对新手很卡
- Helper Bot **自动检测 user ID**（用户发消息就能拿到）
- 引导式对话比文档/截图更友好
- 最终生成一个 **setup code** 给用户粘贴到 App

**流程：**

```
┌──────────────────────────────┐
│  ← Step 3/3                  │
│                              │
│  Connect a chat platform     │
│                              │
│  ┌────────────────────────┐  │
│  │ ⭐ Use @RbotSetupBot   │  │
│  │   Guided setup (easy)   │  │
│  └────────────────────────┘  │
│                              │
│  ┌────────────────────────┐  │
│  │   Set up manually       │  │
│  │   I have a bot token    │  │
│  └────────────────────────┘  │
│                              │
└──────────────────────────────┘
```

**选 @RbotSetupBot：**
```
┌──────────────────────────────┐
│  Setup via @RbotSetupBot    │
│                              │
│  Which platform?             │
│                              │
│  ┌────────────────────────┐  │
│  │ 📱 Telegram             │  │
│  │  → Open @RbotSetupBot  │  │
│  └────────────────────────┘  │
│  ┌────────────────────────┐  │
│  │ 💬 Discord              │  │
│  │  → Invite Setup Bot     │  │
│  └────────────────────────┘  │
│                              │
│  After setup, paste your     │
│  code below:                 │
│                              │
│  Setup Code                  │
│  ┌────────────────────────┐  │
│  │ BOTDROP-xxxxxxxxxxxx     │  │
│  └────────────────────────┘  │
│                              │
│  [  Connect & Start  ]       │
│                              │
└──────────────────────────────┘
```

**@RbotSetupBot 对话流程（Telegram 示例）：**

```
User: /start
Bot:  🦉 Welcome to Rbot Setup!
      I'll help you set up your personal AI bot.
      
      First, let's create your bot:
      1. Open @BotFather
      2. Send /newbot
      3. Choose a name and username
      4. Copy the token and send it to me

User: 7123456789:AAF8x...（粘贴 token）

Bot:  ✅ Got it!
      
      Your info:
      • Bot token: 7123456...✓
      • Your Telegram ID: 987654321 (auto-detected)
      
      Your setup code:
      ╔════════════════════════════╗
      ║  BOTDROP-tg-A7x9Kp2mB4...  ║
      ╚════════════════════════════╝
      
      → Go back to the Rbot app and paste this code.
      
      (Code expires in 10 minutes)
```

**Setup Code 内容（base64 编码的 JSON）：**
```json
{
  "v": 1,
  "platform": "telegram",
  "bot_token": "7123456789:AAF8x...",
  "owner_id": "987654321",
  "created_at": 1738764000
}
```

App 解码后直接写入 openclaw.json 的 channels 配置。

**Helper Bot 的好处：**
- User ID **零输入**（bot 自动从 message.from.id 拿到）
- Token 输入有 **即时验证**（bot 可以调 TG API 验证 token 有效性）
- 引导文案在 **聊天界面** 里，用户不用切来切去看文档
- 错误可以 **对话式排查**（"这个 token 看起来不对，确认是从 @BotFather 拿的吗？"）

**手动模式（高级用户）保留：**

```
┌──────────────────────────────┐
│  Manual Setup                │
│                              │
│  Platform                    │
│  ┌────────────────────────┐  │
│  │ 📱 Telegram          ▼ │  │
│  └────────────────────────┘  │
│                              │
│  Bot Token                   │
│  ┌────────────────────────┐  │
│  │ 123456:ABC-DEF...      │  │
│  └────────────────────────┘  │
│                              │
│  Your User ID (owner)        │
│  ┌────────────────────────┐  │
│  │ 987654321               │  │
│  └────────────────────────┘  │
│                              │
│  [  Connect & Start  ]       │
│                              │
└──────────────────────────────┘
```

---

## Dashboard（极简）

```
┌──────────────────────────────┐
│  🦉 Rbot                  │
├──────────────────────────────┤
│                              │
│     ● Running                │
│     Uptime: 2h 15m           │
│                              │
│  ─────────────────────────   │
│                              │
│  📱 Telegram  ● Connected    │
│  💬 Discord   ○ —            │
│                              │
│  ─────────────────────────   │
│                              │
│  [🔄 Restart] [⏹ Stop]      │
│                              │
│  ─────────────────────────   │
│                              │
│  💡 Chat with Rbot on     │
│  Telegram to configure       │
│  more settings               │
│                              │
│  ┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈┈   │
│  [🖥 Open Terminal]           │
│                              │
└──────────────────────────────┘
```

- **极简**：状态 + 频道 + 重启/停止
- 核心引导文案：**"Chat with Rbot on Telegram to configure more"**
- "Open Terminal" 放底部，高级用户才需要
- 不做 Settings 页面 — 所有配置变更通过跟 bot 聊天完成
  - 用户："帮我加个 Discord"
  - Rbot："好的，把 bot token 给我"
  - → Rbot 修改 openclaw.json → 重启 gateway

---

## 技术实现

### 1. RbotService（后台命令执行 + Gateway 生命周期）

```java
public class RbotService extends Service {
    
    // 后台执行 shell 命令
    private CommandResult exec(String cmd) {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
        pb.environment().put("PREFIX", TERMUX_PREFIX_DIR_PATH);
        pb.environment().put("HOME", TERMUX_HOME_DIR_PATH);
        pb.environment().put("PATH", TERMUX_PREFIX_DIR_PATH + "/bin");
        pb.environment().put("TMPDIR", TERMUX_PREFIX_DIR_PATH + "/tmp");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // ... read output, wait for exit
    }
    
    // Step 1: 安装
    public void install(ProgressCallback cb) {
        cb.onStep(0, "Fixing permissions...");
        exec("chmod +x $PREFIX/bin/* && chmod +x $PREFIX/lib/node_modules/.bin/* 2>/dev/null");
        
        cb.onStep(1, "Verifying Node.js...");
        CommandResult r = exec("node --version && npm --version");
        if (!r.ok()) { cb.onError("Node.js not found"); return; }
        
        cb.onStep(2, "Installing OpenClaw...");
        r = exec("npm install -g openclaw@latest --ignore-scripts");
        if (!r.ok()) { cb.onError(r.stderr); return; }
        
        cb.onDone();
    }
    
    // Step 2: 验证 API Key
    public void verifyKey(String provider, String key, Callback cb) {
        // 用 curl 做轻量验证
        String cmd = buildVerifyCommand(provider, key);
        CommandResult r = exec(cmd);
        cb.onResult(r.ok(), parseModel(r.stdout));
    }
    
    // Step 3: 写配置 + 启动
    public void configureAndStart(Config config, Callback cb) {
        writeOpenclawJson(config);   // 直接写 JSON
        writeAuthCredentials(config); // openclaw auth set ...
        exec("termux-chroot openclaw gateway start --daemon");
        cb.onStarted();
    }
    
    // Gateway 控制
    public void restart() { exec("termux-chroot openclaw gateway restart"); }
    public void stop() { exec("termux-chroot openclaw gateway stop"); }
    public GatewayStatus status() { 
        return parseStatus(exec("termux-chroot openclaw gateway status --json")); 
    }
}
```

### 2. Auth 处理策略

**不在 Java 侧重新实现 auth 逻辑**。OpenClaw CLI 已经处理了所有 provider 的 auth 流程（API key、setup token、OAuth、device flow）。App 的 auth 步骤有两种实现方式：

**方式 A：简单情况（API Key / Setup Token）— App 直接处理**

```java
// API Key 类型：直接写文件
void setApiKeyAuth(String provider, String key) {
    // 调用 openclaw auth set
    exec("termux-chroot openclaw auth set " + provider + " " + shellEscape(key));
}
```

**方式 B：复杂情况（OAuth / Device Flow）— 委托给 openclaw CLI**

```java
// OAuth 类型：在后台启动 openclaw 的 auth 流程
// openclaw 内部会调用 termux-open-url 打开浏览器
// 用户在浏览器完成登录后，openclaw 自动拿到 token
void startOAuthFlow(String provider) {
    exec("termux-chroot openclaw auth set " + provider + " --interactive");
    // 监听 auth 完成事件
}
```

这样 App 侧的代码量最小，auth 逻辑的维护跟着 openclaw 走。

**配置写入**：

```json
{
  "agents": {
    "defaults": {
      "model": "anthropic/claude-sonnet-4-5",
      "workspace": "~/rbot"
    }
  },
  "channels": {
    "telegram": {
      "accounts": {
        "default": { "token": "<bot_token>" }
      },
      "bindings": [{ "account": "default", "agent": "main" }],
      "pairing": {
        "mode": "allowlist",
        "allowlist": ["<user_id>"]
      }
    }
  }
}
```

### 3. @RbotSetupBot 架构

```
┌──────────────────────────┐     ┌──────────────────────────┐
│  @RbotSetupBot (TG)     │     │  @RbotSetupBot (DC)     │
│  (轻量 Node.js bot)       │     │  (轻量 Node.js bot)       │
│                          │     │                          │
│  功能：                    │     │  功能：                    │
│  - 引导创建 bot            │     │  - 引导创建 bot            │
│  - 验证 bot token         │     │  - 验证 bot token         │
│  - 自动检测 user ID       │     │  - 自动检测 user ID       │
│  - 生成 setup code        │     │  - 生成 setup code        │
└──────────────────────────┘     └──────────────────────────┘
              │                               │
              └───────────┬───────────────────┘
                          │
                   Setup Code 格式
                   BOTDROP-{platform}-{base64_payload}
                          │
              ┌───────────┴───────────────────┐
              │  Rbot App (Android)         │
              │  解码 → 写 openclaw.json       │
              │       → 启动 gateway           │
              └───────────────────────────────┘
```

**Setup Code**：自包含，不需要服务端存储

```
BOTDROP-tg-eyJ2IjoxLCJ0IjoiNzEyMzQ1Njc4OTpBQUY4eC4uLiIsInUiOiI5ODc2NTQzMjEifQ==
       │    └─ base64({ "v":1, "t":"<bot_token>", "u":"<user_id>" })
       └─ platform: tg / dc
```

App 侧解码：
```java
void applySetupCode(String code) {
    // BOTDROP-tg-xxxxx → 解析 platform + decode base64
    String[] parts = code.split("-", 3);
    String platform = parts[1]; // "tg" or "dc"
    String payload = new String(Base64.decode(parts[2]));
    JSONObject data = new JSONObject(payload);
    
    String botToken = data.getString("t");
    String ownerId = data.getString("u");
    
    writeChannelConfig(platform, botToken, ownerId);
}
```

**Helper Bot 部署**：
- 独立的轻量 Node.js 项目（不是 OpenClaw 实例）
- 部署在我们的服务器上
- 开源（可以 self-host）
- 代码量很小：就是收集 token + 返回 setup code

### 4. 前台通知保活

Gateway 运行时通过 Android Foreground Service + 通知保活：

```
🦉 Rbot is running
   Connected to Telegram • Tap to manage
```

### 5. 状态检测 (RbotLauncherActivity)

```java
// 快速文件检测，不需要启动任何进程
boolean hasBootstrap = new File(PREFIX + "/bin/node").exists();
boolean hasOpenclaw = new File(PREFIX + "/lib/node_modules/openclaw").exists();
boolean hasConfig = new File(HOME + "/.config/openclaw/openclaw.json").exists();

if (!hasBootstrap) → 等待 TermuxInstaller
if (!hasOpenclaw)  → SetupActivity step 1
if (!hasConfig)    → SetupActivity step 2
else               → DashboardActivity (auto-start gateway if needed)
```

---

## Bot 辅助配置（后续功能）

用户跟 Rbot 聊天时可以做：

| 用户说 | Rbot 做 |
|--------|---------|
| "帮我加一个 Discord bot" | 要 token → 修改 openclaw.json → restart |
| "换成 GPT-4.5" | 修改 model → restart |
| "更新 OpenClaw" | `npm update -g openclaw` → restart |
| "看看日志" | 读取 gateway logs → 发送 |
| "重启一下" | `openclaw gateway restart` |

这些操作 Rbot 通过 `gateway` tool 和 `exec` tool 就能完成。
需要在 AGENTS.md / SOUL.md 里加入对应的指引。

---

## 文件结构

```
rbot-android/app/src/main/java/com/termux/app/rbot/
├── RbotLauncherActivity.java     # 启动路由
├── SetupActivity.java             # 3 步向导
├── steps/
│   ├── InstallFragment.java       # Step 1: Welcome + 自动安装
│   ├── ApiKeyFragment.java        # Step 2: Provider + API Key
│   └── ChannelFragment.java       # Step 3: Connect TG/DC
├── DashboardActivity.java         # 极简状态面板
├── RbotService.java              # 后台服务 (命令执行 + gateway)
└── RbotConfig.java               # 配置读写工具类
```

Layouts:
```
res/layout/
├── activity_launcher.xml          # 启动页 (splash)
├── activity_setup.xml             # ViewPager2 容器
├── activity_dashboard.xml         # 状态面板
├── fragment_install.xml           # 安装进度
├── fragment_apikey.xml            # API key 输入
└── fragment_channel.xml           # 频道连接
```

---

## 里程碑

### GUI-M0: 自动安装（不出现终端）
- [ ] `RbotLauncherActivity` + 状态检测
- [ ] `RbotService` 后台命令执行
- [ ] `SetupActivity` + ViewPager2
- [ ] `InstallFragment`（自动安装 + 进度）
- [ ] 修改 `AndroidManifest.xml`（launcher 改为 RbotLauncherActivity）
- [ ] 测试：首次启动 → 自动安装 OpenClaw → 无终端

### GUI-M1: Auth（多 provider 多方式）
- [ ] `AuthFragment`（provider 列表 + auth 方式选择）
- [ ] API Key / Setup Token 输入 + 验证
- [ ] OAuth flow（委托 openclaw CLI + `termux-open-url`）
- [ ] 配置写入（openclaw.json + auth credentials）
- [ ] 测试：各 provider 的 auth 方式都能跑通

### GUI-M2: @RbotSetupBot + 频道连接
- [ ] TG Helper Bot（Node.js，引导式对话 + 生成 setup code）
- [ ] DC Helper Bot（同上）
- [ ] `ChannelFragment`（输入 setup code 或手动填 token）
- [ ] Setup Code 解码 + 配置写入
- [ ] 写完配置后自动启动 gateway
- [ ] 测试：通过 helper bot 拿到 code → 粘贴 → bot 上线

### GUI-M3: Dashboard + 保活
- [ ] `DashboardActivity`（状态 + 频道 + 重启/停止）
- [ ] Foreground Service 通知保活
- [ ] App 杀掉后 gateway 继续运行
- [ ] "Open Terminal" 入口

### GUI-M4: 打磨
- [ ] Rbot 品牌 theme（颜色、icon）
- [ ] 错误处理细化 + 重试
- [ ] 深色模式
- [ ] 引导文案优化

---

## 设计原则

1. **最少步骤**：3 步搞定，不多问
2. **AI 做配置**：App 只点火，后续配置用户跟 bot 聊天完成
3. **不重复造轮子**：不做 Settings UI，不做 Web UI，配置变更全走 bot
4. **终端是后备**：高级用户从 Dashboard 底部进入
5. **后台常驻**：Gateway 以 daemon + Foreground Service 运行

---

## Development Notes

### Implementation Strategy
- Break work into small, testable milestones
- Follow the component architecture defined above
- Leverage existing Termux infrastructure where possible
- Test thoroughly at each stage
- Document any technical decisions or challenges encountered

---

_Created: 2026-02-05_
_Updated: 2026-02-07 (Cleaned for open-source release)_
_Status: Design complete, ready for implementation_
