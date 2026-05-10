# Rbot

在 Android 上通过 chroot 一键部署 AstrBot 终端 —— 无需手动敲命令，GUI 全程搞定。

## 📚 快速指南

| 文档 | 说明 |
|------|------|
| [飞书 CLI 安装与使用指南](docs/guide-feishu-cli.md) | 飞书 CLI 完整配置流程、踩坑记录、常用命令 |
| [浏览器自动化指南](docs/guide-browser-automation.md) | Playwright 安装配置、核心用法、实战示例 |

## 功能定位

Rbot 是一个**轻量级一键终端部署壳子**，专为 AstrBot 设计：

1. **部署** —— 通过 chroot 运行 Ubuntu rootfs，安装 AstrBot（Python 3.13）
2. **启停** —— 一键启动/停止 AstrBot，后台保活
3. **终端** —— 内置全功能终端（快捷键、文字选择复制粘贴）
4. **日志** —— 实时查看 AstrBot 运行日志
5. **管理** —— 重装依赖、重置密码、修复环境

> 如果你需要完整的管理界面（频道配置、模型管理等），推荐 [BotPocket](https://gitee.com/deku772/bot-pocket)。

## 特性

- **一键部署** —— rootfs 提取 + AstrBot 安装，全自动
- **chroot 原生运行** —— 零性能损耗，直接跑在 Linux 环境
- **内置终端** —— ZeroTermux 风格快捷键栏，长按选择复制粘贴，sticky modifier 键
- **Python 3.13** —— 使用 deadsnakes PPA，满足 AstrBot v4.x 要求
- **SSH 远程访问** —— 自动生成 root 密码，支持外部 SSH 连接
- **后台保活** —— 前台服务 + 自动重启
- **开机自启** —— 开机即用
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
│  ← root@localhost    📋  ⌨  │  ← 终端标题栏
├─────────────────────────────┤
│  root@rbot:~#               │
│  _                          │  ← 终端（全功能，支持选择复制）
│                             │
├─────────────────────────────┤
│  ESC /  -  HOME  ↑  END PGUP│  ← 快捷键第 1 行
│  TAB CTRL ALT  ←  ↓  → PGDN│  ← 快捷键第 2 行
└─────────────────────────────┘
```

### 终端操作

| 操作 | 说明 |
|------|------|
| 长按文字 | 进入选择模式，拖动光标调整范围 |
| 复制 / 粘贴 | 选择后点击浮动菜单的"复制"或"粘贴" |
| CTRL / ALT | 单击 = sticky（自动释放），长按 = 锁定（切换） |
| 方向键 | 支持长按连续输入 |

### 主页

```
┌─────────────────────────────┐
│  BINARIES  │  ROOTFS        │
│    ✅ 已就绪 │  ✅ 已就绪     │
├─────────────────────────────┤
│  BOOTSTRAP │  ASTRBOT       │
│    ✅ 运行中 │  ✅ 运行中     │
├─────────────────────────────┤
│  SSH: 192.168.x.x:22        │
│  密码: ********  [停止 SSH]  │
│  [终端]                     │
├─────────────────────────────┤
│  [启动]  [停止]  [安装]     │
├─────────────────────────────┤
│  [首页] [日志] [设置] [...]  │
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
│  Rbot UI                             │
│  ├── MainActivity（状态面板 + 导航）   │
│  ├── ShellActivity（内置终端）         │
│  ├── LogActivity（日志查看）           │
│  ├── SettingsActivity（设置）          │
│  └── SetupActivity / ManageActivity   │
├──────────────────────────────────────┤
│  RbotService（前台保活服务）           │
├──────────────────────────────────────┤
│  ChrootManager（chroot 核心操作）      │
├──────────────────────────────────────┤
│  su -c chroot /data/rbot /bin/bash   │
├──────────────────────────────────────┤
│  Ubuntu rootfs（/data/rbot/）         │
│    ├── AstrBot + Python 3.13 + pip   │
│    └── Dropbear SSH（端口 22）        │
└──────────────────────────────────────┘
```

## 分支说明

| 分支 | 说明 |
|------|------|
| `root` | **主分支** —— chroot 方案（需要 Root） |
| `no-root` | 历史分支 —— Termux + proot 方案 |
| `master` | 历史分支 —— 旧版 |

## 版本历史

### v2.0.5 (2026-05-10)

- 新增内置终端（ShellActivity）：ZeroTermux 风格 2×7 快捷键栏
- 终端支持 sticky modifier 键（CTRL/ALT 单击粘滞、长按锁定）
- 终端支持长按文字选择复制粘贴
- 修复终端输入不实时刷新
- 修复页面切换卡顿（chroot 初始化移到后台线程）
- 修复 chroot shell 启动报错（PATH、cwd、.hushlogin）
- SSH 改用 Dropbear 替代 OpenSSH，修复密码验证失败

### v2.0.4 (2026-05-09)

- SSH 改用 Dropbear 替代 OpenSSH，修复 chroot 中密码验证失败

### v2.0.1 (2026-04-15)

- 修复 AstrBot 启动/停止/状态检测（toybox bash 兼容性）
- 修复 `wc -l` exit code 问题导致状态始终显示"已停止"
- 修复 stopAstrBot 的 PID 解析（`for line in $(ps ...)` 无法正确解析）
- 移除 MonitorAlarmReceiver 周期检查（改为开机拉起一次）
- 启动命令统一为 `python3 main.py`

### v2.0.0 (2026-04-15)

- 修复 `/tmp` 不可写导致备份失败（tmpfs + TMPDIR）
- 修复停止后 AstrBot 自动重启（同步 commit + 验证杀死）
- 备份恢复改为直接 cp，更稳定
- SSH 信息点击复制
- 移除 Header 右上角 SSH 按钮
- 构建修复：Android Studio versionCode/versionName 为空

### v1.0 (2026-04-11)

- 全新 UI：日志/管理双 Tab 设计
- 集成 SSH 服务，支持远程管理
- 自动设置 root 密码
- Python 3.13 环境（deadsnakes PPA）
- 管理功能：重装依赖、重装 AstrBot、重置密码、修复环境
- 状态检测修复

## Gitee 仓库

- 镜像：https://gitee.com/deku772/Rbot（root 分支）
- 主仓库：https://github.com/deku772/Rbot

## 许可证

基于 [GNU General Public License v3.0](LICENSE) 开源。
基于 [Termux](https://github.com/termux/termux-app)（GPLv3）构建。
