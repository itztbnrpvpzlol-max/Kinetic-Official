# VILLAON V501L — deeper GLES static-analysis notes

Analysis target: Aurea 0.4.7-beta, package `com.aurea.aurea`, extracted from the successful GZ decompiler artifact.

## Confirmed facts

- The APK's required GLES level is 3.1; the device reports GLES 3.2.
- Vulkan is marked optional in the manifest.
- The APK ships `armeabi-v7a` native libraries, including a stripped `libaurea.so` (~80.2 MB).
- The native library includes a GLES shader compile log marker: `GLES_COMPILE_BEGIN name=%s stage=%u bytes=%zu`.
- It also includes a general shader failure message: `video GL: shader nao compilou: %s`.
- The native library contains hundreds of embedded GLSL strings. In the binary, many begin with a custom comment of the form `// AUREA_SPIRV_FNV64 <hash>` immediately before `#version 310 es`.

## Working hypothesis (not yet confirmed)

The V501L's reported `P0005: #version must be on the first line` may be caused by a strict GLES shader compiler rejecting the custom comment/header arrangement, or by a dynamically assembled shader that places another line before its `#version` directive. The embedded comment is legal GLSL in principle, so it must not be assumed to be the cause without the failing shader name and complete driver log.

## Why no blind native patch was made

The custom FNV64 marker may be used by the engine to associate GLSL with cached SPIR-V. Removing or moving it throughout the native library could damage shader lookup or Vulkan behavior. The library is stripped and the original renderer source is absent from the APK. A safe patch needs the exact failing shader name, stage, and source assembly path.

## Phone-only evidence collection

No computer or ADB is required. On the Android phone:
1. Open Settings and search for **Developer options**.
2. If Developer options are not enabled, open **About phone** and tap **Build number** seven times (enter the device PIN if asked).
3. Open **Developer options** and choose **Take bug report** (choose Full report if prompted).
4. Reproduce the Aurea launch/graphics failure when prompted, then wait for Android to prepare the report.
5. Share the generated ZIP file into the ChatGPT conversation as an attachment.

The report should include Android logcat lines that identify the failing shader. Review the report before sharing if it contains unrelated personal information.

## Next engineering step

Use the exact shader name and stage from the phone's bug report to trace the matching embedded or generated source. Then test a narrowly scoped GLES source-normalization fix (ensure `#version` is the first directive) without altering unrelated shader strings or the SPIR-V hash metadata. No APK binary has been modified yet.
