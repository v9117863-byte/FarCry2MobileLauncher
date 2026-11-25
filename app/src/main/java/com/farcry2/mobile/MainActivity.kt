package com.farcry2.mobile

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.documentfile.provider.DocumentFile
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.core.TarCompressorUtils
import com.winlator.core.GeneralComponents
import com.winlator.core.DefaultVersion
import com.winlator.xenvironment.RootFS
import com.winlator.xenvironment.RootFSInstaller
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var web: WebView
    private var nativeUi: NativeLauncherUi? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("fc2", MODE_PRIVATE) }
    private var selectedTree: Uri? = null
    @Volatile private var gameProcess: Process? = null
    @Volatile private var launchingGame = false
    @Volatile private var launchStage = "Ожидание"
    @Volatile private var launchLastPercent = 0
    @Volatile private var launchWatchdogReported = false
    @Volatile private var inputSocket: LocalSocket? = null
    private val requestFolder = 4102
    private val requestRuntimeFolder = 4103
    private val requestWorldMap = 4104

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTree = prefs.getString("game_tree_uri", null)?.let(Uri::parse)
        com.winlator.core.ProcessHelper.setNativeLibraryDir(applicationInfo.nativeLibraryDir)
        val bridge = AndroidBridge()
        nativeUi = NativeLauncherUi(this, object : NativeLauncherUi.Actions {
            override fun launch() = bridge.launchGame()
            override fun resumeGame() { this@MainActivity.finish() }
            override fun chooseGameFolder() = bridge.chooseGameFolder()
            override fun importGameFiles() = bridge.importGameFiles()
            override fun chooseRuntimeFolder() = bridge.chooseRuntimeFolder()
            override fun importRuntime() = bridge.importRuntimeFolder()
            override fun chooseMap() = bridge.chooseWorldMap()
            override fun stopGame() = bridge.stopGame()
            override fun deviceInfo() = bridge.getDeviceInfo()
            override fun gameStatus() = bridge.getGameInstallStatus()
            override fun runtimeStatus() = bridge.getRuntimeBundleStatus()
            override fun saveSettings(json: String) = bridge.saveLaunchSettings(json)
            override fun copyError() = bridge.copyErrorToClipboard("Native launcher diagnostic report")
        })
        setContentView(nativeUi!!.root)
        nativeUi!!.refreshStatus()
        if (intent.getBooleanExtra("show_pause_menu", false)) nativeUi!!.showPauseMenu()
    }

    // Wine's Winlator build hardcodes the original package path. Redirect that
    // exact-length string to an app-owned symlink so wineserver and ntdll use this RootFS.
    private fun patchWineRootfsPath(rootDir: File) {
        val oldPath = "/data/data/com.winlator/files/rootfs".toByteArray(Charsets.UTF_8)
        val replacement = "/data/data/com.farcry2.mobile/r/////".toByteArray(Charsets.UTF_8)
        check(oldPath.size == replacement.size) { "Wine path patch must preserve binary size" }

        val alias = File(filesDir.parentFile, "r")
        if (alias.exists()) {
            check(alias.canonicalFile == rootDir.canonicalFile) {
                "Wine RootFS alias already exists and points elsewhere: " + alias.absolutePath
            }
        } else {
            android.system.Os.symlink(rootDir.absolutePath, alias.absolutePath)
        }

        val targets = listOf(
            File(rootDir, "opt/wine/bin/wineserver"),
            File(rootDir, "opt/wine/lib/wine/x86_64-unix/ntdll.so")
        )
        for (target in targets) {
            check(target.isFile) { "Wine component missing: " + target.absolutePath }
            val bytes = target.readBytes()
            var count = 0
            var i = 0
            while (i <= bytes.size - oldPath.size) {
                var match = true
                for (j in oldPath.indices) {
                    if (bytes[i + j] != oldPath[j]) { match = false; break }
                }
                if (match) {
                    System.arraycopy(replacement, 0, bytes, i, replacement.size)
                    count++
                    i += replacement.size
                } else {
                    i++
                }
            }
            if (count > 0) target.writeBytes(bytes)
            appendLaunchLog("WINE_ROOTFS_PATH_PATCH file=" + target.name + " replacements=" + count)
        }
    }

    // Some Android tar extractors restore RootFS contents without Unix execute bits.
    // Reapply them to Linux ELF binaries and shebang scripts before launching Box64/Wine.
    private fun restoreRootFSExecutePermissions(rootDir: File) {
        var repairedFiles = 0
        var repairedDirectories = 0
        // EACCES can occur when a parent directory lacks search/execute permission.
        rootDir.walkTopDown().forEach { file ->
            if (file.isDirectory) {
                if (!file.canRead() && file.setReadable(true, false)) repairedDirectories++
                if (!file.canExecute() && file.setExecutable(true, false)) repairedDirectories++
                if (!file.canRead() || !file.canExecute()) {
                    throw IllegalStateException("Нет прав прохода по каталогу RootFS: " + file.absolutePath)
                }
                return@forEach
            }
            if (!file.isFile) return@forEach
            if (!file.canRead() && file.setReadable(true, false)) repairedFiles++
            val executableCandidate = try {
                java.io.FileInputStream(file).use { input ->
                    val header = ByteArray(4)
                    val count = input.read(header)
                    (count >= 4 && header[0] == 0x7f.toByte() &&
                        header[1] == 'E'.code.toByte() &&
                        header[2] == 'L'.code.toByte() &&
                        header[3] == 'F'.code.toByte()) ||
                    (count >= 2 && header[0] == '#'.code.toByte() &&
                        header[1] == '!'.code.toByte())
                }
            } catch (_: Exception) { false }
            if (executableCandidate && !file.canExecute()) {
                val changed = file.setExecutable(true, false)
                if (!changed && !file.canExecute()) {
                    throw IllegalStateException("Не удалось выставить права запуска: " + file.absolutePath)
                }
                repairedFiles++
            }
        }
        val box64 = File(rootDir, "usr/local/bin/box64")
        if (!box64.isFile) throw IllegalStateException("В RootFS отсутствует Box64: " + box64.absolutePath)
        if (!box64.canRead() && !box64.setReadable(true, false) && !box64.canRead()) {
            throw IllegalStateException("Android не разрешил чтение Box64: " + box64.absolutePath)
        }
        if (!box64.canExecute() && !box64.setExecutable(true, false) && !box64.canExecute()) {
            throw IllegalStateException("Android не разрешил запуск Box64: " + box64.absolutePath)
        }
        var parent = box64.parentFile
        while (parent != null && parent.toPath().startsWith(rootDir.toPath())) {
            if (!parent.canExecute()) throw IllegalStateException("Нет права прохода к Box64 через каталог: " + parent.absolutePath)
            parent = parent.parentFile
        }
        appendLaunchLog("ROOTFS_EXEC_PERMISSIONS repairedFiles=" + repairedFiles + " repairedDirectories=" + repairedDirectories + " box64Read=" + box64.canRead() + " box64Executable=" + box64.canExecute())
    }
    private fun appendLaunchLog(message: String) {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val logFile = File(dir, "fc2-launch.log")
            val line = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date()) + "  " + message.replace("\\n", " ") + "\\n"
            FileOutputStream(logFile, true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            android.util.Log.e("FC2-Launch", "Cannot write launch log", e)
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface fun copyErrorToClipboard(errorText: String) {
            val logTail = try {
                val dir = getExternalFilesDir(null) ?: filesDir
                val launcherLog = File(dir, "fc2-launch.log")
                // Winlator subprocess diagnostics are written separately by ProcessHelper.
                // Include them too: a missing X11 window often means Box64/Wine failed
                // before the launcher itself receives a Java exception.
                val winlatorLog = File(dir, "Winlator/logs.txt")
                val launcherLines = if (launcherLog.isFile) launcherLog.readLines(Charsets.UTF_8).takeLast(60) else listOf("Журнал лаунчера пуст")
                val winlatorLines = if (winlatorLog.isFile) winlatorLog.readLines(Charsets.UTF_8).takeLast(120) else listOf("Журнал Wine/Box64 пока не создан")
                val dxvkDir = File(RootFS.find(this@MainActivity).rootDir, "tmp")
                val dxvkLogs = dxvkDir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }?.takeLast(4)
                    ?.flatMap { file -> listOf("DXVK FILE: " + file.name) + file.readLines(Charsets.UTF_8).takeLast(100) }
                    ?: listOf("Отдельные DXVK-логи пока не созданы")
                "ЛАУНЧЕР:\\n" + launcherLines.joinToString("\\n") +
                    "\\n\\nWINE / BOX64 / XSERVER:\\n" + winlatorLines.joinToString("\\n") +
                    "\\n\\nDXVK:\\n" + dxvkLogs.joinToString("\\n")
            } catch (e: Exception) {
                "Не удалось прочитать журналы: " + (e.message ?: "неизвестная ошибка")
            }
            val report = "FAR CRY 2 MOBILE — ОТЧЁТ ОБ ОШИБКЕ\n\n" + errorText +
                "\n\nПОСЛЕДНИЕ ЗАПИСИ ЖУРНАЛА:\n" + logTail
            runOnUiThread {
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Far Cry 2 error report", report))
                    Toast.makeText(this@MainActivity, "Текст ошибки и лог скопированы", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Не удалось скопировать ошибку: " + (e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
        @JavascriptInterface fun getDeviceInfo(): String {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mem)
            val config = am.deviceConfigurationInfo
            val stat = StatFs(filesDir.absolutePath)
            val totalDisk = stat.totalBytes
            val freeDisk = stat.availableBytes
            val out = JSONObject()
            out.put("manufacturer", Build.MANUFACTURER ?: "Unknown")
            out.put("model", Build.MODEL ?: "Unknown")
            out.put("device", Build.DEVICE ?: "Unknown")
            out.put("android", Build.VERSION.RELEASE ?: "Unknown")
            out.put("sdk", Build.VERSION.SDK_INT)
            out.put("ramTotalBytes", mem.totalMem)
            out.put("ramAvailableBytes", mem.availMem)
            out.put("abis", Build.SUPPORTED_ABIS.joinToString(", "))
            out.put("glesVersion", String.format("%x", config.reqGlEsVersion))
            out.put("storageTotalBytes", totalDisk)
            out.put("storageFreeBytes", freeDisk)
            out.put("isLowRamDevice", am.isLowRamDevice)
            return out.toString()
        }

        @JavascriptInterface fun chooseGameFolder() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                startActivityForResult(intent, requestFolder)
            }
        }

        @JavascriptInterface fun chooseWorldMap() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(intent, requestWorldMap)
            }
        }

        @JavascriptInterface fun chooseRuntimeFolder() {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                startActivityForResult(intent, requestRuntimeFolder)
            }
        }

        @JavascriptInterface fun importRuntimeFolder() {
            val tree = prefs.getString("runtime_tree_uri", null)?.let(Uri::parse)
            if (tree == null) {
                runOnUiThread { evaluate("window.onRuntimeResult&&window.onRuntimeResult(false,'Сначала выбери папку')") }
                return
            }
            worker.execute {
                try {
                    val root = DocumentFile.fromTreeUri(this@MainActivity, tree)
                        ?: throw IllegalStateException("Не удалось открыть папку")
                    val target = File(filesDir, "runtime")
                    if (target.exists() && !target.deleteRecursively()) throw IllegalStateException("Не удалось очистить старые файлы")
                    if (!target.mkdirs()) throw IllegalStateException("Не удалось создать папку")
                    copyDocumentContents(root, target)
                    val manifest = File(target, "runtime-manifest.json")
                    if (!manifest.isFile) throw IllegalStateException("Не найден файл описания модуля в выбранной папке")
                    val json = JSONObject(manifest.readText(Charsets.UTF_8))
                    val required = listOf("entrypoint", "wine", "box86", "box64", "rootfs", "graphicsBackend")
                    val missing = required.filter { key ->
                        val rel = json.optString(key, "")
                        if (rel.isBlank()) true else {
                            val candidate = File(target, rel).canonicalFile
                            !candidate.toPath().startsWith(target.canonicalFile.toPath()) || !candidate.exists()
                        }
                    }
                    if (missing.isNotEmpty()) throw IllegalStateException("Модуль неполный. Отсутствуют компоненты: " + missing.joinToString())
                    listOf("entrypoint", "wine", "box86", "box64").forEach { key ->
                        val executable = File(target, json.getString(key))
                        if (executable.isFile && !executable.setExecutable(true, false) && !executable.canExecute()) {
                            throw IllegalStateException("Не удалось сделать исполняемым компонент: " + key)
                        }
                    }
                    val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                    val supported = json.optJSONArray("supportedAbis")
                    if (supported != null && (0 until supported.length()).none { supported.optString(it) == abi }) {
                        throw IllegalStateException("Пакет не поддерживает архитектуру устройства: " + abi)
                    }
                    prefs.edit().putBoolean("runtime_imported", true).putString("runtime_path", target.absolutePath).apply()
                    runOnUiThread { evaluate("window.onRuntimeResult&&window.onRuntimeResult(true,'Игровой модуль скопирован и проверен')") }
                } catch (e: Exception) {
                    File(filesDir, "runtime").deleteRecursively()
                    prefs.edit().putBoolean("runtime_imported", false).remove("runtime_path").apply()
                    val msg = JSONObject.quote(e.message ?: "Ошибка импорта модуля")
                    runOnUiThread { evaluate("window.onRuntimeResult&&window.onRuntimeResult(false," + msg + ")") }
                }
            }
        }

        @JavascriptInterface fun importGameFiles() {
            val tree = selectedTree
            if (tree == null) {
                runOnUiThread { evaluate("window.onGameFolderResult&&window.onGameFolderResult(false,'Сначала выбери папку игры')") }
                return
            }
            worker.execute {
                try {
                    val root = DocumentFile.fromTreeUri(this@MainActivity, tree)
                        ?: throw IllegalStateException("Не удалось открыть папку")
                    val exe = findFile(root, "FarCry2.exe")
                        ?: throw IllegalStateException("FarCry2.exe не найден в выбранной папке")
                    val target = File(filesDir, "game").apply { if (exists()) deleteRecursively(); mkdirs() }
                    val sourceRoot = root
                    copyTree(sourceRoot, target)
                    val copiedExe = findFile(target, "FarCry2.exe")
                        ?: throw IllegalStateException("FarCry2.exe не скопирован")
                    if (!copiedExe.exists()) throw IllegalStateException("Не удалось скопировать FarCry2.exe")
                    prefs.edit().putBoolean("game_imported", true).putString("game_path", copiedExe.absolutePath).apply()
                    runOnUiThread {
                        evaluate("window.onGameImportProgress&&window.onGameImportProgress(1,'Импорт игры завершён',100)")
                        evaluate("window.onGameFolderResult&&window.onGameFolderResult(true,'Файлы игры импортированы')")
                    }
                } catch (e: Exception) {
                    val msg = JSONObject.quote(e.message ?: "Ошибка импорта")
                    runOnUiThread { evaluate("window.onGameFolderResult&&window.onGameFolderResult(false,$msg)") }
                }
            }
        }

        @JavascriptInterface fun getGameInstallStatus(): String {
            val tree = selectedTree
            val imported = prefs.getBoolean("game_imported", false)
            val exePath = prefs.getString("game_path", null)
            val exeFound = if (imported) exePath?.let { File(it).isFile } == true
                else tree?.let { DocumentFile.fromTreeUri(this@MainActivity, it)?.let { root -> findFile(root, "FarCry2.exe") != null } } ?: false
            return JSONObject().put("folderSelected", tree != null).put("exeFound", exeFound)
                .put("imported", imported && exeFound).toString()
        }

        @JavascriptInterface fun getRuntimeBundleStatus(): String {
            val imported = prefs.getBoolean("runtime_imported", false)
            val path = prefs.getString("runtime_path", null)
            val manifest = path?.let { File(it, "runtime-manifest.json") }
            val valid = imported && manifest?.isFile == true
            val runtimeBundled = assets.list("runtime")?.contains("runtime-manifest.json") == true
            val rootAssets = assets.list("")?.toSet() ?: emptySet()
            val winlatorBundled = rootAssets.contains("rootfs.tzst") && rootAssets.contains("container_pattern.tzst")
            val bundled = runtimeBundled || winlatorBundled
            return JSONObject().put("bundled", bundled).put("winlatorBundled", winlatorBundled).put("imported", valid)
                .put("components", if (winlatorBundled) rootAssets.toList() else assets.list("runtime")?.toList() ?: emptyList<String>()).toString()
        }

        @JavascriptInterface fun launchGame(): String {
            if (!prefs.getBoolean("game_imported", false)) {
                return JSONObject().put("started", false).put("message", "Сначала полностью импортируй папку Far Cry 2 во внутреннее хранилище приложения.").toString()
            }
            val gameExePath = prefs.getString("game_path", null)
                ?: return JSONObject().put("started", false).put("message", "Не сохранён путь FarCry2.exe. Повтори импорт игры.").toString()
            val gameExe = try { File(gameExePath).canonicalFile } catch (e: Exception) {
                return JSONObject().put("started", false).put("message", "Некорректный путь к FarCry2.exe").toString()
            }
            val gameRoot = File(filesDir, "game").canonicalFile
            if (!gameExe.toPath().startsWith(gameRoot.toPath()) || !gameExe.isFile || gameExe.length() < 1024L) {
                prefs.edit().putBoolean("game_imported", false).apply()
                return JSONObject().put("started", false).put("message", "Файл FarCry2.exe отсутствует или импорт повреждён. Импортируй игру заново.").toString()
            }
            try {
                assets.open(RootFSInstaller.FILENAME).use { }
                assets.open("container_pattern.tzst").use { }
            } catch (e: Exception) {
                return JSONObject().put("started", false).put("message", "Не найдены системные компоненты игрового модуля. Нужна полная сборка приложения.").toString()
            }
            synchronized(this@MainActivity) {
                if (launchingGame) return JSONObject().put("started", false).put("preparing", true)
                    .put("message", "Подготовка игры уже выполняется: " + launchStage).toString()
                launchingGame = true
                launchWatchdogReported = false
            }
            reportGameProgress(4, "Проверяю игровые файлы и окружение")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (launchingGame && !launchWatchdogReported) {
                    launchWatchdogReported = true
                    reportGameProgress(launchLastPercent, "ДОЛГОЕ ОЖИДАНИЕ: этап «" + launchStage + "» не ответил за 3 минуты. Пришли скриншот этого сообщения.")
                }
            }, 180_000L)
            worker.execute {
                try {
                    reportGameProgress(10, "Проверяю системные файлы")
                    val rootFS = RootFS.find(this@MainActivity)
                    val rootDir = rootFS.rootDir
                    // RootFS.isValid() checks its version marker, not whether the Box64
                    // executable survived extraction. Treat a missing Box64 as an incomplete install.
                    val box64BeforeRepair = File(rootDir, "usr/local/bin/box64")
                    if (!rootFS.isValid() || !box64BeforeRepair.isFile) {
                        if (!box64BeforeRepair.isFile) {
                            // Box64 is a separately installed GeneralComponents package, not part of rootfs.tzst.
                            // Clear Winlator's cached version so GuestProgramLauncherComponent extracts it again.
                            androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                                .edit().remove("current_box64_version").apply()
                            appendLaunchLog("BOX64 CACHE RESET: missing " + box64BeforeRepair.absolutePath)
                        }
                        reportGameProgress(18, "Подготавливаю системные файлы")
                        if (rootDir.exists() && !rootDir.deleteRecursively()) {
                            throw IllegalStateException("Не удалось очистить повреждённый RootFS")
                        }
                        if (!rootDir.mkdirs() && !rootDir.isDirectory) {
                            throw IllegalStateException("Не удалось создать каталог RootFS")
                        }
                        val extracted = TarCompressorUtils.extract(
                            TarCompressorUtils.Type.ZSTD,
                            this@MainActivity,
                            RootFSInstaller.FILENAME,
                            rootDir
                        )
                        if (!extracted) throw IllegalStateException("Не удалось распаковать RootFS. Проверь свободное место.")
                        reportGameProgress(76, "Проверяю установленную систему")
                        rootFS.createRFSVersionFile(RootFSInstaller.LATEST_VERSION.toInt())
                    }
                    if (!rootFS.isValid()) throw IllegalStateException("RootFS не прошёл проверку после установки")
                    // Box64 is a separate Winlator component archive, not included in rootfs.tzst.
                    // Install it explicitly here instead of relying on a cached-version check during launch.
                    val winlatorPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this@MainActivity)
                    val requestedBox64Version = winlatorPrefs.getString("box64_version", DefaultVersion.BOX64) ?: DefaultVersion.BOX64
                    val box64Binary = File(rootDir, "usr/local/bin/box64")
                    if (!box64Binary.isFile || box64Binary.length() == 0L) {
                        appendLaunchLog("BOX64 INSTALL BEGIN version=" + requestedBox64Version + " archive=box64/box64-" + requestedBox64Version + ".tzst")
                        GeneralComponents.extractFile(GeneralComponents.Type.BOX64, this@MainActivity, requestedBox64Version, DefaultVersion.BOX64)
                    }
                    if (!box64Binary.isFile || box64Binary.length() == 0L) {
                        throw IllegalStateException("Не удалось установить Box64 из архива box64-" + requestedBox64Version + ".tzst. Проверь комплектность APK.")
                    }
                    winlatorPrefs.edit().putString("current_box64_version", requestedBox64Version).apply()
                    appendLaunchLog("BOX64 INSTALL OK bytes=" + box64Binary.length() + " path=" + box64Binary.absolutePath)
                    // The first preparation repairs executable bits and patches two Wine
                    // binaries. Both operations scan large files, so cache the result and
                    // only repeat them after a fresh RootFS install or if a key binary is missing.
                    val preparedMarker = File(rootDir, ".fc2-runtime-prepared-v1")
                    val wineServer = File(rootDir, "opt/wine/bin/wineserver")
                    val ntdll = File(rootDir, "opt/wine/lib/wine/x86_64-unix/ntdll.so")
                    val runtimeNeedsPreparation = !preparedMarker.isFile ||
                        !box64Binary.canExecute() || !wineServer.canExecute() || !ntdll.canRead()
                    if (runtimeNeedsPreparation) {
                        reportGameProgress(78, "Однократная настройка игровых компонентов")
                        restoreRootFSExecutePermissions(rootDir)
                        patchWineRootfsPath(rootDir)
                        preparedMarker.writeText("prepared-v1")
                        appendLaunchLog("RUNTIME_PREPARATION complete; cached for next launch")
                    } else {
                        appendLaunchLog("RUNTIME_PREPARATION skipped; cached installation is ready")
                    }
                    reportGameProgress(84, "Настраиваю игровую среду")
                    runOnUiThread {
                        try {
                            val manager = ContainerManager(this@MainActivity)
                            val savedId = prefs.getInt("winlator_container_id", -1)
                            val existing = manager.getContainerById(savedId)
                            if (existing != null) {
                                completeWinlatorLaunch(manager, existing, gameExe)
                            } else {
                                reportGameProgress(90, "Создаю игровую сессию")
                                val config = JSONObject().put("name", "Far Cry 2")
                                manager.createContainerAsync(config) { created ->
                                    if (created == null) {
                                        launchingGame = false
                                        reportGameLaunch(false, "Не удалось создать игровую сессию. Проверь свободное место.")
                                    } else {
                                        completeWinlatorLaunch(manager, created, gameExe)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            launchingGame = false
                            reportGameLaunch(false, "Ошибка подготовки игры: " + (e.message ?: "неизвестная ошибка"))
                        }
                    }
                } catch (e: Exception) {
                    launchingGame = false
                    val message = e.message ?: "Не удалось подготовить системные файлы"
                    runOnUiThread { reportGameLaunch(false, message) }
                }
            }
            return JSONObject().put("started", false).put("preparing", true)
                .put("message", "Подготавливаю игровые компоненты. Первый запуск может занять несколько минут.").toString()
        }

        private fun completeWinlatorLaunch(manager: ContainerManager, container: Container, gameExe: File) {
            try {
                val gameDir = gameExe.parentFile?.canonicalFile
                    ?: throw IllegalStateException("Не удалось определить папку игры")
                // Rebuild drive mappings without stale F: entries, then point F: at this exact imported game folder.
                val driveMap = StringBuilder()
                container.drivesIterator().forEach { drive ->
                    if (!drive.letter.equals("F", ignoreCase = true)) {
                        // The upstream drive parser uses the character before the next drive letter as a separator.
                        // Restore the trailing slash removed by drivesIterator() so existing mappings stay intact.
                        driveMap.append(drive.letter).append(':').append(drive.path).append('/')
                    }
                }
                driveMap.append("F:").append(gameDir.absolutePath)
                container.setDrives(driveMap.toString())
                container.setScreenSize(resolveRequestedScreenSize())
                // Far Cry 2 reads its renderer from GamerProfile.xml; seed DX9 before its first run.
                val fc2ProfileDir = File(container.getUserDir(), "Documents/My Games/Far Cry 2")
                if (!fc2ProfileDir.isDirectory && !fc2ProfileDir.mkdirs()) {
                    throw IllegalStateException("Не удалось создать папку настроек Far Cry 2")
                }
                val fc2Profile = File(fc2ProfileDir, "GamerProfile.xml")
                val profileResolution = container.getScreenSize().split("x")
                val profileWidth = profileResolution.getOrNull(0)?.toIntOrNull() ?: 960
                val profileHeight = profileResolution.getOrNull(1)?.toIntOrNull() ?: 540
                if (!fc2Profile.isFile) {
                    fc2Profile.writeText(
                        """<GamerProfile>
<SoundProfile MusicEnabled="1" MasterVolume="100"/>
<RenderProfile MultiSampleMode="0" AlphaToCoverage="0" ResolutionX="$profileWidth" ResolutionY="$profileHeight" Quality="custom" Fullscreen="0" Maximized="0" ForceWidescreen="1" AspectRatio="0" VSync="0" RefreshRate="60" DisableMip0Loading="0" MaxDriverBufferedFrames="0" Platform="d3d9" ShowFPS="0" ClustersZPassMaxLOD="1" Brightness="1" Contrast="1" GammaRamp="1" GammaRampR="1" GammaRampG="1" GammaRampB="1" AllowAsynchShaderLoading="1">
<CustomQuality><quality ResolutionX="$profileWidth" ResolutionY="$profileHeight" EnvironmentQuality="low" AntiPortalQuality="high" PostFxQuality="low" TextureQuality="low" TextureResolutionQuality="low" WaterQuality="low" DepthPassQuality="high" VegetationQuality="low" TerrainQuality="low" GeometryQuality="low" AmbientQuality="low" ShadowQuality="off" Hdr="0" HdrFP32="0" Bloom="0" id="custom"/></CustomQuality>
</RenderProfile>
<NetworkProfile CustomMapMaxUploadRateOnline="10240" OnlineEnginePort="9000" OnlineServicePort="9001" FileTransferHostPort="9002" FileTransferClientPort="9003" LanBroadcastPort="9004" ScanFreePorts="1" ScanPortRange="1000" ScanPortStart="9000" SessionProvider="" DetectPublicAddress="1" MaxUploadOnline="768"><Accounts/></NetworkProfile>
<GameProfile Sensitivity="1" Invert_y="0" UseMouseSmooth="0" Smoothness="1" Smoothness_Ironsight="1" HelpCrosshair="0" UseCompassMiniMap="1" UseRoadSignHilight="1" UseSubtitles="1" UseAmbx="0" Autosave="1" Machete="0" DifficultyLevel="1" ClanTag=""><FireConfig QualitySetting="VeryHigh"/></GameProfile>
<RealTreeProfile Quality="VeryHigh"><CustomQuality/></RealTreeProfile>
<EngineProfile><PhysicConfig QualitySetting="VeryHigh"/><QcConfig GatherFPS="1" GatherAICnt="1" IsQcTester="0"/><InputConfig/></EngineProfile>
</GamerProfile>"""
                    )
                } else {
                    val oldProfile = fc2Profile.readText()
                    val forcedDx9Profile = oldProfile.replace(
                        Regex("Platform=\"[^\"]*\""),
                        "Platform=\"d3d9\""
                    )
                    if (forcedDx9Profile != oldProfile) fc2Profile.writeText(forcedDx9Profile)
                }
                // Some Wine prefixes expose the game profile directly under the user home.
                // Keep a second copy there as well; the Windows Documents location remains primary.
                val alternateProfile = File(container.getUserDir(), "My Games/Far Cry 2/GamerProfile.xml")
                if (!alternateProfile.isFile) {
                    val alternateDir = alternateProfile.parentFile
                    if (alternateDir != null && (alternateDir.isDirectory || alternateDir.mkdirs())) {
                        fc2Profile.copyTo(alternateProfile, overwrite = false)
                    }
                }
                appendLaunchLog("FC2_PROFILE path=" + fc2Profile.absolutePath + " alternate=" + alternateProfile.absolutePath + " platform=d3d9 size=" + profileWidth + "x" + profileHeight)
                // Apply the graphics choice from the launcher. Previously the UI saved
                // this preference but the Winlator container kept its default Vortek
                // Vulkan driver, so the visible "Turnip" selection had no effect.
                val launchSettings = try {
                    prefs.getString("launch_settings", null)?.let { JSONObject(it) }
                } catch (_: Exception) { null }
                val requestedDriver = launchSettings?.optString("graphicsDriver", "Системный") ?: "Системный"
                // DXVK in this X11 guest needs VK_KHR_surface. Android's direct
                // Turnip Vulkan loader does not expose the X11 WSI surface required by
                // Wine's DXVK instance here. Winlator's Vortek bridge is the upstream
                // path that forwards guest Vulkan work to the Android renderer and
                // provides the X11 surface integration.
                val graphicsDriver = when (requestedDriver) {
                    "VirGL" -> com.winlator.container.GraphicsDrivers.VORTEK + "," +
                        com.winlator.container.GraphicsDrivers.VIRGL
                    else -> com.winlator.container.GraphicsDrivers.VORTEK + "," +
                        com.winlator.container.GraphicsDrivers.DEFAULT_OPENGL_DRIVER
                }
                container.setGraphicsDriver(graphicsDriver)
                // Far Cry 2 uses Direct3D 9. Keep DXVK as the renderer: WineD3D
                // cannot create an EGL/OpenGL context in this Android RootFS (libEGL.so.1
                // is not provided), as confirmed by the device log.
                container.setDXWrapper(com.winlator.container.DXWrappers.DXVK)
                container.setDXWrapperConfig("")
                val gameEnv = com.winlator.core.EnvVars(container.getEnvVars())
                gameEnv.put("WINEESYNC", "0")
                gameEnv.put("WINEFSYNC", "0")
                // Explicitly prefer DXVK's native D3D9/DXGI DLLs for this 32-bit game.
                // Wine's registry defaults are not enough to diagnose whether the
                // native wrapper is actually selected in the experimental WoW64 path.
                gameEnv.put("WINEDLLOVERRIDES", "d3d9=n,b;dxgi=n,b")
                // The previous run hid all DXVK output (upstream defaults to "none").
                // Capture renderer initialization and Wine DLL loading into RootFS/tmp.
                val dxvkLogDir = File(RootFS.find(this@MainActivity).rootDir, "tmp")
                if (!dxvkLogDir.isDirectory) dxvkLogDir.mkdirs()
                gameEnv.put("DXVK_LOG_LEVEL", "info")
                gameEnv.put("DXVK_LOG_PATH", dxvkLogDir.absolutePath)
                gameEnv.put("WINEDEBUG", "+loaddll,+seh")
                container.setEnvVars(gameEnv.toString())
                appendLaunchLog("GRAPHICS_DRIVER requested=$requestedDriver applied=$graphicsDriver")
                appendLaunchLog("DX9_COMPATIBILITY wrapper=dxvk overrides=d3d9:n,b;dxgi:n,b env=WINEESYNC:0,WINEFSYNC:0")
                appendLaunchLog("DXVK_DIAGNOSTICS level=info path=" + dxvkLogDir.absolutePath + " WINEDEBUG=+loaddll,+seh")
                container.saveData()
                prefs.edit().putInt("winlator_container_id", container.id).apply()
                val intent = Intent(this@MainActivity, com.winlator.XServerDisplayActivity::class.java).apply {
                    putExtra("container_id", container.id)
                    putExtra("exec_path", gameExe.absolutePath)
                }
                launchingGame = false
                appendLaunchLog("START_ACTIVITY containerId=${container.id}, exe=${gameExe.absolutePath}")
                reportGameProgress(98, "Передаю FarCry2.exe игровой системе")
                // Opening XServerDisplayActivity only confirms that Android accepted
                // the activity request. It does NOT prove that Wine or Far Cry 2
                // created a game window, so keep the UI in a pending state.
                reportGameProgress(95, "Окно запуска открыто. Ожидание окна Far Cry 2…")
                appendLaunchLog("START_ACTIVITY accepted; waiting for Wine/X11 game-window confirmation")
                startActivity(intent)
            } catch (e: Exception) {
                launchingGame = false
                reportGameLaunch(false, "Не удалось открыть игровую сессию: " + (e.message ?: "ошибка"))
            }
        }

        private fun resolveRequestedScreenSize(): String {
            val settings = try {
                prefs.getString("launch_settings", null)?.let { JSONObject(it) }
            } catch (_: Exception) { null }
            return when (settings?.optString("resolution", "540p") ?: "540p") {
                "480p" -> "854x480"
                "600p" -> "1066x600"
                "720p" -> "1280x720"
                "Native" -> {
                    val metrics = android.util.DisplayMetrics()
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.getRealMetrics(metrics)
                    val width = maxOf(metrics.widthPixels, metrics.heightPixels)
                    val height = minOf(metrics.widthPixels, metrics.heightPixels)
                    "${width}x${height}"
                }
                else -> "960x540"
            }
        }

        private fun reportGameProgress(percent: Int, message: String) {
            launchLastPercent = percent.coerceIn(0, 100)
            launchStage = message
            appendLaunchLog("PROGRESS $percent%: $message")
            runOnUiThread {
                evaluate("window.onGameLaunchProgress&&window.onGameLaunchProgress(" + percent.coerceIn(0, 100) + "," + JSONObject.quote(message) + ")")
            }
        }

        private fun reportGameLaunch(ok: Boolean, message: String) {
            appendLaunchLog("RESULT ok=$ok: $message")
            runOnUiThread {
                evaluate("window.onGameLaunchResult&&window.onGameLaunchResult(" + ok + "," + JSONObject.quote(message) + ")")
            }
        }

        @JavascriptInterface fun stopGame(): String {
            synchronized(this@MainActivity) {
                val stoppedWinlator = try {
                    com.winlator.XServerDisplayActivity.stopActiveSession()
                } catch (_: Exception) { false }
                val process = gameProcess
                val stoppedProcess = process?.isAlive == true
                if (stoppedProcess) process?.destroy()
                gameProcess = null
                try { inputSocket?.close() } catch (_: Exception) { }
                inputSocket = null
                val stopped = stoppedWinlator || stoppedProcess
                appendLaunchLog("STOP_REQUEST stopped=$stopped")
                return JSONObject().put("stopped", stopped)
                    .put("message", if (stopped) "Игровая сессия остановлена" else "Активная игровая сессия не найдена").toString()
            }
        }

        @JavascriptInterface fun isGameRunning(): Boolean =
            gameProcess?.isAlive == true || try { com.winlator.XServerDisplayActivity.isSessionActive() } catch (_: Exception) { false }

        @JavascriptInterface fun saveLaunchSettings(json: String) {
            prefs.edit().putString("launch_settings", json).apply()
        }

        @JavascriptInterface fun onGameInput(json: String) {
            worker.execute {
                try {
                    var socket = inputSocket
                    if (socket == null || !socket.isConnected) {
                        socket?.close()
                        socket = LocalSocket()
                        socket.connect(LocalSocketAddress("fc2_runtime_input", LocalSocketAddress.Namespace.ABSTRACT))
                        inputSocket = socket
                    }
                    socket.getOutputStream().apply {
                        write((json + "\\n").toByteArray(Charsets.UTF_8))
                        flush()
                    }
                } catch (_: Exception) {
                    try { inputSocket?.close() } catch (_: Exception) { }
                    inputSocket = null
                }
            }
        }

        @JavascriptInterface fun vibrate() {
            try {
                if (Build.VERSION.SDK_INT >= 31) {
                    val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    manager.defaultVibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    (getSystemService(VIBRATOR_SERVICE) as Vibrator).vibrate(18)
                }
            } catch (_: Exception) { }
        }
    }

    private fun evaluate(js: String) {
        val message = when {
            js.contains("onGameFolderResult") -> "Папка игры обновлена"
            js.contains("onRuntimeResult") -> "Результат операции с runtime обновлён"
            js.contains("onGameLaunchProgress") -> "Подготовка игры: " + launchStage
            js.contains("onGameLaunchResult") -> if (js.contains("true")) "Игра запускается" else "Не удалось запустить игру"
            js.contains("onWorldMapResult") -> "Операция с картой завершена"
            else -> "Состояние обновлено"
        }
        runOnUiThread { nativeUi?.onNativeMessage(message) }
    }

    private fun findFile(dir: DocumentFile, wanted: String): DocumentFile? {
        for (child in dir.listFiles()) {
            if (child.isFile && child.name.equals(wanted, ignoreCase = true)) return child
            if (child.isDirectory) findFile(child, wanted)?.let { return it }
        }
        return null
    }

    private fun findFile(dir: File, wanted: String): File? {
        dir.listFiles()?.forEach { child ->
            if (child.isFile && child.name.equals(wanted, ignoreCase = true)) return child
            if (child.isDirectory) findFile(child, wanted)?.let { return it }
        }
        return null
    }

    private fun copyDocumentContents(source: DocumentFile, destination: File) {
        source.listFiles().forEach { child ->
            val name = child.name ?: throw IllegalStateException("У файла runtime отсутствует имя")
            val out = File(destination, name).canonicalFile
            if (!out.toPath().startsWith(destination.canonicalFile.toPath())) throw IllegalStateException("Недопустимый путь в runtime")
            if (child.isDirectory) {
                if (!out.exists() && !out.mkdirs()) throw IllegalStateException("Не удалось создать " + child.name)
                copyDocumentContents(child, out)
            } else if (child.isFile) {
                contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output, 1024 * 1024) }
                } ?: throw IllegalStateException("Не удалось прочитать " + child.name)
            }
        }
    }

    private fun copyTree(source: DocumentFile, destination: File) {
        if (source.isDirectory) {
            val dir = File(destination, source.name ?: "data").apply { mkdirs() }
            source.listFiles().forEach { child -> copyTree(child, dir) }
        } else if (source.isFile) {
            val out = File(destination, source.name ?: "file")
            contentResolver.openInputStream(source.uri)?.use { input ->
                FileOutputStream(out).use { output -> input.copyTo(output, 1024 * 1024) }
            } ?: throw IllegalStateException("Не удалось прочитать ${source.name}")
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestWorldMap && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Не удалось прочитать изображение карты")
                if (bytes.size > 12 * 1024 * 1024) throw IllegalStateException("Файл карты слишком большой (максимум 12 МБ)")
                val target = File(filesDir, "world-map.jpg")
                target.writeBytes(bytes)
                val mime = contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                prefs.edit().putString("world_map_mime", mime).apply()
                val localUri = Uri.fromFile(target).toString()
                evaluate("window.onWorldMapResult&&window.onWorldMapResult(true," + JSONObject.quote(localUri) + ",\"Карта сохранена в приложении\"," + JSONObject.quote(mime) + ",true)")
            } catch (e: Exception) {
                evaluate("window.onWorldMapResult&&window.onWorldMapResult(false,\"\"," + JSONObject.quote(e.message ?: "Ошибка загрузки карты") + ",\"image/jpeg\",true)")
            }
            return
        }
        if ((requestCode == requestFolder || requestCode == requestRuntimeFolder) && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
            if (requestCode == requestFolder) {
                selectedTree = uri
                prefs.edit().putString("game_tree_uri", uri.toString()).putBoolean("game_imported", false).apply()
                evaluate("window.onGameFolderResult&&window.onGameFolderResult(true,'Папка игры выбрана')")
            } else {
                prefs.edit().putString("runtime_tree_uri", uri.toString()).putBoolean("runtime_imported", false).apply()
                evaluate("window.onRuntimeResult&&window.onRuntimeResult(true,'Папка выбрана. Нажми ИМПОРТ')")
            }
        }
    }

    override fun onDestroy() {
        gameProcess?.let { if (it.isAlive) it.destroy() }
        try { inputSocket?.close() } catch (_: Exception) { }
        worker.shutdown()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        val active = try { com.winlator.XServerDisplayActivity.isSessionActive() } catch (_: Exception) { false }
        if (active) {
            if (nativeUi?.isPauseMenuVisible() == true) finish() else nativeUi?.showPauseMenu()
        } else super.onBackPressed()
    }
}
