# Verification

## Brick Buildings, 23 November 2026

The offline Gradle build passed all 137 Kotlin tests (91 simulation and persistence, 46 shared game tests), formatting, Android lint, debug APK assembly, and release AAB packaging. All 47 Python tooling tests passed. The local API 26 emulator passed six instrumentation tests, including a horizontal drag, tap to launch, explicit resume, campaign selection, and four-pointer Classic versus. Version 1 save fixtures retain Classic progress when opened in version 2.

A headless controller sampled ball positions at 10 Hz and steered the speed-limited paddle with ordinary inputs. It cleared Outpost, Hangar, Twin Towers, Observatory, Launch Gantry, and Orbital Citadel in approximately 30, 57, 67, 71, 66, and 115 seconds respectively.

## First Contact, 16 November 2026

Local verification completed on **16 November 2026**. These results cover the first chapter implementation and its Android/desktop builds.

| Check | Result |
| --- | --- |
| Full Gradle `check` | Passed, including Kotlin compilation, formatting checks and Android lint |
| Simulation, progression, persistence and input tests | 70 passed |
| Shared game/UI/audio tests | 39 passed |
| Python packaging/SDK bootstrap tests | 47 passed on Windows and Linux |
| Locked Linux SDK profiles | build, emulator26 and emulator35 installed and verified; both AVD configurations created |
| Actual Linux CI bootstrap | Fresh downloads, SDK tool execution, environment exports and archive-cache cleanup passed |
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

- Hosted verification and API 26/API 35 instrumentation passed on commit `5e66d39`; the SDK-lock and test-portability batch is validated locally and reruns CI when pushed. See [PR #1](https://github.com/lubacareer/arcade1/pull/1) for current CI and review status. The complete SDK archive lock and pin-update trust boundary are documented in [architecture.md](architecture.md).
- Physical-device input latency, sustained thermal behavior, and high-refresh performance have not been measured. Emulator and desktop results do not establish those hardware characteristics.
- macOS launcher arguments and its AAPT2 checksum are configured and validated without a Mac runtime; actual macOS execution remains untested.
- The release bundle is unsigned. Production signing and Play Store publication have not been performed.
