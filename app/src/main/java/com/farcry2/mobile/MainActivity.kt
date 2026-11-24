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
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.documentfile.provider.DocumentFile
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.core.TarCompressorUtils
import com.winlator.xenvironment.RootFS
import com.winlator.xenvironment.RootFSInstaller
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var web: WebView
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
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val mapFile = File(filesDir, "world-map.jpg")
                    if (mapFile.isFile && mapFile.length() <= 12L * 1024 * 1024) {
                        // Pass a local file URI instead of a multi-megabyte Base64 string through evaluateJavascript.
                        val localUri = Uri.fromFile(mapFile).toString()
                        val mime = prefs.getString("world_map_mime", "image/jpeg") ?: "image/jpeg"
                        view?.evaluateJavascript(
                            "window.onWorldMapResult&&window.onWorldMapResult(true," + JSONObject.quote(localUri) + "," + JSONObject.quote("Сохранённая карта загружена") + "," + JSONObject.quote(mime) + ",true)",
                            null
                        )
                    }
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(AndroidBridge(), "AndroidDevice")
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
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
                    if (!rootFS.isValid()) {
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
                container.saveData()
                prefs.edit().putInt("winlator_container_id", container.id).apply()
                val intent = Intent(this@MainActivity, com.winlator.XServerDisplayActivity::class.java).apply {
                    putExtra("container_id", container.id)
                    putExtra("exec_path", gameExe.absolutePath)
                }
                launchingGame = false
                appendLaunchLog("START_ACTIVITY containerId=${container.id}, exe=${gameExe.absolutePath}")
                reportGameProgress(98, "Передаю FarCry2.exe игровой системе")
                reportGameLaunch(true, "Игровая сессия готова. Запускаю Far Cry 2.")
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
                val process = gameProcess
                if (process?.isAlive == true) process.destroy()
                gameProcess = null
                try { inputSocket?.close() } catch (_: Exception) { }
                inputSocket = null
                return JSONObject().put("stopped", true).put("message", "Игровая сессия остановлена").toString()
            }
        }

        @JavascriptInterface fun isGameRunning(): Boolean = gameProcess?.isAlive == true

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
        if (::web.isInitialized) web.evaluateJavascript(js, null)
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
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
