#!/usr/bin/env python3
"""Validate and stage a legally obtained Winlator-compatible runtime bundle."""
import argparse, json, os, shutil, stat, sys
from pathlib import Path

REQUIRED = ("entrypoint", "wine", "box86", "box64", "rootfs", "graphicsBackend")

def fail(message):
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(2)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Runtime root containing runtime-manifest.json")
    parser.add_argument("--project", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    source = args.source.resolve()
    manifest_path = source / "runtime-manifest.json"
    if not manifest_path.is_file(): fail(f"{manifest_path} not found")
    try: manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except Exception as exc: fail(f"invalid JSON manifest: {exc}")
    missing_keys = [key for key in REQUIRED if not isinstance(manifest.get(key), str) or not manifest[key].strip()]
    if missing_keys: fail("missing manifest keys: " + ", ".join(missing_keys))
    for key in REQUIRED:
        rel = Path(manifest[key])
        if rel.is_absolute() or ".." in rel.parts: fail(f"unsafe path in {key}: {rel}")
        item = (source / rel).resolve()
        if source not in item.parents and item != source: fail(f"path escapes runtime root: {key}")
        if not item.exists(): fail(f"missing runtime component {key}: {rel}")
    entry = source / manifest["entrypoint"]
    if not entry.is_file(): fail("entrypoint must be a file")
    if not os.access(entry, os.X_OK): fail("entrypoint is not executable; chmod +x it first")
    destination = args.project / "app" / "src" / "main" / "assets" / "runtime"
    if destination.exists(): shutil.rmtree(destination)
    shutil.copytree(source, destination)
    for root, _, files in os.walk(destination):
        for name in files:
            path = Path(root) / name
            rel = path.relative_to(destination).as_posix()
            if rel == manifest["entrypoint"] or rel in (manifest["wine"], manifest["box86"], manifest["box64"]):
                path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    print(f"Validated and staged runtime {manifest.get('version', 'unknown')} -> {destination}")
    print("Next: build the APK with the Android SDK/NDK versions required by the pinned runtime.")

if __name__ == "__main__": main()
