# Repository Guidelines

## Project Structure & Module Organization
This is a multi-module Android project built with Gradle.
- `app/`: main ANDBOTT Android app (UI, services, setup flow, resources, native bootstrap glue).
- `termux-shared/`: shared Termux-based Android utilities used by the app.
- `terminal-emulator/` and `terminal-view/`: terminal engine and rendering libraries.
- `app/src/test/` and `terminal-emulator/src/test/`: JVM unit tests.
- `worker/`: Cloudflare Worker used for version/update APIs.
- `docs/`, `art/`, `fastlane/`, `site/`: design docs, assets, release metadata, and website files.

## Build, Test, and Development Commands
Use JDK 17, Android SDK, and NDK (see `README.md`).
- `./gradlew assembleDebug`: build debug APK.
- `./gradlew installDebug`: install debug APK on a connected device.
- `./gradlew :app:testDebugUnitTest`: run app unit tests.
- `./gradlew :terminal-emulator:testDebugUnitTest`: run emulator module tests.
- `./gradlew clean`: remove build artifacts.
- `cd worker && npm run dev`: run Worker locally with Wrangler.
- `cd worker && npm run deploy`: deploy Worker.

## Coding Style & Naming Conventions
- Java style follows Android conventions: 4-space indentation, clear method names, small focused methods.
- Keep package structure consistent (`app.rbot.*`, `com.termux.*`).
- Class names use `PascalCase`; methods/fields use `camelCase`; constants use `UPPER_SNAKE_CASE`.
- Test classes end with `Test` (for example `RbotServiceTest`, `TerminalRowTest`).
- Prefer comments only where intent is not obvious.

## Testing Guidelines
- Test stack: JUnit 4 across modules; Robolectric is enabled in `app` tests.
- Add or update tests for behavior changes, especially setup flow, background services, and terminal/core logic.
- Run targeted module tests before opening a PR, then run full debug test/build commands.

## Commit & Pull Request Guidelines
- Follow Conventional Commits as used in history: `feat: ...`, `fix: ...`, `test: ...`.
- Keep commits focused and descriptive (one logical change per commit).
- PRs should include: concise summary, testing performed (commands/results), linked issue (if any), and screenshots/GIFs for UI changes.

## Security & Configuration Tips
- Do not commit secrets, signing keys, or local machine overrides.
- Report vulnerabilities via `SECURITY.md` process (no public security issues).
