package com.marhaba.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

/** TV home with a cached NWS forecast; no ads, analytics or background service. */
class HomeActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }
    private val white = Color.rgb(245, 246, 244)
    private val muted = Color.rgb(183, 196, 191)
    private lateinit var grid: GridLayout
    private lateinit var controls: LinearLayout
    private var dialogOpen = false
    private var consumeWakeKey = false
    private val hideControls = Runnable {
        if (active && !dialogOpen) {
            if (controls.hasFocus()) grid.getChildAt(0)?.requestFocus()
            controls.visibility = View.INVISIBLE
        }
    }
    private fun resetControls() {
        if (!::controls.isInitialized) return
        controls.visibility = View.VISIBLE
        handler.removeCallbacks(hideControls)
        if (active && !dialogOpen) handler.postDelayed(hideControls, 20000)
    }
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val navigation = event.keyCode in listOf(19,20,21,22,23,66,4)
        if (navigation && event.action == android.view.KeyEvent.ACTION_DOWN) {
            consumeWakeKey = controls.visibility != View.VISIBLE
            resetControls()
            if (consumeWakeKey) return true
        }
        if (navigation && event.action == android.view.KeyEvent.ACTION_UP && consumeWakeKey) {
            consumeWakeKey = false; return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onUserInteraction() { super.onUserInteraction(); cancelBootLaunch(); resetControls() }
    private lateinit var weather: WeatherStrip
    private lateinit var clock: DigitalClock
    private val backdrop = MarhabaBackdrop()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val bootLaunch = Runnable {
        if (active && !dialogOpen) {
            val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
            // Persist before starting: returning Home must never reopen playback.
            if (boot >= 0 && prefs.edit().putInt("handledBoot", boot).commit()) {
                packageManager.getLeanbackLaunchIntentForPackage("com.restaurant.iptv")?.let { launch(it) }
            }
        }
    }
    private fun scheduleBootLaunch() {
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val previous = if (prefs.contains("handledBoot")) prefs.getInt("handledBoot", -1) else null
        if (previous == null && boot >= 0) prefs.edit().putInt("handledBoot", boot).commit()
        if (BootLaunchPolicy.shouldLaunch(previous, boot)) {
            handler.removeCallbacks(bootLaunch)
            handler.postDelayed(bootLaunch, 8000)
        }
    }
    private fun cancelBootLaunch() {
        handler.removeCallbacks(bootLaunch)
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (boot >= 0) prefs.edit().putInt("handledBoot", boot).commit()
    }
    private var fetching = false
    private var active = false
    private var lastAttempt = 0L
    private var failedWeather = false
    private val sceneryFrame = object : Runnable {
        override fun run() {
            if (!active) return
            backdrop.invalidateSelf()
            handler.postDelayed(this, 125)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            backdrop.invalidateSelf()
            refreshWeather()
            handler.postDelayed(this, 60000)
        }
    }
    private fun refreshWeather() {
        val now = System.currentTimeMillis()
        val fetched = prefs.getLong("weatherTime", 0)
        weather.update(prefs.getString("weather", null), fetched, failedWeather)
        if (fetching || now - fetched < 1800000 || now - lastAttempt < 300000) return
        fetching = true; lastAttempt = now
        worker.execute {
            val result = runCatching { Forecast.fetch() }
            handler.post {
                fetching = false
                if (isDestroyed) return@post
                result.onSuccess { raw ->
                    prefs.edit().putString("weather", raw).putLong("weatherTime", System.currentTimeMillis()).apply()
                }
                failedWeather = result.isFailure
                if (active) weather.update(prefs.getString("weather", null), prefs.getLong("weatherTime", 0), failedWeather)
            }
        }
    }
    private data class App(val name: String, val pkg: String, val intent: Intent)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, color: Int = white) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
    }
    private fun glass(focused: Boolean = false) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        if (focused) intArrayOf(Color.argb(110, 175, 193, 181), Color.argb(170, 38, 55, 49))
        else intArrayOf(Color.argb(85, 96, 119, 108), Color.argb(175, 22, 36, 31))
    ).apply {
        cornerRadius = dp(22).toFloat()
        setStroke(dp(1), if (focused) Color.argb(220, 239, 240, 222) else Color.argb(60, 221, 234, 222))
    }
    private fun styleDialog(dialog: android.app.Dialog) {
        dialogOpen = true
        handler.removeCallbacks(hideControls)
        dialog.setOnDismissListener { dialogOpen = false; resetControls() }
        dialog.show()
        dialog.window?.setBackgroundDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(45, 61, 53), Color.rgb(22, 35, 30))).apply {
            cornerRadius = dp(24).toFloat(); setStroke(dp(1), Color.argb(75, 221, 234, 222))
        })
        dialog.window?.setLayout(dp(480), dp(410))
        dialog.window?.setDimAmount(.45f)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            dialog.window?.attributes = dialog.window?.attributes?.apply { blurBehindRadius = dp(24) }
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(46), dp(22), dp(46), dp(18))
            background = backdrop
        }
        val header = FrameLayout(this)
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.marhaba_logo); scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Marhaba Grill"
        }, FrameLayout.LayoutParams(dp(145), dp(90), Gravity.CENTER))
        header.addView(TextClock(this).apply {
            format12Hour = "EEEE\nMMM d, yyyy"; format24Hour = "EEEE\nMMM d, yyyy"
            textSize = 18f; setTextColor(white)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setLineSpacing(dp(5).toFloat(), 1f)
            setShadowLayer(dp(3).toFloat(), 0f, 1f, Color.BLACK)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.LEFT or Gravity.CENTER_VERTICAL))
        clock = DigitalClock(this)
        header.addView(clock, FrameLayout.LayoutParams(dp(190), dp(64), Gravity.RIGHT or Gravity.CENTER_VERTICAL))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(100)))
        val scroll = ScrollView(this).apply { isFillViewport = false; clipToPadding = false; setPadding(0, dp(12), 0, dp(8)) }
        grid = GridLayout(this).apply { columnCount = 6; clipChildren = false; clipToPadding = false }
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply {
            gravity = Gravity.CENTER; background = glass()
            setPadding(dp(5), dp(4), dp(5), dp(4)); elevation = dp(6).toFloat()
        }
        controls = footer
        fun action(label: String, block: () -> Unit): Button {
            val button = Button(this).apply {
                text = label; isAllCaps = false; textSize = 13f; setTextColor(white)
                background = glass()
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setOnFocusChangeListener { _, focused ->
                    background = glass(focused)
                    animate().scaleX(if (focused) 1.035f else 1f).scaleY(if (focused) 1.035f else 1f).setDuration(140).start()
                }
                setOnClickListener { block() }
            }
            footer.addView(button, LinearLayout.LayoutParams(dp(120), dp(36)).apply { marginStart = dp(3); marginEnd = dp(3) })
            return button
        }
        action("Apps") { showApps(false) }
        action("Favorites") { showApps(true) }
        action("Settings") { showSettings() }
        weather = WeatherStrip(this).apply {
            isFocusable = true; isClickable = true
            setOnClickListener { toggleWeather() }
            setOnFocusChangeListener { _, _ -> invalidate() }
        }
        root.addView(footer, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.LEFT; bottomMargin = dp(10) })
        root.addView(weather, LinearLayout.LayoutParams(-1, dp(48)))
        setContentView(root)
    }
    override fun onResume() { super.onResume(); render(); active = true; clock.setActive(true); handler.post(sceneryFrame); weather.setActive(true); handler.post(tick); resetControls(); scheduleBootLaunch() }
    override fun onPause() { handler.removeCallbacks(bootLaunch); active = false; clock.setActive(false); handler.removeCallbacks(sceneryFrame); handler.removeCallbacks(hideControls); weather.setActive(false); handler.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy() }
    private fun showSettings() {
        if (!android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) {
            launch(Intent(Settings.ACTION_SETTINGS)); return
        }
        // Fire's general settings entry belongs to Amazon Home, which may be disabled.
        val destinations = listOf(
            "Network" to "network.NetworkActivity",
            "Display and sound" to "display_sounds.DisplayAndSoundsActivity",
            "Remotes and Bluetooth" to "controllers_bluetooth_devices.ControllersAndBluetoothActivity",
            "Applications" to "applications.ApplicationsActivity",
            "Preferences" to "preferences.PreferencesActivity",
            "My Fire TV" to "device.DeviceActivity"
        )
        showPicker("Settings", destinations.map { it.first }) { index ->
            launch(Intent().setClassName("com.amazon.tv.settings.v2",
                "com.amazon.tv.settings.v2.tv.${destinations[index].second}"))
        }
    }

    private fun apps(): List<App> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return packageManager.queryIntentActivities(query, 0).filter { it.activityInfo.packageName != packageName }
            .map { App(it.loadLabel(packageManager).toString(), it.activityInfo.packageName,
                Intent(Intent.ACTION_MAIN).setClassName(it.activityInfo.packageName, it.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .distinctBy { it.pkg }.sortedBy { it.name.lowercase() }
    }
    private fun homeOrder(): List<String> {
        val saved = prefs.getString("homeOrder", null)
        if (saved != null) return runCatching {
            val array = org.json.JSONArray(saved)
            (0 until array.length()).map { array.getString(it) }.distinct()
        }.getOrDefault(emptyList())
        return prefs.getStringSet("favorites", setOf("com.restaurant.iptv"))!!.sortedBy {
            if (it == "com.restaurant.iptv") "" else it
        }
    }
    private fun saveHomeOrder(order: List<String>) {
        prefs.edit().putString("homeOrder", org.json.JSONArray(order.distinct()).toString()).apply()
    }
    private fun favorites() = homeOrder().toSet()
    private fun render() {
        val focused = grid.findFocus()?.tag
        grid.removeAllViews()
        val installed = apps().associateBy { it.pkg }
        val selected = homeOrder().mapNotNull { installed[it] }
        if (selected.isEmpty()) {
            val add = Button(this).apply {
                text = "Add apps to Home"; isAllCaps = false; setTextColor(white)
                background = glass(); setOnClickListener { showApps(true) }
            }
            grid.addView(add, GridLayout.LayoutParams().apply { width = dp(240); height = dp(64) })
        }
        val tileWidth = 112
        selected.forEach { app ->
            val tile = LinearLayout(this).apply {
                tag = app.pkg; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                isFocusable = true; isClickable = true; elevation = dp(8).toFloat(); setPadding(dp(10), dp(10), dp(10), dp(8))
                background = glass()
            }
            val icon = ImageView(this).apply { setImageDrawable(packageManager.getApplicationIcon(app.pkg)); scaleType = ImageView.ScaleType.FIT_CENTER }
            tile.addView(icon, LinearLayout.LayoutParams(dp(60), dp(60)))
            tile.addView(text(if (app.pkg == "com.restaurant.iptv") "MMG TV" else app.name, 13f).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0)
            }, LinearLayout.LayoutParams(-1, dp(28)))
            tile.contentDescription = "${app.name}. Hold OK or press Menu for options."
            tile.setOnClickListener { launch(app.intent) }
            tile.setOnLongClickListener { showAppMenu(app); true }
            tile.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
                    if (event.action == android.view.KeyEvent.ACTION_UP) showAppMenu(app)
                    true
                } else false
            }
            tile.setOnFocusChangeListener { _, hasFocus ->
                tile.background = glass(hasFocus)
                tile.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(120).start()
            }
            grid.addView(tile, GridLayout.LayoutParams().apply { width = dp(tileWidth); height = dp(112); setMargins(dp(8), dp(8), dp(8), dp(12)) })
        }
        if (focused != null) grid.findViewWithTag<View>(focused)?.requestFocus()
        else if (!window.decorView.hasFocus()) grid.getChildAt(0)?.requestFocus()
    }
    private fun showPicker(title: String, labels: List<String>, selected: Set<Int>? = null,
                           save: ((Set<Int>) -> Unit)? = null, choose: (Int) -> Unit = {}) {
        cancelBootLaunch()
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        panel.addView(text(title, 24f).apply { setPadding(0, 0, 0, dp(14)) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val checked = selected?.toMutableSet() ?: mutableSetOf()
        labels.forEachIndexed { index, label ->
            val row: TextView = if (selected != null) CheckBox(this).apply {
                text = label; isChecked = index in checked
                setOnCheckedChangeListener { _, value -> if (value) checked.add(index) else checked.remove(index) }
            } else Button(this).apply {
                text = label; isAllCaps = false
                setOnClickListener { dialog.dismiss(); choose(index) }
            }
            row.textSize = 16f; row.setTextColor(white); row.background = glass()
            row.setPadding(dp(12), 0, dp(12), 0)
            row.setOnFocusChangeListener { _, focused -> row.background = glass(focused) }
            list.addView(row, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(6) })
        }
        panel.addView(ScrollView(this).apply { addView(list); isFillViewport = false },
            LinearLayout.LayoutParams(-1, dp(minOf(250, labels.size * 54))))
        val actions = LinearLayout(this).apply { gravity = Gravity.RIGHT }
        fun button(label: String, action: () -> Unit) {
            actions.addView(Button(this).apply {
                text = label; isAllCaps = false; setTextColor(white); background = glass()
                setOnFocusChangeListener { _, focused -> background = glass(focused) }
                setOnClickListener { dialog.dismiss(); action() }
            }, LinearLayout.LayoutParams(dp(120), dp(46)).apply { marginStart = dp(8) })
        }
        button(if (selected == null) "Close" else "Cancel") {}
        if (selected != null) button("Save") { save?.invoke(checked) }
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        dialog.setContentView(panel); styleDialog(dialog)
        dialog.window?.setLayout(dp(480), -2)
        list.getChildAt(0)?.requestFocus()
    }
    private fun showApps(edit: Boolean) {
        val all = apps()
        if (edit) {
            showPicker("Favorites", all.map { it.name },
                all.indices.filter { all[it].pkg in favorites() }.toSet(), save = { selected ->
                    saveHomeOrder(HomeOrder.reconcile(homeOrder(), selected.map { all[it].pkg }.toSet()))
                    render(); grid.getChildAt(0)?.requestFocus()
                })
        } else showPicker("All apps", all.map { it.name }) { launch(all[it].intent) }
    }
    private fun showAppMenu(app: App, reorder: Boolean = false) {
        cancelBootLaunch()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(22), dp(24), dp(20))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply {
            setImageDrawable(packageManager.getApplicationIcon(app.pkg))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(18) })
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(text(if (app.pkg == "com.restaurant.iptv") "MMG TV" else app.name, 24f).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
        })
        title.addView(text(if (reorder) "Reorder on Home" else "App options", 13f, muted).apply { setPadding(0, dp(5), 0, 0) })
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })
        val dialog = android.app.Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(panel)
        }
        var firstAction: View? = null
        fun row(label: String, enabled: Boolean = true, action: () -> Unit) {
            val button = Button(this).apply {
                text = label; textSize = 16f; isAllCaps = false; gravity = Gravity.CENTER_VERTICAL or Gravity.LEFT
                setPadding(dp(18), 0, dp(18), 0); setTextColor(if (enabled) white else muted)
                isEnabled = enabled; alpha = if (enabled) 1f else .4f
                background = glass()
                setOnFocusChangeListener { _, focused -> background = glass(focused) }
                setOnClickListener { dialog.dismiss(); action() }
            }
            panel.addView(button, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) })
            if (enabled && firstAction == null) firstAction = button
        }
        if (reorder) {
            val installed = apps().map { it.pkg }.toSet()
            val visible = homeOrder().filter { it in installed }
            val index = visible.indexOf(app.pkg)
            fun move(delta: Int) {
                saveHomeOrder(HomeOrder.move(homeOrder(), app.pkg, delta, installed)); render()
                grid.findViewWithTag<View>(app.pkg)?.requestFocus()
                showAppMenu(app, true)
            }
            row("Move left", index > 0) { move(-1) }
            row("Move right", index >= 0 && index < visible.lastIndex) { move(1) }
            row("Done") { grid.findViewWithTag<View>(app.pkg)?.requestFocus() }
            panel.addView(text("Position ${index + 1} of ${visible.size}", 13f, muted))
        } else {
            row("Open") { launch(app.intent) }
            row("Reorder", homeOrder().count { key -> apps().any { it.pkg == key } } > 1) { showAppMenu(app, true) }
            row("Remove from Home") {
                saveHomeOrder(homeOrder().filterNot { it == app.pkg }); render()
                grid.getChildAt(0)?.requestFocus()
                Toast.makeText(this, "Removed from Home. Still available in Apps and Favorites.", Toast.LENGTH_LONG).show()
            }
            row("App info") { launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${app.pkg}"))) }
            panel.addView(text("Removing from Home keeps the app installed.", 12f, muted))
        }
        styleDialog(dialog)
        dialog.window?.setLayout(dp(420), -2)
        firstAction?.requestFocus()
    }
    private fun launch(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { Toast.makeText(this, "This app is unavailable. Try TV settings.", Toast.LENGTH_LONG).show() }
    }
    @Deprecated("Home stays on screen")
    override fun onBackPressed() {
        if (weather.expanded) toggleWeather() else grid.getChildAt(0)?.requestFocus()
    }
    private fun toggleWeather() {
        weather.expanded = !weather.expanded
        weather.layoutParams = (weather.layoutParams as LinearLayout.LayoutParams).apply {
            height = dp(if (weather.expanded) 158 else 48)
            width = -1
            marginStart = 0
        }
        weather.invalidate()
    }
}
