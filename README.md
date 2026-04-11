# Rbot

在 Android 上通过 chroot 一键部署 AstrBot 终端 —— 无需手动敲命令，GUI 全程搞定。

## 功能定位

Rbot 是一个**轻量级一键终端部署壳子**，专为 AstrBot 设计：

1. **部署** —— 通过 chroot 运行 Ubuntu rootfs，安装 AstrBot（Python 3.13）
2. **启停** —— 一键启动/停止 AstrBot，后台保活
3. **日志** —— 实时查看 AstrBot 运行日志
4. **管理** —— 重装依赖、重置密码、修复环境

> 如果你需要完整的管理界面（频道配置、模型管理等），推荐 [BotPocket](https://gitee.com/deku772/bot-pocket)。

## 特性

- **一键部署** —— rootfs 提取 + AstrBot 安装，全自动
- **chroot 原生运行** —— 零性能损耗，直接跑在 Linux 环境
- **Python 3.13** —— 使用 deadsnakes PPA，满足 AstrBot v4.x 要求
- **SSH 远程访问** —— 自动生成 root 密码，支持外部 SSH 连接
- **后台保活** —— 前台服务 + 自动重启
- **开机自启** —— 开机即用
- **Gitee 加速** —— 国内下载加速
- **需要 Root** —— 使用 chroot 原生运行

## 安装

### 下载 APK

从 [Releases](https://gitee.com/deku772/Rbot/releases) 下载最新版本。

### 从源码构建

前置要求：
- JDK 17
- Android SDK（compileSdk 36）
- NDK r29+

```bash
git clone https://gitee.com/deku772/Rbot.git
cd Rbot
git checkout root
./gradlew assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/`。

## 使用

1. **首次启动** —— 授予 Root 权限，自动下载并部署 Ubuntu rootfs
2. **安装 AstrBot** —— 点击"安装 AstrBot"，自动完成环境配置
3. **启动服务** —— 点击"启动"，AstrBot 将在后台运行
4. **查看日志** —— 在"日志" Tab 实时查看运行状态
5. **SSH 连接** —— 在 SSH 信息面板查看 IP、端口、用户名和密码

## 界面

```
┌─────────────────────────────┐
│  Rbot v1.0                  │
├─────────────────────────────┤
│  [日志] [管理]              │
├─────────────────────────────┤
│  SSH 信息                   │
│  状态: 🟢 运行中            │
│  地址: 192.168.1.100:8022   │
│  用户: root / 密码: ********│
│  [停止]                     │
├─────────────────────────────┤
│  日志输出...                │
│  [2026-04-11] AstrBot 启动  │
│  ...                        │
└─────────────────────────────┘
```

## 管理功能

在"管理" Tab 中可以：

- **重装系统依赖** —— 重新安装 Python 3.13、git、curl 等
- **重装 AstrBot** —— 保留配置，重新安装 AstrBot
- **重置 Root 密码** —— 生成新密码或自定义密码
- **修复环境** —— 重新挂载 chroot、修复 apt、重启 SSH
- **完全重装** —— 清除所有数据，重新部署

## 存储布局

```
/data/rbot/                         ← chroot 根目录
├── .rbot-rootfs-ready             ← rootfs 就绪标记
├── .rbot-astrbot-ready            ← AstrBot 安装标记
└── root/                          ← rootfs 内容（root:root 700）
    ├── astrbot/                   ← AstrBot 安装目录
    └── .rbot_pass                 ← root 密码文件

/storage/emulated/0/rbot/cache/     ← sdcard 缓存
    └── ubuntu22_openclaw.tar.gz   ← rootfs 压缩包（~809MB）
```

## 架构

```
┌──────────────────────────────────────┐
│  Rbot UI（MainActivity）              │
│  ├── 日志 Tab（实时日志）              │
│  └── 管理 Tab（环境管理）              │
├──────────────────────────────────────┤
│  RbotService（前台保活服务）           │
├──────────────────────────────────────┤
│  ChrootManager（chroot 核心操作）      │
├──────────────────────────────────────┤
│  su -c chroot /data/rbot /bin/bash   │
├──────────────────────────────────────┤
│  Ubuntu rootfs（/data/rbot/）         │
│    ├── AstrBot + Python 3.13 + pip   │
│    └── OpenSSH Server（端口 8022）    │
└──────────────────────────────────────┘
```

## 分支说明

| 分支 | 说明 |
|------|------|
| `root` | **主分支** —— chroot 方案（需要 Root） |
| `no-root` | 历史分支 —— Termux + proot 方案 |
| `master` | 历史分支 —— 旧版 |

## 版本历史

### v1.0 (2026-04-11)

- 全新 UI：日志/管理双 Tab 设计
- 集成 SSH 服务，支持远程管理
- 自动设置 root 密码
- Python 3.13 环境（deadsnakes PPA）
- 管理功能：重装依赖、重装 AstrBot、重置密码、修复环境
- 状态检测修复

## Gitee 仓库

- 主仓库：https://gitee.com/deku772/Rbot（root 分支）

## 许可证

基于 [GNU General Public License v3.0](LICENSE) 开源。
基于 [Termux](https://github.com/termux/termux-app)（GPLv3）构建。
