# EffectCraft integration architecture

## Status

This is the integration baseline for Kinetic's Android-first migration. The EffectCraft source is pinned as a Git submodule at `third_party/effectcraft`; the gitlink commit is the authoritative upstream version. Updating the submodule must be an explicit, reviewed change.

- Upstream: https://github.com/storytold/effectcraft
- Pinned upstream commit: `60e2cbbbe3d73c0a9074c25a1a615ccfec18721c`
- Upstream version at this pin: `0.24.1`
- Upstream license declaration: `MIT OR Apache-2.0`

Keep the upstream `LICENSE-MIT`, `LICENSE-APACHE`, `NOTICE`, `ATTRIBUTION.md`, and per-file notices intact. Review bundled assets and transitive dependency licenses before packaging them in Kinetic.

## Product boundary

Kinetic remains the product and presentation layer. Preserve its Jetpack Compose editor, timeline gestures and scrubbing, preview controls, properties panel, property units, and Kinetic visual language. Extend its existing `SettingsScreen.kt` and `AppSettings.kt`; do not replace them with EffectCraft's egui UI.

## Runtime layers

1. **Kinetic Compose UI** — owns navigation, timeline interaction, playhead/scrubbing presentation, preview surface, property controls, and settings.
2. **Kotlin integration layer** — owns lifecycle, project migration, media URI permissions, command serialization, UI state projection, and error handling.
3. **JNI boundary** — a narrow, versioned JSON command/event interface. Do not expose Rust internal structs directly to Kotlin.
4. **Kinetic Android host (Rust)** — owns the EffectCraft session, Android-compatible services, media access adapters, render scheduling, and export integration.
5. **EffectCraft workspace** — the upstream project model, command registry, history/undo, effects, compositing, render and export capabilities, subject to Android target validation.

## Important platform constraint

The upstream workspace is a multi-application Rust workspace, not an Android library. Its root workspace includes desktop apps and dependencies such as egui/eframe, native helper processes, audio backends, and GPU integrations. The Android app must not blindly build every workspace member.

Keep the full upstream source available in the submodule, but compile only the engine and the required dependency path for the Android host. Build a platform-specific host adapter rather than assuming the desktop `effectcraft-host` is Android-ready. Validate every selected crate and codec on Android ABIs before enabling it in production.

## Integration rules

- Keep the existing Kinetic renderer available during migration; do not remove it until feature parity and performance are demonstrated.
- Treat EffectCraft commands as the engine API. The Kotlin layer should map editor actions to stable command IDs and JSON parameters.
- Translate engine events and project revisions back into Compose state. Keep all engine mutation on a controlled execution thread.
- Preserve existing `.kinetic` projects through an explicit import/migration layer. Never overwrite source projects during migration.
- Keep timeline display units and Kinetic property presentation independent from EffectCraft's internal time/property representation.
- Add capability checks so unsupported Android operations are disabled with a clear explanation rather than crashing.
- Add tests for command round-trips, undo/redo, project migration, render output, and export before switching existing workflows over.

## Android-first delivery sequence

1. Pin and vendor the complete upstream source (this commit).
2. Inventory Android-compatible crates and platform-specific dependencies.
3. Define the Kotlin/JNI protocol and Android Rust host.
4. Prove project creation, command execution, undo/redo, and a deterministic CPU render on Android.
5. Connect Kinetic's timeline and properties UI through the Kotlin integration layer.
6. Port media import, preview scheduling, effects, and export incrementally.
7. Expand settings for preview quality, cache, performance, timeline behavior, and export.
8. Keep iOS as a later host target behind the same command protocol.

## Build baseline

Kinetic currently uses Android Gradle Plugin 8.9.1, Kotlin 2.2.10, compile SDK 36, min SDK 26, and CMake 3.22.1. Keep the existing C++/GLES renderer intact while the Rust integration is introduced as a separate native library. The Rust toolchain must satisfy EffectCraft's pinned workspace requirements (edition 2024, Rust 1.95).
