package com.restaurant.iptv.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.restaurant.iptv.data.Prefs
import com.restaurant.iptv.data.Repository
import com.restaurant.iptv.data.entity.ChannelEntity
import com.restaurant.iptv.databinding.ActivityMainBinding
import com.restaurant.iptv.player.PlayState
import com.restaurant.iptv.player.PlaybackService
import com.restaurant.iptv.player.PlaybackState
import com.restaurant.iptv.update.ReadyUpdate
import com.restaurant.iptv.update.UpdateState
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: Repository
    private lateinit var prefs: Prefs

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: RichChannelAdapter

    private var providerId: Long? = null
    private var allVisible: List<ChannelEntity> = emptyList()
    private var favorites: Set<String> = emptySet()
    private var categories: List<String> = emptyList()
    private var focusedChannel: ChannelUi? = null

    private val ui = Handler(Looper.getMainLooper())
    private var numberBuffer = ""
    private var pendingCategory: String? = null
    private val categorySwitch = Runnable { pendingCategory?.let { showCategory(it) } }

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
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

        requestNotifPermissionIfNeeded()
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
            allVisible = repo.getVisibleChannels(prov.id)
            val groups = allVisible.map { it.groupTitle }.filter { it.isNotBlank() }.distinct().sorted()
            categories = listOf(CAT_ALL, CAT_FAV) + groups
            categoryAdapter.submit(categories)
            showCategory(categoryAdapter.current() ?: CAT_ALL)
        }
    }

    private fun showCategory(name: String) {
        providerId ?: return
        val chans = when (name) {
            CAT_ALL -> allVisible
            CAT_FAV -> allVisible.filter { favorites.contains(it.streamKey) }
            else -> allVisible.filter { it.groupTitle == name }
        }
        val list = chans.map { ch -> ChannelUi(ch, null, null, favorites.contains(ch.streamKey)) }
        channelAdapter.submit(list)
        binding.channelHeader.text = "$name  (${chans.size})"
    }

    /** Update the "selected channel" card on the right as the user scrolls. */
    private fun onChannelFocused(item: ChannelUi) {
        focusedChannel = item
        val ch = item.channel
        binding.selNumber.text = if (ch.number > 0) ch.number.toString() else ""
        binding.selName.text = ch.name
        if (!ch.logoUrl.isNullOrBlank()) binding.selLogo.load(ch.logoUrl) else binding.selLogo.setImageDrawable(null)
    }

    private fun toggleFavorite(ch: ChannelEntity) {
        val pid = providerId ?: return
        lifecycleScope.launch {
            repo.toggleFavorite(pid, ch.streamKey)
            favorites = repo.getFavorites(pid)
            showCategory(categoryAdapter.current() ?: CAT_ALL)
        }
    }

    private fun playChannel(ch: ChannelEntity) {
        ui.removeCallbacks(categorySwitch)
        service?.cmdPlayChannel(ch.id)
        hideBrowser()
        showInfoBar(ch)
    }

    // ---------------- Browser + info bar ----------------

    private fun showBrowser() {
        if (providerId == null) { showNoProvider(); return }
        resizePlayerToPreview()
        binding.browserPanel.visibility = View.VISIBLE
        binding.categoryPanel.visibility = View.GONE
        binding.channelList.requestFocus()
    }

    private fun hideBrowser() {
        binding.browserPanel.visibility = View.GONE
        binding.categoryPanel.visibility = View.GONE
        restorePlayerFullscreen()
    }

    private fun categoriesOpen() = binding.categoryPanel.visibility == View.VISIBLE

    /** LEFT opens the category list, landing on the current channel's group. */
    private fun openCategories() {
        ui.removeCallbacks(categorySwitch)
        val group = focusedChannel?.channel?.groupTitle
        val idx = group?.let { categories.indexOf(it) } ?: -1
        binding.categoryPanel.visibility = View.VISIBLE
        if (idx >= 0) {
            categoryAdapter.setSelected(idx)
            showCategory(categories[idx])
            (binding.categoryList.layoutManager as LinearLayoutManager)
                .scrollToPositionWithOffset(idx, dp(140))
        }
        val pos = if (idx >= 0) idx else categoryAdapter.selectedIndex
        binding.categoryList.post {
            binding.categoryList.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
                ?: binding.categoryList.requestFocus()
        }
    }

    private fun closeCategories() {
        binding.categoryPanel.visibility = View.GONE
        binding.channelList.requestFocus()
    }

    // Shrink the live video into a Dish-style box (top-right) while browsing.
    private fun resizePlayerToPreview() {
        val dm = resources.displayMetrics
        val boxW = (dm.widthPixels * 0.32f).toInt()
        val lp = binding.playerView.layoutParams as FrameLayout.LayoutParams
        lp.width = boxW
        lp.height = boxW * 9 / 16
        lp.gravity = Gravity.TOP or Gravity.END
        lp.topMargin = dp(78)
        lp.marginEnd = dp(30)
        binding.playerView.layoutParams = lp
    }

    private fun restorePlayerFullscreen() {
        val lp = binding.playerView.layoutParams as FrameLayout.LayoutParams
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.NO_GRAVITY
        lp.topMargin = 0
        lp.marginEnd = 0
        binding.playerView.layoutParams = lp
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val hideInfo = Runnable { binding.infoBar.visibility = View.GONE }

    private fun showInfoBar(ch: ChannelEntity) {
        binding.infoTitle.text = if (ch.number > 0) "${ch.number}  ${ch.name}" else ch.name
        if (!ch.logoUrl.isNullOrBlank()) binding.infoLogo.load(ch.logoUrl) else binding.infoLogo.setImageDrawable(null)
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
        service?.cmdPlayChannel(ch.id)
        showInfoBar(ch)
    }

    private val tuneRunnable = Runnable {
        val n = numberBuffer.toIntOrNull()
        numberBuffer = ""
        if (n != null) {
            val ch = allVisible.firstOrNull { it.number == n }
            if (ch != null) { service?.cmdPlayChannel(ch.id); showInfoBar(ch) }
            else { binding.infoTitle.text = "No channel $n"; ui.postDelayed(hideInfo, 2000) }
        }
    }

    private fun onDigit(d: Int) {
        numberBuffer += d.toString()
        binding.infoTitle.text = "Channel: $numberBuffer"
        binding.infoLogo.setImageDrawable(null)
        binding.infoBar.visibility = View.VISIBLE
        ui.removeCallbacks(tuneRunnable)
        ui.removeCallbacks(hideInfo)
        ui.postDelayed(tuneRunnable, 1600)
    }

    // ---------------- Remote control ----------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val open = binding.browserPanel.visibility == View.VISIBLE
        val cats = categoriesOpen()
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> when {
                cats -> { closeCategories(); return true }
                open -> { hideBrowser(); return true }
            }
            KeyEvent.KEYCODE_MENU -> { if (open) hideBrowser() else showBrowser(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> when {
                cats -> { closeCategories(); return true }
                !open -> { showBrowser(); return true }
            }
            KeyEvent.KEYCODE_INFO -> { toggleInfoBar(); return true }
            // LEFT opens the category list (TiviMate-style); RIGHT / Back closes it.
            KeyEvent.KEYCODE_DPAD_LEFT -> if (open && !cats && binding.channelList.hasFocus()) {
                openCategories(); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (cats) { closeCategories(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> if (!open) { surf(-1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!open) { surf(1); return true }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> if (!open) { onDigit(keyCode - KeyEvent.KEYCODE_0); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------- Status + updates ----------------

    private fun observeStatus() {
        lifecycleScope.launch {
            PlaybackState.status.collect { s ->
                when (s.state) {
                    PlayState.NO_PROVIDER -> showNoProvider()
                    PlayState.RECOVERING -> {
                        binding.statusText.visibility = View.VISIBLE
                        binding.statusText.text = "Reconnecting ${s.channelName ?: ""} (try ${s.retryCount})…"
                    }
                    PlayState.PLAYING -> if (binding.browserPanel.visibility != View.VISIBLE)
                        binding.statusText.visibility = View.GONE
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
    }
}
