# Firebase Crashlytics

This project supports Firebase Crashlytics for release crash reporting with open-source-safe defaults.

## Setup

1. Create a Firebase Android app for package `app.rbot`.
2. Download `google-services.json` from Firebase.
3. Place it at `app/google-services.json` (do not commit this file).
4. Build as usual:

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

If `app/google-services.json` is missing, Crashlytics Gradle plugins are not applied and app builds continue without Firebase integration.

## Collection behavior

- Debug builds default to `disabled`.
- Release builds default to `enabled`.
- Runtime toggle is available in:
  - `Settings -> Rbot -> Debugging -> Firebase Crashlytics`

## Privacy notes

- Only Firebase Crashlytics SDK is integrated.
- Firebase Analytics is not added.
- No user IDs, emails, API keys, or other custom PII fields are set by app code.
- You are responsible for updating your app privacy policy and store disclosures before distribution.

## Mapping files

When Firebase is configured (`app/google-services.json` exists), release builds enable Crashlytics mapping file upload for deobfuscated stack traces.
