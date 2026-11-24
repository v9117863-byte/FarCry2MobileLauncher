#!/usr/bin/env bash
set -euo pipefail
UPSTREAM="${1:?path to pinned Winlator checkout required}"
APP="app/src/main"
UPSTREAM_NAMESPACE=$(grep -E "namespace ['\"]com\.winlator['\"]" "$UPSTREAM/app/build.gradle" | head -n1 || true)
if [[ -z "$UPSTREAM_NAMESPACE" ]]; then
  echo "Pinned Winlator namespace was not found; generated R imports will not resolve." >&2
  exit 3
fi
mkdir -p "$APP/java" "$APP/cpp" "$APP/res" "$APP/assets"
mkdir -p "$APP/java/com"
cp -a "$UPSTREAM/app/src/main/java/com/winlator" "$APP/java/com/"
# Fail gracefully instead of crashing if the saved container or imported EXE vanished.
python3 - "$APP/java/com/winlator/XServerDisplayActivity.java" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
if "import android.widget.Toast;" not in s:
    s = s.replace("import android.os.Bundle;", "import android.os.Bundle;\nimport android.widget.Toast;")
if "import java.io.File;" not in s:
    s = s.replace("import java.io.", "import java.io.File;\nimport java.io.", 1)
old = """            container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));
            containerManager.activateContainer(container);"""
new = """            container = containerManager.getContainerById(getIntent().getIntExtra("container_id", 0));
            if (container == null) {
                Toast.makeText(this, "Wine-контейнер не найден. Вернись в лаунчер и повтори PLAY.", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            String requestedExecPath = getIntent().getStringExtra("exec_path");
            if (requestedExecPath != null && !new File(requestedExecPath).isFile()) {
                Toast.makeText(this, "FarCry2.exe больше не найден в импортированной папке игры.", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            containerManager.activateContainer(container);"""
if old not in s:
    raise SystemExit("Winlator launch guard insertion point not found")
s = s.replace(old, new, 1)
p.write_text(s)
PY
cp -a "$UPSTREAM/app/src/main/cpp/." "$APP/cpp/"
if [[ -d "$UPSTREAM/app/src/main/jniLibs" ]]; then
  mkdir -p "$APP/jniLibs"
  cp -a "$UPSTREAM/app/src/main/jniLibs/." "$APP/jniLibs/"
fi
# Replace temporary theme fallbacks with upstream's complete resource set.
rm -f "$APP/res/values/winlator_theme_fallback.xml"
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
echo "JNI: $(find "$APP/jniLibs" -type f 2>/dev/null | wc -l)"
echo "Resources: $(find "$APP/res" -type f | wc -l)"
echo "Assets: $(find "$APP/assets" -type f | wc -l)"
