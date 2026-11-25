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
# Isolate this app's X server from other Android apps using the conventional :0 socket.
python3 - "$APP/java/com/winlator/xconnector/UnixSocketConfig.java" "$APP/java/com/winlator/XServerDisplayActivity.java" "$APP/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java" <<'PY'
from pathlib import Path
import sys
config, activity, guest = map(Path, sys.argv[1:])
s = config.read_text()
old = '    public static final String XSERVER_PATH = "/tmp/.X11-unix/X0";'
new = """    public static String XSERVER_PATH = "/tmp/.X11-unix/X0";
    public static String XSERVER_DISPLAY = ":0";
    public static void configureUniqueXServer() {
        int display = android.os.Process.myPid() % 60000;
        if (display == 0) display = 1;
        XSERVER_PATH = "/tmp/.X11-unix/X" + display;
        XSERVER_DISPLAY = ":" + display;
    }"""
if old not in s: raise SystemExit("UnixSocketConfig XSERVER_PATH declaration missing")
config.write_text(s.replace(old, new, 1))
s = activity.read_text()
old = "        super.onCreate(savedInstanceState);"
new = old + "\n        UnixSocketConfig.configureUniqueXServer();"
if old not in s: raise SystemExit("XServerDisplayActivity onCreate marker missing")
activity.write_text(s.replace(old, new, 1))
s = guest.read_text()
# DISPLAY is patched together with the diagnostic setup below.
guest.write_text(s)
PY
# Persist subprocess output directly; upstream LogView only writes when its UI stream is usable.
python3 - "$APP/java/com/winlator/core/ProcessHelper.java" <<'PY'
from pathlib import Path
import re
import sys
p = Path(sys.argv[1])
s = p.read_text()
needle = "    private static void createDebugThread(final InputStream inputStream) {"
method = """    private static volatile String nativeLibraryDir;
    public static void setNativeLibraryDir(String path) { nativeLibraryDir = path; }

    private static java.io.File getDiagnosticLogFile() {
        java.io.File external = new java.io.File(
            android.os.Environment.getExternalStorageDirectory(),
            "Android/data/com.farcry2.mobile/files/Winlator/logs.txt"
        );
        java.io.File parent = external.getParentFile();
        if (parent != null && (parent.isDirectory() || parent.mkdirs())) return external;
        return new java.io.File("/data/data/com.farcry2.mobile/files/Winlator/logs.txt");
    }

    public static synchronized String readDiagnosticLogTail() {
        java.io.File logFile = getDiagnosticLogFile();
        try {
            if (!logFile.isFile()) return "Журнал Wine/Box64 ещё не создан: " + logFile.getAbsolutePath();
            java.util.List<String> lines = java.nio.file.Files.readAllLines(logFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - 200);
            return String.join("\\n", lines.subList(from, lines.size()));
        }
        catch (Exception error) {
            return "Не удалось прочитать журнал " + logFile.getAbsolutePath() + ": " + error;
        }
    }

    public static synchronized void appendDiagnosticLine(String line) {
        java.io.File logFile = getDiagnosticLogFile();
        String entry = "[" + new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date()) + "] " + line;
        android.util.Log.i("FarCry2Diagnostics", line);
        try {
            java.io.File parent = logFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                throw new java.io.IOException("Cannot create " + parent);
            try (java.io.BufferedWriter writer = new java.io.BufferedWriter(new java.io.FileWriter(logFile, true))) {
                writer.write(entry);
                writer.newLine();
                writer.flush();
            }
        }
        catch (Exception error) {
            android.util.Log.e("FarCry2Diagnostics", "Cannot append diagnostics to " + logFile, error);
        }
    }

"""
if needle not in s:
    raise SystemExit("ProcessHelper debug thread marker not found")
s = s.replace(needle, method + needle, 1)
needle2 = "                while ((line = reader.readLine()) != null) {"
replace2 = needle2 + "\n                    appendDiagnosticLine(line);"
if needle2 not in s:
    raise SystemExit("ProcessHelper output read loop not found")
s = s.replace(needle2, replace2, 1)
# Always drain stdout/stderr, even when no DebugDialog callback is registered.
# Otherwise ProcessHelper redirects both streams to /dev/null and no diagnostic reader runs.
redirect_old = '            if (debugCallbacks.isEmpty()) processBuilder.redirectOutput(new File("/dev/null")).redirectErrorStream(true);'
if redirect_old not in s:
    raise SystemExit("ProcessHelper output redirect marker not found")
s = s.replace(redirect_old, '', 1)
thread_old = '''            if (!debugCallbacks.isEmpty()) {
                createDebugThread(process.getInputStream());
                createDebugThread(process.getErrorStream());
            }'''
thread_new = '''            createDebugThread(process.getInputStream());
            createDebugThread(process.getErrorStream());'''
if thread_old not in s:
    raise SystemExit("ProcessHelper debug thread gate not found")
s = s.replace(thread_old, thread_new, 1)
# Add explicit process-launch markers and preserve exceptions that upstream silently swallowed.
exec_method = """    public static int exec(String command, EnvVars envVars, File workingDir, Callback<Integer> terminationCallback) {
        int pid = -1;
        appendDiagnosticLine("ProcessHelper.exec BEGIN: " + command);
        try {
            String[] commandArgs = splitCommand(command);
            // Box64 is unpacked into app-private RootFS by Winlator. Android mounts
            // that writable directory noexec, so execute the same ARM64 binary from
            // PackageManager's executable nativeLibraryDir copy instead.
            if (commandArgs.length > 0 && commandArgs[0].endsWith("/usr/local/bin/box64")) {
                String nativeLibraryPath = nativeLibraryDir;
                boolean relocated = false;
                if (nativeLibraryPath != null && !nativeLibraryPath.isEmpty()) {
                    File packagedBox64 = new File(nativeLibraryPath, "libbox64.so");
                    if (packagedBox64.isFile() && packagedBox64.canExecute()) {
                        appendDiagnosticLine("BOX64_EXEC_PATH: " + commandArgs[0] + " -> " + packagedBox64.getAbsolutePath());
                        commandArgs[0] = packagedBox64.getAbsolutePath();
                        relocated = true;
                    }
                }
                if (!relocated) appendDiagnosticLine("BOX64_EXEC_PATH ERROR: libbox64.so missing/not executable; nativeLibraryDir=" + String.valueOf(nativeLibraryPath));
            }
            appendDiagnosticLine("PROCESS ARGUMENTS READY: argc=" + commandArgs.length + ", executable=" + (commandArgs.length > 0 ? commandArgs[0] : "<none>") + ", cwd=" + String.valueOf(workingDir));
            if (commandArgs.length > 0) {
                File executable = new File(commandArgs[0]);
                if (executable.isFile()) {
                    try {
                        android.system.Os.chmod(executable.getAbsolutePath(), 0755);
                        File parent = executable.getParentFile();
                        while (parent != null && workingDir != null && parent.getAbsolutePath().startsWith(workingDir.getAbsolutePath())) {
                            android.system.Os.chmod(parent.getAbsolutePath(), 0755);
                            if (parent.equals(workingDir)) break;
                            parent = parent.getParentFile();
                        }
                        appendDiagnosticLine("EXECUTABLE PERMISSIONS: path=" + executable + ", canExecute=" + executable.canExecute() + ", length=" + executable.length());
                    }
                    catch (Throwable permissionError) {
                        appendDiagnosticLine("EXECUTABLE CHMOD ERROR: " + permissionError.getClass().getName() + ": " + String.valueOf(permissionError.getMessage()));
                    }
                }
                else appendDiagnosticLine("EXECUTABLE MISSING: " + executable.getAbsolutePath());
            }
            String[] processArgs = commandArgs;
            ProcessBuilder processBuilder = new ProcessBuilder(processArgs).directory(workingDir);
            appendDiagnosticLine("PROCESS BUILDER READY");
            if (envVars != null) {
                Map<String, String> environment = processBuilder.environment();
                for (String name : envVars) environment.put(name, envVars.get(name));
            }
            // Android does not provide the POSIX /dev/shm implementation expected by
            // this Wine build's esync. Disable esync explicitly; wineserver then uses
            // Wine's regular synchronization path instead of failing at shm_open().
            processBuilder.environment().put("WINEESYNC", "0");
            processBuilder.environment().put("WINEFSYNC", "0");
            appendDiagnosticLine("WINE_SYNC_CONFIG WINEESYNC=0 WINEFSYNC=0");
            appendDiagnosticLine("PROCESS START CALL");
            java.lang.Process process = processBuilder.start();
            appendDiagnosticLine("PROCESS START RETURNED");
            createDebugThread(process.getInputStream());
            createDebugThread(process.getErrorStream());
            try {
                Field pidField = process.getClass().getDeclaredField("pid");
                pidField.setAccessible(true);
                pid = pidField.getInt(process);
                appendDiagnosticLine("PROCESS PID=" + pid);
                try { pidField.setAccessible(false); } catch (Exception ignored) {}
            }
            catch (Exception pidError) {
                appendDiagnosticLine("PID LOOKUP FAILED (process may still be running): " + pidError.getClass().getName() + ": " + String.valueOf(pidError.getMessage()));
            }
            if (terminationCallback != null) createWaitForThread(process, terminationCallback);
        }
        catch (Throwable e) {
            appendDiagnosticLine("ProcessHelper.exec ERROR: " + e.getClass().getName() + ": " + String.valueOf(e.getMessage()));
            android.util.Log.e("FarCry2Diagnostics", "ProcessHelper.exec failed", e);
        }
        return pid;
    }"""
pattern = re.compile(r'public static int exec\(String command, EnvVars envVars, File workingDir, Callback<Integer> terminationCallback\) \{.*?\n    \}', re.S)
if not pattern.search(s):
    raise SystemExit("ProcessHelper exec method not found")
s = pattern.sub(lambda m: exec_method, s, count=1)
p.write_text(s)
PY
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
# Expose the active Winlator activity so the launcher Stop action can terminate the real Wine session.
active_field = """
    private static volatile XServerDisplayActivity activeInstance;
    public static boolean isSessionActive() { return activeInstance != null; }
    public static boolean stopActiveSession() {
        final XServerDisplayActivity activity = activeInstance;
        if (activity == null) return false;
        activity.runOnUiThread(() -> {
            com.winlator.core.ProcessHelper.appendDiagnosticLine("STOP REQUEST: finishing active Winlator session");
            activity.finish();
        });
        return true;
    }
"""
if "stopActiveSession()" not in s:
    s = s.replace(field_patch, field_patch + active_field, 1)
oncreate = "        super.onCreate(savedInstanceState);"
if "activeInstance = this;" not in s:
    if oncreate not in s: raise SystemExit("XServer onCreate marker not found")
    s = s.replace(oncreate, oncreate + "\n        activeInstance = this;", 1)
# Replace Winlator's navigation drawer Back handler with our separate native pause screen.
old_back = """    @Override
    public void onBackPressed() {
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            else drawerLayout.closeDrawers();
        }
    }"""
new_back = """    @Override
    public void onBackPressed() {
        if (activeInstance == this && !isFinishing()) {
            android.content.Intent pauseIntent = new android.content.Intent(
                this, com.farcry2.mobile.MainActivity.class);
            pauseIntent.putExtra("show_pause_menu", true);
            startActivity(pauseIntent);
        }
        else super.onBackPressed();
    }"""
if old_back not in s: raise SystemExit("Original XServer Back handler not found")
s = s.replace(old_back, new_back, 1)
destroy_old = """    protected void onDestroy() {
        winHandler.stop();"""
destroy_new = """    protected void onDestroy() {
        if (activeInstance == this) activeInstance = null;
        com.winlator.core.ProcessHelper.appendDiagnosticLine("XServerDisplayActivity destroyed");
        winHandler.stop();"""
if destroy_old in s:
    s = s.replace(destroy_old, destroy_new, 1)
# The launcher intentionally omits upstream SettingsFragment. Enable persistent diagnostics
# automatically so a user can retrieve the Wine/Box64 log without hidden settings.
prefs_marker = "        preferences = PreferenceManager.getDefaultSharedPreferences(this);"
prefs_patch = prefs_marker + """
        preferences.edit()
            .putBoolean("enable_wine_debug", true)
            .putInt("box64_logs", Math.max(1, preferences.getInt("box64_logs", 0)))
            .putBoolean("save_logs_to_file", true)
            .apply();"""
if prefs_marker not in s:
    raise SystemExit("Preferences initialization marker not found")
s = s.replace(prefs_marker, prefs_patch, 1)
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
new_cb = """guestProgramLauncherComponent.setTerminationCallback((status) -> {
            com.winlator.core.ProcessHelper.appendDiagnosticLine("WINE PROCESS EXITED status=" + status);
            runOnUiThread(() -> {
            new AlertDialog.Builder(this)
                .setTitle("Far Cry 2 не запустилась")
                .setMessage("Игровой процесс завершился с кодом " + status + ". Экран Winlator оставлен открытым, чтобы не возвращать тебя молча в лаунчер.")
                .setNeutralButton("Скопировать журнал", (dialog, which) -> {
                    try {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Far Cry 2 error log", com.winlator.core.ProcessHelper.readDiagnosticLogTail()));
                        Toast.makeText(this, "Журнал скопирован", Toast.LENGTH_LONG).show();
                    } catch (Throwable copyError) {
                        Toast.makeText(this, "Ошибка копирования: " + copyError.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setPositiveButton("Вернуться в лаунчер", (dialog, which) -> finish())
                .setNegativeButton("Остаться здесь", null)
                .setCancelable(false)
                .show();
            });
        });"""
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
                .setNeutralButton("Скопировать журнал", (dialog, which) -> {
                    try {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Far Cry 2 Winlator diagnostics", com.winlator.core.ProcessHelper.readDiagnosticLogTail()));
                        Toast.makeText(this, "Журнал Wine/Box64 скопирован", Toast.LENGTH_LONG).show();
                    }
                    catch (Throwable copyError) {
                        Toast.makeText(this, "Не удалось скопировать журнал: " + copyError.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Вернуться в лаунчер", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
        }, 120000);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (!isGenerateWineprefix()) {
                    startupStage.set("Подготовка файлов Wine");
                    android.util.Log.i("FarCry2Startup", startupStage.get());
                    com.winlator.core.ProcessHelper.appendDiagnosticLine("STARTUP: " + startupStage.get());
                    setupWineSystemFiles();
                    startupStage.set("Распаковка графического драйвера");
                    android.util.Log.i("FarCry2Startup", startupStage.get());
                    com.winlator.core.ProcessHelper.appendDiagnosticLine("STARTUP: " + startupStage.get());
                    extractGraphicsDriverFiles();
                    startupStage.set("Настройка звука Wine");
                    android.util.Log.i("FarCry2Startup", startupStage.get());
                    com.winlator.core.ProcessHelper.appendDiagnosticLine("STARTUP: " + startupStage.get());
                    changeWineAudioDriver();
                }
                startupStage.set("Запуск X-сервера и Wine");
                android.util.Log.i("FarCry2Startup", startupStage.get());
                    com.winlator.core.ProcessHelper.appendDiagnosticLine("STARTUP: " + startupStage.get());
                setupXEnvironment();
                startupStage.set("Ожидание первого окна игры");
                android.util.Log.i("FarCry2Startup", startupStage.get());
                    com.winlator.core.ProcessHelper.appendDiagnosticLine("STARTUP: " + startupStage.get());
            }
            catch (Throwable error) {
                android.util.Log.e("FarCry2Startup", "Winlator startup failed at stage: " + startupStage.get(), error);
                com.winlator.core.ProcessHelper.appendDiagnosticLine("STARTUP ERROR at " + startupStage.get() + ": " + error.getClass().getName() + ": " + String.valueOf(error.getMessage()));
                runOnUiThread(() -> {
                    String detail = "Этап: " + startupStage.get() + "\\n" + error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage());
                    new AlertDialog.Builder(this)
                        .setTitle("Ошибка запуска Winlator")
                        .setMessage("Сбой произошёл до запуска FarCry2.exe.\\n\\n"+detail)
                        .setNeutralButton("Скопировать журнал", (dialog, which) -> {
                            try {
                                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Far Cry 2 startup error log", com.winlator.core.ProcessHelper.readDiagnosticLogTail()));
                                Toast.makeText(this, "Журнал скопирован", Toast.LENGTH_LONG).show();
                            } catch (Throwable copyError) {
                                Toast.makeText(this, "Ошибка копирования: " + copyError.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        })
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
                    com.winlator.core.ProcessHelper.appendDiagnosticLine("STARTUP COMPLETE: first renderable X11 window appeared");
                    flags[0] = true;"""
if first_window_old not in s:
    raise SystemExit("First X11 window hook not found")
s = s.replace(first_window_old, first_window_new, 1)
p.write_text(s)
PY
# Add focused X11 diagnostics: the Wine log reports nodrv_CreateWindow, so record
# whether Winlator successfully creates its Unix-domain display socket before Wine starts.
python3 - "$APP/java/com/winlator/xenvironment/components/XServerComponent.java" "$APP/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java" <<'PY'
from pathlib import Path
import sys
xserver = Path(sys.argv[1])
s = xserver.read_text()
old = """        connector = new XConnectorEpoll(socketConfig, new XClientConnectionHandler(xServer), new XClientRequestHandler());
        connector.setInitialInputBufferCapacity(4096);
        connector.setInitialOutputBufferCapacity(4096);
        connector.setCanReceiveAncillaryMessages(true);
        connector.start();"""
new = """        com.winlator.core.ProcessHelper.appendDiagnosticLine("XSERVER BIND BEGIN path=" + socketConfig.path);
        connector = new XConnectorEpoll(socketConfig, new XClientConnectionHandler(xServer), new XClientRequestHandler());
        com.winlator.core.ProcessHelper.appendDiagnosticLine("XSERVER NATIVE SOCKET ALLOCATED path=" + socketConfig.path + " exists=" + new java.io.File(socketConfig.path).exists());
        connector.setInitialInputBufferCapacity(4096);
        connector.setInitialOutputBufferCapacity(4096);
        connector.setCanReceiveAncillaryMessages(true);
        connector.start();
        com.winlator.core.ProcessHelper.appendDiagnosticLine("XSERVER EPOLL STARTED path=" + socketConfig.path + " exists=" + new java.io.File(socketConfig.path).exists());"""
if old not in s: raise SystemExit("XServerComponent.start marker missing")
xserver.write_text(s.replace(old, new, 1))
guest = Path(sys.argv[2])
s = guest.read_text()
# Do not launch Wine until the per-process abstract X11 socket is accepting clients.
# Xlib inside Wine resolves :N to @/tmp/.X11-unix/XN on Android; a filesystem
# socket under RootFS alone is invisible to the non-chrooted Wine process.
imports = """import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
"""
if "import android.net.LocalSocket;" not in s:
    s = s.replace("import android.content.Context;", imports + "import android.content.Context;", 1)
await_method = """    private void awaitXServerSocket() {
        String socketName = UnixSocketConfig.XSERVER_PATH;
        long deadline = SystemClock.elapsedRealtime() + 5000L;
        String lastError = "not attempted";
        while (SystemClock.elapsedRealtime() < deadline) {
            LocalSocket probe = new LocalSocket();
            try {
                probe.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
                ProcessHelper.appendDiagnosticLine("XSERVER ABSTRACT SOCKET READY name=@" + socketName);
                return;
            }
            catch (Exception error) {
                lastError = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            }
            finally {
                try { probe.close(); } catch (Exception ignored) {}
            }
            SystemClock.sleep(50L);
        }
        ProcessHelper.appendDiagnosticLine("XSERVER ABSTRACT SOCKET NOT READY name=@" + socketName + " lastError=" + lastError);
        throw new IllegalStateException("XServer не принимает подключения через Android abstract socket: " + lastError);
    }

"""
if "private void awaitXServerSocket()" not in s:
    needle = "    private int execGuestProgram() {"
    if needle not in s: raise SystemExit("GuestProgramLauncher exec method marker missing")
    s = s.replace(needle, await_method + needle, 1)
start_old = """            copyDefaultBox64RCFile();
            pid = execGuestProgram();"""
start_new = """            copyDefaultBox64RCFile();
            awaitXServerSocket();
            pid = execGuestProgram();"""
if start_old not in s: raise SystemExit("GuestProgramLauncher.start insertion marker missing")
s = s.replace(start_old, start_new, 1)
old = """        envVars.put("DISPLAY", ":0");
        envVars.put("PATH", rootDir+rootFS.getWinePath()+"/bin:"+rootDir+"/usr/local/bin:"+rootDir+"/usr/bin");"""
new = """        envVars.put("DISPLAY", UnixSocketConfig.XSERVER_DISPLAY);
        File x11Socket = new File(rootDir, UnixSocketConfig.XSERVER_PATH);
        ProcessHelper.appendDiagnosticLine("WINE DISPLAY CONFIG DISPLAY=" + UnixSocketConfig.XSERVER_DISPLAY + " X11_SOCKET=" + x11Socket.getAbsolutePath() + " exists=" + x11Socket.exists() + " canRead=" + x11Socket.canRead() + " canWrite=" + x11Socket.canWrite());
        File fontConfig = new File(rootDir, "/etc/fonts/fonts.conf");
        if (fontConfig.isFile()) {
            envVars.put("FONTCONFIG_FILE", fontConfig.getAbsolutePath());
            envVars.put("FONTCONFIG_PATH", new File(rootDir, "/etc/fonts").getAbsolutePath());
            ProcessHelper.appendDiagnosticLine("FONTCONFIG CONFIGURED file=" + fontConfig.getAbsolutePath());
        }
        else ProcessHelper.appendDiagnosticLine("FONTCONFIG CONFIG MISSING expected=" + fontConfig.getAbsolutePath());
        envVars.put("PATH", rootDir+rootFS.getWinePath()+"/bin:"+rootDir+"/usr/local/bin:"+rootDir+"/usr/bin");"""
if old not in s: raise SystemExit("Guest DISPLAY setup marker missing")
guest.write_text(s.replace(old, new, 1))
# Container-specific environment variables are merged after the initial defaults.
# Re-assert the dynamic display AFTER that merge so a saved DISPLAY=:0 cannot
# point Wine away from the per-process X11 listener.
s = guest.read_text()
merge = '        if (this.envVars != null) envVars.putAll(this.envVars);'
final_display = merge + """
        envVars.put("DISPLAY", UnixSocketConfig.XSERVER_DISPLAY);
        ProcessHelper.appendDiagnosticLine("WINE DISPLAY FINAL DISPLAY=" + UnixSocketConfig.XSERVER_DISPLAY);
"""
if merge not in s: raise SystemExit("GuestProgramLauncher environment merge marker missing")
guest.write_text(s.replace(merge, final_display, 1))
PY
# Trace the actual Wine -> X11 connection, not merely socket-file creation.
python3 - "$APP/java/com/winlator/xconnector/XConnectorEpoll.java" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
if "FC2_X11_CLIENT_CONNECTED" not in s:
    old = """    private long nativePtr;

    static {"""
    new = """    private long nativePtr;
    private final java.util.concurrent.atomic.AtomicBoolean firstClientLogged = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean firstRequestLogged = new java.util.concurrent.atomic.AtomicBoolean(false);

    static {"""
    if old not in s: raise SystemExit("XConnectorEpoll field marker missing")
    s = s.replace(old, new, 1)
    old = """        ConnectedClient client = connectionHandler.newConnectedClient(clientPtr, fd);
        client.createInputStream(initialInputBufferCapacity);"""
    new = """        ConnectedClient client = connectionHandler.newConnectedClient(clientPtr, fd);
        if (firstClientLogged.compareAndSet(false, true))
            com.winlator.core.ProcessHelper.appendDiagnosticLine("FC2_X11_CLIENT_CONNECTED fd=" + fd + " clientPtr=" + clientPtr);
        client.createInputStream(initialInputBufferCapacity);"""
    if old not in s: raise SystemExit("XConnectorEpoll new-client marker missing")
    s = s.replace(old, new, 1)
    old = """                if (inputStream.readMoreData(canReceiveAncillaryMessages) > 0) {
                    int activePosition = 0;"""
    new = """                int bytesRead = inputStream.readMoreData(canReceiveAncillaryMessages);
                if (bytesRead > 0) {
                    if (firstRequestLogged.compareAndSet(false, true))
                        com.winlator.core.ProcessHelper.appendDiagnosticLine("FC2_X11_FIRST_REQUEST bytes=" + bytesRead + " fd=" + client.fd);
                    int activePosition = 0;"""
    if old not in s: raise SystemExit("XConnectorEpoll input marker missing")
    s = s.replace(old, new, 1)
    old = """        catch (IOException e) {
            killConnection(client);
        }"""
    new = """        catch (IOException e) {
            com.winlator.core.ProcessHelper.appendDiagnosticLine("FC2_X11_CLIENT_IO_ERROR fd=" + client.fd + " error=" + e.getClass().getName() + ": " + String.valueOf(e.getMessage()));
            killConnection(client);
        }"""
    if old not in s: raise SystemExit("XConnectorEpoll IOException marker missing")
    s = s.replace(old, new, 1)
p.write_text(s)
PY
cp -a "$UPSTREAM/app/src/main/cpp/." "$APP/cpp/"
# Wine's Xlib connects to the Linux abstract X11 socket before the filesystem
# socket. The filesystem socket lives inside RootFS, while Wine itself is not
# chrooted, so /tmp/.X11-unix/X0 on Android cannot reach that RootFS pathname.
# Publish the same X server on the abstract AF_UNIX address as well.
python3 - "$APP/cpp/winlator/src/xconnector_epoll.c" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
for header in ("#include <errno.h>", "#include <stddef.h>"):
    if header not in s:
        s = s.replace("#include <stdbool.h>", "#include <stdbool.h>\n" + header, 1)
old = """    int serverFd;
    int shutdownFd;"""
new = """    int serverFd;
    int abstractServerFd;
    int shutdownFd;"""
if old not in s: raise SystemExit("XConnectorEpoll server fd fields not found")
s = s.replace(old, new, 1)
needle = """static void loadJMethods(JMethods* jmethods) {"""
helper = """static int createAbstractX11Socket(const char* sockPath) {
    __android_log_print(ANDROID_LOG_INFO, "FarCry2X11",
        "Creating abstract X11 socket for filesystem path: %s", sockPath ? sockPath : "(null)");

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    struct sockaddr_un address = {0};
    address.sun_family = AF_UNIX;
    char displayPath[sizeof(address.sun_path)];
    const char* marker = sockPath ? strstr(sockPath, "/tmp/.X11-unix/") : NULL;
    if (marker) snprintf(displayPath, sizeof(displayPath), "%s", marker);
    else snprintf(displayPath, sizeof(displayPath), "%s", "/tmp/.X11-unix/X0");
    size_t pathLength = strlen(displayPath);
    if (pathLength + 1 > sizeof(address.sun_path)) {
        errno = ENAMETOOLONG;
        goto error;
    }
    address.sun_path[0] = '\\0';
    memcpy(address.sun_path + 1, displayPath, pathLength);

    socklen_t addressLength = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + pathLength);
    if (bind(fd, (struct sockaddr*)&address, addressLength) < 0) goto error;
    if (listen(fd, MAX_EVENTS) < 0) goto error;

    __android_log_print(ANDROID_LOG_INFO, "FarCry2X11",
        "Bound abstract X11 socket @%s", displayPath);
    return fd;

error:
    __android_log_print(ANDROID_LOG_ERROR, "FarCry2X11",
        "Failed to bind abstract X11 socket: %s", strerror(errno));
    CLOSEFD(fd);
    return -1;
}

"""
if needle not in s: raise SystemExit("XConnectorEpoll JNI method marker missing")
s = s.replace(needle, helper + needle, 1)
old = """    if (connector->serverFd > 0) {
        removeFdFromEpoll(connector->epollFd, connector->serverFd);
        CLOSEFD(connector->serverFd);
    }

    if (connector->shutdownFd > 0) {"""
new = """    if (connector->serverFd > 0) {
        removeFdFromEpoll(connector->epollFd, connector->serverFd);
        CLOSEFD(connector->serverFd);
    }

    if (connector->abstractServerFd > 0) {
        removeFdFromEpoll(connector->epollFd, connector->abstractServerFd);
        CLOSEFD(connector->abstractServerFd);
    }

    if (connector->shutdownFd > 0) {"""
if old not in s: raise SystemExit("XConnectorEpoll destroy socket block missing")
s = s.replace(old, new, 1)
old = """    XConnectorEpoll* connector = calloc(1, sizeof(XConnectorEpoll));

    connector->epollFd = epoll_create(MAX_EVENTS);"""
new = """    XConnectorEpoll* connector = calloc(1, sizeof(XConnectorEpoll));
    connector->abstractServerFd = -1;

    connector->epollFd = epoll_create(MAX_EVENTS);"""
if old not in s: raise SystemExit("XConnectorEpoll allocate block missing")
s = s.replace(old, new, 1)
old = """    if (!addFdToEpoll(connector->epollFd, connector->serverFd, &connector->serverFd)) goto error;
    if (!addFdToEpoll(connector->epollFd, connector->shutdownFd, &connector->shutdownFd)) goto error;"""
new = """    if (!addFdToEpoll(connector->epollFd, connector->serverFd, &connector->serverFd)) goto error;

    connector->abstractServerFd = createAbstractX11Socket(sockPath);
    if (connector->abstractServerFd < 0) goto error;
    if (!addFdToEpoll(connector->epollFd, connector->abstractServerFd, &connector->abstractServerFd)) {
        __android_log_print(ANDROID_LOG_ERROR, "FarCry2X11",
            "Failed to register abstract X11 socket in epoll: %s", strerror(errno));
        goto error;
    }

    if (!addFdToEpoll(connector->epollFd, connector->shutdownFd, &connector->shutdownFd)) goto error;"""
if old not in s: raise SystemExit("XConnectorEpoll epoll registration block missing")
s = s.replace(old, new, 1)
old = """            if (events[i].data.ptr == &connector->serverFd) {
                int clientFd = accept(connector->serverFd, NULL, NULL);
                if (clientFd >= 0) XConnectorEpoll_handleNewConnection(connector, clientFd);
            }
            else if (events[i].data.ptr != &connector->shutdownFd &&"""
new = """            if (events[i].data.ptr == &connector->serverFd ||
                    events[i].data.ptr == &connector->abstractServerFd) {
                int listeningFd = events[i].data.ptr == &connector->abstractServerFd ?
                    connector->abstractServerFd : connector->serverFd;
                int clientFd = accept(listeningFd, NULL, NULL);
                if (clientFd >= 0) XConnectorEpoll_handleNewConnection(connector, clientFd);
            }
            else if (events[i].data.ptr != &connector->shutdownFd &&"""
if old not in s: raise SystemExit("XConnectorEpoll accept dispatch block missing")
s = s.replace(old, new, 1)
# Preserve the exact native failure stage and errno. The Java wrapper otherwise
# collapses every socket/epoll failure into "Failed to allocate XConnectorEpoll".
for header in ("#include <errno.h>", "#include <stdio.h>"):
    if header not in s:
        s = s.replace("#include <stdbool.h>", "#include <stdbool.h>\n" + header, 1)

allocate_old = """static XConnectorEpoll* XConnectorEpoll_allocate(JNIEnv* env, jobject obj, const char* sockPath) {
    XConnectorEpoll* connector = calloc(1, sizeof(XConnectorEpoll));
    connector->abstractServerFd = -1;

    connector->epollFd = epoll_create(MAX_EVENTS);
    if (connector->epollFd < 0) goto error;

    connector->serverFd = createServerSocket(sockPath);
    if (connector->serverFd < 0) goto error;

    connector->shutdownFd = eventfd(0, EFD_NONBLOCK);
    if (connector->shutdownFd < 0) goto error;

    if (!addFdToEpoll(connector->epollFd, connector->serverFd, &connector->serverFd)) goto error;

    connector->abstractServerFd = createAbstractX11Socket(sockPath);
    if (connector->abstractServerFd < 0) goto error;
    if (!addFdToEpoll(connector->epollFd, connector->abstractServerFd, &connector->abstractServerFd)) {
        __android_log_print(ANDROID_LOG_ERROR, "FarCry2X11",
            "Failed to register abstract X11 socket in epoll: %s", strerror(errno));
        goto error;
    }

    if (!addFdToEpoll(connector->epollFd, connector->shutdownFd, &connector->shutdownFd)) goto error;

    (*env)->GetJavaVM(env, &connector->jmethods.jvm);
    connector->jmethods.obj = (*env)->NewGlobalRef(env, obj);
    return connector;

error:
    XConnectorEpoll_destroy(env, connector);
    return NULL;
}"""
allocate_new = """static XConnectorEpoll* XConnectorEpoll_allocate(JNIEnv* env, jobject obj, const char* sockPath,
        const char** failureStage, int* failureErrno) {
    XConnectorEpoll* connector = calloc(1, sizeof(XConnectorEpoll));
    if (!connector) {
        *failureStage = "calloc connector";
        *failureErrno = ENOMEM;
        return NULL;
    }
    connector->epollFd = -1;
    connector->serverFd = -1;
    connector->abstractServerFd = -1;
    connector->shutdownFd = -1;

    connector->epollFd = epoll_create(MAX_EVENTS);
    if (connector->epollFd < 0) { *failureStage = "epoll_create"; goto error; }

    connector->serverFd = createServerSocket(sockPath);
    if (connector->serverFd < 0) { *failureStage = "filesystem X11 socket bind/listen"; goto error; }

    if (!addFdToEpoll(connector->epollFd, connector->serverFd, &connector->serverFd)) {
        *failureStage = "register filesystem X11 socket in epoll"; goto error;
    }

    // Only the X11 listener needs an abstract AF_UNIX alias. Other Winlator
    // connectors (SysVSHM, VirGL, Vortek, ALSA, PulseAudio) have their own
    // filesystem sockets and must not all contend for the same abstract X11 name.
    if (sockPath && strstr(sockPath, "/tmp/.X11-unix/") != NULL) {
        connector->abstractServerFd = createAbstractX11Socket(sockPath);
        if (connector->abstractServerFd < 0) { *failureStage = "abstract X11 socket bind/listen"; goto error; }

        if (!addFdToEpoll(connector->epollFd, connector->abstractServerFd, &connector->abstractServerFd)) {
            *failureStage = "register abstract X11 socket in epoll"; goto error;
        }
    }

    connector->shutdownFd = eventfd(0, EFD_NONBLOCK);
    if (connector->shutdownFd < 0) { *failureStage = "eventfd"; goto error; }

    if (!addFdToEpoll(connector->epollFd, connector->shutdownFd, &connector->shutdownFd)) {
        *failureStage = "register shutdown eventfd in epoll"; goto error;
    }

    (*env)->GetJavaVM(env, &connector->jmethods.jvm);
    connector->jmethods.obj = (*env)->NewGlobalRef(env, obj);
    return connector;

error:
    *failureErrno = errno;
    XConnectorEpoll_destroy(env, connector);
    return NULL;
}"""
if allocate_old not in s:
    raise SystemExit("Exact XConnectorEpoll_allocate implementation not found; refusing blind patch")
s = s.replace(allocate_old, allocate_new, 1)

jni_old = """    const char* pathPtr = (*env)->GetStringUTFChars(env, sockPath, 0);
    XConnectorEpoll* connector = XConnectorEpoll_allocate(env, obj, pathPtr);

    (*env)->ReleaseStringUTFChars(env, sockPath, pathPtr);
    return (jlong)connector;"""
jni_new = """    const char* pathPtr = (*env)->GetStringUTFChars(env, sockPath, 0);
    const char* failureStage = "unknown native allocation stage";
    int failureErrno = 0;
    XConnectorEpoll* connector = XConnectorEpoll_allocate(env, obj, pathPtr, &failureStage, &failureErrno);

    (*env)->ReleaseStringUTFChars(env, sockPath, pathPtr);
    if (!connector) {
        char message[256];
        snprintf(message, sizeof(message), "XConnectorEpoll allocation failed at %s (errno=%d: %s)",
            failureStage, failureErrno, strerror(failureErrno));
        __android_log_print(ANDROID_LOG_ERROR, "FarCry2X11", "%s", message);
        jclass exceptionClass = (*env)->FindClass(env, "java/lang/RuntimeException");
        if (exceptionClass) (*env)->ThrowNew(env, exceptionClass, message);
        return 0;
    }
    return (jlong)connector;"""
if jni_old not in s:
    raise SystemExit("nativeAllocate wrapper marker not found")
s = s.replace(jni_old, jni_new, 1)

p.write_text(s)
PY
if [[ -d "$UPSTREAM/app/src/main/jniLibs" ]]; then
  mkdir -p "$APP/jniLibs"
  cp -a "$UPSTREAM/app/src/main/jniLibs/." "$APP/jniLibs/"
fi
# Android 10+ may deny execve() from the writable app data/RootFS mount.
# Package the ARM64 Box64 executable as a JNI payload so PackageManager extracts
# it into nativeLibraryDir, which is the app's executable native-code location.
BOX64_ARCHIVE="$UPSTREAM/app/src/main/assets/box64/box64-0.4.4.tzst"
if [[ ! -f "$BOX64_ARCHIVE" ]]; then
  echo "Pinned Box64 archive not found: $BOX64_ARCHIVE" >&2
  exit 4
fi
tar --zstd -tf "$BOX64_ARCHIVE" > /tmp/fc2-box64-members.txt
BOX64_MEMBER=""
while IFS= read -r member; do
  case "$member" in
    usr/local/bin/box64|*/usr/local/bin/box64) BOX64_MEMBER="$member"; break ;;
  esac
done < /tmp/fc2-box64-members.txt
if [[ -z "$BOX64_MEMBER" ]]; then
  echo "usr/local/bin/box64 was not found inside $BOX64_ARCHIVE" >&2
  exit 5
fi
mkdir -p "$APP/jniLibs/arm64-v8a"
tar --zstd -xOf "$BOX64_ARCHIVE" "$BOX64_MEMBER" > "$APP/jniLibs/arm64-v8a/libbox64.so"
chmod 0755 "$APP/jniLibs/arm64-v8a/libbox64.so"
# The upstream Box64 ELF embeds the upstream package ID in PT_INTERP. Our app
# uses com.farcry2.mobile, so rewrite the loader path to this app's extracted RootFS.
patchelf --set-interpreter /data/data/com.farcry2.mobile/files/rootfs/lib/ld-linux-aarch64.so.1 "$APP/jniLibs/arm64-v8a/libbox64.so"
echo "Box64 interpreter: $(patchelf --print-interpreter "$APP/jniLibs/arm64-v8a/libbox64.so")"
file "$APP/jniLibs/arm64-v8a/libbox64.so"
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
  # Keep launcher-owned files when an upstream asset uses the same relative path.
  if [[ -e "$APP/assets/$rel" ]]; then
    echo "Keeping launcher asset on collision: $rel"
    continue
  fi
  cp "$file" "$APP/assets/$rel"
done < <(find "$UPSTREAM/app/src/main/assets" -type f -print0)
echo "Java: $(find "$APP/java/com/winlator" -type f -name '*.java' | wc -l)"
echo "C/C++: $(find "$APP/cpp" -type f | wc -l)"
echo "JNI: $(find "$APP/jniLibs" -type f 2>/dev/null | wc -l)"
echo "Resources: $(find "$APP/res" -type f | wc -l)"
echo "Assets: $(find "$APP/assets" -type f | wc -l)"
