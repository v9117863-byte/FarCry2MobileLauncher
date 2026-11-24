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
field_marker = "public class XServerDisplayActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {"
field_patch = field_marker + "\n    private final java.util.concurrent.atomic.AtomicBoolean startupFinished = new java.util.concurrent.atomic.AtomicBoolean(false);"
if field_marker not in s:
    raise SystemExit("Activity class declaration not found")
s = s.replace(field_marker, field_patch, 1)
if "import android.widget.Toast;" not in s:
    s = s.replace("import android.os.Bundle;", "import android.os.Bundle;\nimport android.widget.Toast;")
if "import android.app.AlertDialog;" not in s:
    s = s.replace("import android.app.Activity;", "import android.app.Activity;\nimport android.app.AlertDialog;")
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
# Keep the failure visible instead of silently restarting the launcher when Wine exits.
old_cb = 'guestProgramLauncherComponent.setTerminationCallback((status) -> exit());'
new_cb = """guestProgramLauncherComponent.setTerminationCallback((status) -> runOnUiThread(() -> {
            new AlertDialog.Builder(this)
                .setTitle("Far Cry 2 не запустилась")
                .setMessage("Игровой процесс завершился с кодом " + status + ". Экран Winlator оставлен открытым, чтобы не возвращать тебя молча в лаунчер. Проверь EXE и настройки контейнера.")
                .setPositiveButton("Вернуться в лаунчер", (dialog, which) -> finish())
                .setNegativeButton("Остаться здесь", null)
                .setCancelable(false)
                .show();
        }));"""
if old_cb not in s:
    raise SystemExit("Winlator termination callback insertion point not found")
s = s.replace(old_cb, new_cb, 1)
# Catch failures during Winlator's background initialization; otherwise the display stays blank forever.
startup_old = """        Executors.newSingleThreadExecutor().execute(() -> {
            if (!isGenerateWineprefix()) {
                setupWineSystemFiles();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
            }
            setupXEnvironment();
        });"""
startup_new = """        final java.util.concurrent.atomic.AtomicReference<String> startupStage = new java.util.concurrent.atomic.AtomicReference<>("Подготовка Winlator");
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (startupFinished.get() || isFinishing()) return;
            String stage = startupStage.get();
            android.util.Log.e("FarCry2Startup", "Startup timeout; current stage: " + stage);
            new AlertDialog.Builder(this)
                .setTitle("Запуск Far Cry 2 задерживается")
                .setMessage("Winlator всё ещё выполняет этап:\\n\\n" + stage + "\\n\\nМожно подождать или вернуться в лаунчер. Подробности этапов записаны в системный журнал Android.")
                .setPositiveButton("Продолжить ожидание", null)
                .setNegativeButton("Вернуться в лаунчер", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
        }, 120000);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (!isGenerateWineprefix()) {
                    startupStage.set("Подготовка файлов Wine");
                    android.util.Log.i("FarCry2Startup", startupStage.get());
                    setupWineSystemFiles();
                    startupStage.set("Распаковка графического драйвера");
                    android.util.Log.i("FarCry2Startup", startupStage.get());
                    extractGraphicsDriverFiles();
                    startupStage.set("Настройка звука Wine");
                    android.util.Log.i("FarCry2Startup", startupStage.get());
                    changeWineAudioDriver();
                }
                startupStage.set("Запуск X-сервера и Wine");
                android.util.Log.i("FarCry2Startup", startupStage.get());
                setupXEnvironment();
                startupStage.set("Ожидание первого окна игры");
                android.util.Log.i("FarCry2Startup", startupStage.get());
            }
            catch (Throwable error) {
                android.util.Log.e("FarCry2Startup", "Winlator startup failed at stage: " + startupStage.get(), error);
                runOnUiThread(() -> {
                    String detail = "Этап: " + startupStage.get() + "\\n" + error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage());
                    new AlertDialog.Builder(this)
                        .setTitle("Ошибка запуска Winlator")
                        .setMessage("Сбой произошёл до запуска FarCry2.exe.\\n\\n"+detail+"\\n\\nПодробности сохранены в системном журнале Android.")
                        .setPositiveButton("Вернуться в лаунчер", (dialog, which) -> finish())
                        .setNegativeButton("Остаться на экране", null)
                        .setCancelable(false)
                        .show();
                });
            }
        });"""
if startup_old not in s:
    raise SystemExit("Winlator startup block not found")
s = s.replace(startup_old, startup_new, 1)
window_gate_old = "if (!flags[0] && window.isRenderable() && !window.getClassName().isEmpty()) {"
window_gate_new = "if (!flags[0] && window.isRenderable()) {"
if window_gate_old not in s:
    raise SystemExit("First renderable-window gate not found")
s = s.replace(window_gate_old, window_gate_new, 1)
first_window_old = """                    preloaderDialog.closeOnUiThread();
                    flags[0] = true;"""
first_window_new = """                    preloaderDialog.closeOnUiThread();
                    startupFinished.set(true);
                    android.util.Log.i("FarCry2Startup", "First renderable X11 window appeared; startup complete");
                    flags[0] = true;"""
if first_window_old not in s:
    raise SystemExit("First X11 window hook not found")
s = s.replace(first_window_old, first_window_new, 1)
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
# Rebrand the native startup overlay while keeping the underlying graphics/session engine intact.
python3 - "$APP/res/values/strings.xml" "$APP/res/values-ru/strings.xml" <<'PY'
from pathlib import Path
import sys
for name in sys.argv[1:]:
    p = Path(name)
    if not p.exists():
        continue
    s = p.read_text()
    if 'name="starting_up"' in s:
        import re
        s = re.sub(r'(<string name="starting_up">).*?(</string>)', lambda m: m.group(1) + "Preparing Far Cry 2..." + m.group(2), s, count=1)
    if 'name="loading"' in s:
        import re
        label = "Preparing Far Cry 2..." if "/values/" in name else "Подготовка Far Cry 2..."
        s = re.sub(r'(<string name="loading">).*?(</string>)', lambda m: m.group(1) + label + m.group(2), s, count=1)
    p.write_text(s)
PY
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
