# OpenClaw Hide Update UI Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Hide OpenClaw update prompts and version-management entry points when the APK contains the offline OpenClaw bundle.

**Architecture:** Reuse the offline bundle manifest as the single source of truth. Add a small helper in `BundledOpenclawUtils`, then have the dashboard and agent-selection screens use that helper to hide version-management UI and short-circuit update checks.

**Tech Stack:** Android Java UI code, Robolectric/JUnit 4 unit tests.

---

### Task 1: Add a bundle-aware gate

**Files:**
- Modify: `app/src/main/java/app/rbot/BundledOpenclawUtils.java`
- Test: `app/src/test/java/app/rbot/BundledOpenclawUtilsTest.java`

**Step 1: Write the failing test**

Add tests for:
- bundled manifest disables version-management features
- missing manifest keeps version-management features enabled

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests app.rbot.BundledOpenclawUtilsTest --no-daemon`

Expected: FAIL because the helper does not exist yet.

**Step 3: Write minimal implementation**

Add a small helper on `BundledOpenclawUtils` that returns `true` when an offline bundle manifest is present.

**Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests app.rbot.BundledOpenclawUtilsTest --no-daemon`

Expected: PASS

### Task 2: Hide update/version UI behind the gate

**Files:**
- Modify: `app/src/main/java/app/rbot/DashboardActivity.java`
- Modify: `app/src/main/java/app/rbot/AgentSelectionFragment.java`

**Step 1: Wire the gate into dashboard**

- hide the update-check button when offline bundle mode is active
- skip automatic update checks and manual check action when disabled
- dismiss any lingering update dialog when the gate is off

**Step 2: Wire the gate into agent selection**

- hide the version-manager button when offline bundle mode is active
- do not attach the version-manager click handler when disabled
- disable the version pin easter egg when version-management is disabled

**Step 3: Run focused verification**

Run: `./gradlew :app:testDebugUnitTest --tests app.rbot.BundledOpenclawUtilsTest --no-daemon`

Expected: PASS

### Task 3: Run regression verification

**Files:**
- None unless follow-up fixes are needed

**Step 1: Run app unit tests**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS
