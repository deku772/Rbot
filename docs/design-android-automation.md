# Android UI Automation (No-Root) Design

Goal: let Rbot/OpenClaw reliably control the device UI on a dedicated phone without root, using a structured (selector-based) API instead of coordinate clicking.

## Scope

- Read the current foreground window’s accessibility node tree.
- Perform actions on nodes: click, long-click, scroll, focus, set text.
- Perform global actions: back, home, recents, notifications.
- Synchronization primitives: wait for window/content changes and element appearance.
- Optional fallback (later): screenshots via MediaProjection for apps that hide accessibility nodes.

Non-goals:

- Bypassing Android security surfaces (lockscreen, permission dialogs, payment UIs). Some are intentionally blocked.

## Architecture

1. **AccessibilityService** (`RbotAccessibilityService`)
   - Subscribes to window/content change events.
   - Maintains a cached snapshot of the active window node tree.
   - Executes node actions (via `AccessibilityNodeInfo.performAction`).
   - Exposes a binder interface to the controller.

2. **Automation Controller Service** (`AutomationControllerService`, foreground)
   - Hosts a local API server bound to `127.0.0.1:<port>` (HTTP or WebSocket).
   - Translates API calls into binder calls on `RbotAccessibilityService`.
   - Provides request/response timeouts and a single-flight lock to avoid concurrent conflicting actions.

3. **OpenClaw Integration**
   - An OpenClaw tool/plugin calls the local API (no direct Android API access from Node).
   - Tools are selector-driven to stay stable across devices/resolutions.

## API (Proposed)

- `GET /ui/tree` -> returns a compact tree of nodes:
  - `nodeId`, `package`, `class`, `text`, `contentDesc`, `resourceId`, `bounds`, `clickable`, `enabled`, `visible`, `children[]`
- `POST /ui/find` -> `{ selector, mode: "first"|"all", timeoutMs }` -> nodes
- `POST /ui/action` -> `{ target: selector|nodeId, action, args?, timeoutMs }`
  - actions: `click`, `longClick`, `scrollForward`, `scrollBackward`, `focus`, `setText`
- `POST /ui/global` -> `{ action }` where action is `back|home|recents|notifications`
- `POST /ui/wait` -> `{ event: "windowChanged"|"contentChanged"|"exists", selector?, timeoutMs }`

## Selectors

Selectors should support:

- Exact/match: `resourceId`, `className`, `packageName`
- Text: `text`, `textContains`, `contentDescContains`
- State: `clickable`, `enabled`, `visible`
- Geometry: `boundsContains(x,y)`, `boundsIntersects(rect)`
- Composition: `and/or/not`, plus optional `parent/child` constraints.

Implementation note: compile selectors once per request, then traverse the cached tree to match; prefer stable attributes (resourceId, contentDesc) over text.

## Permissions & Setup UX

- Accessibility must be manually enabled by the user in system settings.
- Controller service runs as a foreground service with persistent notification.
- Recommend Battery = Unrestricted and Background data allowed; surface status on the launcher screen.

## Reliability Notes

- Some apps/system UIs hide nodes or reject actions; return structured errors:
  - `NOT_FOUND`, `NOT_CLICKABLE`, `SECURITY_BLOCKED`, `TIMEOUT`, `SERVICE_DISABLED`
- For “setText” failures, plan an optional IME-based input path later.

## Security Model (Dedicated Device)

Even on a dedicated phone, keep the API local-only:

- Bind to `127.0.0.1` only.
- Require a random session token stored in app-private storage for API calls.
- Reject requests if accessibility is disabled.

## Testing Plan

- Unit test selector matching on synthetic trees.
- Instrumentation tests on a simple demo app (known view IDs) for click/scroll/setText.
- Add a diagnostic screen to show current tree + last action result.

