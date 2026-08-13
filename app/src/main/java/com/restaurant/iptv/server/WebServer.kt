package com.restaurant.iptv.server

import android.content.Context
import android.util.Log
import com.restaurant.iptv.BuildConfig
import com.restaurant.iptv.data.Prefs
import com.restaurant.iptv.data.Repository
import com.restaurant.iptv.data.entity.ProviderEntity
import com.restaurant.iptv.player.PlaybackCommands
import com.restaurant.iptv.player.PlaybackState
import com.restaurant.iptv.update.UpdateChecker
import com.restaurant.iptv.update.UpdateState
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveParameters
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
                allowMethod(HttpMethod.Get)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Delete)
                allowNonSimpleContentTypes = true
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
                get("/logo.png") { call.respondAsset("logo.png", ContentType.Image.PNG) }

                // --- Central dashboard: the list of TVs to control ---
                get("/api/tvs") {
                    call.respondJson(json.encodeToString(repo.getTvs()))
                }
                post("/api/tvs/add") {
                    val p = call.receiveParameters()
                    val address = p["address"]?.trim().orEmpty()
                    if (address.isEmpty()) return@post call.respondJson(json.encodeToString(ApiResult(false, "missing address")))
                    repo.addTv(p["name"]?.trim().orEmpty(), address)
                    call.respondJson(json.encodeToString(ApiResult(true)))
                }
                post("/api/tvs/remove") {
                    val address = call.receiveParameters()["address"]?.trim().orEmpty()
                    if (address.isNotEmpty()) repo.removeTv(address)
                    call.respondJson(json.encodeToString(ApiResult(true)))
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
                    val list = if (prov == null) emptyList() else repo.getVisibleChannels(prov.id).map {
                        ChannelDto(it.id, it.name, it.groupTitle, it.number, it.logoUrl)
                    }
                    call.respondJson(json.encodeToString(list))
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

    private suspend fun io.ktor.server.application.ApplicationCall.respondJson(body: String) =
        respondText(body, ContentType.Application.Json)

    private suspend fun io.ktor.server.application.ApplicationCall.respondAsset(name: String, type: ContentType) {
        val bytes = context.assets.open("webui/$name").readBytes()
        respondBytes(bytes, type)
    }

    companion object { private const val TAG = "WebServer" }
}
