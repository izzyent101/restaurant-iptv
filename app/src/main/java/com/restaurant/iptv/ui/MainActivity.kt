package com.restaurant.iptv.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.restaurant.iptv.data.Prefs
import com.restaurant.iptv.data.Repository
import com.restaurant.iptv.data.entity.ChannelEntity
import com.restaurant.iptv.databinding.ActivityMainBinding
import com.restaurant.iptv.epg.EpgStore
import com.restaurant.iptv.epg.Programme
import com.restaurant.iptv.player.PlayState
import com.restaurant.iptv.player.PlaybackService
import com.restaurant.iptv.player.PlaybackState
import com.restaurant.iptv.update.ReadyUpdate
import com.restaurant.iptv.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: Repository
    private lateinit var prefs: Prefs

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: RichChannelAdapter
    private lateinit var historyAdapter: HistoryTileAdapter

    private var providerId: Long? = null
    private var allVisible: List<ChannelEntity> = emptyList()
    private var favorites: Set<String> = emptySet()
    private var categories: List<String> = emptyList()
    private var focusedChannel: ChannelUi? = null
    private var recent: MutableList<String> = mutableListOf()

    private val ui = Handler(Looper.getMainLooper())
    private var numberBuffer = ""
    private var pendingCategory: String? = null
    private val categorySwitch = Runnable { pendingCategory?.let { showCategory(it) } }

    // --- Cold-start splash state ---
    private var splashDone = false
    private var splashShownAt = 0L
    private val splashTimeout = Runnable { dismissSplash() }

    /** Fade the splash out, honoring a minimum on-screen hold so it never blinks. */
    private fun dismissSplash() {
        if (splashDone) return
        val elapsed = android.os.SystemClock.uptimeMillis() - splashShownAt
        if (elapsed < SPLASH_MIN_MS) {
            ui.postDelayed({ dismissSplash() }, SPLASH_MIN_MS - elapsed)
            return
        }
        splashDone = true
        ui.removeCallbacks(splashTimeout)
        binding.splashOverlay.animate().alpha(0f).setDuration(500)
            .withEndAction { binding.splashOverlay.visibility = View.GONE }
            .start()
    }

    private var service: PlaybackService? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? PlaybackService.LocalBinder)?.service ?: return
            service = svc
            binding.playerView.player = svc.activePlayer
            svc.onPlayerReady = { p -> runOnUiThread { binding.playerView.player = p } }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Cold-start splash (backlit logo) is the window background until the UI
        // is ready; swap to the plain theme so it doesn't bleed through playback.
        setTheme(com.restaurant.iptv.R.style.Theme_RestaurantIptv)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Branded startup: the backlit emblem holds until the stream is actually
        // PLAYING (so the fade reveals live video, never a black screen), with a
        // hard cap of 6s and a minimum hold of 1s so it never just blinks.
        if (savedInstanceState == null) {
            splashShownAt = android.os.SystemClock.uptimeMillis()
            ui.postDelayed(splashTimeout, SPLASH_MAX_MS)
        } else {
            splashDone = true
            binding.splashOverlay.visibility = View.GONE
        }
        repo = Repository(applicationContext)
        prefs = Prefs(applicationContext)

        categoryAdapter = CategoryAdapter { name ->
            // Debounce: as the D-pad flies through categories we only rebuild the
            // (heavy) channel list once focus settles for ~180ms. Keeps scroll smooth.
            pendingCategory = name
            binding.channelHeader.text = name
            ui.removeCallbacks(categorySwitch)
            ui.postDelayed(categorySwitch, 180)
        }
        channelAdapter = RichChannelAdapter(
            onPlay = { ch -> playChannel(ch) },
            onFocusCh = { uiItem -> onChannelFocused(uiItem) },
            onToggleFav = { ch -> toggleFavorite(ch) }
        )

        binding.categoryList.layoutManager = LinearLayoutManager(this)
        binding.categoryList.adapter = categoryAdapter
        binding.categoryList.setHasFixedSize(true)
        binding.categoryList.itemAnimator = null
        binding.categoryList.setItemViewCacheSize(24)
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = channelAdapter
        binding.channelList.setHasFixedSize(true)
        binding.channelList.itemAnimator = null
        binding.channelList.setItemViewCacheSize(24)

        historyAdapter = HistoryTileAdapter(onPlay = { ch -> playChannel(ch) })
        binding.historyList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.historyList.adapter = historyAdapter
        binding.historyList.itemAnimator = null
        binding.historyClear.setOnClickListener { clearHistory() }

        requestNotifPermissionIfNeeded()
        promptAutostartPermissionIfNeeded()
        PlaybackService.start(this)
        bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)

        observeStatus()
        observeUpdates()
        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    // ---------------- Data ----------------

    private fun loadData() {
        lifecycleScope.launch {
            val prov = repo.getActiveProvider()
            if (prov == null) {
                providerId = null
                showNoProvider()
                return@launch
            }
            providerId = prov.id
            binding.statusText.visibility = View.GONE
            favorites = repo.getFavorites(prov.id)
            recent = prefs.recentKeys().toMutableList()
            allVisible = repo.getVisibleChannels(prov.id)
            val groups = repo.orderGroups(
                prov.id,
                allVisible.map { it.groupTitle }.filter { it.isNotBlank() }.distinct().sorted()
            )
            categories = listOf(CAT_ALL, CAT_FAV) + groups
            categoryAdapter.submit(categories)
            showCategory(categoryAdapter.current() ?: CAT_ALL)
            ensureEpg(prov.id)
        }
    }

    /** Load the XMLTV guide (auto-derived from the Xtream login) once per session,
     *  then refresh the visible rows so the now-playing subtitles appear. */
    private fun ensureEpg(providerId: Long) {
        if (EpgStore.channelsWithData(providerId) > 0) return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { repo.refreshEpg(providerId) }.getOrDefault(false) }
            if (ok && this@MainActivity.providerId == providerId) {
                if (browserOpen()) {
                    // Refresh rows without stealing the user's place mid-browse.
                    val prev = focusedRowPos(binding.channelList)
                    val inChannels = prev >= 0
                    showCategory(categoryAdapter.current() ?: CAT_ALL)
                    if (inChannels) binding.channelList.post { focusRow(binding.channelList, prev) }
                } else {
                    showCategory(categoryAdapter.current() ?: CAT_ALL)
                }
            }
        }
    }

    private fun showCategory(name: String) {
        providerId ?: return
        val chans = when (name) {
            CAT_ALL -> allVisible
            CAT_FAV -> allVisible.filter { favorites.contains(it.streamKey) }
            else -> allVisible.filter { it.groupTitle == name }
        }
        val pid = providerId ?: -1L
        val list = chans.map { ch ->
            val (now, next) = EpgStore.nowNext(pid, ch.epgChannelId)
            ChannelUi(ch, now, next, favorites.contains(ch.streamKey))
        }
        channelAdapter.submit(list)
        binding.channelHeader.text = name
        binding.channelCount.text = if (chans.isEmpty()) "" else "${chans.size} channels"
    }

    /** Track which channel is highlighted (used when opening categories). */
    private fun onChannelFocused(item: ChannelUi) {
        focusedChannel = item
    }

    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    private fun fmtTime(ms: Long): String = timeFmt.format(Date(ms))

    // ---------------- Focus-safe helpers ----------------

    /** Focus a specific row, retrying across layout passes; never leaves focus dead. */
    private fun focusRow(rv: RecyclerView, pos: Int, attempt: Int = 0) {
        if (pos < 0 || rv.adapter?.itemCount == 0) { rv.requestFocus(); return }
        val p = pos.coerceAtMost((rv.adapter?.itemCount ?: 1) - 1)
        val vh = rv.findViewHolderForAdapterPosition(p)
        if (vh != null) {
            vh.itemView.requestFocus()
            ensureFullyVisible(rv, vh.itemView)
        } else if (attempt < 4) {
            (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(p, dp(160))
            rv.post { focusRow(rv, p, attempt + 1) }
        } else {
            rv.requestFocus()
        }
    }

    /** Nudge the list so the focused row is never clipped at an edge. */
    private fun ensureFullyVisible(rv: RecyclerView, v: View) {
        rv.post {
            val pad = dp(10)
            when {
                v.bottom > rv.height - pad -> rv.scrollBy(0, v.bottom - (rv.height - pad))
                v.top < pad -> rv.scrollBy(0, v.top - pad)
            }
        }
    }

    private fun focusedRowPos(rv: RecyclerView): Int {
        val child = rv.focusedChild ?: return -1
        return rv.getChildAdapterPosition(child)
    }

    private fun toggleFavorite(ch: ChannelEntity) {
        val pid = providerId ?: return
        lifecycleScope.launch {
            val nowFav = repo.toggleFavorite(pid, ch.streamKey)
            favorites = repo.getFavorites(pid)
            if (categoryAdapter.current() == CAT_FAV) {
                // The row leaves/enters the Favorites list: rebuild, but put focus
                // back on the same spot instead of the top.
                val prev = focusedRowPos(binding.channelList)
                showCategory(CAT_FAV)
                if (channelAdapter.size() == 0) openCategories()
                else binding.channelList.post { focusRow(binding.channelList, prev) }
            } else {
                // Everywhere else: flip the star in place. Focus never moves.
                channelAdapter.setFavorite(ch.streamKey, nowFav)
            }
        }
    }

    private fun playChannel(ch: ChannelEntity) {
        ui.removeCallbacks(categorySwitch)
        recordHistory(ch)
        service?.cmdPlayChannel(ch.id)
        hideBrowser()
        hideHistory()
        showInfoBar(ch)
    }

    /** Push a channel to the front of the recently-watched history (persisted). */
    private fun recordHistory(ch: ChannelEntity) {
        val key = ch.streamKey
        recent.remove(key)
        recent.add(0, key)
        while (recent.size > 40) recent.removeAt(recent.size - 1)
        lifecycleScope.launch { prefs.setRecentKeys(recent) }
    }

    private fun clearHistory() {
        recent.clear()
        lifecycleScope.launch { prefs.setRecentKeys(emptyList()) }
        historyAdapter.submit(emptyList())
        binding.historyEmpty.visibility = View.VISIBLE
        binding.historyClear.visibility = View.GONE
    }

    // ---------------- Browse overlay (video stays full-screen) ----------------

    private fun historyOpen() = binding.historyBar.visibility == View.VISIBLE
    private fun browserOpen() = binding.browserPanel.visibility == View.VISIBLE
    private fun categoriesOpen() = binding.categoryColumn.visibility == View.VISIBLE

    // LEFT from full-screen: the channel list, landed ON the channel that's
    // playing (its category selected, list scrolled + focused there) — TiviMate-style.
    private fun showBrowser() {
        if (providerId == null) { showNoProvider(); return }
        hideHistory()
        binding.browserHint.text = HINT_BROWSER

        val cur = currentPlayingChannel()
        if (cur != null) {
            val idx = categories.indexOf(cur.groupTitle)
            if (idx >= 0) categoryAdapter.setSelected(idx)
        }
        showCategory(categoryAdapter.current() ?: CAT_ALL)

        binding.categoryColumn.visibility = View.GONE
        binding.browserPanel.visibility = View.VISIBLE

        // Scroll to + focus the playing channel inside the list.
        val pos = cur?.let { channelAdapter.positionOf(it.id) } ?: -1
        if (pos >= 0) {
            (binding.channelList.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(pos, dp(180))
        }
        binding.channelList.post { focusRow(binding.channelList, if (pos >= 0) pos else 0) }
    }

    private fun hideBrowser() {
        ui.removeCallbacks(categorySwitch)
        pendingCategory = null
        binding.browserPanel.visibility = View.GONE
        binding.categoryColumn.visibility = View.GONE
    }

    /** Reveal the categories column beside the channel list (no overlap),
     *  landing on the current channel's group. */
    private fun openCategories() {
        ui.removeCallbacks(categorySwitch)
        val group = focusedChannel?.channel?.groupTitle
        val idx = group?.let { categories.indexOf(it) } ?: -1
        binding.categoryColumn.visibility = View.VISIBLE
        if (idx >= 0) {
            categoryAdapter.setSelected(idx)
            showCategory(categories[idx])
        }
        val pos = if (idx >= 0) idx else categoryAdapter.selectedIndex
        // Scroll only AFTER the column has laid out (it was just made visible) —
        // scrolling before layout is what clipped the last category off-screen.
        binding.categoryList.post {
            (binding.categoryList.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(pos, dp(140))
            binding.categoryList.post { focusRow(binding.categoryList, pos) }
        }
    }

    private fun closeCategories() {
        binding.categoryColumn.visibility = View.GONE
        val pos = focusedChannel?.let { channelAdapter.positionOf(it.channel.id) } ?: 0
        binding.channelList.post { focusRow(binding.channelList, pos) }
    }

    // OK from full-screen: the recently-watched strip (bottom), TiviMate-style.
    private fun showHistory() {
        if (providerId == null) { showNoProvider(); return }
        hideBrowser()
        val recentChans = recent.mapNotNull { key -> allVisible.firstOrNull { it.streamKey == key } }
        historyAdapter.submit(recentChans)
        binding.historyEmpty.visibility = if (recentChans.isEmpty()) View.VISIBLE else View.GONE
        binding.historyClear.visibility = if (recentChans.isEmpty()) View.GONE else View.VISIBLE
        binding.historyBar.visibility = View.VISIBLE
        binding.historyList.post {
            if (recentChans.isNotEmpty()) focusRow(binding.historyList, 0)
            else binding.historyBar.requestFocus()
        }
    }

    private fun hideHistory() {
        binding.historyBar.visibility = View.GONE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val hideInfo = Runnable { binding.infoBar.visibility = View.GONE }

    private fun showInfoBar(ch: ChannelEntity) {
        val pid = providerId ?: -1L
        binding.infoTitle.text = ch.name
        if (!ch.logoUrl.isNullOrBlank()) binding.infoLogo.load(ch.logoUrl) else binding.infoLogo.setImageDrawable(null)
        val (now, _) = EpgStore.nowNext(pid, ch.epgChannelId)
        if (now != null) {
            binding.infoNow.text = "${now.title}   ${fmtTime(now.startMs)} – ${fmtTime(now.endMs)}"
            binding.infoNow.visibility = View.VISIBLE
        } else binding.infoNow.visibility = View.GONE
        binding.infoBar.visibility = View.VISIBLE
        ui.removeCallbacks(hideInfo)
        ui.postDelayed(hideInfo, 6000)
    }

    private fun toggleInfoBar() {
        if (binding.infoBar.visibility == View.VISIBLE) {
            binding.infoBar.visibility = View.GONE
        } else {
            currentPlayingChannel()?.let { showInfoBar(it) }
        }
    }

    private fun currentPlayingChannel(): ChannelEntity? {
        val id = PlaybackState.status.value.channelId ?: return null
        return allVisible.firstOrNull { it.id == id }
    }

    // ---------------- Zapping ----------------

    private fun surf(delta: Int) {
        if (allVisible.isEmpty()) return
        val curId = PlaybackState.status.value.channelId
        val idx = allVisible.indexOfFirst { it.id == curId }
        val nextIdx = if (idx < 0) 0 else ((idx + delta) % allVisible.size + allVisible.size) % allVisible.size
        val ch = allVisible.getOrNull(nextIdx) ?: return
        recordHistory(ch)
        service?.cmdPlayChannel(ch.id)
        showInfoBar(ch)
    }

    private val tuneRunnable = Runnable {
        val n = numberBuffer.toIntOrNull()
        numberBuffer = ""
        if (n != null) {
            val ch = allVisible.firstOrNull { it.number == n }
            if (ch != null) { recordHistory(ch); service?.cmdPlayChannel(ch.id); showInfoBar(ch) }
            else { binding.infoTitle.text = "No channel $n"; ui.postDelayed(hideInfo, 2000) }
        }
    }

    private fun onDigit(d: Int) {
        numberBuffer += d.toString()
        binding.infoTitle.text = "Channel: $numberBuffer"
        binding.infoLogo.setImageDrawable(null)
        binding.infoNow.visibility = View.GONE
        binding.infoBar.visibility = View.VISIBLE
        ui.removeCallbacks(tuneRunnable)
        ui.removeCallbacks(hideInfo)
        ui.postDelayed(tuneRunnable, 1600)
    }

    // ---------------- Remote control ----------------

    private val navKeys = setOf(
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER
    )

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val open = browserOpen()
        val hist = historyOpen()
        val cats = categoriesOpen()

        // Dead-focus rescue: if an overlay is up but nothing is focused (empty
        // list, view recycled, etc.), the remote must never go unresponsive.
        if (keyCode in navKeys && currentFocus == null) {
            if (hist) {
                if (historyAdapter.itemCount > 0) focusRow(binding.historyList, 0) else hideHistory()
                return true
            }
            if (open) {
                if (channelAdapter.size() > 0) focusRow(binding.channelList, 0)
                else if (cats) focusRow(binding.categoryList, categoryAdapter.selectedIndex)
                else openCategories()
                return true
            }
        }

        // History strip: keep vertical presses inside it (◄ ► scroll the tiles).
        if (hist) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (binding.historyClear.visibility == View.VISIBLE) binding.historyClear.requestFocus()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (binding.historyClear.hasFocus()) focusRow(binding.historyList, 0)
                    return true
                }
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> when {
                hist -> { hideHistory(); return true }
                cats -> { closeCategories(); return true }
                open -> { hideBrowser(); return true }
            }
            KeyEvent.KEYCODE_MENU -> {
                when { hist -> hideHistory(); open -> hideBrowser(); else -> showBrowser() }; return true
            }
            // OK from full-screen opens the recently-watched strip (TiviMate-style).
            // When a list is open, OK is consumed by the focused row/tile (plays it).
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when {
                !open && !hist -> { showHistory(); return true }
            }
            KeyEvent.KEYCODE_INFO -> { toggleInfoBar(); return true }
            // TiviMate: LEFT from full-screen opens the channel list; a second LEFT
            // reveals the categories column beside it; LEFT again moves onto it.
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                hist -> {
                    // Hard stop at the first tile — no wrapping/jumping.
                    if (focusedRowPos(binding.historyList) == 0) return true
                    return super.onKeyDown(keyCode, event)
                }
                !open -> { showBrowser(); return true }
                binding.categoryList.hasFocus() -> return true    // already leftmost
                binding.channelList.hasFocus() -> {
                    // Coming back from channels lands on the CURRENT category,
                    // never the top of the list.
                    if (cats) focusRow(binding.categoryList, categoryAdapter.selectedIndex)
                    else openCategories()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                hist -> {
                    val n = historyAdapter.itemCount
                    if (n > 0 && focusedRowPos(binding.historyList) == n - 1) return true
                    return super.onKeyDown(keyCode, event)
                }
                binding.categoryList.hasFocus() -> { focusRow(binding.channelList, 0); return true }
            }
            // Hard stop at the top/bottom of the lists — reaching an end must NOT
            // leap focus to another column or wrap around.
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (open) {
                    val rv = when {
                        binding.categoryList.hasFocus() -> binding.categoryList
                        binding.channelList.hasFocus() -> binding.channelList
                        else -> null
                    }
                    if (rv != null && focusedRowPos(rv) == 0) return true
                }
                if (!open && !hist) { surf(-1); return true }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (open) {
                    val rv = when {
                        binding.categoryList.hasFocus() -> binding.categoryList
                        binding.channelList.hasFocus() -> binding.channelList
                        else -> null
                    }
                    val last = (rv?.adapter?.itemCount ?: 0) - 1
                    if (rv != null && last >= 0 && focusedRowPos(rv) == last) return true
                }
                if (!open && !hist) { surf(1); return true }
            }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> if (!open && !hist) { onDigit(keyCode - KeyEvent.KEYCODE_0); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------- Status + updates ----------------

    private fun observeStatus() {
        lifecycleScope.launch {
            PlaybackState.status.collect { s ->
                when (s.state) {
                    PlayState.NO_PROVIDER -> { dismissSplash(); showNoProvider() }
                    PlayState.RECOVERING -> {
                        binding.statusText.visibility = View.VISIBLE
                        binding.statusText.text = "Reconnecting ${s.channelName ?: ""} (try ${s.retryCount})…"
                    }
                    PlayState.PLAYING -> {
                        dismissSplash()
                        if (binding.browserPanel.visibility != View.VISIBLE)
                            binding.statusText.visibility = View.GONE
                    }
                    else -> { /* leave as-is */ }
                }
            }
        }
    }

    private fun showNoProvider() {
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = "No provider yet.\nOpen  http://${localIp()}:8080  on your phone or PC to set up this TV."
    }

    private fun observeUpdates() {
        lifecycleScope.launch {
            UpdateState.installRequested.collect { req -> req?.let { installUpdate(it) } }
        }
    }

    private fun installUpdate(update: ReadyUpdate) {
        try {
            val uri = FileProvider.getUriForFile(this, "com.restaurant.iptv.updateprovider", update.file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            UpdateState.clearInstall()
        } catch (e: Exception) {
            UpdateState.clearInstall()
            UpdateState.message("Install failed: ${e.message}")
        }
    }

    /** Android 10+/Fire OS 8+ only auto-launch the UI on boot when "Display
     *  over other apps" is granted. Ask once; skip devices that don't need it. */
    private fun promptAutostartPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (Settings.canDrawOverlays(this)) return
        lifecycleScope.launch {
            if (prefs.overlayPrompted()) return@launch
            prefs.setOverlayPrompted()
            Toast.makeText(
                this@MainActivity,
                "Allow “Display over other apps” so MMG TV can start itself when the TV powers on.",
                Toast.LENGTH_LONG
            ).show()
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
        }
    }

    private fun localIp(): String {
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) return addr.hostAddress ?: continue
                }
            }
        } catch (_: Exception) {}
        return "this-tv-ip"
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        try { unbindService(connection) } catch (_: Exception) {}
        service?.onPlayerReady = null
        binding.playerView.player = null
        super.onDestroy()
    }

    companion object {
        private const val CAT_ALL = "All"
        private const val CAT_FAV = "★ Favorites"
        private const val HINT_BROWSER = "OK  Play        ◄  Categories        Hold OK  ★ Favorite"
        private const val SPLASH_MIN_MS = 1000L
        private const val SPLASH_MAX_MS = 6000L
    }
}
