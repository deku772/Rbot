# Rbot v1.0 正式发布

## 更新日志

### v1.0.1786

**全新 Ubuntu 24.04 rootfs**
- 替换旧 Ubuntu 22.04 为 Ubuntu 24.04 arm64 rootfs（480MB）
- 内置 Python 3.12 + Node.js 20 + npm 10.8
- DNS 改为 223.5.5.5（阿里），apt 源自动切换阿里云镜像

**交互式安装流程**
- 安装向导完全重构为交互式，每步需用户确认
- GitHub 代理测速：5 个代理（直连/edgeone/hk/gh-proxy/llkk）+ 自定义，显示延迟（🟢🟡🔴）
- AstrBot 版本选择：从 GitHub Releases 获取最新 5 个版本，用户选择后克隆
- 实时进度输出：解压、apt、pip、备份恢复全程可见

**PEP 668 / venv 支持**
- Ubuntu 24.04 不允许系统级 pip install → 自动创建 venv
- AstrBot 运行在 `/root/astrbot/venv/bin/python3`
- pip 依赖安装超时自适应：有输出就重置，300s 无输出才算超时

**chroot 环境修复**
- `/dev` 改为整目录 bind mount（修复 /dev/null 权限问题）
- 新增 `ensureGpgv()`：apt update 前自动安装 gpgv
- apt 操作前自动清理残留锁 + `dpkg --configure -a`
- 新增 `fixDevNull()` 检查/修复 /dev/null 可写性

**日志体验升级**
- 日志倒序排列（新→旧），最新日志永远在顶部
- 新增「清理日志」按钮（加大尺寸）
- 恢复 App 时显示分割线
- 所有长操作走 WithProgress 路径，实时显示过程
- Adaptive timeout：有输出就重置计时器，不再固定超时

**备份恢复修复**
- 修复备份路径多套一层 `/data` 的问题
- `/data/rbot/root/` 检测改用 root `test -d`（Java File API 无法读取 root:root 700 目录）
- 恢复时先停 AstrBot → 删旧 data → cp 备份为新 data

**其他**
- 重装 rootfs 时自动备份 AstrBot 数据
- 新增重装 rootfs 选项（MainActivity 管理面板）
- 新增 GitHubProxyManager：代理测速 + AstrBot 版本获取
- tar 解压加 `--checkpoint=500` 进度指示
- cp 同步加后台进度报告（每 5 秒显示已复制大小）

---

### v1.0.1762+

**Bug 修复**
- 修复 `/tmp` 不可写导致备份失败的问题（挂载 tmpfs + 设置 TMPDIR 环境变量）
- 修复点击停止后 AstrBot 仍自动重启的问题（移除自动重启逻辑，改为纯手动启停）
- 修复 SSH 连接信息无法复制的问题（点击 `root@IP` 即可复制）
- 修复 Android Studio 构建时 versionCode/versionName 为空的问题
- 修复 Gradle 找不到 git 导致版本号获取失败的问题（改用本地 version.properties 计数器）

**备份恢复**
- 备份/恢复改为直接 `cp -r` 文件复制，不再依赖 tar 和 `/tmp` 临时空间

---

## 下载

- **APK**: [app-debug.apk](https://gitee.com/deku772/Rbot/releases/download/v1.0.1786/app-debug.apk) (87.7 MB)

## 系统要求

- Android 8.0+ (API 26+)
- **需要 Root 权限**（chroot 方案）
- 存储空间：至少 2GB 可用空间（rootfs ~480MB + AstrBot）

## 安装步骤

1. 下载 APK 并安装
2. 授予 Root 权限
3. 跟随交互式向导完成安装
4. 在 SSH 信息面板查看连接信息

## 默认配置

- **SSH 端口**: 8022
- **用户名**: root
- **密码**: 自动生成的 8 位随机密码
- **AstrBot 目录**: `/root/astrbot`（venv: `/root/astrbot/venv`）
- **WebUI**: 启动后在浏览器访问设备 IP:6185
- **DNS**: 223.5.5.5（阿里）
- **apt 源**: 阿里云 arm64 镜像

## 源码

```bash
git clone https://gitee.com/deku772/Rbot.git
cd Rbot
git checkout root
```

## 许可证

GPL v3.0
