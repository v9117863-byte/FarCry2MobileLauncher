# Winlator source integration inventory

Pinned upstream: `brunodev85/winlator-app`
Commit: `f8a211ce7fd776de9a2642b45d166016876eaa86`
Audit date: 2026-11-24

## Scope measured from the pinned Git tree

| Area | Files |
|---|---:|
| Java source (`app/src/main/java`) | 276 |
| Android resources (`app/src/main/res`) | 243 |
| Native C/C++ source (`app/src/main/cpp`) | 298 |
| JNI libraries (`app/src/main/jniLibs`) | 24 |
| Runtime assets (`app/src/main/assets`) | 70 |
| **Total in these areas** | **911** |
| Entire upstream repository | **939** |

This is not a small library dependency. The runtime launch path depends on
Winlator's container model, X server, graphics renderer, Wine setup, audio
services, native libraries, resources, and packaged RootFS assets.

## Android integration constraints

The pinned upstream app uses:

- namespace and application ID `com.winlator`;
- `XServerDisplayActivity` as its guest display activity;
- `com.winlator.services.ForegroundService`;
- AndroidX AppCompat, Preference, Material, Lifecycle;
- Zstandard, XZ and Commons Compress;
- NDK `24.0.8215888`, CMake `3.22.1`, arm64-v8a;
- an upstream manifest with runtime permissions, FileProvider and multiple activities.

The Far Cry 2 launcher must retain application ID `com.farcry2.mobile`.
Therefore the upstream launcher activity must not replace our WebView
`MainActivity`. The intended architecture is to preserve the Far Cry 2
launcher as the exported entry activity and launch only the integrated
Winlator XServer display activity for the selected game.

## Safe integration sequence

1. Vendor the pinned source under a dedicated upstream namespace, retaining
   copyright headers and the upstream license.
2. Merge required dependencies, native build modules, JNI libraries and
   resources without replacing the Far Cry 2 launcher resources or package ID.
3. Merge only the required Winlator manifest declarations; keep the
   Far Cry 2 launcher as the sole MAIN/LAUNCHER activity.
4. Add an offline first-run RootFS installer using the pinned assets and
   upstream extraction/versioning behavior.
5. Create and persist a Winlator container, then pass its ID and the selected
   executable to `XServerDisplayActivity`.
6. Add CI gates for native compilation, APK installability, offline RootFS
   initialization, and an emulator smoke test.
7. Validate actual Far Cry 2 gameplay, touch controls, audio and stability
   on a physical arm64 device.

## License and redistribution

The upstream root license is LGPL-2.1. The repository also contains third-party
native components with their own license files. Before distributing a bundled
APK, retain the relevant notices and licenses and provide the corresponding
source/offer and relinking materials required by each applicable license.
The upstream root license alone does not establish redistribution rights for
every runtime asset or third-party binary; each asset's provenance and terms
must be checked separately.

## Current project status

This inventory is a source audit and integration plan, not a claim that the
upstream engine has already been copied or linked. The current app remains
the Far Cry 2 launcher shell and generic runtime host until the steps above
are implemented and verified.
