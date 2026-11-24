package com.farcry2.mobile

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
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
    private val requestFolder = 4102
    private val requestRuntimeFolder = 4103

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
                .put("components", assets.list("runtime")?.toList() ?: emptyList()).toString()
        }

        @JavascriptInterface fun launchGame(): String {
            val status = JSONObject(getRuntimeBundleStatus())
            if (!status.optBoolean("imported") && !status.optBoolean("bundled")) {
                return JSONObject().put("started", false)
                    .put("message", "Сначала импортируй полный runtime-пакет. Без Wine, Box86/Box64, RootFS и графического backend игра не запускается.")
                    .toString()
            }
            return JSONObject().put("started", false)
                .put("message", "Runtime найден, но адаптер запуска Windows-процесса и графический вывод ещё не реализованы. Игра пока не запущена.")
                .toString()
        }

        @JavascriptInterface fun saveLaunchSettings(json: String) {
            prefs.edit().putString("launch_settings", json).apply()
        }

        @JavascriptInterface fun onGameInput(json: String) {
            // Input bridge endpoint reserved for the future native runtime input daemon.
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
        worker.shutdown()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
