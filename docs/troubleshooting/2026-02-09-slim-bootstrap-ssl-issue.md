# Slim Bootstrap SSL Certificate Issue

**Date:** 2026-02-09
**Status:** Root cause identified, rolled back to stable version
**Severity:** Critical - breaks all HTTPS network requests

## Summary

After deploying slim bootstrap (94.6MB, reduced from 159MB), the Telegram bot failed to respond. All `fetch` API calls in Node.js failed with `TypeError: fetch failed`.

**Root cause:** Slim bootstrap's "non-essential file removal" removed the configuration that sets the `SSL_CERT_FILE` environment variable, causing Node.js/undici to fail finding CA certificates.

## Symptoms

From `gateway.log`:
```
[openclaw] Non-fatal unhandled rejection (continuing): TypeError: fetch failed
    at node:internal/deps/undici/undici:16416:13
[telegram] sendChatAction failed: Network request for 'sendChatAction' failed!
[telegram] sendMessage failed: Network request for 'sendMessage' failed!
```

**Pattern:**
- Gateway starts successfully ✓
- Telegram bot connection initializes ✓
- ALL HTTP/HTTPS requests fail via Node.js fetch ✗

## Root Cause Analysis

### Investigation Steps

1. **Error pattern:** All `fetch` calls fail at undici (Node.js internal fetch implementation)

2. **CA certificate check:**
   ```bash
   ls -la /data/data/app.rbot/files/usr/etc/tls/
   # Result: cert.pem (225KB) EXISTS

   ls -la /data/data/app.rbot/files/usr/etc/ssl/certs/
   # Result: Directory does NOT exist
   ```

3. **Environment variable check:**
   ```bash
   echo $SSL_CERT_FILE    # Empty
   echo $SSL_CERT_DIR     # Empty
   echo $NODE_EXTRA_CA_CERTS  # Empty
   ```

4. **Hypothesis test:**
   ```bash
   export SSL_CERT_FILE=/data/data/app.rbot/files/usr/etc/tls/cert.pem
   node -e "fetch('https://api.telegram.org').then(r => console.log('SUCCESS:', r.status))"
   # Result: SUCCESS: 200
   ```

### Root Cause

**Node.js `fetch` (undici) uses system OpenSSL** and looks for CA certificates in:
1. `SSL_CERT_FILE` environment variable (file path)
2. `SSL_CERT_DIR` environment variable (directory path)
3. System defaults: `/etc/ssl/cert.pem` or `/etc/ssl/certs/`

**What slim bootstrap broke:**
- Removed the configuration that sets `SSL_CERT_FILE=/data/data/app.rbot/files/usr/etc/tls/cert.pem`
- The certificate file exists, but Node.js can't find it without the environment variable

**Why it worked before:**
The full bootstrap (159MB) included proper environment variable configuration, likely in:
- Profile scripts (`/etc/profile.d/`)
- Or bash initialization files

## Solution

### Quick Fix (for testing)

Set environment variable before running gateway:
```bash
export SSL_CERT_FILE=/data/data/app.rbot/files/usr/etc/tls/cert.pem
```

### Permanent Fix (for future slim bootstrap)

When creating slim bootstrap, ensure one of these:

**Option A: Set environment variable in profile**
```bash
# In /etc/profile.d/ssl-certs.sh or similar
export SSL_CERT_FILE=/data/data/app.rbot/files/usr/etc/tls/cert.pem
```

**Option B: Add to gateway startup**
Modify `RbotService.java` executeCommandSync():
```java
pb.environment().put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
pb.environment().put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
pb.environment().put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + System.getenv("PATH"));
pb.environment().put("TMPDIR", TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH);
// ADD THIS:
pb.environment().put("SSL_CERT_FILE", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/tls/cert.pem");
```

**Option C: Create symlink to standard location**
```bash
mkdir -p /usr/etc/ssl
ln -s /usr/etc/tls/cert.pem /usr/etc/ssl/cert.pem
```

## Lessons Learned

### For Bootstrap Optimization

1. **Test network functionality after any slim optimization**
   - Don't just test that gateway starts
   - Test actual HTTP/HTTPS requests
   - Test with real Telegram/Discord bots

2. **Document what was removed**
   - Keep a diff or changelog of "non-essential file removal"
   - Helps debugging when issues arise

3. **Check environment variables**
   - Many Node.js/system features depend on env vars
   - `SSL_CERT_FILE`, `NODE_OPTIONS`, etc.

4. **Consider the risk/benefit ratio**
   - 159MB → 94.6MB saves ~65MB
   - But debugging cost is high
   - Risk of hidden issues in production

### For Debugging

1. **Systematic approach works**
   - Phase 1: Investigate root cause (don't guess)
   - Phase 2: Compare working vs broken
   - Phase 3: Form hypothesis and test minimally
   - Phase 4: Implement single fix

2. **Test hypotheses with minimal changes**
   - Single environment variable change confirmed root cause
   - No need to reinstall or rebuild

3. **Trust but verify**
   - CA certificates existed, but weren't being used
   - Always check actual behavior, not just file presence

## Decision: Rollback to Stable

**Chosen approach:** Revert to pre-slim bootstrap (166MB)

**Rationale:**
- Critical production issue affecting all users
- Unknown what else might be broken by "non-essential file removal"
- ~65MB savings not worth the risk and debugging cost
- Can re-attempt slim optimization later with proper testing

**Rollback details:**
- Target version: `bootstrap-2026.02.07-r1+rbot` (166MB)
- Latest slim version: `bootstrap-2026.02.08-r1+rbot` (99MB) ❌

**Action items:**
1. ✅ Identify last working bootstrap version: `bootstrap-2026.02.07-r1+rbot`
2. ✅ Update CI to use fixed version (not `/latest/`)
3. ⏳ Set `bootstrap-2026.02.07-r1+rbot` as latest release in rbot-packages repo
4. ⏳ Test end-to-end before releasing
5. ✅ Document this issue for future reference

**How to set correct latest release:**
```bash
# In GitHub rbot-packages repository:
# https://github.com/zhixianio/rbot-packages/releases
#
# 1. Find "bootstrap-2026.02.07-r1+rbot" release
# 2. Click "..." menu → "Set as latest release"
# 3. This ensures /releases/latest/ points to stable version
```

## References

- [Node.js undici CA certificates discussion](https://github.com/nodejs/undici/discussions/1364)
- [Node.js Enterprise Network Configuration](https://nodejs.org/en/learn/http/enterprise-network-configuration)
- [NODE_EXTRA_CA_CERTS usage](https://answers.netlify.com/t/correct-path-for-node-extra-ca-certs/47437)
- Systematic debugging skill: `/Users/zhixian/.claude/plugins/cache/superpowers-marketplace/superpowers/4.0.3/skills/systematic-debugging`

---

_Documented with [Claude Code](https://claude.ai/claude-code)_
