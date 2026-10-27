package com.marhaba.launcher

import android.app.Activity
import android.app.AlertDialog
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
    override fun onUserInteraction() { super.onUserInteraction(); resetControls() }
    private lateinit var weather: WeatherStrip
    private val backdrop = MarhabaBackdrop()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var fetching = false
    private var active = false
    private var lastAttempt = 0L
    private var failedWeather = false
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
    private fun styleDialog(dialog: AlertDialog) {
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
        header.addView(TextClock(this).apply { format12Hour = "h:mm a"; format24Hour = "HH:mm"; textSize = 19f; setTextColor(white) }, FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT or Gravity.CENTER_VERTICAL))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(100)))
        val scroll = ScrollView(this).apply { isFillViewport = false; clipToPadding = false; setPadding(0, dp(12), 0, dp(8)) }
        grid = GridLayout(this).apply { columnCount = 5; clipChildren = false; clipToPadding = false }
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
        action("Settings") { launch(Intent(Settings.ACTION_SETTINGS)) }
        weather = WeatherStrip(this).apply {
            isFocusable = true; isClickable = true
            setOnClickListener { toggleWeather() }
            setOnFocusChangeListener { _, _ -> invalidate() }
        }
        root.addView(footer, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })
        root.addView(weather, LinearLayout.LayoutParams(-1, dp(48)))
        setContentView(root)
    }
    override fun onResume() { super.onResume(); render(); active = true; weather.setActive(true); handler.post(tick); resetControls() }
    override fun onPause() { active = false; handler.removeCallbacks(hideControls); weather.setActive(false); handler.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy() }
    private fun apps(): List<App> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return packageManager.queryIntentActivities(query, 0).filter { it.activityInfo.packageName != packageName }
            .map { App(it.loadLabel(packageManager).toString(), it.activityInfo.packageName,
                Intent(Intent.ACTION_MAIN).setClassName(it.activityInfo.packageName, it.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .distinctBy { it.pkg }.sortedBy { it.name.lowercase() }
    }
    private fun favorites() = prefs.getStringSet("favorites", setOf("com.restaurant.iptv"))!!.toSet()
    private fun render() {
        val focused = grid.findFocus()?.tag
        grid.removeAllViews()
        val selected = apps().filter { it.pkg in favorites() || it.pkg == "com.restaurant.iptv" }
            .sortedBy { if (it.pkg == "com.restaurant.iptv") "" else it.name.lowercase() }
        val tileWidth = ((resources.displayMetrics.widthPixels / resources.displayMetrics.density - 92) / 5).toInt() - 16
        selected.forEach { app ->
            val tile = LinearLayout(this).apply {
                tag = app.pkg; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                isFocusable = true; isClickable = true; elevation = dp(8).toFloat(); setPadding(dp(12), dp(12), dp(12), dp(10))
                background = glass()
            }
            val icon = ImageView(this).apply { setImageDrawable(packageManager.getApplicationIcon(app.pkg)); scaleType = ImageView.ScaleType.FIT_CENTER }
            tile.addView(icon, LinearLayout.LayoutParams(dp(76), dp(76)))
            tile.addView(text(if (app.pkg == "com.restaurant.iptv") "MMG TV" else app.name, 15f).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(10), 0, 0)
            }, LinearLayout.LayoutParams(-1, dp(35)))
            tile.setOnClickListener { launch(app.intent) }
            tile.setOnFocusChangeListener { _, hasFocus ->
                tile.background = glass(hasFocus)
                tile.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(120).start()
            }
            grid.addView(tile, GridLayout.LayoutParams().apply { width = dp(tileWidth); height = dp(136); setMargins(dp(8), dp(8), dp(8), dp(12)) })
        }
        if (focused != null) grid.findViewWithTag<View>(focused)?.requestFocus()
        else if (!window.decorView.hasFocus()) grid.getChildAt(0)?.requestFocus()
    }
    private fun showApps(edit: Boolean) {
        val all = apps()
        if (!edit) {
            styleDialog(AlertDialog.Builder(this).setTitle("All apps").setItems(all.map { it.name }.toTypedArray()) { _, index -> launch(all[index].intent) }.setNegativeButton("Close", null).create())
        } else {
            val chosen = favorites().toMutableSet()
            styleDialog(AlertDialog.Builder(this).setTitle("Favorites")
                .setMultiChoiceItems(all.map { it.name }.toTypedArray(), all.map { it.pkg in chosen }.toBooleanArray()) { _, index, checked ->
                    if (checked) chosen.add(all[index].pkg) else chosen.remove(all[index].pkg)
                }.setPositiveButton("Save") { _, _ ->
                    chosen.add("com.restaurant.iptv"); prefs.edit().putStringSet("favorites", chosen).apply(); render()
                }.setNegativeButton("Cancel", null).create())
        }
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
