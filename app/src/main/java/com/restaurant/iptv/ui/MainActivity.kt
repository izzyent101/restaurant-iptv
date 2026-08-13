package com.restaurant.iptv.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.restaurant.iptv.data.Repository
import com.restaurant.iptv.databinding.ActivityMainBinding
import com.restaurant.iptv.player.PlaybackService
import com.restaurant.iptv.player.PlaybackState
import com.restaurant.iptv.player.PlayState
import com.restaurant.iptv.update.ReadyUpdate
import com.restaurant.iptv.update.UpdateState
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: Repository
    private lateinit var adapter: ChannelAdapter

    private var service: PlaybackService? = null
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? PlaybackService.LocalBinder)?.service ?: return
            service = svc
            binding.playerView.player = svc.activePlayer
            // Re-attach the surface if the service rebuilds the player mid-recovery.
            svc.onPlayerReady = { p -> runOnUiThread { binding.playerView.player = p } }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = Repository(applicationContext)

        adapter = ChannelAdapter { ch ->
            service?.cmdPlayChannel(ch.id)
            hideChannelPanel()
        }
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = adapter

        requestNotifPermissionIfNeeded()
        PlaybackService.start(this)
        bindService(Intent(this, PlaybackService::class.java), connection, Context.BIND_AUTO_CREATE)

        observeStatus()
        observeUpdates()
        loadChannels()
    }

    // Installs ONLY when the operator clicks "Install now" in the web UI
    // (which sets installRequested). Never triggered automatically, so the
    // live stream is never interrupted by a surprise update.
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

    override fun onResume() {
        super.onResume()
        loadChannels()
    }

    private fun loadChannels() {
        lifecycleScope.launch {
            val prov = repo.getActiveProvider()
            val channels = if (prov != null) repo.getVisibleChannels(prov.id) else emptyList()
            adapter.submit(channels)
            binding.channelHeader.text = "Channels (${channels.size})"
        }
    }

    private fun observeStatus() {
        lifecycleScope.launch {
            PlaybackState.status.collect { s ->
                val t = binding.statusText
                when (s.state) {
                    PlayState.PLAYING -> t.visibility = View.GONE
                    PlayState.NO_PROVIDER -> {
                        t.visibility = View.VISIBLE
                        t.text = "No provider set up.\nOpen  http://${localIp()}:8080  on your phone or PC."
                    }
                    PlayState.IDLE -> {
                        t.visibility = View.VISIBLE
                        t.text = "Idle · press OK for channels · web: http://${localIp()}:8080"
                    }
                    PlayState.RECOVERING -> {
                        t.visibility = View.VISIBLE
                        t.text = "Reconnecting ${s.channelName ?: ""} (try ${s.retryCount})…"
                    }
                    PlayState.BUFFERING -> {
                        t.visibility = View.VISIBLE
                        t.text = "Buffering ${s.channelName ?: ""}…"
                    }
                    PlayState.ERROR -> {
                        t.visibility = View.VISIBLE
                        t.text = "Error: ${s.lastError ?: "unknown"}"
                    }
                }
            }
        }
    }

    // ---------- Remote control ----------
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val panelOpen = binding.channelPanel.visibility == View.VISIBLE
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (panelOpen) { hideChannelPanel(); return true }
            }
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!panelOpen) { showChannelPanel(); return true }
            }
            KeyEvent.KEYCODE_INFO -> {
                binding.statusText.visibility =
                    if (binding.statusText.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showChannelPanel() {
        loadChannels()
        binding.channelPanel.visibility = View.VISIBLE
        binding.channelList.requestFocus()
    }

    private fun hideChannelPanel() {
        binding.channelPanel.visibility = View.GONE
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
            }
        }
    }

    private fun localIp(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && addr.isSiteLocalAddress) return addr.hostAddress ?: continue
                }
            }
        } catch (_: Exception) {}
        return "this-tv-ip"
    }

    override fun onDestroy() {
        try { unbindService(connection) } catch (_: Exception) {}
        service?.onPlayerReady = null
        binding.playerView.player = null
        super.onDestroy()
    }
}
