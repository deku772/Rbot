# OpenClaw Gateway 启动失败：koffi native module 在 Android 上不可用

**日期**: 2026-02-19
**影响**: OpenClaw gateway 无法在 Botdrop (Termux) 环境中启动

## 问题现象

执行 `openclaw gateway run` 后，gateway 立即崩溃，日志如下：

```
Error: Cannot find the native Koffi module; did you bundle it correctly?
    at init (/data/data/app.rbot/files/usr/lib/node_modules/openclaw/node_modules/koffi/index.js:502:15)
    at Object.<anonymous> (/data/data/app.rbot/files/usr/lib/node_modules/openclaw/node_modules/koffi/index.js:636:12)
```

## 根因分析

1. koffi 是一个 Node.js FFI (Foreign Function Interface) 库，需要加载平台特定的 `.node` 原生二进制文件
2. koffi 提供了 `linux_arm64`、`darwin_arm64` 等平台的预编译文件，但**没有 `android_arm64`**
3. Node.js 在 Termux 上报告 `process.platform = "android"`、`process.arch = "arm64"`
4. koffi 拼接出 `android_arm64` 作为平台标识，找不到匹配的二进制文件
5. `linux_arm64` 的二进制文件由于 libc 不兼容（glibc vs bionic）也无法在 Android 上使用

**依赖链**:
```
openclaw → @mariozechner/pi-tui → koffi
```

koffi 在 pi-tui 中仅用于在 **Windows** 上加载 `kernel32.dll`（处理 Shift+Tab 键盘事件），pi-tui 代码中已有 fallback：

```javascript
// koffi not available — Shift+Tab won't be distinguishable from Tab
```

因此在 Android/Termux 环境中 koffi 完全不需要。

## 修复方案

用一个 mock 模块替换 koffi 的 `index.js`，使 `import koffi from "koffi"` 不再崩溃。

### 手动执行

```bash
# 备份原文件
cp /data/data/app.rbot/files/usr/lib/node_modules/openclaw/node_modules/koffi/index.js \
   /data/data/app.rbot/files/usr/lib/node_modules/openclaw/node_modules/koffi/index.js.orig

# 写入 mock 模块
cat > /data/data/app.rbot/files/usr/lib/node_modules/openclaw/node_modules/koffi/index.js << 'EOF'
// Mock koffi module for platforms where native module is unavailable (e.g. Android/Termux)
module.exports = {
  load() {
    throw new Error("koffi native module not available on this platform");
  }
};
EOF

# 重启 gateway
pkill -f "openclaw.*gateway" 2>/dev/null; sleep 1
openclaw gateway run --force
```

### 使用脚本

补丁文件位于 `docs/troubleshooting/patches/` 目录：

- `koffi-mock.js` — mock 模块
- `fix-koffi.sh` — 自动备份并替换的脚本

```bash
bash docs/troubleshooting/patches/fix-koffi.sh
```

## 注意事项

- **openclaw 更新后需重新应用**：`npm update -g openclaw` 会覆盖 koffi 的 `index.js`，需要重新执行修复脚本
- 原始文件备份在 `index.js.orig`
- `openclaw gateway status` 会报 "Gateway service install not supported on android"，这是另一个问题（service 管理不支持 Android），不影响 gateway 实际运行
