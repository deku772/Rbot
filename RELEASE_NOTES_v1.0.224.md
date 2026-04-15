# Release v1.0.224 - 移除 Hermes Agent 支持

## 重大变更

本版本移除了 Hermes Agent 支持，专注于 AstrBot 单一引擎，简化代码结构和维护成本。

## 变更内容

### 移除的功能
- ❌ 移除 Hermes Agent 适配器和管理界面
- ❌ 移除 Hermes 相关的安装、启动、停止方法
- ❌ 移除 Hermes SSH 密钥文件
- ❌ 移除引擎选择界面（现在只支持 AstrBot）

### 代码优化
- ✅ 简化 `BotAdapter` 和 `BotManager` 架构
- ✅ 清理 `ChrootManager` 中的 Hermes 相关方法（约 200 行代码）
- ✅ 简化 `SetupActivity` 和 `MainActivity` 逻辑
- ✅ 更新 `.gitignore` 排除 SSH 密钥和构建日志

### 技术细节
- 删除文件：
  - `HermesAdapter.java`
  - `HermesManagementActivity.java`
  - `activity_hermes_management.xml`
  - `hermes_ssh_key` 相关文件
- 修改文件：
  - `BotAdapter.java` - 移除 `ID_HERMES` 常量
  - `BotManager.java` - 移除 Hermes 注册
  - `ChrootManager.java` - 移除所有 Hermes 方法
  - `RbotConstants.java` - 移除 Hermes 常量
  - `SetupActivity.java` - 隐藏引擎选择
  - `MainActivity.java` - 简化导航逻辑

## 升级说明

从旧版本升级的用户：
1. 如果之前使用 Hermes Agent，升级后将自动切换到 AstrBot
2. Hermes 相关数据不会被自动删除，可手动清理 `/data/rbot/hermes` 目录
3. 建议重新安装以获得最佳体验

## 下载

- **APK 文件**: `app-debug.apk` (约 10MB)
- **最低 Android 版本**: Android 7.0 (API 24)
- **推荐 Android 版本**: Android 10+ (API 29+)

## 安装方法

```bash
adb install -r app-debug.apk
```

或直接在手机上安装 APK 文件。

## 已知问题

无

## 下一步计划

- 优化 AstrBot 安装流程
- 改进日志显示界面
- 添加更多配置选项

---

**完整变更日志**: https://gitee.com/deku772/rbot/compare/v1.0.1786...v1.0.224
