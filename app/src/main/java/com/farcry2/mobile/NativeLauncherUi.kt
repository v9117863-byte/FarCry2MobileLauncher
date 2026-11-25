package com.farcry2.mobile

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject

/**
 * Fully native launcher surface. No WebView, HTML, CSS or JavaScript UI.
 * The existing Winlator integration remains owned by MainActivity.AndroidBridge.
 */
class NativeLauncherUi(
    private val activity: Activity,
    private val actions: Actions
) {
    interface Actions {
        fun launch(): String
        fun chooseGameFolder()
        fun importGameFiles()
        fun chooseRuntimeFolder()
        fun importRuntime()
        fun chooseMap()
        fun stopGame(): String
        fun deviceInfo(): String
        fun gameStatus(): String
        fun runtimeStatus(): String
        fun saveSettings(json: String)
        fun copyError()
    }

    private val bg = Color.rgb(17, 20, 15)
    private val panel = Color.rgb(35, 40, 29)
    private val olive = Color.rgb(67, 76, 49)
    private val sand = Color.rgb(213, 190, 137)
    private val gold = Color.rgb(199, 151, 65)
    private val text = Color.rgb(231, 222, 197)
    private val muted = Color.rgb(164, 157, 133)
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var gameState: TextView
    private lateinit var runtimeState: TextView
    private var currentPage = "home"
    val root: View

    init {
        val frame = FrameLayout(activity).apply { setBackgroundColor(bg) }
        val scroll = ScrollView(activity).apply { isFillViewport = true }
        body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        scroll.addView(body)
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root = frame
        showHome()
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private fun card(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = shape(panel, gold, 1)
        val p = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.bottomMargin = dp(12)
        layoutParams = p
    }
    private fun shape(color: Int, stroke: Int = color, width: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(3).toFloat()
        if (width > 0) setStroke(dp(width), stroke)
    }
    private fun label(value: String, size: Float = 14f, color: Int = text, bold: Boolean = false) =
        TextView(activity).apply {
            this.text = value; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setPadding(0, dp(3), 0, dp(5))
        }
    private fun button(value: String, onClick: () -> Unit, primary: Boolean = false): Button =
        Button(activity).apply {
            text = value
            isAllCaps = false
            textSize = 14f
            setTextColor(if (primary) Color.rgb(24, 25, 18) else text)
            background = shape(if (primary) gold else olive, gold, 1)
            minHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { onClick() }
            val p = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
            p.topMargin = dp(7); layoutParams = p
        }
    private fun heading(title: String, subtitle: String) {
        body.removeAllViews()
        body.addView(label("FAR CRY 2", 29f, sand, true))
        body.addView(label(title.uppercase(), 17f, gold, true))
        body.addView(label(subtitle, 11f, muted))
    }
    private fun addCard(title: String): LinearLayout {
        val c = card()
        c.addView(label(title.uppercase(), 15f, gold, true))
        body.addView(c)
        return c
    }
    private fun showStatus(message: String) {
        status.text = message
    }
    private fun makeStatus(c: LinearLayout) {
        status = label("Готов к работе", 13f, sand)
        status.setPadding(0, dp(10), 0, dp(8))
        c.addView(status)
    }

    private fun showHome() {
        currentPage = "home"
        heading("FIELD OPERATIONS", "АФРИКА  ·  SINGLE PLAYER")
        val c = addCard("Игровая сессия")
        c.addView(label("Запуск Far Cry 2 через встроенную систему Winlator", 13f, text))
        c.addView(button("▶   ЗАПУСТИТЬ FAR CRY 2", {
            val result = try { JSONObject(actions.launch()) } catch (_: Exception) { JSONObject() }
            showStatus(result.optString("message", "Запрос запуска отправлен"))
        }, true))
        c.addView(button("■   ОСТАНОВИТЬ ИГРУ", {
            val result = try { JSONObject(actions.stopGame()) } catch (_: Exception) { JSONObject() }
            showStatus(result.optString("message", "Запрос остановки отправлен"))
        }))
        makeStatus(c)

        val files = addCard("Игровые файлы")
        gameState = label("Проверка…", 13f, sand)
        files.addView(gameState)
        files.addView(button("ВЫБРАТЬ ПАПКУ ИГРЫ", { actions.chooseGameFolder() }))
        files.addView(button("ИМПОРТИРОВАТЬ ФАЙЛЫ", { actions.importGameFiles() }))
        files.addView(button("ОБНОВИТЬ СТАТУС", { refreshStatus() }))

        val settings = addCard("Система")
        settings.addView(button("НАСТРОЙКИ", { showSettings() }, true))
        settings.addView(button("ДИАГНОСТИКА УСТРОЙСТВА", { showDiagnostics() }))
        settings.addView(button("ДОПОЛНИТЕЛЬНЫЙ ИГРОВОЙ МОДУЛЬ", { showRuntime() }))
        refreshStatus()
    }

    private fun showSettings() {
        currentPage = "settings"
        heading("OPTIONS", "НАСТРОЙКИ ИГРОВОЙ СИСТЕМЫ")
        val c = addCard("Экран и изображение")
        val resolution = spinner(listOf("480p", "540p", "600p", "720p", "Native"), "540p")
        c.addView(label("Разрешение игрового экрана", 13f)); c.addView(resolution)
        val graphics = addCard("Графика и производительность")
        val driver = spinner(listOf("Turnip", "VirGL", "Системный"), "Turnip")
        graphics.addView(label("Графический драйвер", 13f)); graphics.addView(driver)
        val quality = spinner(listOf("Низкая", "Средняя", "Высокая"), "Низкая")
        graphics.addView(label("Профиль производительности", 13f)); graphics.addView(quality)
        val controls = addCard("Управление")
        val touch = CheckBox(activity).apply { text = "Показывать сенсорное управление"; setTextColor(text); isChecked = true }
        controls.addView(touch)
        val misc = addCard("Системные действия")
        misc.addView(button("ВЫБРАТЬ КАРТУ", { actions.chooseMap() }))
        misc.addView(button("ДИАГНОСТИКА", { showDiagnostics() }))
        misc.addView(button("СОХРАНИТЬ НАСТРОЙКИ", {
            val json = JSONObject()
                .put("resolution", resolution.selectedItem?.toString() ?: "540p")
                .put("graphicsDriver", driver.selectedItem?.toString() ?: "Turnip")
                .put("performance", quality.selectedItem?.toString() ?: "Низкая")
                .put("touchControls", touch.isChecked)
            actions.saveSettings(json.toString())
            showStatus("Настройки сохранены")
        }, true))
        body.addView(button("‹   ГЛАВНОЕ МЕНЮ", { showHome() }))
    }

    private fun spinner(values: List<String>, selected: String): Spinner {
        val s = Spinner(activity)
        s.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, values)
        s.setSelection(values.indexOf(selected).coerceAtLeast(0))
        return s
    }

    private fun showRuntime() {
        currentPage = "runtime"
        heading("SYSTEM COMPONENTS", "ИМПОРТ И ПРОВЕРКА WINLATOR RUNTIME")
        val c = addCard("Игровая система")
        runtimeState = label("Проверка…", 13f, sand)
        c.addView(runtimeState)
        c.addView(button("ВЫБРАТЬ ПАПКУ МОДУЛЯ", { actions.chooseRuntimeFolder() }))
        c.addView(button("ИМПОРТИРОВАТЬ И ПРОВЕРИТЬ", { actions.importRuntime() }, true))
        body.addView(button("‹   ГЛАВНОЕ МЕНЮ", { showHome() }))
        refreshStatus()
    }

    private fun showDiagnostics() {
        currentPage = "diagnostics"
        heading("FIELD REPORT", "ДИАГНОСТИКА УСТРОЙСТВА И ИГРОВОЙ СРЕДЫ")
        val c = addCard("Устройство")
        val info = try { JSONObject(actions.deviceInfo()) } catch (_: Exception) { JSONObject() }
        listOf("Модель" to info.optString("manufacturer") + " " + info.optString("model"),
            "Android" to info.optString("android"),
            "Архитектура" to info.optString("abis"),
            "OpenGL ES" to info.optString("glesVersion"),
            "Свободная память" to (info.optLong("ramAvailableBytes") / 1024 / 1024).toString() + " МБ",
            "Свободное место" to (info.optLong("storageFreeBytes") / 1024 / 1024).toString() + " МБ"
        ).forEach { (k, v) -> c.addView(label("$k: $v", 13f, text)) }
        c.addView(button("СКОПИРОВАТЬ ОТЧЁТ ОБ ОШИБКЕ", { actions.copyError() }))
        body.addView(button("‹   ГЛАВНОЕ МЕНЮ", { showHome() }))
    }

    fun refreshStatus() {
        try {
            val g = JSONObject(actions.gameStatus())
            gameState.text = when {
                g.optBoolean("imported") -> "Игра импортирована и готова"
                g.optBoolean("exeFound") -> "FarCry2.exe найден — требуется импорт"
                g.optBoolean("folderSelected") -> "Папка выбрана — FarCry2.exe не найден"
                else -> "Папка игры не выбрана"
            }
        } catch (_: Exception) { if (::gameState.isInitialized) gameState.text = "Не удалось проверить игровые файлы" }
        if (::runtimeState.isInitialized) {
            runtimeState.text = try {
                val r = JSONObject(actions.runtimeStatus())
                when { r.optBoolean("winlatorBundled") -> "Winlator встроен в APK"; r.optBoolean("imported") -> "Дополнительный модуль установлен"; else -> "Используются системные компоненты APK" }
            } catch (_: Exception) { "Статус недоступен" }
        }
    }

    fun onNativeMessage(message: String) {
        if (!::status.isInitialized) return
        status.text = message
        refreshStatus()
    }
}
