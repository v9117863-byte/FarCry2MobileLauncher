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
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            addJavascriptInterface(AndroidBridge(), "AndroidDevice")
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
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
                runOnUiThread { evaluate("window.onRuntimeResult&&window.onRuntimeResult(false,'Сначала выбери папку runtime')") }
                return
            }
            worker.execute {
                try {
                    val root = DocumentFile.fromTreeUri(this@MainActivity, tree)
                        ?: throw IllegalStateException("Не удалось открыть папку runtime")
                    val target = File(filesDir, "runtime")
                    if (target.exists() && !target.deleteRecursively()) throw IllegalStateException("Не удалось очистить старую папку runtime")
                    if (!target.mkdirs()) throw IllegalStateException("Не удалось создать папку runtime")
                    copyDocumentContents(root, target)
                    val manifest = File(target, "runtime-manifest.json")
                    if (!manifest.isFile) throw IllegalStateException("Не найден runtime-manifest.json в корне выбранной папки")
                    val json = JSONObject(manifest.readText(Charsets.UTF_8))
                    val required = listOf("entrypoint", "wine", "box86", "box64", "rootfs", "graphicsBackend")
                    val missing = required.filter { key ->
                        val rel = json.optString(key, "")
                        if (rel.isBlank()) true else {
                            val candidate = File(target, rel).canonicalFile
                            !candidate.toPath().startsWith(target.canonicalFile.toPath()) || !candidate.exists()
                        }
                    }
                    if (missing.isNotEmpty()) throw IllegalStateException("Runtime неполный. Отсутствуют компоненты: " + missing.joinToString())
                    listOf("entrypoint", "wine", "box86", "box64").forEach { key ->
                        val executable = File(target, json.getString(key))
                        if (executable.isFile && !executable.setExecutable(true, false) && !executable.canExecute()) {
                            throw IllegalStateException("Не удалось сделать исполняемым компонент: " + key)
                        }
                    }
                    val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                    val supported = json.optJSONArray("supportedAbis")
                    if (supported != null && (0 until supported.length()).none { supported.optString(it) == abi }) {
                        throw IllegalStateException("Пакет runtime не поддерживает ABI устройства: " + abi)
                    }
                    prefs.edit().putBoolean("runtime_imported", true).putString("runtime_path", target.absolutePath).apply()
                    runOnUiThread { evaluate("window.onRuntimeResult&&window.onRuntimeResult(true,'Runtime скопирован и проверен: все компоненты найдены')") }
                } catch (e: Exception) {
                    File(filesDir, "runtime").deleteRecursively()
                    prefs.edit().putBoolean("runtime_imported", false).remove("runtime_path").apply()
                    val msg = JSONObject.quote(e.message ?: "Ошибка импорта runtime")
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
            val bundled = assets.list("runtime")?.contains("runtime-manifest.json") == true
            return JSONObject().put("bundled", bundled).put("imported", valid)
                .put("components", assets.list("runtime")?.toList() ?: emptyList<String>()).toString()
        }

        @JavascriptInterface fun launchGame(): String {
            synchronized(this@MainActivity) {
                if (gameProcess?.isAlive == true) {
                    return JSONObject().put("started", true).put("alreadyRunning", true)
                        .put("message", "Игровой runtime уже запущен внутри Far Cry 2 Mobile").toString()
                }
                val runtimePath = prefs.getString("runtime_path", null)
                    ?: return JSONObject().put("started", false).put("message", "Сначала импортируй runtime-пакет").toString()
                val gameExePath = prefs.getString("game_path", null)
                    ?: return JSONObject().put("started", false).put("message", "Сначала импортируй файлы Far Cry 2").toString()
                val runtimeRoot = File(runtimePath).canonicalFile
                val gameExe = File(gameExePath).canonicalFile
                val manifestFile = File(runtimeRoot, "runtime-manifest.json")
                if (!manifestFile.isFile || !gameExe.isFile) {
                    return JSONObject().put("started", false).put("message", "Не найдены manifest runtime или FarCry2.exe").toString()
                }
                try {
                    val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
                    fun runtimeFile(key: String): File {
                        val rel = manifest.optString(key, "")
                        require(rel.isNotBlank()) { "В runtime-manifest.json отсутствует $key" }
                        val file = File(runtimeRoot, rel).canonicalFile
                        require(file.toPath().startsWith(runtimeRoot.toPath()) && file.exists()) { "Компонент $key не найден или путь некорректен" }
                        return file
                    }
                    val entrypoint = runtimeFile("entrypoint")
                    runtimeFile("wine")
                    runtimeFile("box86")
                    runtimeFile("box64")
                    runtimeFile("rootfs")
                    runtimeFile("graphicsBackend")
                    require(entrypoint.setExecutable(true, false) || entrypoint.canExecute()) {
                        "Entry point runtime не является исполняемым"
                    }
                    val args = mutableListOf(entrypoint.absolutePath)
                    val launchArgs = manifest.optJSONArray("launchArgs")
                    if (launchArgs != null) {
                        for (i in 0 until launchArgs.length()) {
                            args.add(launchArgs.optString(i).replace("{gameExe}", gameExe.absolutePath)
                                .replace("{gameDir}", gameExe.parentFile?.absolutePath ?: filesDir.absolutePath))
                        }
                    } else {
                        args.add(gameExe.absolutePath)
                    }
                    val log = File(filesDir, "runtime-launch.log")
                    val builder = ProcessBuilder(args)
                        .directory(runtimeRoot)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                    builder.environment().apply {
                        put("FC2_GAME_EXE", gameExe.absolutePath)
                        put("FC2_GAME_DIR", gameExe.parentFile?.absolutePath ?: filesDir.absolutePath)
                        put("FC2_RUNTIME_DIR", runtimeRoot.absolutePath)
                        put("FC2_ANDROID_ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                        put("HOME", filesDir.absolutePath)
                    }
                    gameProcess = builder.start()
                    getSharedPreferences("fc2", MODE_PRIVATE).edit().putBoolean("game_process_started", true).apply()
                    return JSONObject().put("started", true)
                        .put("message", "Runtime запущен как внутренний процесс приложения. Графический вывод зависит от backend пакета.")
                        .put("log", log.absolutePath).toString()
                } catch (e: Exception) {
                    gameProcess = null
                    return JSONObject().put("started", false)
                        .put("message", "Не удалось запустить runtime: " + (e.message ?: "ошибка"))
                        .toString()
                }
            }
        }

        @JavascriptInterface fun stopGame(): String {
            synchronized(this@MainActivity) {
                val process = gameProcess
                if (process?.isAlive == true) process.destroy()
                gameProcess = null
                try { inputSocket?.close() } catch (_: Exception) { }
                inputSocket = null
                return JSONObject().put("stopped", true).put("message", "Внутренний runtime остановлен").toString()
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
                val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                evaluate("window.onWorldMapResult&&window.onWorldMapResult(true," + JSONObject.quote(encoded) + ",\"Карта загружена в приложение\")")
            } catch (e: Exception) {
                evaluate("window.onWorldMapResult&&window.onWorldMapResult(false,\"\"," + JSONObject.quote(e.message ?: "Ошибка загрузки карты") + ")")
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
                evaluate("window.onRuntimeResult&&window.onRuntimeResult(true,'Папка runtime выбрана. Нажми IMPORT RUNTIME')")
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
