# BotDrop

Run AI agents on your Android phone — no terminal, no CLI, just a guided setup.

BotDrop wraps [AstrBot](https://github.com/Soulter/AstrBot) into a user-friendly Android app. Install, configure, and manage your AI agent through a simple GUI.

## Features

- **Guided setup** — One-tap install with automatic environment provisioning
- **Multi-provider support** — OpenAI, Anthropic, Google Gemini, and more via AstrBot
- **Multi-platform integration** — Telegram, Discord, QQ, WeChat, and more
- **Background service** — Keeps your agent running with auto-restart
- **Hybrid storage** — Executables in internal storage (symlink-safe), caches on sdcard (reinstall-friendly)
- **No terminal required** — Everything happens through the GUI
- **No root required** — Works on non-rooted devices (root optional for advanced features)

## Installation

### Download APK

Download the latest APK from [Releases](../../releases).

### Build from Source

Prerequisites:
- Android SDK (compileSdk 36)
- NDK r29+
- JDK 17

```bash
git clone https://github.com/zhixianio/botdrop-android.git
cd botdrop-android
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/`.

## Architecture

BotDrop is built on [Termux](https://github.com/termux/termux-app), providing a Linux environment for running AI agents on Android.

```
┌──────────────────────────────────┐
│     BotDrop UI (app.botdrop)     │
├──────────────────────────────────┤
│     Termux Core (proot/apt)      │
├──────────────────────────────────┤
│  Ubuntu Rootfs (proot-distro)    │
├──────────────────────────────────┤
│  AstrBot + Python3 + pip         │
└──────────────────────────────────┘
```

### Storage Layout

```
/data/data/app.botdrop/files/          ← Internal storage (Android standard)
├── usr/                               ← Termux $PREFIX (symlink-safe ext4)
│   ├── bin/                           ← bash, proot, proot-distro, etc.
│   └── var/lib/proot-distro/
│       └── installed-rootfs/ubuntu/   ← Ubuntu rootfs runtime (~1GB+)
└── home/                              ← $HOME (AstrBot config & data)
    └── astrbot/

/storage/emulated/0/botdrop/           ← Sdcard (user-visible, backup-friendly)
└── cache/
    └── ubuntu22_openclaw.tar.gz       ← Rootfs download cache (preserved on reinstall)
```

- **Internal storage**: Required for executables and rootfs (ext4 supports symlinks)
- **Sdcard cache**: Large file downloads (~809MB rootfs), survives environment reinstalls
- Android sdcardfs/FUSE does not support symlinks, so rootfs must be extracted to internal storage

### Install Flow

BotDrop's 4-step automated setup:

| Step | Description |
|------|-------------|
| Step 0 | Initialize Termux environment |
| Step 1 | Install Termux bootstrap + proot-distro |
| Step 2 | Download & extract Ubuntu rootfs (3-tier fallback) |
| Step 3 | Install AstrBot inside Ubuntu |

**Rootfs download fallback order:**
1. Local file: `/storage/emulated/0/claw-apk/ubuntu22_openclaw.tar.gz`
2. Sdcard cache: `/storage/emulated/0/botdrop/cache/ubuntu22_openclaw.tar.gz`
3. GitHub: `TermuxCHN/rootfs` (saved to sdcard cache for future use)

Tier 1 success automatically caches to sdcard for future reinstalls.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## Crash Reporting

See [docs/crashlytics.md](docs/crashlytics.md) for Firebase Crashlytics setup, build behavior, and privacy notes.

## License

This project is licensed under the [GNU General Public License v3.0](LICENSE).

Built on [Termux](https://github.com/termux/termux-app) (GPLv3).
