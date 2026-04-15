# Release Notes v2.0.1

## 修复 AstrBot 启动/停止/状态检测

### 版本信息
- 版本号: 2.0.1
- 版本代码: 249
- 构建时间: 2026-04-15

### 修复内容

#### 1. startAstrBot() 修复
- **问题**: 分两次调用 execInChroot 导致进程丢失（第一次启动后台进程，第二次 execInChroot 执行 check 时获取不到 PID）
- **修复**: 改回 `python3 main.py` 单次 execInChroot 完成启动+等待+验证

#### 2. stopAstrBot() 修复
- **问题**: `for line in $(ps ...)` 写法会把输出按空格拆成单词，无法正确解析 PID
- **修复**: 改用 `pkill -f 'python.*main\.py'`（toybox pkill 支持 `-f`），三步杀进程：删PID文件 → pkill → 删PID文件

#### 3. isAstrBotRunning() / getFullStatus() 修复
- **问题**: `wc -l` 在 count=0 时返回 exit code 1，导致 `result.success()` 永远 false，状态始终显示"已停止"，停止按钮被禁用
- **修复**: 去掉 `result.success()` 判断，改为 `pgrep -f 'python.*main\.py'` 优先模式匹配，count > 0 即认为运行中

#### 4. toybox bash 兼容性改进
- 移除了 `setsid`（Android toybox 不支持）
- 移除了对 `pkill`/`awk`/`cut`/`sed`/`wc`/`head` 等扩展命令的依赖
- 所有 shell 脚本改用 toybox bash 内置的 `case` 字符串匹配

### 已移除功能
- MonitorAlarmReceiver（每30秒检查已被移除，改为开机启动一次即可）
- Hermes Agent 支持（专注 AstrBot 单一引擎）

### 技术细节
- 修改文件: `ChrootManager.java`, `AstrBotAdapter.java`, `GatewayMonitorService.java`, `BootReceiver.java`, `AndroidManifest.xml`
- JDK: 17
- 目标 SDK: 34
- 最低 SDK: 24

---

## 安装说明

APK 位置: `app/build/outputs/apk/debug/app-debug.apk`

安装方法:
1. 将 APK 传输到手机
2. 使用文件管理器打开并安装
3. 或使用 ADB: `adb install -r app-debug.apk`

---

## 测试清单

- [ ] 启动 AstrBot → 状态显示"运行中"，停止按钮可点击
- [ ] 停止 AstrBot → 进程被彻底杀死，状态显示"已停止"
- [ ] 重复启动/停止多次，状态始终准确
- [ ] 开机后 AstrBot 自动启动（如已启用开机自启）
