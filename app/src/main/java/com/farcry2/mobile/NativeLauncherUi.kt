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
        fun showMap()
        fun stopGame(): String
        fun deviceInfo(): String
        fun gameStatus(): String
        fun runtimeStatus(): String
        fun saveSettings(json: String)
        fun loadControlLayout(): String
        fun saveControlLayout(json: String)
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
    private lateinit var importGameButton: TextView
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
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
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
        val initialGameState = try { JSONObject(actions.gameStatus()) } catch (_: Exception) { JSONObject() }
        importGameButton = button("Импортировать игру") {
            val state = try { JSONObject(actions.gameStatus()) } catch (_: Exception) { JSONObject() }
            if (state.optBoolean("folderSelected")) actions.importGameFiles() else actions.chooseGameFolder()
        }.apply { visibility = if (initialGameState.optBoolean("imported")) View.GONE else View.VISIBLE }
        body.addView(importGameButton)
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
        body.addView(button("Открыть карту") { actions.showMap() })
        body.addView(button("Загрузить изображение карты") { actions.chooseMap(); message("Выбор изображения открыт") })
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
        val installed = try { JSONObject(actions.gameStatus()).optBoolean("imported") } catch (_: Exception) { false }
        if (!installed) {
            body.addView(button("Выбрать папку игры") { actions.chooseGameFolder() })
            body.addView(button("Импортировать файлы") { actions.importGameFiles() })
        }
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
        val driver = spinner(listOf("Системный", "Turnip", "VirGL"), "Системный")
        body.addView(text("Графический драйвер", 12f, Color.WHITE)); body.addView(driver)
        val quality = spinner(listOf("Низкая", "Средняя", "Высокая"), "Низкая")
        body.addView(text("Профиль производительности", 12f, Color.WHITE)); body.addView(quality)
        val touch = CheckBox(activity).apply {
            text = "Сенсорное управление"; setTextColor(Color.WHITE); isChecked = true
        }
        body.addView(touch)
        body.addView(button("Редактор управления") { showControlEditor() })
        body.addView(button("Сохранить настройки", true) {
            val json = JSONObject().put("resolution", resolution.selectedItem?.toString() ?: "540p")
                .put("graphicsDriver", driver.selectedItem?.toString() ?: "Системный")
                .put("performance", quality.selectedItem?.toString() ?: "Низкая")
                .put("touchControls", touch.isChecked)
            actions.saveSettings(json.toString())
            message("Настройки сохранены")
        })
        body.addView(button("Игровые компоненты") { showRuntime() })
        body.addView(button("Назад") { if (currentPage == "settings") showHome() })
    }

    private fun showControlEditor() {
        currentPage = "control-editor"
        heading("CONTROL LAYOUT", "DRAG TO MOVE  /  DOUBLE TAP TO RESIZE")
        val elements = try { org.json.JSONArray(actions.loadControlLayout()) } catch (_: Exception) { org.json.JSONArray() }
        val preview = ControlEditorCanvas(activity, elements)
        body.addView(text("ПЕРЕТАСКИВАЙ КНОПКИ. ДВОЙНОЕ НАЖАТИЕ — УВЕЛИЧИТЬ.", 10f, cream, true))
        body.addView(preview, LinearLayout.LayoutParams(-1, dp(220)).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        body.addView(button("РАЗВЕРНУТЬ РЕДАКТОР НА ВЕСЬ ЭКРАН", true) {
            showFullscreenControlEditor(elements, preview)
        })
        val addRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val choices = listOf("FIRE", "AIM", "RELOAD", "JUMP", "USE", "CROUCH", "MAP")
        val choice = spinner(choices, "RELOAD")
        addRow.addView(choice, LinearLayout.LayoutParams(0, dp(44), 1f))
        addRow.addView(button("＋ ДОБАВИТЬ") {
            val action = choice.selectedItem?.toString() ?: "RELOAD"
            addControlElement(elements, action)
            preview.invalidate()
            message("$action добавлена")
        }.apply { layoutParams = LinearLayout.LayoutParams(dp(116), dp(44)) })
        body.addView(addRow)
        body.addView(button("УДАЛИТЬ ВЫБРАННУЮ") {
            val selected = preview.selectedIndex
            if (selected >= 0 && selected < elements.length()) {
                elements.remove(selected)
                preview.selectedIndex = -1
                preview.invalidate()
                message("Кнопка удалена")
            } else message("Сначала выбери кнопку на поле")
        })
        body.addView(button("СОХРАНИТЬ РАСКЛАДКУ", true) {
            actions.saveControlLayout(elements.toString())
            message("Раскладка сохранена. Применится при следующем запуске игры.")
        })
        body.addView(button("НАЗАД") { showSettings() })
    }

    private fun addControlElement(elements: org.json.JSONArray, action: String) {
        val spec = when (action) {
            "FIRE" -> arrayOf("MOUSE_LEFT_BUTTON", "NONE", "NONE", "NONE")
            "AIM" -> arrayOf("MOUSE_RIGHT_BUTTON", "NONE", "NONE", "NONE")
            "RELOAD" -> arrayOf("KEY_R", "NONE", "NONE", "NONE")
            "JUMP" -> arrayOf("KEY_SPACE", "NONE", "NONE", "NONE")
            "USE" -> arrayOf("KEY_E", "NONE", "NONE", "NONE")
            "CROUCH" -> arrayOf("KEY_CTRL_L", "NONE", "NONE", "NONE")
            else -> arrayOf("KEY_M", "NONE", "NONE", "NONE")
        }
        elements.put(JSONObject().put("type", "BUTTON").put("shape", "CIRCLE")
            .put("bindings", org.json.JSONArray(spec)).put("scale", 0.9)
            .put("x", 0.5).put("y", 0.5).put("toggleSwitch", false)
            .put("text", action).put("iconId", 0))
    }

    private fun showFullscreenControlEditor(elements: org.json.JSONArray, mainPreview: ControlEditorCanvas) {
        val dialog = android.app.Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(Color.rgb(13, 20, 16))
        }
        val caption = text("ПОЛНЫЙ ЭКРАН — ПЕРЕТАСКИВАЙ КНОПКИ ДЛЯ ТОЧНОЙ НАСТРОЙКИ", 12f, cream, true)
        layout.addView(caption, LinearLayout.LayoutParams(-1, dp(38)))
        val largePreview = ControlEditorCanvas(activity, elements)
        layout.addView(largePreview, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(6); bottomMargin = dp(8)
        })
        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(button("УДАЛИТЬ") {
            val selected = largePreview.selectedIndex
            if (selected in 0 until elements.length()) {
                elements.remove(selected)
                largePreview.selectedIndex = -1
                largePreview.invalidate()
            } else message("Сначала выбери кнопку")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f) })
        controls.addView(button("СОХРАНИТЬ", true) {
            actions.saveControlLayout(elements.toString())
            message("Раскладка сохранена")
        }.apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f) })
        controls.addView(button("ГОТОВО") {
            mainPreview.invalidate()
            dialog.dismiss()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f) })
        layout.addView(controls)
        dialog.setContentView(layout)
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }

    private class ControlEditorCanvas(
        context: Activity,
        private val elements: org.json.JSONArray
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var selectedIndex = -1
        private var dragging = -1
        private var lastTapIndex = -1
        private var lastTapAt = 0L
        private var downX = 0f
        private var downY = 0f
        private val density = context.resources.displayMetrics.density

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            paint.shader = RadialGradient(w/2f,h/2f,h*0.9f,
                intArrayOf(Color.rgb(69,78,52),Color.rgb(25,34,27),Color.rgb(10,16,12)),
                null,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,w,h,paint); paint.shader = null
            paint.color = Color.argb(35,230,211,157)
            for (x in 0..(w/24).toInt()) canvas.drawLine(x*24f,0f,x*24f,h,paint)
            for (y in 0..(h/24).toInt()) canvas.drawLine(0f,y*24f,w,y*24f,paint)
            paint.color = Color.argb(90,226,199,137)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f*density
            canvas.drawRoundRect(5f,5f,w-5f,h-5f,12f*density,12f*density,paint)
            paint.style = Paint.Style.FILL
            for (i in 0 until elements.length()) {
                val item = elements.optJSONObject(i) ?: continue
                val x = (item.optDouble("x",0.5)*w).toFloat()
                val y = (item.optDouble("y",0.5)*h).toFloat()
                val scale = item.optDouble("scale",0.9).toFloat()
                val radius = (if (item.optString("type") == "D_PAD" || item.optString("type") == "TRACKPAD") 35f else 23f) * density * scale
                val label = item.optString("text","BTN")
                val color = when (label.uppercase()) {
                    "FIRE" -> Color.rgb(153,75,48)
                    "AIM" -> Color.rgb(81,101,71)
                    "MOVE" -> Color.rgb(105,111,70)
                    "LOOK" -> Color.rgb(72,91,75)
                    "RELOAD" -> Color.rgb(138,105,58)
                    else -> Color.rgb(91,99,70)
                }
                paint.shader = RadialGradient(x-radius*.35f,y-radius*.4f,radius*1.5f,
                    intArrayOf(Color.rgb(203,179,119),color,Color.rgb(23,31,23)),
                    null,Shader.TileMode.CLAMP)
                canvas.drawCircle(x,y,radius,paint); paint.shader = null
                val save = canvas.save()
                canvas.clipPath(Path().apply { addCircle(x,y,radius-2f,Path.Direction.CW) })
                paint.color = Color.argb(38,245,227,180)
                paint.strokeWidth = 1.4f*density
                for (k in -3..3) canvas.drawLine(x-radius,y+k*8f*density,x+radius,y+(k+5)*8f*density,paint)
                canvas.restoreToCount(save)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = if (i == selectedIndex) 3f*density else 1f*density
                paint.color = if (i == selectedIndex) Color.rgb(255,215,126) else Color.argb(190,213,190,126)
                canvas.drawCircle(x,y,radius,paint); paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.create("sans-serif-condensed",Typeface.BOLD)
                paint.textSize = (if (radius > 30f*density) 10f else 9f)*density
                canvas.drawText(label.take(8),x,y+3.5f*density,paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val w = width.toFloat().coerceAtLeast(1f)
            val h = height.toFloat().coerceAtLeast(1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX=event.x; downY=event.y; dragging=-1
                    var best=Float.MAX_VALUE
                    for (i in 0 until elements.length()) {
                        val item=elements.optJSONObject(i) ?: continue
                        val x=(item.optDouble("x",0.5)*w).toFloat()
                        val y=(item.optDouble("y",0.5)*h).toFloat()
                        val r=(if(item.optString("type")=="D_PAD" || item.optString("type")=="TRACKPAD") 38f else 28f)*density*item.optDouble("scale",0.9).toFloat()
                        val d=kotlin.math.hypot((event.x-x).toDouble(),(event.y-y).toDouble())
                        if(d<=r.toDouble() && d<best.toDouble()){best=d.toFloat();dragging=i}
                    }
                    selectedIndex=dragging
                    if(dragging>=0){
                        val now=event.eventTime
                        if(dragging==lastTapIndex && now-lastTapAt<320L){
                            val item=elements.optJSONObject(dragging)
                            item?.put("scale",(item.optDouble("scale",0.9)+0.1).coerceAtMost(1.6))
                            lastTapIndex=-1
                        } else {lastTapIndex=dragging;lastTapAt=now}
                    }
                    invalidate(); return true
                }
                MotionEvent.ACTION_MOVE -> if(dragging>=0){
                    val item=elements.optJSONObject(dragging) ?: return true
                    val dx=event.x-downX; val dy=event.y-downY
                    val oldX=(item.optDouble("x",0.5)*w).toFloat()
                    val oldY=(item.optDouble("y",0.5)*h).toFloat()
                    item.put("x",((oldX+dx)/w).coerceIn(0.04f,0.96f))
                    item.put("y",((oldY+dy)/h).coerceIn(0.08f,0.92f))
                    downX=event.x; downY=event.y; invalidate(); return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {dragging=-1;return true}
            }
            return true
        }
    }

    private fun spinner(values: List<String>, selected: String): Spinner {
        val s = Spinner(activity)
        s.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, values)
        s.setSelection(values.indexOf(selected).coerceAtLeast(0))
        return s
    }

    private fun showRuntime() {
        currentPage = "runtime"
        heading("SYSTEM", "GAME COMPONENTS")
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
                val installed = g.optBoolean("imported")
                gameState.text = when {
                    installed -> "Игра импортирована и готова"
                    g.optBoolean("exeFound") -> "FarCry2.exe найден — требуется импорт"
                    g.optBoolean("folderSelected") -> "Папка выбрана, EXE не найден"
                    else -> "Папка игры не выбрана"
                }
                if (::importGameButton.isInitialized) {
                    importGameButton.visibility = if (installed) View.GONE else View.VISIBLE
                }
            } catch (_: Exception) { gameState.text = "Не удалось проверить игровые файлы" }
        }
        if (::runtimeState.isInitialized) {
            runtimeState.text = try {
                val r = JSONObject(actions.runtimeStatus())
                when { r.optBoolean("winlatorBundled") -> "Игровые компоненты встроены в приложение"
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
                Color.TRANSPARENT,Color.argb(125,12,19,17),Shader.TileMode.CLAMP)
            canvas.drawRect(w*.45f,0f,w,h,p); p.shader = null
        }
    }
}
