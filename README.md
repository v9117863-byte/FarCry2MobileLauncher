# Far Cry 2 Mobile Launcher

Offline-first Android launcher shell for a user-supplied Far Cry 2 PC installation.

## Current status

This repository is a launcher/runtime-host scaffold, **not a playable APK**.

The Android shell includes:
- SAF game-folder selection and import into app-private storage.
- Runtime manifest validation and extraction scaffolding.
- Launch settings hand-off and basic Qualcomm/Adreno vs MediaTek/Mali device profiling.
- Touch-input event forwarding contract over an Android abstract local socket.
- A release gate that can reject builds without a complete runtime bundle.

## Missing components

No Wine, Box86/Box64, Linux root filesystem, Direct3D translation layer, GPU driver, X server, audio stack, or runtime-side input daemon is included. Consequently Far Cry 2 cannot currently be launched from this repository. No APK has been built or device-tested.

## Runtime integration

See [RUNTIME_INTEGRATION.md](RUNTIME_INTEGRATION.md) for the required runtime contract and packaging steps. The runtime template is documentation only.

Upstream reference: https://github.com/brunodev85/winlator

## Build

Open in Android Studio with Android Gradle Plugin 8.6.1 and Kotlin 2.0.21, then run:

```sh
./gradlew :app:assembleDebug
```

Android SDK/Gradle tooling is not available in the current build workspace, so an APK is not supplied.

## Game files

Use only a legally obtained copy of Far Cry 2. Game files are not included in this repository.
