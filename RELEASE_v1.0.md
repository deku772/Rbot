# Rbot v1.0 正式发布

## 更新日志

### v1.0.1762+

**Bug 修复**
- 修复 `/tmp` 不可写导致备份失败的问题（挂载 tmpfs + 设置 TMPDIR 环境变量）
- 修复点击停止后 AstrBot 仍自动重启的问题（移除自动重启逻辑，改为纯手动启停）
- 修复 SSH 连接信息无法复制的问题（点击 `root@IP` 即可复制）
- 修复 Android Studio 构建时 versionCode/versionName 为空的问题（版本号逻辑移至 android{} 块之前）
- 修复 Gradle 找不到 git 导致版本号获取失败的问题（改用本地 version.properties 计数器）

**备份恢复**
- 备份/恢复改为直接 `cp -r` 文件复制，不再依赖 tar 和 `/tmp` 临时空间，更稳定
- 备份前自动初始化 chroot 环境，确保 tmpfs 已挂载

**SSH 体验**
- SSH 信息格式改为 `root@局域网IP`，点击即可复制
- 改进局域网 IP 检测逻辑（ifconfig → ip addr → ip route → getprop）
- 移除 Header 右上角 SSH 按钮（已有独立面板控制）

**构建**
- 版本号改为本地 `version.properties` 自动递增，不再依赖 git
- `version.properties` 已加入 `.gitignore`

---

## 下载

- **APK**: [app-debug.apk](https://gitee.com/deku772/Rbot/releases/download/v1.0/app-debug.apk) (83.6 MB)

## 新特性

### 全新 UI 设计
- **双 Tab 界面**：日志页 + 管理页
- **SSH 信息常驻显示**：IP、端口、用户名、密码一目了然
- **实时日志**：AstrBot 运行日志实时滚动

### SSH 远程访问
- 内置 OpenSSH Server（端口 8022）
- 自动生成 root 密码
- 支持外部 SSH 客户端连接管理

### Python 3.13 环境
- 使用 deadsnakes PPA 安装 Python 3.13
- 满足 AstrBot v4.x 的 Python 3.12+ 要求
- 自动设置 python3 默认指向 3.13

### 管理功能
在"管理" Tab 中可以：
- 🔄 **重装系统依赖** —— 重新安装 Python 3.13、git、curl 等
- 🤖 **重装 AstrBot** —— 保留配置，重新安装 AstrBot
- 🔐 **重置 Root 密码** —— 生成新密码或自定义密码
- 🛠️ **修复环境** —— 重新挂载 chroot、修复 apt、重启 SSH
- ⚠️ **完全重装** —— 清除所有数据，重新部署

### Bug 修复
- 修复 AstrBot 状态检测问题（实际运行但显示"已停止"）
- 修复 Java record 访问器调用方式

## 系统要求

- Android 8.0+ (API 26+)
- **需要 Root 权限**（chroot 方案）
- 存储空间：至少 2GB 可用空间（rootfs ~800MB + AstrBot）

## 安装步骤

1. 下载 APK 并安装
2. 授予 Root 权限
3. 点击"安装 Rootfs"下载 Ubuntu 环境
4. 点击"安装 AstrBot"部署机器人
5. 点击"启动"运行 AstrBot
6. 在 SSH 信息面板查看连接信息

## 默认配置

- **SSH 端口**: 8022
- **用户名**: root
- **密码**: 自动生成的 8 位随机密码（安装时显示）
- **AstrBot 目录**: `/root/astrbot`
- **WebUI**: 启动后在浏览器访问设备 IP:6185

## 源码

```bash
git clone https://gitee.com/deku772/Rbot.git
cd Rbot
git checkout root
```

## 许可证

GPL v3.0
