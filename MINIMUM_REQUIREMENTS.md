# Android system requirements

These are project targets for the launcher and a future Windows compatibility runtime. They are not benchmark results or a promise that Far Cry 2 already runs on the listed devices.

## Minimum target for installing the launcher

- Android 8.0 (API 26) or newer.
- ARM64 device preferred; the current target is `arm64-v8a`.
- 4 GB RAM can be enough for the launcher interface and file import.
- At least 2 GB free storage for the launcher and temporary import operations.

## Practical minimum target for attempting the PC game

- Android 10 or newer.
- 64-bit ARM processor.
- 6 GB RAM recommended; 4 GB is an experimental low-memory case and may be unstable.
- GPU/driver with a compatible Vulkan 1.1 or OpenGL ES 3.2 path supported by the eventual graphics translation backend.
- At least 10–12 GB free storage, depending on the size of the user's own Far Cry 2 installation, extracted runtime, shader cache, and temporary files.
- Active cooling / a device that can sustain performance without severe thermal throttling is strongly preferred.

## Better target

- Android 12 or newer.
- 8 GB RAM.
- Recent Adreno or Mali GPU with a tested driver path.
- 15 GB or more free storage.

## SoC expectations (not compatibility guarantees)

| SoC family | Initial expectation |
|---|---|
| Snapdragon 680 / 685 | Entry-level test target; expect low settings and possible driver/performance limits |
| MediaTek Helio G85 | Entry-level experimental target; compatibility must be tested |
| MediaTek Helio G99 | More promising of these targets, especially with 6–8 GB RAM; still unverified |

## Important distinctions

- The Android app's install floor is lower than the requirements for running a translated Windows game.
- RAM capacity alone does not determine performance. GPU driver support, graphics API translation, CPU sustained clocks, thermal behavior, and game configuration are critical.
- The current repository contains a native Android launcher host and HTML interface, but it does not yet contain Wine, Box64/Box86, a DirectX 9 translation backend, or the runtime input/audio stack.
- Therefore, no listed device is currently confirmed to launch the PC game. Physical-device testing is required before publishing a compatibility list.
