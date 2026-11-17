# Kinetic EffectCraft Android bridge

This crate is the Android-specific JNI boundary. It intentionally depends on
`effectcraft-engine`, not the desktop `effectcraft-host` or egui frontend.

## Current scope

- Own one process-wide EffectCraft `Session`.
- Accept a stable command ID and JSON object parameters.
- Return a versioned JSON response containing the command result, project revision,
  and serialized engine events.
- Keep Kinetic's existing Kotlin/C++ renderer active as a fallback.

The default `Session::new()` uses EffectCraft's default filesystem services and
`NoFootage`. Media import, Android content-URI handling, footage decoding, GPU
acceleration, and export require dedicated Android host adapters and are not enabled
by this initial bridge.

## Build prerequisites

- Rust 1.95 or newer (the pinned upstream workspace uses edition 2024).
- Android NDK and the Android Rust target toolchains.
- `cargo-ndk` installed.
- The repository cloned with submodules:
  `git clone --recurse-submodules ...`

Normal Android builds include the JNI library automatically. Build it for the app's
configured Android ABIs with:

```sh
./gradlew :app:assembleDebug
```

The Gradle task runs `cargo ndk` and packages the Rust library under
`app/src/main/jniLibs`. Set `-PKINETIC_BUILD_EFFECTCRAFT=false` only when a build
pipeline has already compiled and copied the matching JNI library into that folder.
The existing CMake/GLES renderer remains available as a fallback.
