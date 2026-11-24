#!/usr/bin/env bash
set -euo pipefail
UPSTREAM="${1:?path to pinned Winlator checkout required}"
APP="app/src/main"
mkdir -p "$APP/java" "$APP/cpp" "$APP/jniLibs" "$APP/res" "$APP/assets"
cp -a "$UPSTREAM/app/src/main/java/com/winlator" "$APP/java/"
cp -a "$UPSTREAM/app/src/main/cpp/." "$APP/cpp/"
cp -a "$UPSTREAM/app/src/main/jniLibs/." "$APP/jniLibs/"
cp -a "$UPSTREAM/app/src/main/res/." "$APP/res/"
while IFS= read -r -d '' file; do
  rel="${file#"$UPSTREAM/app/src/main/assets/"}"
  [[ "$rel" == "index.html" ]] && continue
  mkdir -p "$APP/assets/$(dirname "$rel")"
  if [[ -e "$APP/assets/$rel" ]]; then echo "Asset collision: $rel" >&2; exit 2; fi
  cp "$file" "$APP/assets/$rel"
done < <(find "$UPSTREAM/app/src/main/assets" -type f -print0)
echo "Java: $(find "$APP/java/com/winlator" -type f -name '*.java' | wc -l)"
echo "C/C++: $(find "$APP/cpp" -type f | wc -l)"
echo "JNI: $(find "$APP/jniLibs" -type f | wc -l)"
echo "Resources: $(find "$APP/res" -type f | wc -l)"
echo "Assets: $(find "$APP/assets" -type f | wc -l)"
