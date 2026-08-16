package com.restaurant.iptv

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import java.io.File

/**
 * Bounded caches.
 *
 * Channel-logo caching is left unbounded by default (Coil sizes its disk cache
 * as a % of free space and its memory cache as a % of our largeHeap). On TVs
 * with small storage that grows until the device is starved and the FIRMWARE
 * reboots — which looked like an app crash-loop. Everything is capped here, and
 * a startup sweep trims the cache dir if it ever escapes anyway.
 */
class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        trimCacheIfOversized()
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(24 * 1024 * 1024)    // 24 MB of decoded logos (Int API)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(64L * 1024 * 1024)   // 64 MB on disk, LRU-evicted
                    .build()
            }
            .respectCacheHeaders(false)                 // logos rarely change
            .build()

    /** Safety net: if anything ever balloons the cache dir, wipe it at startup. */
    private fun trimCacheIfOversized() {
        try {
            val dir = cacheDir ?: return
            val size = dirSize(dir)
            if (size > CACHE_LIMIT_BYTES) {
                Log.w(TAG, "Cache dir ${size / 1024 / 1024} MB > limit — clearing")
                dir.listFiles()?.forEach { it.deleteRecursively() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "cache trim failed", t)
        }
    }

    private fun dirSize(f: File): Long =
        if (f.isDirectory) (f.listFiles()?.sumOf { dirSize(it) } ?: 0L) else f.length()

    private companion object {
        const val TAG = "App"
        const val CACHE_LIMIT_BYTES = 150L * 1024 * 1024
    }
}
