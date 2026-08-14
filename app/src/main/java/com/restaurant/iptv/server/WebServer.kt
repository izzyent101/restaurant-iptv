package com.restaurant.iptv.server

import android.content.Context
import android.util.Log
import com.restaurant.iptv.BuildConfig
import com.restaurant.iptv.data.Prefs
import com.restaurant.iptv.data.Repository
import com.restaurant.iptv.data.entity.ProviderEntity
import com.restaurant.iptv.epg.EpgStore
import com.restaurant.iptv.player.PlaybackCommands
import com.restaurant.iptv.player.PlaybackState
import com.restaurant.iptv.update.UpdateChecker
import com.restaurant.iptv.update.UpdateState
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Embedded Ktor (CIO) control server. Each TV runs its own instance and
 * exposes only over the LAN / Tailscale — never the public internet.
 * The browser UI drives everything here; the on-TV app stays a dumb player.
 */
class WebServer(
    private val context: Context,
    private val commands: PlaybackCommands,
    private val repo: Repository,
    private val port: Int
) {
    private var engine: ApplicationEngine? = null
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val prefs = Prefs(context)

    fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            // The dashboard page (served by one TV) calls the other TVs'
            // APIs cross-origin, so allow it. Only ever exposed on LAN/Tailscale.
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowHeader("X-Access-Key")
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Delete)
                allowNonSimpleContentTypes = true
            }

            // Password gate. When a control password is set, NOTHING is served
            // until the visitor authenticates — not even the app HTML. Page
            // requests from an unauthenticated client get only the bare login
            // screen (no provider/setup/dashboard content leaks). API calls get
            // a 401. Auth is proven by the X-Access-Key header (used by the app's
            // fetches and cross-TV dashboard calls) OR the httpOnly "mak" cookie
            // (set at login, used for top-level page loads).
            intercept(ApplicationCallPipeline.Plugins) {
                if (call.request.httpMethod == HttpMethod.Options) return@intercept
                val key = prefs.accessKey()
                if (key.isEmpty()) return@intercept  // no password → fully open

                val path = call.request.uri.substringBefore('?')
                // Endpoints needed to render/handle the login screen itself, plus the
                // PWA manifest/icons (Chrome fetches these without cookies; they leak
                // only the app name and logo, never any content or settings).
                if (path == "/api/security/status" || path == "/api/login" ||
                    path == "/manifest.webmanifest" || path == "/icon-192.png" ||
                    path == "/icon-512.png" || path == "/apple-touch-icon.png"
                ) return@intercept

                val authed = call.request.headers["X-Access-Key"] == key ||
                    call.request.cookies["mak"] == key
                if (authed) return@intercept

                if (path.startsWith("/api/")) {
                    call.respondText(
                        json.encodeToString(ApiResult(false, "unauthorized")),
                        ContentType.Application.Json, HttpStatusCode.Unauthorized
                    )
                } else {
                    // Any page/asset request → serve ONLY the login screen.
                    val bytes = this@WebServer.context.assets.open("webui/login.html").readBytes()
                    call.respondBytes(bytes, ContentType.Text.Html)
                }
                finish()
            }
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    Log.e(TAG, "request failed", cause)
                    call.respondText(
                        json.encodeToString(ApiResult(false, cause.message ?: "error")),
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }
            routing {
                // --- Static web UI (served from assets) ---
                get("/") { call.respondAsset("index.html", ContentType.Text.Html) }
                get("/app.js") { call.respondAsset("app.js", ContentType.Text.JavaScript) }
                get("/style.css") { call.respondAsset("style.css", ContentType.Text.CSS) }
                get("/dashboard") { call.respondAsset("dashboard.html", ContentType.Text.Html) }
                get("/dashboard.js") { call.respondAsset("dashboard.js", ContentType.Text.JavaScript) }
                get("/login.html") { call.respondAsset("login.html", ContentType.Text.Html) }
                get("/logo.png") { call.respondAsset("logo.png", ContentType.Image.PNG) }
                // PWA install bits (add-to-home-screen). Exempt from the auth gate below.
                get("/manifest.webmanifest") {
                    call.respondAsset("manifest.webmanifest", ContentType.parse("application/manifest+json"))
                }
                get("/icon-192.png") { call.respondAsset("icon-192.png", ContentType.Image.PNG) }
                get("/icon-512.png") { call.respondAsset("icon-512.png", ContentType.Image.PNG) }
                get("/apple-touch-icon.png") { call.respondAsset("apple-touch-icon.png", ContentType.Image.PNG) }

                // --- Central dashboard: the list of TVs to control ---
                get("/api/tvs") {
                    call.respondJson(json.encodeToString(repo.getTvs()))
                }
                post("/api/tvs/add") {
                    val p = call.receiveParameters()
                    val address = p["address"]?.trim().orEmpty()
                    if (address.isEmpty()) return@post call.respondJson(json.encodeToString(ApiResult(false, "missing address")))
                    repo.addTv(p["name"]?.trim().orEmpty(), address, p["mac"])
                    call.respondJson(json.encodeToString(ApiResult(true)))
                }
                // Wake-on-LAN: broadcast a magic packet so an awake TV can power
                // on a sleeping one (dashboard "Wake" button).
                post("/api/wol") {
                    val macBytes = parseMac(call.receiveParameters()["mac"].orEmpty())
                        ?: return@post call.respondJson(json.encodeToString(ApiResult(false, "bad MAC address")))
                    val ok = runCatching {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { sendMagicPacket(macBytes) }
                    }.isSuccess
                    call.respondJson(json.encodeToString(ApiResult(ok, if (ok) "Wake signal sent" else "Failed to send")))
                }
                post("/api/tvs/remove") {
                    val address = call.receiveParameters()["address"]?.trim().orEmpty()
                    if (address.isNotEmpty()) repo.removeTv(address)
                    call.respondJson(json.encodeToString(ApiResult(true)))
                }

                // --- Security (control-panel password) ---
                get("/api/security/status") {
                    call.respondJson("{\"protected\":${prefs.accessKey().isNotEmpty()}}")
                }
                // Login from the bare login screen: verify the password, then set
                // an httpOnly session cookie so page loads are authenticated.
                post("/api/login") {
                    val pw = call.receiveParameters()["password"]?.trim().orEmpty()
                    val key = prefs.accessKey()
                    if (key.isNotEmpty() && pw == key) {
                        call.response.cookies.append(authCookie(key))
                        call.respondJson(json.encodeToString(ApiResult(true)))
                    } else {
                        call.respondText(
                            json.encodeToString(ApiResult(false, "Wrong password")),
                            ContentType.Application.Json, HttpStatusCode.Unauthorized
                        )
                    }
                }
                post("/api/security/password") {
                    // Reachable when no password is set (open) or with the current
                    // password (enforced by the gate above). Empty clears protection.
                    val newKey = call.receiveParameters()["password"]?.trim().orEmpty()
                    prefs.setAccessKey(newKey)
                    // Keep the setter authenticated (or clear the cookie on removal).
                    if (newKey.isEmpty()) call.response.cookies.append(Cookie("mak", "", path = "/", maxAge = 0, httpOnly = true))
                    else call.response.cookies.append(authCookie(newKey))
                    call.respondJson(json.encodeToString(ApiResult(true, if (newKey.isEmpty()) "Password cleared" else "Password set")))
                }

                // --- Auto-update ---
                get("/api/version") {
                    val avail = UpdateState.available.value
                    val dto = VersionDto(
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                        updateRepo = prefs.updateRepo(),
                        manifestUrl = prefs.updateManifestUrl(),
                        updateMessage = UpdateState.lastMessage.value,
                        updateAvailable = avail != null,
                        availableVersion = avail?.versionName ?: ""
                    )
                    call.respondJson(json.encodeToString(dto))
                }
                post("/api/update/config") {
                    val p = call.receiveParameters()
                    val tokenParam = p["token"]
                    val token = when {
                        tokenParam == null -> null
                        tokenParam == "clear" -> ""
                        tokenParam.isBlank() -> null
                        else -> tokenParam.trim()
                    }
                    prefs.setUpdateConfig(token, p["manifestUrl"]?.trim(), p["repo"]?.trim()?.takeIf { it.isNotEmpty() })
                    call.respondJson(json.encodeToString(ApiResult(true, "Saved")))
                }
                // Check + download only. Does NOT install (won't interrupt playback).
                post("/api/update/check") {
                    UpdateChecker.checkAndDownload(this@WebServer.context)
                    val avail = UpdateState.available.value
                    call.respondJson(json.encodeToString(ApiResult(avail != null, UpdateState.lastMessage.value)))
                }
                // Operator-triggered install. The foreground app fires the installer.
                post("/api/update/install") {
                    val avail = UpdateState.available.value
                    if (avail == null) {
                        call.respondJson(json.encodeToString(ApiResult(false, "No update downloaded — check first")))
                    } else {
                        UpdateState.requestInstall()
                        call.respondJson(json.encodeToString(ApiResult(true, "Installing ${avail.versionName} — confirm on the TV")))
                    }
                }

                // --- Status + data ---
                get("/api/status") {
                    val s = PlaybackState.status.value
                    val prov = repo.getActiveProvider()?.toDto()
                    val dto = StatusDto(
                        state = s.state.name.lowercase(),
                        channelId = s.channelId,
                        channelName = s.channelName,
                        providerId = s.providerId,
                        secondsInState = (System.currentTimeMillis() - s.stateSince) / 1000,
                        retryCount = s.retryCount,
                        recreateCount = s.recreateCount,
                        lastError = s.lastError,
                        provider = prov
                    )
                    call.respondJson(json.encodeToString(dto))
                }

                get("/api/provider") {
                    val prov = repo.getActiveProvider()?.toDto()
                    call.respondJson(if (prov != null) json.encodeToString(prov) else "null")
                }

                get("/api/channels") {
                    val prov = repo.getActiveProvider()
                    val list = if (prov == null) emptyList() else {
                        val favs = repo.getFavorites(prov.id)
                        val nowMs = System.currentTimeMillis()
                        repo.getVisibleChannels(prov.id).map {
                            val (now, next) = EpgStore.nowNext(prov.id, it.epgChannelId)
                            val pct = if (now != null && now.endMs > now.startMs)
                                (((nowMs - now.startMs) * 100) / (now.endMs - now.startMs)).toInt().coerceIn(0, 100)
                            else -1
                            ChannelDto(
                                it.id, it.name, it.groupTitle, it.number, it.logoUrl,
                                favorite = favs.contains(it.streamKey),
                                epgNow = now?.title, epgNext = next?.title, epgProgress = pct
                            )
                        }
                    }
                    call.respondJson(json.encodeToString(list))
                }
                post("/api/favorite") {
                    val prov = repo.getActiveProvider()
                    val id = call.receiveParameters()["channelId"]?.toLongOrNull()
                    if (prov == null || id == null) return@post call.respondJson(json.encodeToString(ApiResult(false, "missing channel")))
                    val ch = repo.getChannel(id) ?: return@post call.respondJson(json.encodeToString(ApiResult(false, "not found")))
                    val nowFav = repo.toggleFavorite(prov.id, ch.streamKey)
                    call.respondJson(json.encodeToString(ApiResult(true, if (nowFav) "added" else "removed")))
                }
                post("/api/epg/refresh") {
                    val prov = repo.getActiveProvider()
                        ?: return@post call.respondJson(json.encodeToString(ApiResult(false, "no provider")))
                    val ok = repo.refreshEpg(prov.id)
                    call.respondJson(json.encodeToString(ApiResult(ok, if (ok) "EPG loaded" else "No EPG available")))
                }

                get("/api/groups") {
                    val prov = repo.getActiveProvider()
                    val list = if (prov == null) emptyList() else {
                        val hidden = repo.getHiddenGroups(prov.id).toSet()
                        repo.getGroups(prov.id).map { GroupDto(it, hidden.contains(it)) }
                    }
                    call.respondJson(json.encodeToString(list))
                }

                // --- Playback control ---
                post("/api/play") {
                    val id = call.receiveParameters()["channelId"]?.toLongOrNull()
                    if (id == null) return@post call.respondJson(json.encodeToString(ApiResult(false, "missing channelId")))
                    commands.cmdPlayChannel(id)
                    call.respondJson(json.encodeToString(ApiResult(true)))
                }
                post("/api/stop") {
                    commands.cmdStop()
                    call.respondJson(json.encodeToString(ApiResult(true)))
                }
                post("/api/retry") {
                    commands.cmdRetryNow()
                    call.respondJson(json.encodeToString(ApiResult(true)))
                }
                post("/api/refresh") {
                    val prov = repo.getActiveProvider()
                        ?: return@post call.respondJson(json.encodeToString(ApiResult(false, "no provider")))
                    val res = repo.refreshProvider(prov.id)
                    call.respondJson(json.encodeToString(ApiResult(res.ok, res.error ?: "${res.channelCount} channels")))
                }

                // --- Provider setup (per-TV Xtream/M3U login lives here) ---
                post("/api/provider") {
                    val params = call.receiveParameters()
                    val existing = repo.getActiveProvider()
                    val type = params["type"]?.trim().orEmpty().ifEmpty { "xtream" }
                    val name = params["name"]?.trim().orEmpty().ifEmpty { "Provider" }
                    // Keep the old password if the field was left blank on edit.
                    val pass = params["password"]?.takeIf { it.isNotEmpty() } ?: existing?.xtreamPassword

                    val entity = ProviderEntity(
                        id = existing?.id ?: 0,
                        name = name,
                        type = type,
                        xtreamServer = params["server"]?.trim()?.takeIf { it.isNotEmpty() },
                        xtreamUsername = params["username"]?.trim()?.takeIf { it.isNotEmpty() },
                        xtreamPassword = pass,
                        m3uUrl = params["m3uUrl"]?.trim()?.takeIf { it.isNotEmpty() },
                        epgUrl = params["epgUrl"]?.trim()?.takeIf { it.isNotEmpty() },
                        active = true,
                        lastUpdated = existing?.lastUpdated ?: 0
                    )
                    val id = repo.saveProvider(entity)
                    val res = repo.refreshProvider(id)
                    if (res.ok) commands.cmdRefreshAndResume()
                    call.respondJson(json.encodeToString(ApiResult(res.ok, res.error ?: "${res.channelCount} channels loaded", id)))
                }

                post("/api/provider/delete") {
                    val prov = repo.getActiveProvider()
                    if (prov != null) { commands.cmdStop(); repo.deleteProvider(prov.id) }
                    call.respondJson(json.encodeToString(ApiResult(true)))
                }

                // --- Hide / show provider groups (preserved across refreshes) ---
                post("/api/groups/hide") {
                    val prov = repo.getActiveProvider()
                    val group = call.receiveParameters()["group"]
                    if (prov != null && group != null) repo.hideGroup(prov.id, group)
                    call.respondJson(json.encodeToString(ApiResult(true)))
                }
                post("/api/groups/unhide") {
                    val prov = repo.getActiveProvider()
                    val group = call.receiveParameters()["group"]
                    if (prov != null && group != null) repo.unhideGroup(prov.id, group)
                    call.respondJson(json.encodeToString(ApiResult(true)))
                }
                // Curate: keep ONLY the given groups visible, hide every other group.
                // Groups are newline-separated so commas in names are safe.
                post("/api/groups/keep") {
                    val prov = repo.getActiveProvider()
                    val keep = call.receiveParameters()["groups"]
                        ?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toHashSet()
                        ?: hashSetOf()
                    if (prov != null) {
                        for (g in repo.getGroups(prov.id)) {
                            if (keep.contains(g)) repo.unhideGroup(prov.id, g) else repo.hideGroup(prov.id, g)
                        }
                    }
                    call.respondJson(json.encodeToString(ApiResult(true)))
                }
                post("/api/groups/showall") {
                    val prov = repo.getActiveProvider()
                    if (prov != null) for (g in repo.getHiddenGroups(prov.id)) repo.unhideGroup(prov.id, g)
                    call.respondJson(json.encodeToString(ApiResult(true)))
                }
                post("/api/groups/hideall") {
                    val prov = repo.getActiveProvider()
                    if (prov != null) for (g in repo.getGroups(prov.id)) repo.hideGroup(prov.id, g)
                    call.respondJson(json.encodeToString(ApiResult(true)))
                }
            }
        }.also { it.start(wait = false) }
        Log.i(TAG, "Web control server listening on :$port")
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
    }

    private fun ProviderEntity.toDto() = ProviderDto(
        id = id, name = name, type = type,
        server = xtreamServer, username = xtreamUsername,
        hasPassword = !xtreamPassword.isNullOrEmpty(),
        m3uUrl = m3uUrl, epgUrl = epgUrl,
        channelCount = channelCount, expiresAt = expiresAt,
        maxConnections = maxConnections, lastUpdated = lastUpdated,
        lastError = lastError, active = active
    )

    // ---------- Wake-on-LAN ----------

    private fun parseMac(s: String): ByteArray? {
        val parts = s.trim().split(":", "-").filter { it.isNotEmpty() }
        if (parts.size != 6) return null
        return try { ByteArray(6) { i -> parts[i].toInt(16).toByte() } } catch (_: Exception) { null }
    }

    private fun sendMagicPacket(mac: ByteArray) {
        val payload = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) payload[i] = 0xFF.toByte()
        for (r in 0 until 16) System.arraycopy(mac, 0, payload, 6 + r * 6, 6)
        java.net.DatagramSocket().use { sock ->
            sock.broadcast = true
            for (port in intArrayOf(9, 7)) {
                sock.send(
                    java.net.DatagramPacket(
                        payload, payload.size,
                        java.net.InetAddress.getByName("255.255.255.255"), port
                    )
                )
            }
        }
    }

    /** One-year httpOnly session cookie proving the visitor entered the password. */
    private fun authCookie(key: String) = Cookie(
        name = "mak",
        value = key,
        path = "/",
        maxAge = 60 * 60 * 24 * 365,
        httpOnly = true,
        extensions = mapOf("SameSite" to "Strict")
    )

    private suspend fun io.ktor.server.application.ApplicationCall.respondJson(body: String) =
        respondText(body, ContentType.Application.Json)

    private suspend fun io.ktor.server.application.ApplicationCall.respondAsset(name: String, type: ContentType) {
        val bytes = context.assets.open("webui/$name").readBytes()
        respondBytes(bytes, type)
    }

    companion object { private const val TAG = "WebServer" }
}
