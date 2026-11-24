# Runtime integration — v0.10.0

## Verified launcher state

The Android launcher host builds successfully (GitHub Actions run #16). The host can validate a runtime manifest, start its declared entrypoint, and send newline-delimited JSON input messages to the Android abstract local socket `fc2_runtime_input`.

This is only the Android-side host contract. It is **not** a bundled Windows compatibility runtime and does not yet provide a working Far Cry 2 launch.

## Runtime payload still required

The repository does not contain a complete, tested set of:
- Wine and its 32-bit Windows support;
- Box86/Box64 and their loader/runtime dependencies;
- Linux root filesystem and PRoot/environment setup;
- Mesa plus a device-appropriate graphics backend (Turnip for supported Adreno devices, or a suitable Mali path);
- DXVK/WineD3D components for Direct3D 9/10;
- X server/display integration and Android surface lifecycle;
- audio bridge and runtime-side controller input daemon.

The manifest and ProcessBuilder host code cannot replace these components. In particular, starting a Linux entrypoint as an Android child process does not by itself create a display surface or a working Wine environment.

## Winlator upstream audit

Reference project: https://github.com/brunodev85/winlator-app

The upstream repository is a full Android application, not a small embeddable graphics library. The inspected main branch contains about 1,056 tracked tree entries and includes:
- `XServerDisplayActivity`, `XServerView`, `XServerComponent`;
- `ContainerManager`, `RootFS`, `RootFSInstaller`, Wine setup/management classes;
- a native CMake build with multiple native subprojects (including renderer and Winlator-specific components);
- JNI libraries, Android resources, and additional runtime/asset handling.

Its app module uses a separate application namespace, native build configuration (CMake 3.22.1 / NDK 24), and AndroidX/Material/compression dependencies. `XServerView` alone is not sufficient: it depends on the X server, container/environment, native libraries, resources, and lifecycle orchestration.

## Integration decision

Do not copy only `XServerView` or create a fake SurfaceView placeholder. The viable one-APK path is to integrate the Winlator application engine as the base of this app (or port its complete app module), then bring the Far Cry 2 launcher UI and game-import flow into that same application. This keeps the user's requirement of one installed app without launching a separate Winlator app.

The integration must preserve the upstream LGPL-2.1 license notices and provide corresponding source/changes for redistributed LGPL components. Verify the exact license files for every imported component before packaging.

## Required integration sequence

1. Establish a reproducible build from the upstream Winlator source and record its exact commit.
2. Merge the Far Cry 2 launcher UI/import flow into the Winlator-based app, retaining one application ID and one APK.
3. Connect game selection to a Winlator container profile with a 32-bit Windows environment.
4. Add Far Cry 2 launch configuration and map the in-app touch controller to the X server input path.
5. Verify rendering, audio, suspend/resume, and stop/cleanup on physical Adreno and Mali devices.
6. Only label an APK as a game-capable build after a real Far Cry 2 process reaches visible in-app gameplay.

## Existing host packaging contract

A standalone runtime bundle (if retained for experiments) uses `runtime-manifest.json` with the keys `entrypoint`, `wine`, `box86`, `box64`, `rootfs`, and `graphicsBackend`. The packaging helper is:

```sh
python3 tools/package-runtime.py /path/to/runtime-root
```

Build-time payload validation can be required with:

```sh
./gradlew assembleDebug -PrequireBundledRuntime=true
```

Do not treat a manifest passing validation as proof that the runtime works.

## Device and compatibility notes

Far Cry 2 is a 32-bit Windows game with Direct3D 9/10-era rendering. Runtime compatibility and performance are not guaranteed. Test the same game build and settings on each GPU/backend combination. Do not force Turnip on Mali devices.
