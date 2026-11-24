# Winlator upstream pin and port inventory

Audit date: 2026-11-24

## Pinned upstream revision

- Repository: https://github.com/brunodev85/winlator-app
- Branch audited: `main`
- Exact commit: `f8a211ce7fd776de9a2642b45d166016876eaa86`
- Commit date: 2026-11-20
- Do not integrate from a moving branch; use this commit as the reproducible audit baseline.

## Findings from the pinned source

The upstream Android manifest registers more than the display activity:
- `com.winlator.MainActivity`
- `com.winlator.XServerDisplayActivity`
- `com.winlator.ControlsEditorActivity`
- `com.winlator.ExternalControllerBindingsActivity`
- `com.winlator.services.ForegroundService`
- `com.winlator.FileProvider`

The manifest also declares audio settings, wake lock, foreground service, notification, network and storage permissions/features. The Far Cry 2 app manifest must be reconciled with these requirements rather than replaced blindly.

The upstream app module uses:
- namespace/application package `com.winlator`;
- compile SDK 35, min SDK 26, target SDK 28;
- Android Gradle Plugin 8.4.2;
- NDK 24.0.8215888 and CMake 3.22.1;
- Java source under `app/src/main/java/com/winlator`;
- native build roots `winlator`, `vortekrenderer`, `virglrenderer`, `midihandler`, `libadrenotools`, and `gladiorenderer`;
- JNI libraries and resources under the app module.

The native build root is not a standalone X server library. It adds all six native subprojects. Importing only Java display classes would leave unresolved native and Java dependencies.

## License handling

The upstream root `LICENSE` is GNU LGPL version 2.1. The source tree also contains separate license files for libadrenotools, linkernsbypass, and the bundled FluidSynth component. This is not proof that every third-party dependency shares the root license.

Before redistributing a merged APK:
1. Preserve the upstream and component license texts and copyright notices.
2. Audit every native subdirectory, JNI binary, asset, and dependency for its own license.
3. Mark modified LGPL-covered files and dates.
4. Provide the corresponding source and relinking materials required by the applicable LGPL terms.
5. Keep a third-party notices inventory in the repository and in the distributed app.

## Integration order

1. Create a dedicated integration branch based on the pinned revision.
2. Import the complete upstream app module and native tree; do not cherry-pick XServerView alone.
3. Resolve package/application ID and resource namespace collisions while preserving one final Android application.
4. Merge required manifest components, permissions, services, providers, and themes.
5. Build upstream unchanged first in CI; then merge the Far Cry 2 launcher UI.
6. Add a Far Cry 2 container profile and import path; verify 32-bit Wine and Direct3D translation on real devices.
7. Verify in-app video, audio, input, background/foreground lifecycle, and cleanup before calling the APK game-capable.

## Current project status

This file records source reconnaissance and a pinned baseline only. The current Far Cry 2 APK does not yet contain the Winlator engine or a working Windows runtime. A successful launcher APK build is not evidence that Far Cry 2 can run.
