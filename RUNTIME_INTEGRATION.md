# Runtime integration — v0.8.0

## Current status
The Android host can extract a bundled runtime from assets/runtime, validate its manifest, launch the declared entrypoint, and forward controller events to its local input socket. This is a host contract, not a complete emulator.

## Runtime payload is missing
No Wine, Box86/Box64, root filesystem, Direct3D translation layer, GPU driver, X server, audio stack, or runtime-side input daemon is included. Far Cry 2 cannot currently launch from this repository.

## Runtime requirements
A complete, ABI-compatible runtime must be built or obtained separately, with licenses and notices preserved. Winlator is a relevant upstream reference: https://github.com/brunodev85/winlator

The runtime entrypoint must start an input daemon accepting newline-delimited UTF-8 JSON on Android abstract local socket fc2_runtime_input, then translate controller axes/buttons into X11/Wine mouse and keyboard events.

## Packaging
1. Build/obtain a complete licensed runtime for arm64-v8a.
2. Add runtime-manifest.json with entrypoint, wine, box86, box64, rootfs and graphicsBackend paths.
3. Ensure entrypoint is executable.
4. Run python3 tools/package-runtime.py /path/to/runtime-root.
5. Build with -PrequireBundledRuntime=true.
6. Test on actual Adreno and Mali devices. Do not force Turnip on Mali without validation.

Far Cry 2 is a 32-bit Windows game using Direct3D 9/10. Wine compatibility and performance are not guaranteed; test on each GPU/runtime combination.
