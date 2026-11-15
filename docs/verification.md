# Verification

Local verification completed on **15 November 2026**. These results cover the first chapter implementation and its Android/desktop builds.

| Check | Result |
| --- | --- |
| Full Gradle `check` | Passed, including Kotlin compilation, formatting checks and Android lint |
| Simulation, progression, persistence and input tests | 54 passed |
| Shared game/UI tests | 34 passed |
| Python packaging/SDK bootstrap tests | 24 passed |
| Debug APK, minified smoke APK and unsigned release AAB | Built successfully |
| API 26 Android instrumentation | 5 of 5 passed |
| API 37 Android instrumentation | 6 of 6 passed |
| Minified smoke app | Installed and launched successfully on API 26 and API 37 |
| Desktop runner | Launched successfully and visually checked |
| Native ELF and APK ZIP alignment | 16KB checks passed for both `arm64-v8a` and `x86_64` |
| API 37 emulator memory pages | Confirmed at 16,384 bytes |

Instrumentation covers offline launch, landscape controls, four simultaneous touch pointers, explicit pause/resume, audio-focus release/recovery, device directory-sync saves, and safe-inset updates. JVM tests cover physics and scoring, AI and hazards, boss transitions, deterministic replay, progression/rewards, save recovery, pointer ownership, tutorial movement, navigation, and save/purchase failure handling.

Visual checks include the [phone menu](screenshots/phone-menu.png), [phone gameplay](screenshots/phone-game.png), [tablet chapter map](screenshots/tablet-map.png), and [desktop Larger Text + High Contrast](screenshots/desktop-accessible.png).

## Reproduce

From the repository root, use `gradlew.bat` instead of `./gradlew` on Windows:

```sh
./gradlew check :android:assembleDebug :android:assembleSmoke :android:bundleRelease
./gradlew :android:connectedDebugAndroidTest
python -m unittest discover -s tools/tests
python tools/verify_native_alignment.py android/build/outputs/apk/debug/android-debug.apk
python tools/verify_native_alignment.py android/build/outputs/bundle/release/android-release.aab
./gradlew :desktop:run
```

Run instrumentation with the intended emulator/device connected. The API 37 local emulator provides 16KB-page runtime coverage; CI is configured separately for API 26 and API 35.

## Remaining verification

- Hosted CI and automated PR reviews are pending at this local verification checkpoint.
- Physical-device input latency, sustained thermal behavior, and high-refresh performance have not been measured. Emulator and desktop results do not establish those hardware characteristics.
- The release bundle is unsigned. Production signing and Play Store publication have not been performed.
