package com.farcry2.mobile

import android.app.Activity
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import org.json.JSONObject

/** Native landscape menu inspired by classic console game front ends. */
class NativeLauncherUi(private val activity: Activity, private val actions: Actions) {
    interface Actions {
        fun launch(): String
        fun resumeGame()
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

    private val ink = Color.rgb(18, 27, 24)
    private val olive = Color.rgb(157, 174, 133)
    private val cream = Color.rgb(244, 239, 216)
    private val gold = Color.rgb(196, 157, 86)
    private val muted = Color.rgb(213, 216, 192)
    private lateinit var body: LinearLayout
    private val clickTone = ToneGenerator(AudioManager.STREAM_SYSTEM, 38)
    private val soundHandler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var gameState: TextView
    private lateinit var runtimeState: TextView
    private var currentPage = "home"
    val root: View

    init {
        val frame = FrameLayout(activity)
        frame.addView(SceneBackdrop(activity), FrameLayout.LayoutParams(-1, -1))
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val panel = FrameLayout(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(67, 78, 61), Color.rgb(27, 36, 31))).apply {
                setStroke(dp(1), Color.argb(120, 218, 197, 143))
            }
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(dp(22), dp(18), dp(22), dp(18))
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
        }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content, ScrollView.LayoutParams(-1, -2))
        panel.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val panelParams = LinearLayout.LayoutParams(0, -1, 0.38f)
        row.addView(panel, panelParams)
        val right = FrameLayout(activity)
        right.addView(TextView(activity).apply {
            text = "FIELD\nOPERATIONS"
            textSize = 30f
            setTextColor(Color.argb(185, 235, 230, 205))
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(0, 0, dp(28), dp(22))
        }, FrameLayout.LayoutParams(-1, -1))
        row.addView(right, LinearLayout.LayoutParams(0, -1, 0.66f))
        frame.addView(row, FrameLayout.LayoutParams(-1, -1))
        body = content
        root = frame
        showHome()
    }

    private fun playClick() {
        try {
            clickTone.startTone(ToneGenerator.TONE_PROP_ACK, 48)
            soundHandler.postDelayed({ try { clickTone.stopTone() } catch (_: Exception) {} }, 65L)
        } catch (_: Exception) {}
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(activity).apply {
            text = value; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setShadowLayer(1.5f, 1f, 1f, Color.argb(90, 0, 0, 0))
        }
    private fun button(value: String, selected: Boolean = false, onClick: () -> Unit) =
        TextView(activity).apply {
            text = value.uppercase()
            gravity = Gravity.CENTER
            textSize = 17f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setTextColor(if (selected) Color.rgb(22, 26, 23) else cream)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            val face = GradientDrawable().apply {
                setColor(if (selected) Color.rgb(221, 211, 180) else Color.argb(28, 232, 225, 195))
                cornerRadius = dp(2).toFloat()
                setStroke(dp(1), if (selected) Color.rgb(250, 240, 211) else Color.argb(75, 230, 222, 188))
            }
            background = RippleDrawable(
                ColorStateList.valueOf(Color.argb(95, 255, 236, 190)), face, null
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { playClick(); onClick() }
            val p = LinearLayout.LayoutParams(-1, dp(46))
            p.topMargin = dp(2)
            layoutParams = p
        }
    private fun heading(title: String, subtitle: String) {
        body.removeAllViews()
        body.addView(text("FAR CRY 2", 31f, Color.WHITE, true))
        body.addView(text(title.uppercase(), 12f, Color.rgb(48, 57, 41), true).apply {
            letterSpacing = 0.16f
            setPadding(0, dp(1), 0, dp(5))
        })
        body.addView(text(subtitle.uppercase(), 9f, Color.rgb(57, 67, 48)).apply {
            setPadding(0, 0, 0, dp(15))
        })
    }
    private fun section(value: String) {
        body.addView(text(value.uppercase(), 10f, Color.rgb(58, 67, 48), true).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
    }
    private fun message(value: String) {
        if (::status.isInitialized) status.text = value
    }

    private fun showHome() {
        currentPage = "home"
        heading("FIELD OPERATIONS", "AFRICA  /  SINGLE PLAYER")
        body.addView(button("▶   Запустить игру", true) {
            val result = try { JSONObject(actions.launch()) } catch (_: Exception) { JSONObject() }
            message(result.optString("message", "Запрос запуска отправлен"))
        })
        body.addView(button("Настройки") { showSettings() })
        body.addView(View(activity), LinearLayout.LayoutParams(1, 0, 1f))
        section("Состояние системы")
        gameState = text("Проверка игровых файлов…", 11f, Color.WHITE)
        body.addView(gameState)
        status = text("Готов к работе", 11f, Color.WHITE)
        body.addView(status)
        refreshStatus()
    }

    /** Full menu shown when the user returns from the game to the launcher. */
    fun showPauseMenu() {
        currentPage = "pause"
        heading("PAUSED", "FIELD OPERATIONS  /  GAME MENU")
        body.addView(button("▶   Продолжить", true) { actions.resumeGame() })
        body.addView(button("Карта") { actions.chooseMap(); message("Выбор карты открыт") })
        body.addView(button("Игра") { showGameFiles() })
        body.addView(button("Задания") { showDiagnostics() })
        body.addView(button("Статистика") { showDiagnostics() })
        body.addView(button("Настройки") { showSettings() })
        body.addView(button("Выйти из игры") {
            val result = try { JSONObject(actions.stopGame()) } catch (_: Exception) { JSONObject() }
            message(result.optString("message", "Запрос остановки отправлен"))
            showHome()
        })
    }

    private fun showGameFiles() {
        currentPage = "files"
        heading("GAME", "INSTALLATION  /  GAME FILES")
        section("Установка Far Cry 2")
        gameState = text("Проверка…", 12f, Color.WHITE)
        body.addView(gameState)
        body.addView(button("Выбрать папку игры") { actions.chooseGameFolder() })
        body.addView(button("Импортировать файлы") { actions.importGameFiles() })
        body.addView(button("Проверить ещё раз") { refreshStatus() })
        body.addView(button("Назад") { showPauseMenu() })
        refreshStatus()
    }

    private fun showSettings() {
        currentPage = "settings"
        heading("OPTIONS", "GAME AND SYSTEM SETTINGS")
        section("Экран")
        val resolution = spinner(listOf("480p", "540p", "600p", "720p", "Native"), "540p")
        body.addView(text("Разрешение игрового экрана", 12f, Color.WHITE)); body.addView(resolution)
        section("Графика")
        val driver = spinner(listOf("Turnip", "VirGL", "Системный"), "Turnip")
        body.addView(text("Графический драйвер", 12f, Color.WHITE)); body.addView(driver)
        val quality = spinner(listOf("Низкая", "Средняя", "Высокая"), "Низкая")
        body.addView(text("Профиль производительности", 12f, Color.WHITE)); body.addView(quality)
        val touch = CheckBox(activity).apply {
            text = "Сенсорное управление"; setTextColor(Color.WHITE); isChecked = true
        }
        body.addView(touch)
        body.addView(button("Сохранить настройки", true) {
            val json = JSONObject().put("resolution", resolution.selectedItem?.toString() ?: "540p")
                .put("graphicsDriver", driver.selectedItem?.toString() ?: "Turnip")
                .put("performance", quality.selectedItem?.toString() ?: "Низкая")
                .put("touchControls", touch.isChecked)
            actions.saveSettings(json.toString())
            message("Настройки сохранены")
        })
        body.addView(button("Дополнительные модули") { showRuntime() })
        body.addView(button("Назад") { if (currentPage == "settings") showHome() })
    }

    private fun spinner(values: List<String>, selected: String): Spinner {
        val s = Spinner(activity)
        s.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, values)
        s.setSelection(values.indexOf(selected).coerceAtLeast(0))
        return s
    }

    private fun showRuntime() {
        currentPage = "runtime"
        heading("SYSTEM", "WINLATOR RUNTIME COMPONENTS")
        runtimeState = text("Проверка компонентов…", 12f, Color.WHITE)
        body.addView(runtimeState)
        body.addView(button("Выбрать папку модуля") { actions.chooseRuntimeFolder() })
        body.addView(button("Импортировать модуль", true) { actions.importRuntime() })
        body.addView(button("Назад") { showSettings() })
        refreshStatus()
    }

    private fun showDiagnostics() {
        currentPage = "diagnostics"
        heading("FIELD REPORT", "DEVICE AND GAME DIAGNOSTICS")
        val info = try { JSONObject(actions.deviceInfo()) } catch (_: Exception) { JSONObject() }
        listOf("Модель: " + info.optString("manufacturer") + " " + info.optString("model"),
            "Android: " + info.optString("android"), "Архитектура: " + info.optString("abis"),
            "OpenGL ES: " + info.optString("glesVersion"),
            "RAM: " + (info.optLong("ramAvailableBytes") / 1048576) + " MB",
            "Storage: " + (info.optLong("storageFreeBytes") / 1048576) + " MB"
        ).forEach { body.addView(text(it, 11f, Color.WHITE)) }
        body.addView(button("Скопировать отчёт") { actions.copyError() })
        body.addView(button("Назад") { if (currentPage == "diagnostics") showPauseMenu() })
    }

    fun refreshStatus() {
        if (::gameState.isInitialized) {
            try {
                val g = JSONObject(actions.gameStatus())
                gameState.text = when {
                    g.optBoolean("imported") -> "Игра импортирована и готова"
                    g.optBoolean("exeFound") -> "FarCry2.exe найден — требуется импорт"
                    g.optBoolean("folderSelected") -> "Папка выбрана, EXE не найден"
                    else -> "Папка игры не выбрана"
                }
            } catch (_: Exception) { gameState.text = "Не удалось проверить игровые файлы" }
        }
        if (::runtimeState.isInitialized) {
            runtimeState.text = try {
                val r = JSONObject(actions.runtimeStatus())
                when { r.optBoolean("winlatorBundled") -> "Winlator встроен в APK"
                    r.optBoolean("imported") -> "Дополнительный модуль установлен"
                    else -> "Используются системные компоненты APK" }
            } catch (_: Exception) { "Статус недоступен" }
        }
    }

    fun isPauseMenuVisible(): Boolean = currentPage == "pause"

    fun onNativeMessage(value: String) {
        message(value)
        refreshStatus()
    }

    private class SceneBackdrop(context: Activity) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            p.shader = LinearGradient(0f, 0f, 0f, h,
                intArrayOf(Color.rgb(54, 102, 112), Color.rgb(190, 157, 111),
                    Color.rgb(215, 177, 119), Color.rgb(73, 76, 54)),
                floatArrayOf(0f, .38f, .62f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, p); p.shader = null
            // Hazy African sunset.
            p.color = Color.argb(155, 244, 202, 135)
            canvas.drawCircle(w * .72f, h * .29f, h * .105f, p)
            p.color = Color.argb(30, 255, 230, 185)
            canvas.drawCircle(w * .72f, h * .29f, h * .15f, p)
            val far = Path().apply {
                moveTo(0f,h*.57f); lineTo(w*.12f,h*.43f); lineTo(w*.23f,h*.51f)
                lineTo(w*.39f,h*.37f); lineTo(w*.53f,h*.52f); lineTo(w*.7f,h*.4f)
                lineTo(w*.84f,h*.5f); lineTo(w,h*.39f); lineTo(w,h); lineTo(0f,h); close()
            }
            p.color = Color.rgb(87, 99, 77); canvas.drawPath(far,p)
            val mid = Path().apply {
                moveTo(0f,h*.69f); lineTo(w*.16f,h*.59f); lineTo(w*.31f,h*.66f)
                lineTo(w*.49f,h*.55f); lineTo(w*.67f,h*.68f); lineTo(w*.85f,h*.56f)
                lineTo(w,h*.64f); lineTo(w,h); lineTo(0f,h); close()
            }
            p.color = Color.rgb(83, 77, 52); canvas.drawPath(mid,p)
            p.color = Color.rgb(48, 48, 34)
            canvas.drawRect(0f,h*.83f,w,h,p)
            // Acacia silhouettes and dry grass.
            for (i in 0..5) {
                val x = w * (i / 5f) + (i % 2) * 19f
                val base = h * (.84f + (i % 3) * .035f)
                val trunk = (h * .12f).coerceAtLeast(25f)
                p.strokeWidth = (w * .0025f).coerceAtLeast(2f)
                p.color = Color.rgb(39, 43, 31)
                canvas.drawLine(x,base,x-3f,base-trunk,p)
                p.style = Paint.Style.FILL
                p.color = Color.rgb(43, 48, 34)
                canvas.drawOval(x-w*.045f,base-trunk-h*.035f,x+w*.045f,base-trunk+h*.005f,p)
            }
            p.color = Color.argb(38,255,239,205)
            for (i in 0..130) {
                val x = (i * 67 % width.coerceAtLeast(1)).toFloat()
                val y = (i * 97 % height.coerceAtLeast(1)).toFloat()
                canvas.drawCircle(x,y,if(i%4==0) 1.8f else .8f,p)
            }
            // Darken the right edge for legible title artwork.
            p.shader = LinearGradient(w*.45f,0f,w,h,
                intArrayOf(Color.TRANSPARENT,Color.argb(125,12,19,17)),Shader.TileMode.CLAMP)
            canvas.drawRect(w*.45f,0f,w,h,p); p.shader = null
        }
    }
}
}
