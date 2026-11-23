# Space Pong

A free, offline Android arcade game written in Kotlin and libGDX. **Brick Buildings** is a single-paddle brick breaker. **Classic Pong** includes the original four-encounter solo campaign and same-device two-player duels.

**No ads, purchases, paid advantages, accounts, or network permission.**

## Brick Buildings

Play in landscape. Drag horizontally to move the bottom paddle. Tap to launch the docked ball, then use the edges of the paddle to angle your returns. On desktop, use the left and right arrows and Space.

Clear each building to unlock the next: Outpost, Hangar, Twin Towers, Observatory, Launch Gantry, and Orbital Citadel. You have three lives per building. A miss keeps broken bricks cleared, and a retry gives you a fresh layout.

- A marked brick drops a pickup. Catch **Wide Paddle** for eight seconds of extra reach, or **Shield** to save one missed ball.
- Chain hits between paddle returns for 100, 200, 300, then 400 points per brick. A paddle return, shield save, or lost ball resets the combo.
- Your best winning score stays on the device for each building.

## Classic Pong

Drag vertically on the left to move. Tap **Pulse** just before contact to boost the return. Win the first three duels by scoring five goals, then defeat the Warden. Improve medals to earn permanent upgrades and respec for free. In local versus, two players use their own half and Pulse button, with equal equipment. First to seven wins. Desktop controls are W/S and left Shift for player one, arrows and right Shift for player two.

[Classic duel on Android](docs/screenshots/phone-game.png) | [Classic chapter map](docs/screenshots/tablet-map.png)

Both campaigns share accessibility and sound settings. They track and reset progress independently. Android interruptions pause the action until you resume. After process death, restart the active level from its beginning.

## Build

Required: JDK 21, Android SDK platform 37, build tools 36.0.0, and Python 3 for packaging verification. The Gradle wrapper verifies its distribution checksum. No system Gradle installation is needed.

Windows PowerShell, from the repository root:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:ANDROID_HOME = 'C:\path\to\Android\Sdk'
.\gradlew.bat check :android:assembleDebug :android:bundleRelease
.\gradlew.bat :desktop:run
```

Reopen terminals after permanently changing environment variables. Keep machine paths in environment variables or ignored `local.properties`.

Linux/macOS:

```sh
chmod +x gradlew
./gradlew check :android:assembleDebug :android:bundleRelease
./gradlew :desktop:run
```

Linux CI installs an isolated SDK from the checked-in archive lock. To install the same build profile into a fresh directory on a Linux filesystem:

```sh
python3 tools/android_sdk.py install --profile build \
  --sdk-root "$HOME/.cache/space-pong/locked-sdk" \
  --cache-dir "$HOME/.cache/space-pong/sdk-archives" --accept-licenses
export ANDROID_HOME="$HOME/.cache/space-pong/locked-sdk"
```

The installer preserves existing nonempty SDK directories. Emulator CI uses the `emulator26` and `emulator35` profiles. See [architecture](docs/architecture.md) for verification and pin updates.
Outputs:

- Installable test build: `android/build/outputs/apk/debug/android-debug.apk`
- Unsigned release bundle: `android/build/outputs/bundle/release/android-release.aab`
- Minified smoke build with the development signing key: `./gradlew :android:assembleSmoke`

Debug and smoke signatures are for testing. Production signing and store publication are deliberately separate from this source delivery.

## Verify

```sh
./gradlew check
./gradlew :android:connectedDebugAndroidTest
python -m unittest discover -s tools/tests
python tools/verify_native_alignment.py android/build/outputs/apk/debug/android-debug.apk
python tools/verify_native_alignment.py android/build/outputs/bundle/release/android-release.aab
```

`check` runs simulation/persistence/input/UI tests, Kotlin compilation, formatting checks and Android lint. Tests cover swept brick collisions, pickups, combo resets, score persistence, Classic duels, save migration, and pointer ownership. The packaging verifier rejects malformed libraries and checks ELF/ZIP alignment rather than treating missing data as success.

CI builds Android artifacts and runs emulator tests on API 26 and 35. Device performance and touch feel require separate hardware validation; emulator results do not establish physical-device latency or sustained thermal performance.

## Structure

- `simulation`: deterministic 120Hz gameplay, AI, campaign model, local saves and input ownership.
- `game`: shared rendering, menus, audio, effects and platform-independent screen flow.
- `android`: app lifecycle, safe insets, audio focus, haptics, display selection and packaging.
- `desktop`: development/playtesting launcher.

See [verified results and remaining checks](docs/verification.md), [architecture](docs/architecture.md), [asset provenance](docs/assets.md), and the [original Classic campaign plan](https://baz.co/plans/01a1dee5-a7a2-7e13-b215-0fd241dbec57).
