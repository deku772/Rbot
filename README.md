---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '0f596fd7-decb-421b-8758-e19f64c45da9'
  PropagateID: '0f596fd7-decb-421b-8758-e19f64c45da9'
  ReservedCode1: '5798f108-d134-4ae7-92d3-4ee9f570c7bc'
  ReservedCode2: '5798f108-d134-4ae7-92d3-4ee9f570c7bc'
---

# Rbot

AstrBot 的 Android 宿主应用，通过 chroot 在 Android 上运行 Ubuntu 环境并部署 AstrBot。

## 仅为 ROOT 用户设计

本应用**需要 Root 权限**（Magisk / KernelSU / APatch），通过 chroot 方式运行完整 Linux 环境。如果你没有 Root 权限，请使用 [AstrBot 免 Root 版本（3.0.6）](https://github.com/AstrBotDevs/AstrBot/releases)。

## 功能

- 一键安装 Ubuntu rootfs + AstrBot
- 启动/停止 AstrBot 服务
- WebUI 管理面板（端口 6185）
- SSH 远程访问
- 内置终端
- 数据备份与恢复
- 开机自启动（可选）
- GitHub 代理加速下载

## 技术架构

- Kotlin + Jetpack Compose
- libsu (Root Shell)
- chroot 隔离运行
- Hilt 依赖注入
- Room 数据持久化

## 构建

```bash
./gradlew assembleRelease
```

Release APK 约 3.2 MB（R8 代码压缩 + 资源缩减）。