# Release Notes v2.0.0

## 🎉 重大版本更新

### 版本信息
- 版本号: 2.0.0
- 版本代码: 231
- 构建时间: 2026-04-15

### 主要修复

#### 修复 AstrBot 停止功能 ✅
- **问题**: AstrBot 启动后无法正常停止，停止按钮点击后进程仍在运行
- **根本原因**: `getFullStatus()` 方法使用了旧的进程匹配模式 `python3 main.py`，而实际启动命令是 `python -m astrbot`
- **修复**: 统一所有方法的进程匹配模式为 `python.*astrbot`

#### 技术改进
1. **统一进程检测**: 所有启动/停止/状态检测方法现在使用一致的进程匹配模式
2. **符合官方文档**: 使用 `python -m astrbot` 作为启动命令（符合 AstrBot 官方文档）
3. **增强日志**: OpLog 记录所有操作，便于调试

### 已移除功能
- Hermes Agent 支持（已在 v1.0.224 移除）
- 专注于 AstrBot 单一机器人框架

### 安装说明
APK 位置: `app/build/outputs/apk/debug/app-debug.apk`

安装方法:
1. 将 APK 传输到手机
2. 使用文件管理器打开并安装
3. 或使用 ADB: `adb install -r app-debug.apk`

### 测试建议
1. 安装新版本后，点击"启动"按钮
2. 等待 2-3 秒，查看状态应显示"运行中"
3. 点击"停止"按钮，应该成功停止进程
4. 查看日志页面，确认操作日志和 AstrBot 运行日志正常显示

### 已知问题
- SSH 密码认证功能暂时搁置（将在后续版本处理或移除）

### 技术细节
- 修改文件: `app/src/main/java/app/rbot/ChrootManager.java`
- 修改文件: `app/build.gradle`
- JDK: 17
- 目标 SDK: 34
- 最低 SDK: 24

---

## 下一步计划
- 测试 AstrBot 启动/停止功能
- 验证日志显示功能
- 根据测试结果决定是否需要进一步优化
