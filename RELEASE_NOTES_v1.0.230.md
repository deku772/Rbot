# Release Notes v1.0.230

## 修复 AstrBot 停止功能

### 问题描述
- AstrBot 启动后无法正常停止
- 停止按钮点击后进程仍在运行
- 状态检测不一致导致误报

### 根本原因
`getFullStatus()` 方法使用了旧的进程匹配模式 `python3 main.py`，而实际启动命令是 `python -m astrbot`，导致状态检测失败。

### 修复内容
1. **统一进程匹配模式**: 将 `getFullStatus()` 中的进程检测从 `python3 main.py` 改为 `python.*astrbot`
2. **保持一致性**: 现在所有三个方法使用相同的匹配模式:
   - `startAstrBot()`: 启动 `python -m astrbot`
   - `isAstrBotRunning()`: 检测 `python.*astrbot`
   - `stopAstrBot()`: 终止 `python.*astrbot`
   - `getFullStatus()`: 检测 `python.*astrbot` (已修复)

### 技术细节
- 修改文件: `app/src/main/java/app/rbot/ChrootManager.java`
- 修改行数: 第 758 行
- 版本号: v1.0.230

### 测试建议
1. 安装新版本 APK
2. 点击"启动"按钮 - 应该成功启动 AstrBot
3. 等待 2-3 秒查看状态 - 应显示"运行中"
4. 点击"停止"按钮 - 应该成功停止进程
5. 再次查看状态 - 应显示"已停止"
6. 查看日志页面 - 应显示操作日志和 AstrBot 运行日志

### 已知问题
- SSH 密码认证仍有问题(已暂时搁置)
- 日志显示功能正常，但需要确认 AstrBot 日志路径是否正确

---

## 构建信息
- 构建时间: 2026-04-15
- APK 路径: `app/build/outputs/apk/debug/app-debug.apk`
- 版本代码: 230
- 版本名称: 1.0.230
