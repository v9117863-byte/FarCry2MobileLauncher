#!/usr/bin/env bash
set -euo pipefail

UPSTREAM="${1:-.ci/winlator-upstream}"
REPORT="${2:-WINLATOR_UPSTREAM_PREFLIGHT.txt}"
ROOTFS="$UPSTREAM/app/src/main/assets/rootfs.tzst"
CMAKE="$UPSTREAM/app/src/main/cpp/CMakeLists.txt"
MANIFEST="$UPSTREAM/app/src/main/AndroidManifest.xml"
JAVA="$UPSTREAM/app/src/main/java/com/winlator/XServerDisplayActivity.java"

test -f "$ROOTFS"
test -f "$CMAKE"
test -f "$MANIFEST"
test -f "$JAVA"

required_modules=(winlator vortekrenderer virglrenderer midihandler libadrenotools gladiorenderer)
for module in "${required_modules[@]}"; do
  test -f "$UPSTREAM/app/src/main/cpp/$module/CMakeLists.txt" || {
    echo "Missing native module: $module" >&2
    exit 1
  }
done

for asset in container_pattern.tzst rootfs_patches.tzst; do
  test -f "$UPSTREAM/app/src/main/assets/$asset" || {
    echo "Missing required runtime asset: $asset" >&2
    exit 1
  }
done

java_count=$(find "$UPSTREAM/app/src/main/java" -type f -name '*.java' | wc -l)
cpp_count=$(find "$UPSTREAM/app/src/main/cpp" -type f \( -name '*.cpp' -o -name '*.c' -o -name '*.h' -o -name '*.hpp' \) | wc -l)
resource_count=$(find "$UPSTREAM/app/src/main/res" -type f | wc -l)
jni_count=$(find "$UPSTREAM/app/src/main/jniLibs" -type f 2>/dev/null | wc -l)
asset_count=$(find "$UPSTREAM/app/src/main/assets" -type f | wc -l)
asset_bytes=$(du -sb "$UPSTREAM/app/src/main/assets" | cut -f1)
rootfs_bytes=$(stat -c%s "$ROOTFS")

cat > "$REPORT" <<EOF
Winlator upstream preflight
Pinned commit: f8a211ce7fd776de9a2642b45d166016876eaa86
Java files: $java_count
Native source files: $cpp_count
Resource files: $resource_count
JNI files: $jni_count
Runtime asset files: $asset_count
Runtime asset bytes: $asset_bytes
RootFS archive bytes: $rootfs_bytes
Native modules: ${required_modules[*]}
Display activity: present
RootFS archive: present
Container template and RootFS patches: present

This report verifies upstream inputs only. It does NOT claim that the
Winlator runtime is integrated into the Far Cry 2 APK or that the game runs.
EOF

cat "$REPORT"
