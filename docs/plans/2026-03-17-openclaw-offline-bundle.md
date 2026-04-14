# OpenClaw Offline Bundle Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Ship a pinned OpenClaw runtime and the QQ plugin inside the APK so Android installation no longer depends on `npm install` or GitHub reachability.

**Architecture:** Generate a bundled OpenClaw asset set at build time from prebuilt local archives, stage those assets onto the device before install/update, and switch install/update flows from `npm install` to extraction of the bundled runtime plus wrapper regeneration. QQ plugin installation becomes an offline asset deployment into the OpenClaw global extensions directory instead of a runtime marketplace install.

**Tech Stack:** Gradle generated assets, Android `AssetManager`, Java service/install code, Termux shell scripts, JUnit/Robolectric tests.

---

### Task 1: Add generated offline asset packaging to the Android build

**Files:**
- Modify: `app/build.gradle`
- Create: `app/src/main/java/app/rbot/BundledOpenclawUtils.java`
- Test: `app/src/test/java/app/rbot/BundledOpenclawUtilsTest.java`

**Step 1: Write the failing tests**

Add tests for:
- missing bundled asset manifest returns “not bundled”
- manifest parsing returns pinned version and asset names
- install version normalization prefers bundled version when appropriate

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests app.rbot.BundledOpenclawUtilsTest --no-daemon`

Expected: FAIL because `BundledOpenclawUtils` does not exist yet.

**Step 3: Write minimal implementation**

Implement `BundledOpenclawUtils` with:
- asset path constants for offline bundle manifest, runtime tarball, and QQ plugin payload
- manifest parser using `AssetManager`
- helpers for bundled-version checks and target paths under `$PREFIX/share/rbot/offline-openclaw`

Update `app/build.gradle` to:
- add a generated assets directory under `build/generated/assets/offlineOpenclaw`
- create a `prepareBundledOpenclawAssets` task
- read bundle inputs from env or Gradle properties
- copy the prebuilt OpenClaw runtime tarball into generated assets
- unpack the QQ plugin tarball into generated assets as a plain extension directory
- emit a tiny manifest file describing bundled version, runtime archive name, and QQ plugin directory
- wire `mergeDebugAssets` / `mergeReleaseAssets` to depend on the task only when bundle inputs are configured

**Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests app.rbot.BundledOpenclawUtilsTest --no-daemon`

Expected: PASS

**Step 5: Commit**

```bash
git add app/build.gradle app/src/main/java/app/rbot/BundledOpenclawUtils.java app/src/test/java/app/rbot/BundledOpenclawUtilsTest.java
git commit -m "feat: add generated offline openclaw assets"
```

### Task 2: Replace install/update npm flow with offline bundle extraction

**Files:**
- Modify: `app/src/main/java/com/termux/app/TermuxInstaller.java`
- Modify: `app/src/main/java/app/rbot/RbotService.java`
- Modify: `app/src/main/java/app/rbot/AgentSelectionFragment.java`
- Test: `app/src/test/java/app/rbot/RbotServiceTest.java`
- Test: `app/src/test/java/app/rbot/OpenclawVersionUtilsTest.java`

**Step 1: Write the failing tests**

Add tests for:
- install version preference resolves to the bundled version when the bundle is present and the requested version is `openclaw@latest`
- offline install shell generation references the staged runtime tarball instead of `npm install`
- update path chooses offline extraction when the bundled version is targeted

**Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests app.rbot.RbotServiceTest \
  --tests app.rbot.OpenclawVersionUtilsTest \
  --no-daemon
```

Expected: FAIL on missing offline install behavior.

**Step 3: Write minimal implementation**

Change the install/update pipeline to:
- stage bundled assets from APK assets to `$PREFIX/share/rbot/offline-openclaw`
- generate `install.sh` with an offline branch that:
  - extracts the runtime archive into a stable versioned runtime directory
  - installs or verifies `sharp-node-addon` from apt only
  - recreates the Android wrapper pointing at the bundled runtime root
  - deploys QQ plugin files into `~/.openclaw/extensions/qqbot`
  - patches `koffi` only if the bundle still ships it
- make `RbotService.installOpenclaw()` refresh scripts and stage assets before executing the script
- make `RbotService.updateOpenclaw()` reuse the same offline staged bundle when the bundled version is selected
- make `AgentSelectionFragment` default “latest” installs to the bundled version when bundled assets exist

**Step 4: Run tests to verify they pass**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests app.rbot.RbotServiceTest \
  --tests app.rbot.OpenclawVersionUtilsTest \
  --no-daemon
```

Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/com/termux/app/TermuxInstaller.java app/src/main/java/app/rbot/RbotService.java app/src/main/java/app/rbot/AgentSelectionFragment.java app/src/test/java/app/rbot/RbotServiceTest.java app/src/test/java/app/rbot/OpenclawVersionUtilsTest.java
git commit -m "feat: install bundled openclaw runtime offline"
```

### Task 3: Remove QQ plugin’s runtime online install dependency

**Files:**
- Modify: `app/src/main/java/app/rbot/ChannelFormFragment.java`
- Modify: `app/src/main/java/app/rbot/ChannelSetupHelper.java` (if install metadata is needed)
- Test: `app/src/test/java/app/rbot/ChannelSetupHelperTest.java`

**Step 1: Write the failing tests**

Add tests for:
- bundled QQ plugin presence skips remote plugin installation
- local config/plugin entry still enables QQ bot after offline deployment

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests app.rbot.ChannelSetupHelperTest --no-daemon`

Expected: FAIL because the code still expects `openclaw plugins install @sliverp/qqbot@latest`.

**Step 3: Write minimal implementation**

Update QQ channel setup to:
- treat bundled QQ plugin assets as satisfying plugin installation
- keep `openclaw plugins list` only as a compatibility check for already-installed non-bundled cases
- skip remote install when bundled plugin deployment has already happened

**Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests app.rbot.ChannelSetupHelperTest --no-daemon`

Expected: PASS

**Step 5: Commit**

```bash
git add app/src/main/java/app/rbot/ChannelFormFragment.java app/src/main/java/app/rbot/ChannelSetupHelper.java app/src/test/java/app/rbot/ChannelSetupHelperTest.java
git commit -m "feat: prebundle qq plugin for offline install"
```

### Task 4: Verify build outputs and device install flow

**Files:**
- Modify as needed from previous tasks

**Step 1: Build with real bundled inputs**

Run:

```bash
BOTDROP_OPENCLAW_BUNDLE_TGZ=/abs/path/to/openclaw-runtime.tar.gz \
BOTDROP_QQBOT_PLUGIN_TGZ=/abs/path/to/sliverp-qqbot-1.5.4.tgz \
./gradlew :app:assembleDebug :app:installDebug --no-daemon
```

Expected: APK includes generated offline assets and installs on device.

**Step 2: Verify on device**

Run:

```bash
adb shell run-as app.rbot ls -R files/usr/share/rbot/offline-openclaw
adb shell logcat -d -v time | grep -E 'BundledOpenclaw|Rbot\\.TermuxInstaller|Rbot\\.RbotService|qqbot'
```

Expected:
- staged manifest and runtime archive present
- install logs show offline extraction path instead of `npm install`
- QQ plugin deployed under `~/.openclaw/extensions/qqbot`

**Step 3: Run targeted regression tests**

Run:

```bash
./gradlew :app:testDebugUnitTest \
  --tests app.rbot.BundledOpenclawUtilsTest \
  --tests app.rbot.RbotServiceTest \
  --tests app.rbot.OpenclawVersionUtilsTest \
  --tests app.rbot.ChannelSetupHelperTest \
  --no-daemon
```

Expected: PASS

**Step 4: Commit**

```bash
git add docs/plans/2026-03-17-openclaw-offline-bundle.md
git commit -m "docs: add offline openclaw bundle plan"
```
