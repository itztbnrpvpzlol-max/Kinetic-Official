# Kinetic performance and editor redesign

## Product direction

Kinetic is a mobile-first motion design workspace. The editor prioritizes the composition preview, a persistent multi-track timeline, and direct manipulation. Secondary controls should never permanently consume the preview or timeline.

### Navigation and screens
- Home: one prominent New Composition action, import actions below it, then recent projects with thumbnails, dates, and overflow actions.
- Editor: compact top bar (back, editable composition name, undo/redo, export); preview; transport/timecode; timeline; contextual bottom tools.
- Timeline: fixed layer header column, horizontally scrolling ruler/tracks, draggable playhead, resizable height, grouped transform properties, parent indicators, visibility/lock controls, and inline graph mode.
- Graph: a focused overlay attached to the selected property row, with Value/Speed modes, keyframe handles, snapping, and a close affordance that returns to the same timeline position.
- Audio: waveform lane, clip gain, mute/solo, fades, and a dedicated mixer/monitoring view.
- Properties: contextual inspector sheet, not a permanent dock.

## Visual system

- Dark graphite surfaces, restrained violet accent, high-contrast neutral text.
- 8 dp spacing rhythm; 12–18 dp corner radii; hairline separators.
- Typography uses clear hierarchy: compact metadata, readable labels, prominent composition title and timecode.
- Avoid excessive borders, gradients, tiny hit targets, and nested cards.
- Touch targets should be at least 44 dp where practical.

## Performance architecture

### Kotlin / Compose
- UI state and gestures, project persistence, media selection, timeline orchestration.
- Keep playback clock state isolated from unrelated controls; avoid recomposing the whole editor for every transport tick.
- Decode thumbnails and preview frames off the main thread; cache by source and timestamp bucket.
- Do not block gestures or rendering on project writes, metadata probes, or media decoding.

### Native C++ / NDK
- Native library is enabled through CMake and loaded behind a Kotlin bridge.
- Batch curve evaluation and transform preparation across JNI boundaries; avoid per-property JNI calls.
- Future render graph: GPU-backed composition surfaces, texture caching, frame scheduling, and native export.
- Native implementation must be benchmarked against Kotlin before routing hot paths through it.

### Audio
- Media3 ExoPlayer is the decoding/buffering layer for source audio.
- Timeline synchronization is coarse-grained and drift-corrected; do not seek every display frame.
- A future native low-latency mixer can accept decoded PCM blocks through a bounded ring buffer. It should only be introduced with a real decoder-to-mixer path and underrun metrics.

## Acceptance gates
- Build succeeds for arm64-v8a and emulator ABI.
- Scrubbing and keyframe dragging remain responsive while playback runs.
- Audio does not restart or seek continuously during steady playback.
- Playback frame pacing and audio underruns are measured on physical devices.
- No performance improvement is claimed without before/after measurements.


## Typography and icon direction

- Use a platform-native sans-serif family with a deliberate type ramp, compact metadata labels, and tabular numeric treatment for timecode where supported.
- Do not bundle Apple's San Francisco font: it is proprietary and not licensed for arbitrary redistribution. The current Android theme uses the native sans-serif family as a clean, readable baseline.
- Final direction is an iOS-quality feel: calm hierarchy, restrained tracking, consistent weights, and legible small labels.
- UI iconography should be a cohesive editable vector set designed in the Kinetic Figma file, with a consistent 24 dp grid, 2 dp optical stroke, rounded caps/joins, and clear filled/outline states.
- Figma's connected Starter plan currently blocks further MCP calls due its tool-call rate limit. The Figma-authored icon library and its export/import into Android resources remain pending; do not represent the current hand-drawn Canvas glyphs as Figma exports.
