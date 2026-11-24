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
                    val copiedExe = File(target, relativePath(sourceRoot, exe))
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
            val names = assets.list("runtime")?.toList() ?: emptyList()
            val bundled = names.contains("runtime-manifest.json")
            return JSONObject().put("bundled", bundled).put("components", names).toString()
        }

        @JavascriptInterface fun launchGame(): String {
            return JSONObject().put("started", false)
                .put("message", "Windows runtime ещё не встроен. Лаунчер готов, но Far Cry 2 пока не запускается.")
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

    private fun relativePath(root: DocumentFile, file: DocumentFile): String {
        val rootUri = root.uri.toString().trimEnd('/')
        val fileUri = file.uri.toString()
        return file.name ?: fileUri.removePrefix(rootUri).trimStart('/')
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
        if (requestCode == requestFolder && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            contentResolver.takePersistableUriPermission(uri, flags)
            selectedTree = uri
            prefs.edit().putString("game_tree_uri", uri.toString()).putBoolean("game_imported", false).apply()
            evaluate("window.onGameFolderResult&&window.onGameFolderResult(true,'Папка игры выбрана')")
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
