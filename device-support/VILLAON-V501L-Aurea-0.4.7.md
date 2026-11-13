# Aurea 0.4.7 — VILLAON V501L compatibility investigation

Status: initial static analysis complete; runtime fix not yet applied.

## Target device

- Device: VILLAON V501L
- Android: 14
- SoC: Spreadtrum SC9832E
- CPU: 4× ARM Cortex-A53
- GPU: Mali-T820
- RAM: 2 GB
- Graphics reported by device: OpenGL ES 3.2
- Vulkan: unavailable on this firmware/device

## APK facts verified from the decompilation

- Package: `com.aurea.aurea`
- Version: `0.4.7-beta` (versionCode 2210)
- Minimum Android SDK: 26
- Target SDK: 36
- Manifest requires OpenGL ES 3.1 (`0x00030001`).
- Vulkan is declared optional (`required=false`).
- Native libraries in the APK are for `armeabi-v7a` only.
- `libaurea.so` is a 32-bit ARM ELF shared library (~80.2 MB).
- The native engine dynamically uses `libEGL.so` and `libGLESv3.so`, and contains a Vulkan renderer path.
- Native strings include an explicit Vulkan-unavailable fallback and GLES shader compile diagnostics.

## Compatibility assessment

The device meets the APK's declared CPU ABI, Android minimum, and OpenGL ES minimum. The absence of Vulkan should not by itself block startup because Vulkan is optional and the native engine contains a GLES fallback.

The reported failure is in the GLES path: shader compilation reports `P0005: #version must be on the first line`. This points to a shader-source/driver compatibility problem, not an APK ABI or manifest rejection.

The native library contains embedded GLSL shader text with `#version 310 es` directives and preceding shader-hash comment markers. This is an investigation lead only; it is not yet proven to be the shader that fails on the V501L.

## Important limitation

JADX produced partial Java output (it reported 112 decompilation errors), while Apktool decoded the manifest, resources, and smali. The renderer itself lives in the stripped native `libaurea.so`; the original C/C++ renderer source is not included in the APK. The recovered Java layer exposes native engine calls but no confirmed Java-level switch that disables Vulkan or repairs GLES shader source.

No native binary patch has been applied. Changing the large stripped renderer without identifying the failing shader could break rendering or startup.

## Next required evidence

Capture the device log from a fresh launch and include the complete lines around:

- `GLES_COMPILE_BEGIN`
- `GLES shader`
- `video GL: shader nao compilou`
- `P0005`
- `EGL` / `Vulkan`

The failing shader name, stage, and full compiler message are needed before applying a targeted GLES compatibility fix.