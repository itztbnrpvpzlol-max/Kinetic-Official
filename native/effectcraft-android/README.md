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

From this directory, build the supported ABI libraries into the Android app's JNI
library directory:

```sh
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o ../../app/src/main/jniLibs build --release
```

The Gradle build is not wired to invoke this command automatically yet. First validate
the Rust dependency graph and ABI builds independently; then add a reproducible Gradle
task/CI step. Do not remove the existing CMake/GLES engine while this bridge is being
validated.
