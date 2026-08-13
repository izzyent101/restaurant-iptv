package com.restaurant.iptv.update

import android.content.Context
import android.util.Log
import com.restaurant.iptv.BuildConfig
import com.restaurant.iptv.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks for a newer build and downloads it. Two sources, chosen by settings:
 *  - Self-host (if a manifest URL is set): GET a JSON {versionCode, versionName, apkUrl}.
 *  - GitHub Releases (default): read the latest release of the configured repo;
 *    the release tag's digits are the versionCode. Works with a private repo
 *    when a read-only token is set.
 *
 * The download is posted to UpdateState; MainActivity fires the installer.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun checkAndDownload(context: Context): Unit = withContext(Dispatchers.IO) {
        try {
            val prefs = Prefs(context)
            val manifestUrl = prefs.updateManifestUrl().trim()
            val token = prefs.updateToken().trim()
            val current = BuildConfig.VERSION_CODE

            val target: Target? =
                if (manifestUrl.isNotEmpty()) resolveFromManifest(manifestUrl, token)
                else resolveFromGithub(prefs.updateRepo().trim(), token)

            if (target == null || target.versionCode <= current) {
                UpdateState.setAvailable(null)
                UpdateState.message("Up to date (v${BuildConfig.VERSION_NAME})")
                return@withContext
            }

            UpdateState.message("Downloading ${target.versionName}…")
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "marhaba-${target.versionCode}.apk")
            download(target.downloadUrl, if (target.authForDownload) token else "", out)

            // Mark available only — do NOT install. Operator installs on demand.
            UpdateState.setAvailable(ReadyUpdate(out, target.versionName, target.versionCode))
            UpdateState.message("Update ${target.versionName} downloaded — click Install now when ready")
            Log.i(TAG, "Downloaded update ${target.versionName} (${out.length()} bytes)")
        } catch (t: Throwable) {
            Log.e(TAG, "update check failed", t)
            UpdateState.message("Update check failed: ${t.message}")
        }
    }

    private data class Target(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val authForDownload: Boolean
    )

    private fun resolveFromManifest(url: String, token: String): Target? {
        val text = readText(url, token)
        val o = json.parseToJsonElement(text).jsonObject
        val code = o["versionCode"]?.jsonPrimitive?.intOrNull ?: return null
        val name = o["versionName"]?.jsonPrimitive?.contentOrNull ?: code.toString()
        val apk = o["apkUrl"]?.jsonPrimitive?.contentOrNull ?: return null
        return Target(code, name, apk, token.isNotEmpty())
    }

    private fun resolveFromGithub(repo: String, token: String): Target? {
        if (repo.isEmpty()) return null
        val api = "https://api.github.com/repos/$repo/releases/latest"
        val text = readText(api, token, accept = "application/vnd.github+json")
        val o = json.parseToJsonElement(text).jsonObject
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val code = tag.filter { it.isDigit() }.toIntOrNull() ?: return null
        val name = o["name"]?.jsonPrimitive?.contentOrNull ?: tag
        val assets = o["assets"]?.jsonArray ?: return null
        val apkAsset = assets.map { it.jsonObject }.firstOrNull {
            (it["name"]?.jsonPrimitive?.contentOrNull ?: "").endsWith(".apk", true)
        } ?: return null
        // Public repo: use the anonymous browser_download_url. Private repo: use
        // the API asset URL with the token.
        return if (token.isEmpty()) {
            val browserUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null
            Target(code, name, browserUrl, false)
        } else {
            val assetApiUrl = apkAsset["url"]?.jsonPrimitive?.contentOrNull ?: return null
            Target(code, name, assetApiUrl, true)
        }
    }

    // ---- HTTP helpers ----

    private fun readText(urlStr: String, token: String, accept: String = "application/json"): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", "MarhabaIPTV")
            setRequestProperty("Accept", accept)
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
        }
        conn.inputStream.use { return it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Download to file, handling GitHub's asset->CDN redirect (auth must be
     *  dropped on the redirected request). */
    private fun download(urlStr: String, token: String, out: File) {
        var conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "MarhabaIPTV")
            setRequestProperty("Accept", "application/octet-stream")
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
        }
        val code = conn.responseCode
        if (code in 300..399) {
            val loc = conn.getHeaderField("Location")
            conn.disconnect()
            // Follow the redirect WITHOUT the auth header (CDN rejects it).
            conn = (URL(loc).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MarhabaIPTV")
            }
        }
        conn.inputStream.use { input -> out.outputStream.use { input.copyTo(it) } }
        conn.disconnect()
    }
}
