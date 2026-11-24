# Device optimization plan

This document defines the first-pass automatic device profile for the Far Cry 2 Android launcher. It is a configuration policy, not a claim that the Windows game runtime is already integrated or benchmarked.

## Detection inputs

Collect at runtime:
- Android API level and device model
- supported ABIs (prefer arm64-v8a)
- total and currently available RAM
- Android-reported SoC/GPU where available
- OpenGL ES / Vulkan capabilities
- display size and refresh rate
- thermal status (Android PowerManager)

Never infer a precise GPU from a marketing SoC name if Android reports a different renderer. Keep an "Unknown" fallback.

## Profiles

| Profile | Initial internal render scale | Frame pacing target | Runtime memory budget |
|---|---:|---:|---:|
| Saver | 0.60x | 30 FPS | conservative |
| Balanced | 0.70x | 30 FPS | moderate |
| Performance | 0.80x | 40 FPS cap where supported | moderate-high |
| Custom | user controlled | user controlled | bounded by available RAM |

These are starting settings for the runtime/backend, not guaranteed game FPS. The actual render scale must be applied by the graphics backend; changing a launcher setting alone does not change game rendering.

## Automatic selection

1. Prefer arm64-v8a when a compatible 64-bit runtime bundle exists.
2. If total RAM is below 5 GB, choose Saver.
3. If RAM is 5–7 GB, choose Balanced.
4. If RAM is 8 GB or more and the GPU/backend capability check passes, choose Performance.
5. If GPU/API capability is unknown, fall back one profile and show the reason.
6. If Android thermal status becomes MODERATE or worse, reduce render scale one step and cap to 30 FPS.
7. Restore the selected profile gradually after thermal status returns to NORMAL; do not oscillate settings every few seconds.
8. Persist the user's manual override and do not silently replace it on every launch.

## Memory and lifecycle

- Avoid loading game assets into the WebView; game data belongs in app-private storage.
- Stream large imports and verify free space before copying.
- Release WebView and graphics resources when the app is backgrounded.
- Keep only one game/runtime process active.
- Do not clear user game files as part of cache cleanup.
- Report low-storage and low-memory conditions before launch.

## Input and display

- Use pointer capture / multi-touch tracking with stable pointer IDs.
- Apply a configurable stick dead zone and sensitivity curve.
- Send key-down once and key-up once; release all held inputs on focus loss.
- FULL STRETCH is an explicit aspect-ratio override. Default should preserve aspect ratio to avoid distortion.
- Keep UI scaling independent from the game's internal render resolution.

## Validation still required

No performance claim is valid until tested on physical Snapdragon 680/685 and Helio G85/G99 devices. Record average FPS, 1% lows, frame-time variance, RAM peak, temperature, and crashes in a repeatable 10-minute test. The repository currently has no bundled Wine/Box64/graphics runtime and no verified APK build, so this policy cannot yet be exercised end-to-end.
