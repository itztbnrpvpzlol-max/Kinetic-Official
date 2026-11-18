# EffectCraft Native-Core Migration

## Decision

EffectCraft becomes the authoritative project/effects/render engine. Kinetic remains the Android-first editing UI and platform integration layer. The migration is incremental and reversible; the existing Kinetic renderer remains a fallback until parity and stability are demonstrated.

## Non-negotiable constraints

- Keep Android `armeabi-v7a` (32-bit ARM) as the first device target; add other ABIs only after the 32-bit path is verified.
- Preserve existing playback stability. Do not reintroduce proactive prewarming or bitmap overlay fallbacks that were removed to fix crashes.
- Preserve and migrate existing `.kinetic` projects; never overwrite or discard the user's only project copy during migration.
- Keep the EffectCraft upstream source pinned and retain its MIT OR Apache-2.0 notices plus third-party asset/dependency notices.
- Do not expose catalogue entries as usable until their apply, parameter, and render paths work.

## Target architecture

1. **EffectCraft session owner (Rust/JNI):** one long-lived session per open project, serialized command execution, typed responses, event/revision propagation, project save/load.
2. **Project migration adapter (Kotlin):** translate legacy `.kinetic` projects into EffectCraft project items/compositions/layers. Store a migration version and preserve the original document until conversion succeeds.
3. **Android media host:** implement EffectCraft's `FootageSource` against Kinetic's Android media pipeline. Resolve content URIs through app-owned files/descriptors; decode frames at requested source times; return EffectCraft raster frames. Keep decoder ownership and cancellation explicit.
4. **Effect controls:** derive every control from `effect.list` parameter metadata (id, type, default, slider bounds, popup options), not hard-coded effect-name switches.
5. **Preview renderer:** render EffectCraft compositions through `Session::render_rgba8` (or an equivalent bounded frame API), with preview-size limits, cancellation, and cache invalidation tied to project revision and playhead time.
6. **Playback/export:** add frame scheduling and audio/video export only after still-frame preview is correct. Use EffectCraft's export interfaces with Android-specific importer/exporter implementations.
7. **Compatibility:** read old `.kinetic` versions; write a versioned document that can retain the legacy payload during rollout. Provide explicit migration failure/rollback behavior.

## Delivery gates

- Gate A: EffectCraft command/session tests on ARMv7; batch commands and error propagation.
- Gate B: one imported still and one video frame can be rendered by EffectCraft on device.
- Gate C: apply one real EffectCraft effect, edit its native parameters, and see the change in the Kinetic preview.
- Gate D: multiple effects, keyframes, save/reopen, and legacy project migration.
- Gate E: stable timeline playback and export on the target device.

A successful Gradle build or a populated effect catalogue is not proof of rendering correctness. Each gate requires an actual device test before the next one is treated as complete.
