# Architecture

The simulation has no Android or libGDX dependency. Tick-indexed inputs drive a seeded match, and presentation reads state plus events. A fixed-step runner caps catch-up at eight ticks. Rendering interpolation and effects never determine scores, physics, or earned rewards.

Swept collisions account for the ball's whole path within a tick. A bounded collision loop prevents infinite resolution; contact position and paddle velocity determine returns, and speed/angle caps keep rallies playable. AI uses observed state, reaction intervals and seeded error rather than future random outcomes.

Campaign progress is immutable. Unlocks derive from completed encounters, and available credits derive from medals minus upgrade spending. Victory updates one save snapshot. A best-result comparison prevents replay reward duplication. Local versus receives a base loadout independently of campaign state.

Save JSON contains data only, with an explicit version and typed validation. Writes stage and force bytes before atomic replacement, retaining a last-good backup. Unknown future versions are preserved and disable writes. Active match state is intentionally not persisted.

Android owns audio focus, haptics, safe insets and display-mode selection. Interruption pauses the game and clears input. Returning to the app does not resume a rally until the player chooses Resume.

The desktop launcher runs the same game/simulation code for iteration. Native-device frame pacing and input latency remain separate measurements from deterministic simulation tests.

## Build baseline

libGDX 1.14.2, Kotlin 2.4.21, AGP 9.3.3, Gradle 9.5.0 and JDK 21; JVM bytecode target 17. Android minSdk 26, targetSdk 36, compileSdk 37, arm64-v8a/x86_64. AGP's built-in Kotlin uses the same resolved Kotlin compiler as the JVM modules.

Dependency locks and verification metadata record the resolved build. GitHub Actions are pinned by commit. No production signing material or machine configuration belongs in the repository.

## Save failure and concurrency boundaries

Repositories in the same process coordinate by canonical save directory and reject stale snapshots instead of merging conflicting reset, purchase, or reward transactions. A conflict preserves the newer disk save and asks the player to reopen the game. Separate desktop processes must not share an active save directory.

Snapshot bytes are forced before atomic replacement. Android and other POSIX platforms also force the containing directory after each replacement. Windows NIO does not support opening directory channels, so the desktop runner guarantees file flushing and atomic replacement there, without claiming equivalent power-loss durability.

Failed changes remain pending in memory with an explicit retry notice. Retry successfully before exiting; exiting while storage still reports failure can lose unconfirmed changes. The previous committed snapshot remains recoverable. Save I/O currently runs at menu, tutorial, or completed-match transitions; sustained active rallies do not write saves. Hardware storage latency at those transitions remains unmeasured.
