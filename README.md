# BotDrop

在 Android 手机上运行 AI Agent —— 无需终端，一键部署。

BotDrop 将 [AstrBot](https://github.com/Soulter/AstrBot) 封装为友好的 Android 应用，安装、配置、管理全程 GUI 操作。

## 特性

- **一键安装** —— 自动配置 Linux 环境并安装 AstrBot
- **多平台接入** —— Telegram、Discord、QQ、微信等
- **多 AI 供应商** —— OpenAI、Anthropic、Google Gemini 等
- **后台保活** —— 自动重启，稳定运行
- **开机自启** —— 无需手动启动
- **需要 Root** —— 使用 chroot 原生运行，零性能损耗

## 安装

### 下载 APK

从 [Releases](../../releases) 下载最新 APK。

### 从源码构建

前置要求：
- Android SDK（compileSdk 36）
- NDK r29+
- JDK 17

```bash
git clone https://gitee.com/deku772/botdrop-android.git
cd botdrop-android
git checkout root
./gradlew assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/`。

## 架构

BotDrop 使用 chroot 直接运行 Ubuntu rootfs，无需 Termux 和 proot 中间层。

```
┌──────────────────────────────────┐
│     BotDrop UI（app.botdrop）     │
├──────────────────────────────────┤
│     chroot Ubuntu rootfs          │
├──────────────────────────────────┤
│  AstrBot + Python3 + pip          │
└──────────────────────────────────┘
```

### 存储布局

```
/data/botdrop/                        ← chroot 根目录（内部存储 ext4）
├── bin/                              ← 系统二进制（符号链接正常）
├── usr/                              ← Python3, pip, git 等
├── home/astrbot/                     ← AstrBot 配置与数据
└── ...

/storage/emulated/0/botdrop/          ← sdcard（用户可见，方便备份）
└── cache/
    └── ubuntu22_openclaw.tar.gz      ← rootfs 下载缓存（重装保留）
```

- **内部存储**：rootfs 运行时（需要符号链接，必须 ext4）
- **sdcard 缓存**：大文件下载（~809MB rootfs），重装环境时保留，不用重新下载

### 安装流程

| 步骤 | 说明 |
|------|------|
| 第 1 步 | 检测 Root 权限 |
| 第 2 步 | 下载并解压 Ubuntu rootfs |
| 第 3 步 | 安装 AstrBot |
| 第 4 步 | 启动服务 |

**rootfs 下载回退顺序：**
1. 本地文件：`/storage/emulated/0/claw-apk/ubuntu22_openclaw.tar.gz`
2. sdcard 缓存：`/storage/emulated/0/botdrop/cache/ubuntu22_openclaw.tar.gz`
3. GitHub：`TermuxCHN/rootfs`（下载到 sdcard 缓存供后续使用）

本地文件成功后会自动缓存到 sdcard，重装时跳过下载。

## 分支说明

| 分支 | 说明 |
|------|------|
| `root` | 主分支 —— chroot 方案（需要 Root） |
| `no-root` | Termux + proot 方案（无需 Root） |

## 贡献

参见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可证

本项目基于 [GNU General Public License v3.0](LICENSE) 开源。

基于 [Termux](https://github.com/termux/termux-app)（GPLv3）构建。
