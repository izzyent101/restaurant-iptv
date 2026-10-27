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

/** Local-only TV home. No network permission, feeds, analytics or background service. */
class HomeActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }
    private val white = Color.rgb(248, 240, 219)
    private val muted = Color.rgb(186, 184, 155)
    private lateinit var grid: GridLayout
    private data class App(val name: String, val pkg: String, val intent: Intent)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, color: Int = white) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
    }
    private fun rounded(color: Int, stroke: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(20).toFloat(); setStroke(dp(2), stroke)
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(46), dp(30), dp(46), dp(20))
            background = MarhabaBackdrop()
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("MARHABA", 18f).apply { letterSpacing = .2f; typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, dp(36), 1f))
        header.addView(TextClock(this).apply { format12Hour = "h:mm a"; format24Hour = "HH:mm"; textSize = 19f; setTextColor(white) })
        root.addView(header)
        val scroll = ScrollView(this).apply { isFillViewport = false; clipToPadding = false; setPadding(0, dp(45), 0, dp(16)) }
        grid = GridLayout(this).apply { columnCount = 3; clipChildren = false; clipToPadding = false }
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun action(label: String, block: () -> Unit) {
            footer.addView(Button(this).apply {
                text = label; isAllCaps = false; textSize = 13f; setTextColor(white)
                background = rounded(Color.rgb(35, 49, 40), Color.rgb(92, 101, 75))
                setOnFocusChangeListener { _, focused ->
                    background = rounded(if (focused) Color.rgb(79, 88, 59) else Color.rgb(35, 49, 40), if (focused) Color.rgb(224, 190, 119) else Color.rgb(92, 101, 75))
                }
                setOnClickListener { block() }
            }, LinearLayout.LayoutParams(dp(155), dp(44)).apply { marginEnd = dp(12) })
        }
        action("All apps") { showApps(false) }
        action("Edit favorites") { showApps(true) }
        action("TV settings") { launch(Intent(Settings.ACTION_SETTINGS)) }
        root.addView(footer)
        setContentView(root)
    }
    override fun onResume() { super.onResume(); render() }
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
        val tileWidth = ((resources.displayMetrics.widthPixels / resources.displayMetrics.density - 92) / 3).toInt() - 12
        selected.forEach { app ->
            val tile = LinearLayout(this).apply {
                tag = app.pkg; orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                isFocusable = true; isClickable = true; elevation = dp(3).toFloat(); setPadding(dp(10), dp(14), dp(10), dp(12))
                background = rounded(Color.rgb(30, 48, 37))
            }
            val icon = ImageView(this).apply { setImageDrawable(packageManager.getApplicationIcon(app.pkg)); scaleType = ImageView.ScaleType.FIT_CENTER }
            tile.addView(icon, LinearLayout.LayoutParams(dp(100), dp(100)))
            tile.addView(text(if (app.pkg == "com.restaurant.iptv") "MMG TV" else app.name, 15f).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(10), 0, 0)
            }, LinearLayout.LayoutParams(-1, dp(35)))
            tile.setOnClickListener { launch(app.intent) }
            tile.setOnFocusChangeListener { _, hasFocus ->
                tile.background = rounded(if (hasFocus) Color.rgb(56, 72, 48) else Color.rgb(30, 48, 37), if (hasFocus) Color.rgb(230, 195, 135) else Color.TRANSPARENT)
                tile.animate().scaleX(if (hasFocus) 1.04f else 1f).scaleY(if (hasFocus) 1.04f else 1f).setDuration(120).start()
            }
            if (app.pkg == "com.restaurant.iptv") tile.addView(text("LIVE TELEVISION", 10f, muted).apply { gravity = Gravity.CENTER; letterSpacing = .18f; setPadding(0, dp(5), 0, 0) })
            grid.addView(tile, GridLayout.LayoutParams().apply { width = dp(tileWidth); height = dp(200); setMargins(dp(6), dp(6), dp(6), dp(12)) })
        }
        if (focused != null) grid.findViewWithTag<View>(focused)?.requestFocus()
        else if (!window.decorView.hasFocus()) grid.getChildAt(0)?.requestFocus()
    }
    private fun showApps(edit: Boolean) {
        val all = apps()
        if (!edit) {
            AlertDialog.Builder(this).setTitle("All apps").setItems(all.map { it.name }.toTypedArray()) { _, index -> launch(all[index].intent) }.setNegativeButton("Close", null).show()
        } else {
            val chosen = favorites().toMutableSet()
            AlertDialog.Builder(this).setTitle("Choose home screen favorites")
                .setMultiChoiceItems(all.map { it.name }.toTypedArray(), all.map { it.pkg in chosen }.toBooleanArray()) { _, index, checked ->
                    if (checked) chosen.add(all[index].pkg) else chosen.remove(all[index].pkg)
                }.setPositiveButton("Save") { _, _ ->
                    chosen.add("com.restaurant.iptv"); prefs.edit().putStringSet("favorites", chosen).apply(); render()
                }.setNegativeButton("Cancel", null).show()
        }
    }
    private fun launch(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { Toast.makeText(this, "This app is unavailable. Try TV settings.", Toast.LENGTH_LONG).show() }
    }
    @Deprecated("Home stays on screen")
    override fun onBackPressed() { grid.getChildAt(0)?.requestFocus() }
}
