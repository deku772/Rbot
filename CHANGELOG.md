# Changelog

## v2.1.0 (2026-05-11)

### 🛡️ Shizuku 双授权支持
- 新增 Shizuku/Sui 作为传统 `su` 的替代授权方式
- 无需 root 二进制文件即可使用 Shizuku root 模式运行 chroot
- Shizuku ADB 模式（UID 2000）自动检测并提示需要 root 模式
- 权限页面新增 Shizuku 卡片，点击直接跳转 GitHub Releases 下载
- **新增文件**：
  - `IShellService.aidl` — AIDL 接口定义
  - `ShellService.java` — Shizuku UserService 实现（root 进程中执行命令）
  - `AuthManager.java` — 授权管理器（ROOT/SHIZUKU/UNAVAILABLE 三模式检测）

### 🪶 APK 瘦身 (114MB → 10MB)
- 移除未使用的 `bootstrap-aarch64.zip`（106MB）NDK 嵌入
- Rbot 不调用 `getZip()`，bootstrap .so 完全无用
- 禁用 `externalNativeBuild`，APK 体积缩减 91%

### 🐛 Bug 修复
- **HOME 环境变量**：`execInChroot()` 现在显式注入 `HOME=/root`，修复 su -c 继承 Android `HOME=/` 导致 git/npm/pip 全局路径异常

### 🎨 UI 优化
- WebUI 和 SSH 面板改为垂直布局，信息更清晰
- 新增 WebUI 运行状态指示器
- 权限页面 Shizuku 卡片（青色主题）

---

## v2.0.5
- ShellActivity 终端核心修复
- 启用 Termux 内置复制粘贴功能

## v2.0.4
- 修复多个 Android toybox 兼容性问题
- tar 解压超时、格式检测、MD5 校验

## v2.0.3
- SSH 改用 dropbear 替代 openssh

## v2.0.1
- CI 修复和依赖更新

## v2.0.0
- 初始发布：Android chroot AstrBot 一键部署
