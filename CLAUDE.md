# Claude Code Development Guide

This file contains project-specific context for AI assistants working on this codebase.

## Local Development Environment

### Java/JDK Configuration

**This machine uses Homebrew-installed OpenJDK 17:**
```bash
# Java location
JAVA_HOME=/opt/homebrew/opt/openjdk@17
PATH="$JAVA_HOME/bin:$PATH"

# Version
OpenJDK 17.0.18

# Installation path
/opt/homebrew/opt/openjdk@17 -> /opt/homebrew/Cellar/openjdk@17/17.0.18
```

**For Gradle builds, set environment:**
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
```

**Verify Java:**
```bash
$JAVA_HOME/bin/java -version
# Should output: openjdk version "17.0.18"
```

### Building the Project

**Debug APK:**
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew clean assembleDebug

# Output: app/build/outputs/apk/debug/botdrop-app_*_debug.apk
```

**Run tests:**
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :app:testDebugUnitTest
```

**Clean build:**
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew clean
```

## Project Structure

- **Android App:** `app/` - Main ANDBOTT Android application
- **Termux Core:** `termux-shared/`, `terminal-*` - Forked from Termux
- **Version API:** `worker/` - Cloudflare Worker for version checking
- **Documentation:** `docs/` - Design docs, plans, troubleshooting guides
- **Bootstrap:** Downloaded from `zhixianio/botdrop-packages` during build

## Key Commands

```bash
# Build debug APK
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug

# Build release APK (requires signing keys)
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease

# Install on connected device
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew installDebug

# View logs from device
adb logcat | grep -E "ANDBOTT|Termux"
```

## Bootstrap Management

**Current strategy:** Use `/releases/latest/download/` from botdrop-packages

- Latest release should always point to a stable, tested bootstrap
- To rollback: Change latest release in GitHub, no code changes needed
- See `docs/troubleshooting/2026-02-09-slim-bootstrap-ssl-issue.md` for details

## Common Issues

### "Unable to locate a Java Runtime"

**Solution:** Set `JAVA_HOME` before running Gradle:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
```

Or add to your shell profile (`~/.zshrc` or `~/.bashrc`):
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
```

### Bootstrap SSL Certificate Issues

See: `docs/troubleshooting/2026-02-09-slim-bootstrap-ssl-issue.md`

**TL;DR:** Slim bootstraps may break Node.js fetch. Always test HTTPS requests after bootstrap changes.

### Telegram Bot Not Working - "fetch failed" Errors

See: `docs/troubleshooting/2026-02-09-telegram-fetch-ipv6-issue.md`

**TL;DR:** OpenClaw defaults `autoSelectFamily=false` for Node.js 22+, causing IPv6 connection failures. Fix by adding to `~/.openclaw/openclaw.json`:

```json
"channels": {
  "telegram": {
    "network": { "autoSelectFamily": true },
    ...
  }
}
```

## Related Documentation

- `CONTRIBUTING.md` - Contribution guidelines
- `README.md` - Project overview
- `docs/design.md` - Architecture design (Chinese)
- `docs/troubleshooting/` - Troubleshooting guides

---

_Last updated: 2026-02-09_
