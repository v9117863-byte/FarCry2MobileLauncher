# Runtime bundle staging template

This folder is a layout guide only. It intentionally contains no Wine, Box86/Box64, root filesystem, DXVK, Mesa driver, X server, or other executable payload.

A complete ABI-compatible runtime must be obtained/built separately with licenses and notices preserved. Run:

```sh
python3 tools/package-runtime.py /absolute/path/to/runtime-root
```

The runtime root must contain runtime-manifest.json and every referenced file. The entrypoint must be executable. Do not put copyrighted game files in this folder.
