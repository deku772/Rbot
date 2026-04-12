# Rbot Android 项目记忆

> AI Agent 跨会话记忆文件，随项目同步到 Gitee。

## 项目概况
- Rbot Android：基于 chroot（root 方案）的 Android 一键终端部署壳子（原名 BotDrop/ANDBOTT）
- fork 自 github.com/zhixianio/botdrop-android，用户 fork 在 github.com/deku772/botdrop-android
- git remote origin: `https://gitee.com/deku772/Rbot.git`
- 分支策略：`root`（唯一分支，chroot 方案）
- **定位**：一键终端部署壳子，只做：chroot 部署 rootfs → 安装 AstrBot → 启停 → 看日志
- 构建要求：JDK 17, Android SDK, NDK, Gradle 9.2.1 + AGP 8.13.2
- 包名：`app.rbot`

## 核心路径架构（chroot 方案）
- chroot 目录：`/data/rbot` — rootfs 运行时（旧 BotDrop 用 `/data/botdrop`）
- sdcard 缓存：`/storage/emulated/0/rbot/cache/` — rootfs tarball 缓存
- 备份目录：`/storage/emulated/0/rbot/backups/` — 统一备份目录
- AstrBot 安装：chroot 内 `/root/astrbot`
- Marker 文件必须放在 `/data/rbot/`（755）下，不能放在 chroot 内部路径

## 关键文件
- `ChrootManager.java` — chroot 核心（su -c chroot）
- `BotDropService.java` — 前台服务 + AstrBot 生命周期
- `RbotConstants.java` — 常量
- `MainActivity.java` — 主界面（双Tab：日志+管理）
- `GatewayMonitorService.java` — 状态监控服务（只报告，不自动重启）

## 版本号机制
- 使用本地 `version.properties` 文件自动递增（不依赖 git）
- 版本号逻辑必须在 `android {}` 块之前定义（Groovy 顺序执行）
- `version.properties` 已加入 `.gitignore`
- 格式：`1.0.{buildNumber}`，每次构建 +1

## 已踩过的坑
- **外部存储不支持符号链接**：rootfs 必须 chroot 到 ext4 分区
- **App 进程无法读 /data/rbot/root/ 下文件**：chroot 内 /root 是 root:root 700，Java File.exists() 永远 false。所有 marker 文件必须放在 /data/rbot/（755）下
- **Android XML visibility="gone" 不会因 setEnabled 自动恢复**：需要代码显式 setVisibility
- **mktemp/apt 失败**：chroot 内 /tmp 需要 tmpfs 挂载 + TMPDIR 环境变量设置，否则临时文件会泄漏到宿主机 Android 缓存目录
- **Gradle versionCode 为空**：变量定义在 android{} 块之后，引用时未定义；GString 在 ProcessBuilder varargs 中类型转换失败
- **Android Studio Run 配置缓存**：Before launch 需要有 Gradle-aware Make，否则安装旧 APK
- **备份恢复**：用 cp -r 代替 tar，避免 /tmp 不可写问题

## SSH 功能
- 内置 OpenSSH Server
- SSH 信息格式：`root@局域网IP`（点击复制）
- IP 检测优先级：ifconfig wlan0 → ip addr show → ip route get → getprop
- 启停控制：独立的 SSH 面板，不在 Header 按钮

## AstrBot 管理
- 启停：纯手动，不自动重启
- 停止时用 commit() 同步写入配置 + 验证进程死亡（最多重试 3 秒）
- 备份：cp -r 到 /storage/emulated/0/rbot/backups/astrbot_data_{时间戳}/
- 重装 rootfs 时自动备份 AstrBot data

## 服务器资源
- `\\192.168.10.2\share\tmp\claw-apk\ubuntu22_openclaw.tar.gz` — 完整 Ubuntu rootfs (809MB)
- `\\192.168.10.2\share\tmp\bootstrap-aarch64.zip` — Termux bootstrap
- `D:/卿/claw-apk/` — 本地备份
- 设备上文件放在 `/storage/emulated/0/claw-apk/`

## 开发环境
- Windows 构建机器：JDK 17 (`C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot`) + JDK 21
- AGP 8.13.2 + Gradle 9.2.1
- 真机调试：Android Studio + ADB
- SOCKS 代理：7890
- Gitee 仓库：deku772/Rbot

## 用户偏好
- 沟通直接，不要废话
- 最小化修改，不要推倒重来
- 使用绝对路径
- 不要用 Python 脚本做简单字符串替换，直接用 replace_in_file
- 说"推送git"时要同步更新 changelog
- 不需要帮构建，用户自己用 Android Studio 构建

## 变更日志
- **2026-04-12**: Gitee Release v1.0.1765 推送成功，APK 上传（~83.6MB）
- PowerShell -Form 不可用（版本旧），Gitee 上传需用 System.Net.WebClient + 手动 multipart
