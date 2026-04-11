# ANDBOTT

在 Android 上通过 chroot 一键部署 AstrBot 终端 —— 无需手动敲命令，GUI 全程搞定。

## 功能定位（2026-04 重构）

ANDBOTT 从全功能管理 app 精简为**一键终端部署壳子**，只做三件事：

1. **部署** —— 通过 chroot 运行 Ubuntu rootfs，安装 AstrBot
2. **启停** —— 一键启动/停止 AstrBot，后台保活
3. **日志** —— 实时查看 AstrBot 运行日志

> 如果你需要完整的管理界面（频道配置、模型管理、自动化等），推荐 [BotPocket](https://gitee.com/deku772/bot-pocket)（Kotlin + Compose + proot 方案）。

## 特性

- **一键部署** —— rootfs 提取 + AstrBot 安装，全自动
- **chroot 原生运行** —— 零性能损耗，直接跑在 Linux 环境
- **后台保活** —— 前台服务 + 自动重启
- **开机自启** —— 开机即用
- **Gitee 加速** —— GitHub 不稳定时自动回退到 Gitee 镜像
- **需要 Root** —— 使用 chroot 原生运行

## 安装

### 从源码构建

前置要求：
- JDK 17
- Android SDK（compileSdk 36）
- NDK r29+

```bash
git clone https://gitee.com/deku772/botdrop-android.git
cd botdrop-android
git checkout root
./gradlew assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/`。

## 项目现状

### 已完成

- chroot 基础架构（设备挂载、DNS、apt 环境）
- rootfs 自动下载/缓存（sdcard 优先，GitHub/Gitee 回退）
- AstrBot 自动安装（git clone + pip install）
- 前台保活服务（GatewayMonitorService）
- 极简 UI（状态 + 启停 + 日志）
- 开机自启（BootReceiver）

### 当前难点

| 问题 | 根因 | 状态 |
|------|------|------|
| AstrBot 启动失败（`nohup: command not found`） | chroot 内 PATH 不完整，命令需用绝对路径 | 已修复（`/usr/bin/nohup`） |
| AstrBot 启动失败（`/system/bin/sleep` 路径不存在） | sleep 在 chroot 内需用绝对路径 | 已修复（`sleep`） |
| rootfs 符号链接导致 `cp -aL` 报错 | node-compV8 缓存是损坏 symlink | 已修复（容忍+关键文件验证） |
| App 进程无法读 `/data/botdrop/root/` | root:root 700 权限 | 已修复（marker 文件移至 `/data/botdrop/`） |
| apt 无 gpgv 签名验证 | chroot 内无 gpgv | 已修复（`--allow-unauthenticated` + 后补装 gpgv） |
| git clone 无 tty 挂起 | 交互式凭据请求 | 已修复（`GIT_TERMINAL_PROMPT=0`） |
| pip 崩溃 "Cannot find path to android app folder" | `ANDROID_ROOT` 环境变量干扰 | 已修复（`unset ANDROID_ROOT`） |

### 待完成

- [ ] 集成原生终端（TerminalView + chroot PTY），在 App 内直接敲命令
- [ ] 构建验证（APK 实际安装测试）
- [ ] strings.xml / themes.xml 精简（仍有 Termux 残留）
- [ ] AstrBot 配置页（首次启动后跳转配置向导）

## 架构

```
┌──────────────────────────────────────┐
│  ANDBOTT UI（MainActivity + 状态页）  │
├──────────────────────────────────────┤
│  BotDropService（前台保活服务）        │
├──────────────────────────────────────┤
│  GatewayMonitorService（AstrBot 监控）│
├──────────────────────────────────────┤
│  ChrootManager（chroot 核心操作）     │
├──────────────────────────────────────┤
│  su -c chroot /data/botdrop /bin/bash│
├──────────────────────────────────────┤
│  Ubuntu rootfs（/data/botdrop/）     │
│    └── AstrBot + Python3 + pip       │
└──────────────────────────────────────┘
```

## 存储布局

```
/data/botdrop/                      ← chroot 根目录
├── .botdrop-rootfs-ready          ← rootfs 就绪标记
├── .botdrop-astrbot-ready         ← AstrBot 安装标记
└── root/                          ← rootfs 内容（root:root 700）
    └── astrbot/                   ← AstrBot 安装目录

/storage/emulated/0/botdrop/cache/  ← sdcard 缓存
    └── ubuntu22_openclaw.tar.gz   ← rootfs 压缩包（~809MB）
```

## Gitee 仓库

- 主仓库：https://gitee.com/deku772/botdrop-android（root 分支）
- 回滚方案：修改 GitHub latest release，无需改代码

## 分支说明

| 分支 | 说明 |
|------|------|
| `root` | 主分支 —— chroot 方案（需要 Root） |
| `no-root` | 历史分支 —— Termux + proot 方案 |
| `master` | 历史分支 —— 旧版 |

## 许可证

基于 [GNU General Public License v3.0](LICENSE) 开源。
基于 [Termux](https://github.com/termux/termux-app)（GPLv3）构建。
