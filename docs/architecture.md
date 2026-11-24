# Architecture

The simulation has no Android or libGDX dependency. Brick Buildings and Classic Pong have separate engines. Brick levels use top-to-bottom character grids for both collision geometry and menu previews. Tick-indexed inputs drive a seeded match, and presentation reads state plus events. Classic Pong caps catch-up at eight ticks per render. Brick Buildings advances up to twelve 120 Hz ticks per render and retains remaining time. A backlog over 250 ms pauses the session until the player resumes. Rendering interpolation and effects never determine scores, physics, or earned rewards.

Swept collisions account for the ball's whole path within a tick. Brick pickups share the same timeline as ball contacts, so a catch applies when the falling pickup first touches the moving paddle. A bounded collision loop prevents infinite resolution; contact position and paddle velocity determine returns, and speed/angle caps keep rallies playable. AI uses observed state, reaction intervals and seeded error rather than future random outcomes.

Campaign progress is immutable. Unlocks derive from completed encounters, and available credits derive from medals minus upgrade spending. Victory updates one save snapshot. A best-result comparison prevents replay reward duplication. Local versus receives a base loadout independently of campaign state.

Version 3 stores positive winning scores under permanent building IDs. An absent score means the building has yet to be cleared. Completed buildings and the first uncleared building in display order are playable. Version 1 loads with Classic data intact and an empty building record. Version 2 maps its six positions to the original building IDs in release order. Loading leaves the original bytes in place. The next successful save writes version 3 and preserves scores for IDs outside the current catalog.

Save JSON contains data only, with an explicit version and typed validation. Writes stage and force bytes before atomic replacement, retaining a last-good backup. Unknown future versions are preserved and disable writes. Active match state is intentionally not persisted.

Android owns audio focus, haptics, safe insets and display-mode selection. Interruption pauses the game and clears input. Returning to the app does not resume a rally until the player chooses Resume.

The desktop launcher runs the same game/simulation code for iteration. Native-device frame pacing and input latency remain separate measurements from deterministic simulation tests.

## Build baseline

libGDX 1.14.2, Kotlin 2.4.21, AGP 9.3.3, Gradle 9.5.0 and JDK 21; JVM bytecode target 17. Android minSdk 26, targetSdk 36, compileSdk 37, arm64-v8a/x86_64. AGP's built-in Kotlin uses the same resolved Kotlin compiler as the JVM modules.

Dependency locks and verification metadata record the resolved build. GitHub Actions are pinned by commit. No production signing material or machine configuration belongs in the repository.

Linux CI installs the complete SDK from `tools/android-sdk-lock.json`. Each profile selects exact archive URLs, revisions and checked-in SHA-256 hashes, including the emulator and system images. Downloads are verified before extraction or execution. Package-specific registration XML and license metadata are checked in with the lock; CI does not discover new archive versions from live repository metadata.

The SDK-manager compatibility guard accepts installation requests only for verified, already-installed locked packages. Unknown or missing packages and update/uninstall operations fail. Gradle SDK auto-download is disabled. The pinned emulator action uses the same guard; its direct emulator-build download option is not enabled. Installed payload verification detects changes around permitted vendor queries. Only the SDK root .knownPackages cache may change; it must remain a bounded, non-executable regular file. Package payloads, registration XML and accepted license files remain verified.

To update an SDK pin, obtain the package metadata and archive from Google's official repository, check its published size and SHA-1, compute its SHA-256, update the lock and registration metadata together, and validate all affected profiles. SHA-256 pins protect subsequent builds against changed archives; the initial pin update still trusts the reviewed official source. The Linux installer does not manage developers' existing Windows or macOS SDKs.

## Save failure and concurrency boundaries

Repositories in the same process coordinate by canonical save directory. Every writer acquires an exclusive operating-system lock on the permanent `.campaign.lock` file before checking the current revision. It holds that lock through backup and primary publication and the final directory flush. A busy writer can retry. Replacing an existing snapshot requires a prior load, and a stale revision preserves the newer save and asks the player to reopen the game.

Save preparation creates missing directories one at a time and forces each parent entry before creating descendants or staging bytes. Existing directory entries are forced again on retry, including through a fresh repository instance. Snapshot bytes are forced before atomic replacement. Android and other POSIX platforms also force the containing directory after each replacement. Windows NIO does not support opening directory channels, so the desktop runner guarantees file flushing and atomic replacement there, without claiming equivalent power-loss durability.

Failed changes remain pending in memory with an explicit retry notice. Retry successfully before exiting; exiting while storage still reports failure can lose unconfirmed changes. The previous committed snapshot remains recoverable. Save I/O currently runs at menu, tutorial, or completed-match transitions; sustained active rallies do not write saves. Hardware storage latency at those transitions remains unmeasured.
