# Winlator runtime asset audit

Audited upstream `brunodev85/winlator-app` at commit
`f8a211ce7fd776de9a2642b45d166016876eaa86` on 2026-11-24.

## Confirmed offline payload

The upstream APK source tree contains 70 files under `app/src/main/assets`
with a combined uncompressed file size of **149,550,368 bytes** (~142.7 MiB):

| Component | Bytes |
|---|---:|
| `rootfs.tzst` | 65,254,618 |
| `wincomponents/` | 41,249,248 |
| `dxwrapper/` | 15,151,720 |
| `graphics_driver/` | 10,249,040 |
| `container_pattern.tzst` | 7,399,363 |
| `box64/` | 4,544,391 |
| `rootfs_patches.tzst` | 4,174,031 |
| Remaining assets | 11,527,957 |

The total above is asset-file size only. It excludes JNI shared libraries,
compiled native libraries, Android resources, DEX, and APK overhead.
These archives are already compressed; do not assume APK compression will
reduce the payload materially.

## Runtime facts verified in upstream source

- `RootFSInstaller.FILENAME` is exactly `rootfs.tzst`.
- `RootFS.find(context)` uses `<filesDir>/rootfs`.
- RootFS validity is recorded at `rootfs/.winlator/.rfs_version`.
- Wine prefix path is `/home/xuser/.wine`.
- RootFS installation extracts the bundled asset using
  `TarCompressorUtils.extract(Type.ZSTD, activity, FILENAME, rootDir, ...)`.
- The container template is a separate required asset:
  `container_pattern.tzst`.
- The upstream native build uses CMake 3.22.1 and six subdirectories:
  `winlator`, `vortekrenderer`, `virglrenderer`, `midihandler`,
  `libadrenotools`, and `gladiorenderer`.
- The upstream app is configured for arm64-v8a, NDK 24.0.8215888,
  minSdk 26, Java 17, and includes Android activities/resources under
  the `com.winlator` namespace.

## Integration implications

The current Far Cry 2 project is still a WebView launcher with a generic
runtime-manifest/process host. It does **not** contain the Winlator Java
runtime, CMake sources, JNI libraries, RootFS, Wine container manager, or
XServer display pipeline. The generic process host cannot replace
Winlator's `XServerDisplayActivity` and `GuestProgramLauncherComponent`.

A correct integration must preserve the public launcher application ID
`com.farcry2.mobile`, relocate/namespace Winlator code and resources safely,
merge the required manifest entries, include the native build and JNI
libraries, install RootFS/container assets on first launch, create a Wine
container, then launch the guest EXE through the XServer display activity.

## Release gate

Do not mark runtime integration complete and do not publish a playable APK
until all of the following are true:

1. The complete licensed upstream source and required native components are
   integrated and compile for arm64-v8a.
2. The app installs and initializes RootFS offline on Android 10+.
3. A Wine container is created and persisted.
4. The launcher opens the integrated XServer activity without launching
   another app.
5. A real Far Cry 2 installation reaches the game process; logs and on-screen
   output confirm success.
6. Touch input and audio are tested on a physical device.

The asset audit establishes that a fully offline bundle is technically
possible, but its asset payload alone is about 143 MiB. It is not evidence
that the current launcher can run the game.
