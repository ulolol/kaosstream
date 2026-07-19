package com.lagradost.cloudstream3.server

import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.server.plugins.ServerPluginManager
import com.lagradost.cloudstream3.server.storage.DatabaseHelper
import com.lagradost.cloudstream3.server.storage.BookmarkData
import com.lagradost.cloudstream3.server.storage.WatchProgressData
import com.lagradost.cloudstream3.server.storage.ServerDownloadManager
import com.lagradost.cloudstream3.server.challenge.ChallengeClient
import com.lagradost.cloudstream3.network.ChallengeCookieStore
import com.lagradost.cloudstream3.network.CloudflareKillerJvm
import com.lagradost.cloudstream3.network.CookieJarManager
import com.lagradost.cloudstream3.network.PendingRetryStore
import com.lagradost.cloudstream3.network.RetryResultStore
import kotlin.concurrent.thread
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.staticResources
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.google.gson.Gson
import java.io.File
import java.net.InetAddress
import kotlin.concurrent.thread
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URL
import java.util.Collections

@Serializable
private data class ProbeStream(val codec_type: String, val codec_name: String? = null, val profile: String? = null, val level: Int? = null, val codec_tag_string: String? = null)

private fun ProbeStream.toIsoCodecString(): String? {
    val name = codec_name?.lowercase() ?: return null
    return when (name) {
        "h264" -> {
            val profileIdc = when (profile?.lowercase()) {
                "baseline" -> 66
                "main" -> 77
                "high" -> 100
                "high 10" -> 110
                "high 4:2:2" -> 122
                "high 4:4:4" -> 244
                else -> 100
            }
            val constraint = 0
            val levelIdc = level ?: 40
            "avc1.${"%02x%02x%02x".format(profileIdc, constraint, levelIdc)}"
        }
        "hevc" -> {
            val levelIdc = level ?: 120
            "hvc1.1.6.L${levelIdc}.B0"
        }
        "aac" -> "mp4a.40.2"
        "ac3" -> "ac-3"
        "eac3" -> "ec-3"
        "mp3" -> "mp4a.40.34"
        "vp9" -> "vp09.00.10.08"
        "opus" -> "opus"
        "flac" -> "flac"
        "vorbis" -> "vorbis"
        else -> codec_tag_string?.ifBlank { null }
    }
}

@Serializable
private data class ProbeResult(val streams: List<ProbeStream>? = null)

@Serializable
data class ClientLogRequest(
    val level: String,
    val tag: String,
    val message: String,
    val platform: String? = null,
    val userAgent: String? = null
)

@Serializable
data class SearchResponseDto(
    val name: String,
    val url: String,
    val apiName: String,
    val type: String?,
    val posterUrl: String?,
    val id: Int?,
    val quality: String?,
    val score: Double?
)

@Serializable
data class ProviderCapabilitiesDto(
    val instantLinkLoading: Boolean,
    val hasChromecastSupport: Boolean,
    val hasDownloadSupport: Boolean,
    val usesWebView: Boolean,
    val hasMainPage: Boolean,
    val hasQuickSearch: Boolean,
    val sequentialMainPage: Boolean,
    val supportedSyncNames: List<String>,
    val supportedTypes: List<String>,
    val vpnStatus: String,
    val providerType: String,
    val loadLinksTimeoutMs: Long?,
    val getMainPageTimeoutMs: Long?,
    val searchTimeoutMs: Long?,
    val quickSearchTimeoutMs: Long?,
    val loadTimeoutMs: Long?
)

@Serializable
data class ProviderDto(
    val name: String,
    val url: String,
    val capabilities: ProviderCapabilitiesDto
)

@Serializable
data class ProviderFailureDto(
    val provider: String,
    val operation: String,
    val code: String,
    val message: String,
    val timedOut: Boolean = false
)

@Serializable
data class SearchDiagnosticsDto(
    val status: String,
    val results: List<SearchResponseDto>,
    val failures: List<ProviderFailureDto>
)

@Serializable
data class HomeSectionDto(
    val provider: String,
    val name: String,
    val horizontalImages: Boolean,
    val items: List<SearchResponseDto>
)

@Serializable
data class HomeResponseDto(
    val status: String,
    val sections: List<HomeSectionDto>,
    val failures: List<ProviderFailureDto>,
    val hasNext: Boolean = false
)

@Serializable
data class ProviderResultDiagnosticsDto(
    val status: String,
    val failures: List<ProviderFailureDto> = emptyList()
)

@Serializable
data class LoadResponseDto(
    val name: String,
    val url: String,
    val apiName: String,
    val type: String,
    val posterUrl: String?,
    val year: Int?,
    val plot: String?,
    val score: Double?,
    val tags: List<String>?,
    val duration: Int?,
    val trailers: List<TrailerDto>,
    val episodes: List<EpisodeDto>,
    val capabilities: ProviderCapabilitiesDto,
    val diagnostics: ProviderResultDiagnosticsDto
)

@Serializable
data class TrailerDto(
    val name: String,
    val url: String
)

@Serializable
data class EpisodeDto(
    val name: String?,
    val url: String,
    val episode: Int?,
    val season: Int?,
    val rating: Int?,
    val posterUrl: String?,
    val plot: String?,
    val date: String?
)

@Serializable
data class LinkRequest(
    val data: String,
    val provider: String
)

@Serializable
data class LinkResponse(
    val links: List<ExtractorLink>,
    val subtitles: List<SubtitleDto>,
    val capabilities: ProviderCapabilitiesDto,
    val diagnostics: ProviderResultDiagnosticsDto
)

@Serializable
data class SubtitleDto(
    val lang: String,
    val url: String,
    val langTag: String?,
    val headers: Map<String, String>?
)

@Serializable
data class PluginToggleRequest(val enabled: Boolean)

@Serializable
data class ChallengeStartRequest(val url: String, val userAgent: String? = null)

@Serializable
data class ChallengeClickRequest(val x: Double, val y: Double)

@Serializable
data class ChallengeTypeRequest(val text: String)

@Serializable
data class DownloadRequest(
    val id: String,
    val title: String,
    val url: String
)

@Serializable
data class BookmarkRequest(
    val id: String,
    val name: String,
    val url: String,
    val apiName: String,
    val posterUrl: String? = null,
    val type: String? = null
)

@Serializable
data class WatchProgressRequest(
    val id: String,
    val parentId: String? = null,
    val episodeNum: Int? = null,
    val seasonNum: Int? = null,
    val positionMs: Long,
    val durationMs: Long,
    val title: String? = null,
    val posterUrl: String? = null,
    val provider: String? = null,
    val plot: String? = null,
    val type: String? = null,
    val year: Int? = null,
    val score: Double? = null
)

private fun MainAPI.capabilities() = ProviderCapabilitiesDto(
    instantLinkLoading = instantLinkLoading,
    hasChromecastSupport = hasChromecastSupport,
    hasDownloadSupport = hasDownloadSupport,
    usesWebView = usesWebView,
    hasMainPage = hasMainPage,
    hasQuickSearch = hasQuickSearch,
    sequentialMainPage = sequentialMainPage,
    supportedSyncNames = supportedSyncNames.map { it.name },
    supportedTypes = supportedTypes.map { it.name },
    vpnStatus = vpnStatus.name,
    providerType = providerType.name,
    loadLinksTimeoutMs = loadLinksTimeoutMs,
    getMainPageTimeoutMs = getMainPageTimeoutMs,
    searchTimeoutMs = searchTimeoutMs,
    quickSearchTimeoutMs = quickSearchTimeoutMs,
    loadTimeoutMs = loadTimeoutMs
)

private fun resultStatus(resultCount: Int, failureCount: Int): String = when {
    failureCount == 0 && resultCount == 0 -> "empty"
    failureCount == 0 -> "success"
    resultCount == 0 -> "failed"
    else -> "partial"
}

private fun Throwable.failureMessage(): String = message?.takeIf { it.isNotBlank() } ?: this::class.simpleName.orEmpty()

private fun Throwable.failureCode(): String {
    val text = failureMessage()
    return if (text.contains("cloudflare", true) || text.contains("challenge", true) || text.contains("BottomSheetDialogFragment", true) || text.contains("WebView", true) || text.contains("Main dispatcher is missing", true)) {
        "CHALLENGE_REQUIRED"
    } else {
        "PROVIDER_ERROR"
    }
}

private fun isSafeChallengeUrl(rawUrl: String): Boolean = try {
    val parsed = URL(rawUrl)
    if (parsed.protocol !in setOf("http", "https") || parsed.userInfo != null) return false
    val host = parsed.host.lowercase()
    if (host == "localhost" || host.endsWith(".local") || host == "0.0.0.0") return false
    InetAddress.getAllByName(host).all { address ->
        !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isSiteLocalAddress
    }
} catch (_: Throwable) {
    false
}

@Serializable
data class SearchProviderResult(
    val provider: String,
    val results: List<SearchResponseDto>,
    val failure: ProviderFailureDto?
)

private fun com.lagradost.cloudstream3.SearchResponse.toDto() = SearchResponseDto(
    name = name,
    url = url,
    apiName = apiName,
    type = type?.name,
    posterUrl = posterUrl,
    id = id,
    quality = quality?.name,
    score = score?.toDouble()
)

fun main() {
    val port = System.getenv("CS_PORT")?.toIntOrNull() ?: 2106
    
    // Initialize context and DB directories
    ServerContext.init()
    DatabaseHelper.init(ServerContext.dbFile)
    
    // Initialize per-domain cookie jar with file persistence
    ChallengeCookieStore.init(ServerContext.configDir)
    
    // Wire CloudflareKillerJvm interceptor into the app's HTTP client
    // This auto-detects Cloudflare challenges and solves them per-domain
    com.lagradost.cloudstream3.app.baseClient = com.lagradost.cloudstream3.app.baseClient.newBuilder()
        .addInterceptor(CloudflareKillerJvm())
        .build()
    
    Log.i("Application", "CookieJarManager initialized, CloudflareKillerJvm interceptor wired")
    
    // Initialise all built-in providers
    APIHolder.initAll()
    
    // Load dynamic plugins
    ServerPluginManager.loadAllPlugins(ServerContext.pluginsDir)
    
    // Start proactive cookie warmup in background
    startCookieWarmup()
    
    Log.i("Application", "Starting server on port $port...")
    embeddedServer(Netty, port = port) {
        module()
    }.start(wait = true)
}

/**
 * Background thread that proactively re-solves Cloudflare challenges
 * before cookies expire. Runs every 5 minutes.
 *
 * For each stored cookie entry that is >50% through its TTL and has
 * a sourceUrl, triggers a re-solve so fresh cookies are ready before
 * the old ones expire. This avoids 403 errors during regular requests.
 */
private fun startCookieWarmup() {
    thread(name = "cookie-warmup", isDaemon = true) {
        val checkIntervalMs = 5 * 60 * 1000L // every 5 minutes
        while (true) {
            try {
                Thread.sleep(checkIntervalMs)
                val now = System.currentTimeMillis()
                var warmed = 0

                for (host in CookieJarManager.getHosts()) {
                    val entry = CookieJarManager.getEntry(host) ?: continue
                    val age = now - entry.createdAt
                    val ttl = entry.ttlMs
                    // Re-solve if >50% through TTL and we know the source URL
                    if (ttl > 0 && age > ttl / 2 && !entry.sourceUrl.isNullOrBlank()) {
                        Log.i("CookieWarmup", "Proactively re-solving for $host (age=${age/1000}s, TTL=${ttl/1000}s)")
                        if (warmupSolve(host, entry.sourceUrl!!, entry.userAgent)) {
                            warmed++
                        }
                    }
                }

                if (warmed > 0) {
                    Log.i("CookieWarmup", "Proactively warmed $warmed cookie(s)")
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                Log.e("CookieWarmup", "Warmup cycle failed: ${e.message}")
            }
        }
    }
}

/**
 * Solve a Cloudflare challenge for warmup purposes.
 * Direct HTTP to challenge-browser, storing results in CookieJarManager.
 */
private fun warmupSolve(host: String, sourceUrl: String, userAgent: String?): Boolean {
    return try {
        val baseUrl = (System.getenv("CS_CHALLENGE_URL") ?: "http://challenge-browser:3210").trimEnd('/')
        val uaPart = userAgent?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"
        val startPayload = "{\"url\":\"${sourceUrl.replace("\"", "\\\"")}\",\"userAgent\":$uaPart}"

        val connection = URL("$baseUrl/sessions").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(startPayload.encodeToByteArray()) }

        val startBody = connection.inputStream?.bufferedReader()?.use { it.readText() } ?: return false
        val sessionId = Regex("\"id\":\"([^\"]+)").find(startBody)?.groupValues?.get(1) ?: return false

        // Poll for completion (up to 60 seconds)
        val deadline = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000)
            val statusConn = URL("$baseUrl/sessions/$sessionId").openConnection() as HttpURLConnection
            statusConn.connectTimeout = 10_000
            statusConn.readTimeout = 10_000
            val statusBody = statusConn.inputStream?.bufferedReader()?.use { it.readText() } ?: continue

            if (statusBody.contains("\"status\":\"ready\"")) {
                val cookiesConn = URL("$baseUrl/sessions/$sessionId/cookies").openConnection() as HttpURLConnection
                cookiesConn.connectTimeout = 10_000
                cookiesConn.readTimeout = 10_000
                val cookiesJson = cookiesConn.inputStream?.bufferedReader()?.use { it.readText() } ?: return false

                val cookieHeader = Regex("\"name\":\"([^\"]+)\",\"value\":\"([^\"]*)")
                    .findAll(cookiesJson)
                    .joinToString("; ") { "${it.groupValues[1]}=${it.groupValues[2]}" }

                if (cookieHeader.isNotBlank()) {
                    val resolvedUserAgent = Regex("\"userAgent\":\"([^\"]+)")
                        .find(statusBody)?.groupValues?.get(1)
                    CookieJarManager.setCookies(host, cookieHeader, resolvedUserAgent, sourceUrl)
                    return true
                }
                return false
            }
        }
        Log.w("CookieWarmup", "Warmup timed out for $host")
        false
    } catch (e: Exception) {
        Log.e("CookieWarmup", "Warmup failed for $host: ${e.message}")
        false
    }
}

fun Application.module() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        })
    }
    
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.headers.append("Cross-Origin-Opener-Policy", "same-origin")
        call.response.headers.append("Cross-Origin-Embedder-Policy", "credentialless")
        proceed()
    }
    
    install(CORS) {
        anyHost()
        allowHeader("Content-Type")
        allowHeader("Authorization")
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Delete)
        allowMethod(io.ktor.http.HttpMethod.Options)
        allowMethod(io.ktor.http.HttpMethod.Put)
        exposeHeader("X-CloudStream-Result-Status")
        exposeHeader("X-CloudStream-Provider-Failures")
        exposeHeader("X-Video-Codec")
        exposeHeader("X-Audio-Codec")
    }
    
    routing {
        // --- 1. Health Checks ---
        get("/health") {
            call.respond(mapOf("status" to "OK", "version" to "1.0.0"))
        }
        
        // --- 2. Providers ---
        get("/api/v1/providers") {
            val list = APIHolder.allProviders.map {
                ProviderDto(name = it.name, url = it.mainUrl, capabilities = it.capabilities())
            }
            call.respond(list)
        }

        // --- 2.5 Provider homepages ---
        get("/api/v1/home") {
            ChallengeCookieStore.expireIfNeeded()
            val providerName = call.request.queryParameters["provider"]
            val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val providers = if (!providerName.isNullOrBlank()) {
                listOfNotNull(APIHolder.getApiFromNameNull(providerName))
            } else {
                APIHolder.allProviders.filter { it.hasMainPage }
            }
            val writeMutex = kotlinx.coroutines.sync.Mutex()
            call.respondTextWriter(ContentType.Application.Json.withParameter("charset", "utf-8")) {
                coroutineScope {
                    providers.map { api ->
                        async {
                            try {
                                val pageData = api.mainPage.take(12)
                                if (pageData.isNotEmpty()) {
                                    val timeoutMs = api.getMainPageTimeoutMs ?: 8_000L
                                    withTimeoutOrNull(timeoutMs) {
                                        pageData.forEach { requestData ->
                                            try {
                                                val response = api.getMainPage(
                                                    page,
                                                    com.lagradost.cloudstream3.MainPageRequest(
                                                        name = requestData.name,
                                                        data = requestData.data,
                                                        horizontalImages = requestData.horizontalImages
                                                    )
                                                )
                                                val sectionList = response?.items.orEmpty()
                                                sectionList.forEach { list ->
                                                    val section = HomeSectionDto(
                                                        provider = api.name,
                                                        name = list.name,
                                                        horizontalImages = list.isHorizontalImages,
                                                        items = list.list.map { it.toDto() }
                                                    )
                                                    if (section.items.isNotEmpty()) {
                                                        val jsonLine = Json.encodeToString(section)
                                                        writeMutex.withLock {
                                                            write(jsonLine + "\n")
                                                            flush()
                                                        }
                                                    }
                                                }
                                            } catch (e: Throwable) {
                                                // Ignore individual mainpage request failures
                                            }
                                        }
                                    }
                                }
                            } catch (e: Throwable) {
                                // Ignore provider failures
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        // --- 2.6 Quick search ---
        get("/api/v1/quick-search") {
            ChallengeCookieStore.expireIfNeeded()
            val query = call.request.queryParameters["q"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing query parameter 'q'")
            val providerName = call.request.queryParameters["provider"]
            val providers = if (!providerName.isNullOrBlank()) listOfNotNull(APIHolder.getApiFromNameNull(providerName)) else APIHolder.allProviders.filter { it.hasQuickSearch }
            val results = mutableListOf<SearchResponseDto>()
            val failures = mutableListOf<ProviderFailureDto>()
            providers.forEach { api ->
                if (!api.hasQuickSearch) {
                    failures += ProviderFailureDto(api.name, "quick-search", "UNSUPPORTED", "Provider does not implement quick search")
                    return@forEach
                }
                try {
                    val providerResults = withTimeoutOrNull(api.quickSearchTimeoutMs ?: 8_000L) { api.quickSearch(query).orEmpty().map { it.toDto() } }
                    if (providerResults == null) failures += ProviderFailureDto(api.name, "quick-search", "TIMEOUT", "Quick search timed out", true)
                    else results += providerResults
                } catch (e: Throwable) {
                    failures += ProviderFailureDto(api.name, "quick-search", e.failureCode(), e.failureMessage())
                }
            }
            call.respond(SearchDiagnosticsDto(resultStatus(results.size, failures.size), results.distinctBy { it.url }.take(200), failures))
        }
        
        // --- 3. Search ---
        get("/api/v1/search") {
            ChallengeCookieStore.expireIfNeeded()
            val query = call.request.queryParameters["q"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing query parameter 'q'")
            val providerName = call.request.queryParameters["provider"]
            
            val apisToSearch = if (!providerName.isNullOrBlank()) {
                val api = APIHolder.getApiFromNameNull(providerName)
                if (api == null) {
                    call.respond(HttpStatusCode.NotFound, "Provider '$providerName' not found")
                    return@get
                }
                listOf(api)
            } else {
                APIHolder.allProviders
            }
            
            val writeMutex = kotlinx.coroutines.sync.Mutex()
            call.respondTextWriter(ContentType.Application.Json.withParameter("charset", "utf-8")) {
                coroutineScope {
                    apisToSearch.map { api ->
                        async {
                            try {
                                val providerResults = withTimeoutOrNull(api.searchTimeoutMs ?: 8_000L) {
                                    val searchRes = try {
                                        api.search(query, 1)?.items
                                    } catch (innerE: Throwable) {
                                        Log.w("Search", "api.search(query, 1) failed for ${api.name}: $innerE")
                                        innerE.printStackTrace()
                                        if (innerE is NotImplementedError || innerE is LinkageError || innerE.message?.contains("not implemented", ignoreCase = true) == true) {
                                            api.search(query)
                                        } else {
                                            throw innerE
                                        }
                                    }
                                    searchRes?.map { response ->
                                        SearchResponseDto(
                                            name = response.name,
                                            url = response.url,
                                            apiName = response.apiName,
                                            type = response.type?.name,
                                            posterUrl = response.posterUrl,
                                            id = response.id,
                                            quality = response.quality?.name,
                                            score = response.score?.toDouble()
                                        )
                                    } ?: emptyList()
                                }
                                val result = if (providerResults == null) {
                                    SearchProviderResult(
                                        api.name,
                                        emptyList(),
                                        ProviderFailureDto(api.name, "search", "TIMEOUT", "Provider search timed out", true)
                                    )
                                } else {
                                    if (api.name.equals("AnimePahe", ignoreCase = true) && providerResults.isEmpty()) {
                                        SearchProviderResult(
                                            api.name,
                                            emptyList(),
                                            ProviderFailureDto(api.name, "search", "CHALLENGE_REQUIRED", "Provider returned no results after browser verification was required")
                                        )
                                    } else {
                                        SearchProviderResult(api.name, providerResults, null)
                                    }
                                }
                                val jsonLine = Json.encodeToString(result)
                                writeMutex.withLock {
                                    write(jsonLine + "\n")
                                    flush()
                                }
                            } catch (e: Throwable) {
                                val isNotImplemented = e is NotImplementedError || e is LinkageError || e.message?.contains("not implemented", ignoreCase = true) == true
                                val result = if (isNotImplemented) {
                                    Log.w("Search", "Provider ${api.name} search not implemented fallback triggered: $e")
                                    e.printStackTrace()
                                    if (api.hasMainPage) {
                                        try {
                                            val fallback = withTimeoutOrNull(api.getMainPageTimeoutMs ?: 8_000L) {
                                                api.mainPage.take(12).flatMap { requestData ->
                                                    api.getMainPage(
                                                        1,
                                                        com.lagradost.cloudstream3.MainPageRequest(requestData.name, requestData.data, requestData.horizontalImages)
                                                    )?.items.orEmpty().flatMap { it.list }
                                                }.filter { query.equals("popular", true) || it.name.contains(query, true) }.map { it.toDto() }
                                            }
                                            SearchProviderResult(
                                                api.name,
                                                fallback.orEmpty(),
                                                if (fallback == null) ProviderFailureDto(api.name, "search", "TIMEOUT", "Homepage fallback timed out", true) else null
                                            )
                                        } catch (fallbackError: Throwable) {
                                            Log.w("Search", "Fallback main page for ${api.name} failed: $fallbackError")
                                            SearchProviderResult(api.name, emptyList(), ProviderFailureDto(api.name, "search", "UNSUPPORTED", "Provider search is not implemented"))
                                        }
                                    } else if (api.hasQuickSearch) {
                                        try {
                                            val fallback = withTimeoutOrNull(api.quickSearchTimeoutMs ?: 8_000L) {
                                                api.quickSearch(query).orEmpty().map { it.toDto() }
                                            }
                                            SearchProviderResult(
                                                api.name,
                                                fallback.orEmpty(),
                                                if (fallback == null) ProviderFailureDto(api.name, "search", "TIMEOUT", "Fallback quick search timed out", true) else null
                                            )
                                        } catch (fallbackError: Throwable) {
                                            SearchProviderResult(api.name, emptyList(), ProviderFailureDto(api.name, "search", "UNSUPPORTED", "Search and quick search are unavailable"))
                                        }
                                    } else {
                                        SearchProviderResult(api.name, emptyList(), ProviderFailureDto(api.name, "search", "UNSUPPORTED", "Provider search is not implemented"))
                                    }
                                } else {
                                    val code = e.failureCode()
                                    if (code == "CHALLENGE_REQUIRED") {
                                        Log.w("Search", "Provider ${api.name} unavailable in headless mode: ${e.failureMessage()}")
                                    } else {
                                        Log.e("Search", "Error searching ${api.name}: ${e.failureMessage()}")
                                    }
                                    SearchProviderResult(api.name, emptyList(), ProviderFailureDto(api.name, "search", code, e.failureMessage()))
                                }
                                val jsonLine = Json.encodeToString(result)
                                writeMutex.withLock {
                                    write(jsonLine + "\n")
                                    flush()
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        }
        
        // --- 4. Load Metadata & Episodes ---
        get("/api/v1/load") {
            val url = call.request.queryParameters["url"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing query parameter 'url'")
            val providerName = call.request.queryParameters["provider"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing query parameter 'provider'")
            
            val provider = APIHolder.getApiFromNameNull(providerName)
            if (provider == null) {
                call.respond(HttpStatusCode.NotFound, "Provider '$providerName' not found")
                return@get
            }
            
            var loadFailure: ProviderFailureDto? = null
            val details = try {
                withTimeoutOrNull(provider.loadTimeoutMs ?: 8_000L) {
                    provider.load(url)
                } ?: run {
                    loadFailure = ProviderFailureDto(provider.name, "load", "TIMEOUT", "Provider load timed out", true)
                    null
                }
            } catch (e: Throwable) {
                Log.e("Load", "Error loading details for $url: ${e.message}")
                loadFailure = ProviderFailureDto(provider.name, "load", e.failureCode(), e.failureMessage())
                null
            }
            
            if (details == null) {
                call.response.headers.append("X-CloudStream-Result-Status", "failed")
                call.respond(HttpStatusCode.BadGateway, loadFailure ?: ProviderFailureDto(
                    provider.name,
                    "load",
                    "EMPTY_RESULT",
                    "Provider returned no metadata"
                ))
                return@get
            }
            
            val episodesList = when (val res = details) {
                is TvSeriesLoadResponse -> res.episodes.map { ep ->
                    EpisodeDto(
                        name = ep.name,
                        url = ep.data,
                        episode = ep.episode,
                        season = ep.season,
                        rating = ep.score?.toInt(100),
                        posterUrl = ep.posterUrl,
                        plot = ep.description,
                        date = ep.date?.toString()
                    )
                }
                is AnimeLoadResponse -> res.episodes.flatMap { (dubStatus, list) ->
                    list.map { ep ->
                        EpisodeDto(
                            name = ep.name ?: "${res.name} - Ep ${ep.episode} (${dubStatus.name})",
                            url = ep.data,
                            episode = ep.episode,
                            season = ep.season,
                            rating = ep.score?.toInt(100),
                            posterUrl = ep.posterUrl,
                            plot = ep.description,
                            date = ep.date?.toString()
                        )
                    }
                }
                is MovieLoadResponse -> listOf(
                    EpisodeDto(
                        name = res.name,
                        url = res.dataUrl,
                        episode = 1,
                        season = 1,
                        rating = res.score?.toInt(100),
                        posterUrl = res.posterUrl,
                        plot = res.plot,
                        date = null
                    )
                )
                is TorrentLoadResponse -> listOf(
                    EpisodeDto(
                        name = res.name,
                        url = res.magnet ?: res.torrent ?: res.url,
                        episode = 1,
                        season = 1,
                        rating = res.score?.toInt(100),
                        posterUrl = res.posterUrl,
                        plot = res.plot,
                        date = null
                    )
                )
                else -> listOf(
                    EpisodeDto(
                        name = res.name,
                        url = res.url,
                        episode = 1,
                        season = 1,
                        rating = res.score?.toInt(100),
                        posterUrl = res.posterUrl,
                        plot = res.plot,
                        date = null
                    )
                )
            }
            
            val dto = LoadResponseDto(
                name = details.name,
                url = details.url,
                apiName = details.apiName,
                type = details.type.name,
                posterUrl = details.posterUrl,
                year = details.year,
                plot = details.plot,
                score = details.score?.toDouble(),
                tags = details.tags,
                duration = details.duration,
                trailers = details.trailers.map { TrailerDto("Trailer", it.extractorUrl) },
                episodes = episodesList,
                capabilities = provider.capabilities(),
                diagnostics = ProviderResultDiagnosticsDto("success")
            )
            
            call.response.headers.append("X-CloudStream-Result-Status", "success")
            call.respond(dto)
        }
        
        // --- 5. Resolve Direct Streaming Links ---
        post("/api/v1/links") {
            val req = call.receive<LinkRequest>()
            Log.i("Links", "Resolving links for provider=${req.provider}, data=${req.data.take(180)}")
            val provider = APIHolder.getApiFromNameNull(req.provider)
            if (provider == null) {
                call.respond(HttpStatusCode.NotFound, "Provider '${req.provider}' not found")
                return@post
            }
            
            val linksList = Collections.synchronizedList(mutableListOf<ExtractorLink>())
            val subtitlesList = Collections.synchronizedList(mutableListOf<SubtitleDto>())
            var failure: ProviderFailureDto? = null
            
            try {
                val completed = withTimeoutOrNull(provider.loadLinksTimeoutMs ?: 120_000L) {
                    provider.loadLinks(
                    data = req.data,
                    isCasting = false,
                    subtitleCallback = { sub ->
                        subtitlesList.add(
                            SubtitleDto(
                                lang = sub.lang,
                                url = sub.url,
                                langTag = sub.langTag,
                                headers = sub.headers
                            )
                        )
                    },
                    callback = { link ->
                        linksList.add(link)
                    }
                    )
                }
                if (completed == null) {
                    failure = ProviderFailureDto(provider.name, "links", "TIMEOUT", "Provider link loading timed out", true)
                } else if (!completed && linksList.isEmpty() && subtitlesList.isEmpty()) {
                    failure = ProviderFailureDto(provider.name, "links", "PROVIDER_ERROR", "Provider did not resolve any links")
                }
            } catch (e: Throwable) {
                Log.e("Links", "Error loading links for ${req.data}: ${e.message}")
                failure = ProviderFailureDto(provider.name, "links", e.failureCode(), e.failureMessage())
            }

            Log.i("Links", "Resolved ${linksList.size} links and ${subtitlesList.size} subtitles for provider=${req.provider}")
            
            val links = linksList.toList()
            val subtitles = subtitlesList.toList()
            val status = resultStatus(links.size + subtitles.size, if (failure == null) 0 else 1)
            call.response.headers.append("X-CloudStream-Result-Status", status)
            failure?.let {
                call.response.headers.append("X-CloudStream-Provider-Failures", Json.encodeToString(listOf(it)))
            }
            call.respond(
                LinkResponse(
                    links = links,
                    subtitles = subtitles,
                    capabilities = provider.capabilities(),
                    diagnostics = ProviderResultDiagnosticsDto(
                        status = status,
                        failures = listOfNotNull(failure)
                    )
                )
            )
        }
        
        // --- 6. Streaming HTTP range CORS Proxy ---
        get("/api/v1/proxy") {
            val targetUrl = call.request.queryParameters["url"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing parameter 'url'")
            val referer = call.request.queryParameters["referer"]
            val range = call.request.headers["Range"]
            Log.i("Proxy", "Range request: url=${targetUrl.take(80)}..., referer=$referer, range=$range")
            
            val url = URL(targetUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 60_000
            
            // Forward HTTP Range request header if client requested bytes range
            if (range != null) {
                connection.setRequestProperty("Range", range)
            }
            if (referer != null) {
                connection.setRequestProperty("Referer", referer)
            }
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            
            connection.connect()
            
            val status = connection.responseCode
            val rawContentType = connection.contentType ?: "video/mp4"
            val targetPath = targetUrl.substringBefore("?").lowercase()
            val contentType = if (!rawContentType.startsWith("image/", ignoreCase = true) && 
                                  (targetPath.endsWith(".xls") || targetPath.endsWith(".ts"))) {
                "video/mp2t"
            } else {
                rawContentType
            }

            if (status !in 200..299 && status != HttpURLConnection.HTTP_PARTIAL) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText().take(500) }.orEmpty()
                Log.w("Proxy", "Upstream returned HTTP $status for host=${url.host}, type=$contentType")
                call.respondText(
                    "Upstream returned HTTP $status${if (errorBody.isNotBlank()) ": $errorBody" else ""}",
                    status = HttpStatusCode.fromValue(status)
                )
                return@get
            }

            val isM3u8 = contentType.contains("mpegurl", ignoreCase = true) || 
                         contentType.contains("x-mpegurl", ignoreCase = true) ||
                         targetUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true) ||
                         targetUrl.contains("m3u8", ignoreCase = true)
            
            call.response.status(HttpStatusCode.fromValue(status))
            call.response.headers.append("Content-Type", contentType)
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.response.headers.append("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
            call.response.headers.append("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges, Content-Type, X-Video-Codec, X-Audio-Codec")
            call.response.headers.append("Accept-Ranges", "bytes")
            call.response.headers.append("Cross-Origin-Resource-Policy", "cross-origin")
            
            connection.headerFields.forEach { (key, value) ->
                if (key != null) {
                    if (key.equals("Content-Range", ignoreCase = true) || 
                        (!isM3u8 && key.equals("Content-Length", ignoreCase = true))) {
                        call.response.headers.append(key, value.joinToString(","))
                    }
                }
            }
            
            if (isM3u8) {
                val m3u8Content = connection.inputStream.use { it.bufferedReader().readText() }
                val baseUri = try { java.net.URI(targetUrl) } catch (e: Exception) { null }
                val refererParam = if (referer != null) "&referer=${java.net.URLEncoder.encode(referer, "UTF-8")}" else ""
                
                val rewrittenContent = m3u8Content.lineSequence().map { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        line
                    } else {
                        val absoluteUrl = if (baseUri != null) {
                            try {
                                baseUri.resolve(trimmed).toString()
                            } catch (e: Exception) {
                                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                                    trimmed
                                } else {
                                    val baseWithoutPath = targetUrl.substringBeforeLast("/")
                                    "$baseWithoutPath/$trimmed"
                                }
                            }
                        } else {
                            trimmed
                        }
                        "/api/v1/proxy?url=${java.net.URLEncoder.encode(absoluteUrl, "UTF-8")}$refererParam"
                    }
                }.joinToString("\n")
                
                val bytes = rewrittenContent.toByteArray(Charsets.UTF_8)
                call.response.headers.append("Content-Length", bytes.size.toString())
                call.respondBytes(bytes, ContentType.parse(contentType), HttpStatusCode.fromValue(status))
            } else {
                call.respondOutputStream(ContentType.parse(contentType), HttpStatusCode.fromValue(status)) {
                    connection.inputStream.use { input ->
                        input.copyTo(this)
                    }
                }
            }
        }

        // --- 6.1. Smart Backend Transcoding/Remuxing Route ---
        get("/api/v1/transcode") {
            val requestedUrl = call.request.queryParameters["url"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing url")
            Log.i("Transcode", "Received request: url=${requestedUrl.take(80)}...")
            val referer = call.request.queryParameters["referer"] ?: ""

            // We try two URLs in order:
            // 1. Direct upstream: if the URL wraps our proxy, extract the inner URL
            // 2. Local proxy: fallback that benefits from whatever auth/cookies the
            //    proxy endpoint already handles (Gofile etc.)
            var directUrl: String? = null
            if (requestedUrl.startsWith("/api/v1/proxy") || requestedUrl.contains("/api/v1/proxy")) {
                try {
                    val parsed = URI(requestedUrl)
                    val queryMap = parsed.query?.split("&")?.mapNotNull {
                        val parts = it.split("=", limit = 2)
                        if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8") else null
                    }?.toMap() ?: emptyMap()
                    val innerUrl = queryMap["url"]
                    if (innerUrl != null) {
                        directUrl = innerUrl
                        Log.i("Transcode", "Extracted direct upstream URL: ${directUrl.take(80)}...")
                    }
                } catch (e: Exception) {
                    Log.w("Transcode", "Failed to parse proxy URL: ${e.message}")
                }
            }
            val proxyUrl = if (requestedUrl.startsWith("/")) {
                "http://127.0.0.1:${System.getenv("CS_PORT")?.toIntOrNull() ?: 2106}$requestedUrl"
            } else {
                "http://127.0.0.1:${System.getenv("CS_PORT")?.toIntOrNull() ?: 2106}/api/v1/proxy?url=${java.net.URLEncoder.encode(requestedUrl, "UTF-8")}&referer=${java.net.URLEncoder.encode(referer, "UTF-8")}"
            }

            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            val extraHeaders = if (referer.isNotBlank()) "Referer: $referer\r\n" else ""

            // Probe headers always include a Range to avoid servers that return HTML
            // for full (non-range) requests (Googleusercontent, Gofile, etc.)
            // 1MB is enough for codec identification in all standard containers.
            val probeHeaders = extraHeaders + "Range: bytes=0-1048576\r\n"

            // Parse client capabilities (defaulting to safe baseline codecs if missing)
            val supportedVideos = call.request.queryParameters["supportedVideoCodecs"]
                ?.split(",")?.map { it.trim().lowercase() }?.toSet() ?: setOf("h264")
            val supportedAudios = call.request.queryParameters["supportedAudioCodecs"]
                ?.split(",")?.map { it.trim().lowercase() }?.toSet() ?: setOf("aac", "mp3")

            // Probe stream codecs — try direct first, fallback to local proxy
            fun probe(url: String, label: String): List<ProbeStream>? {
                val cmd = mutableListOf("ffprobe", "-v", "quiet", "-print_format", "json", "-show_streams", "-user_agent", userAgent)
                cmd.addAll(listOf("-headers", probeHeaders))
                cmd.add(url)
                return try {
                    val p = ProcessBuilder(cmd).start()
                    val out = p.inputStream.bufferedReader().use { it.readText() }
                    p.waitFor()
                    val json = Json { ignoreUnknownKeys = true }
                    val result = json.decodeFromString<ProbeResult>(out)
                    val vc = result.streams?.find { it.codec_type == "video" }?.codec_name?.lowercase()
                    val ac = result.streams?.find { it.codec_type == "audio" }?.codec_name?.lowercase()
                    Log.i("Transcode", "$label probed — video: $vc, audio: $ac")
                    result.streams
                } catch (e: Throwable) {
                    Log.w("Transcode", "$label probe failed: ${e.message}")
                    null
                }
            }

            // Walk probe URLs: inner direct → original direct → local proxy
            val probeUrls = listOfNotNull(
                directUrl?.let { it to "Direct(inner)" },
                requestedUrl.takeIf { it.startsWith("http") }?.let { it to "Direct(original)" },
                proxyUrl to "Proxy"
            )
            var chosenUrl: String? = null
            var videoCodec: String? = null
            var audioCodec: String? = null
            var videoCodecStr: String? = null
            var audioCodecStr: String? = null
            for ((probeUrl, label) in probeUrls) {
                val streams = probe(probeUrl, label)
                val videoStream = streams?.find { it.codec_type == "video" }
                val audioStream = streams?.find { it.codec_type == "audio" }
                videoCodec = videoStream?.codec_name?.lowercase()
                audioCodec = audioStream?.codec_name?.lowercase()
                videoCodecStr = videoStream?.toIsoCodecString()
                audioCodecStr = audioStream?.toIsoCodecString()
                if (videoCodec != null || audioCodec != null) {
                    chosenUrl = probeUrl
                    break
                }
            }

            val command = mutableListOf<String>()
            command.addAll(listOf("ffmpeg", "-hide_banner", "-loglevel", "warning"))

            if (videoCodec == null && audioCodec == null) {
                Log.w("Transcode", "ffprobe failed on all URLs — trying blind transcode with full re-encode")
                val probeUrl = chosenUrl ?: proxyUrl
                command.addAll(listOf("-user_agent", userAgent, "-i", probeUrl))
                val hasVaapi = java.io.File("/dev/dri/renderD128").exists()
                if (hasVaapi) {
                    Log.i("Transcode", "Blind route: VAAPI h264 + AAC")
                    command.addAll(listOf("-c:v", "h264_vaapi", "-c:a", "aac", "-b:a", "128k"))
                } else {
                    Log.i("Transcode", "Blind route: Software libx264 + AAC")
                    command.addAll(listOf("-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-b:a", "128k"))
                }
            } else {
                Log.i("Transcode", "Probed codecs - Video: $videoCodec, Audio: $audioCodec")
                Log.i("Transcode", "Client support - Videos: $supportedVideos, Audios: $supportedAudios")

                val browserSupportsVideo = videoCodec != null && supportedVideos.contains(videoCodec)
                val browserSupportsAudio = audioCodec != null && supportedAudios.contains(audioCodec)

                val transcodeVideo = !browserSupportsVideo
                val transcodeAudio = !browserSupportsAudio

                Log.i("Transcode", "Decided pathway - Transcode Video: $transcodeVideo, Transcode Audio: $transcodeAudio")

                if (transcodeVideo) {
                    val hasVaapi = java.io.File("/dev/dri/renderD128").exists()
                    if (hasVaapi) {
                        Log.i("Transcode", "VAAPI Hardware Acceleration is AVAILABLE. Enabling VAAPI.")
                        command.addAll(listOf("-hwaccel", "vaapi", "-hwaccel_device", "/dev/dri/renderD128", "-hwaccel_output_format", "vaapi"))
                    } else {
                        Log.i("Transcode", "VAAPI Hardware Acceleration is UNAVAILABLE. Using software encoding.")
                    }
                }

                if (extraHeaders.isNotBlank()) {
                    command.addAll(listOf("-headers", extraHeaders))
                }
                command.addAll(listOf("-user_agent", userAgent, "-i", chosenUrl!!))

                if (transcodeVideo) {
                    val hasVaapi = java.io.File("/dev/dri/renderD128").exists()
                    if (hasVaapi) {
                        command.addAll(listOf("-c:v", "h264_vaapi"))
                    } else {
                        command.addAll(listOf("-c:v", "libx264", "-preset", "ultrafast"))
                    }
                } else {
                    command.addAll(listOf("-c:v", "copy"))
                }

                if (transcodeAudio) {
                    command.addAll(listOf("-c:a", "aac", "-b:a", "128k"))
                } else {
                    command.addAll(listOf("-c:a", "copy"))
                }
            }

            command.addAll(listOf(
                "-movflags", "+cmaf+delay_moov",
                "-f", "mp4",
                "pipe:1"
            ))

            Log.i("Transcode", "Executing FFmpeg: ${command.joinToString(" ")}")
            val process = ProcessBuilder(command).start()

            // Capture ffmpeg stderr in background to surface WARNING logs
            thread(name = "ffmpeg-stderr") {
                try {
                    process.errorStream.bufferedReader().use { reader ->
                        reader.lines().forEach { line ->
                            Log.w("FFmpeg", line)
                        }
                    }
                } catch (_: Throwable) {}
            }

            call.response.header("Content-Type", "video/mp4")
            call.response.header("Cross-Origin-Resource-Policy", "cross-origin")
            if (videoCodecStr != null) call.response.header("X-Video-Codec", videoCodecStr)
            if (audioCodecStr != null) call.response.header("X-Audio-Codec", audioCodecStr)
            
            try {
                call.respondOutputStream(ContentType.parse("video/mp4")) {
                    val buffer = ByteArray(8192)
                    val input = process.inputStream
                    val firstByteDeadline = System.currentTimeMillis() + 30_000L
                    var gotFirstByte = false
                    var idleSince = System.currentTimeMillis()
                    while (true) {
                        if (!process.isAlive && input.available() == 0) {
                            break
                        }
                        if (input.available() > 0) {
                            val bytes = input.read(buffer)
                            if (bytes == -1) break
                            if (!gotFirstByte) {
                                gotFirstByte = true
                                Log.i("Transcode", "First byte sent to client")
                            }
                            idleSince = System.currentTimeMillis()
                            this.write(buffer, 0, bytes)
                            this.flush()
                        } else {
                            if (!gotFirstByte) {
                                if (System.currentTimeMillis() > firstByteDeadline) {
                                    Log.w("Transcode", "No first byte from ffmpeg within 30s — terminating")
                                    process.destroy()
                                    break
                                }
                            } else {
                                if (System.currentTimeMillis() - idleSince > 60_000L) {
                                    Log.w("Transcode", "No data from ffmpeg for 60s — terminating")
                                    process.destroy()
                                    break
                                }
                            }
                            Thread.sleep(100)
                        }
                    }
                }
            } finally {
                process.destroy()
            }
        }
        
        // --- 7. Bookmarks ---
        get("/api/v1/bookmarks") {
            call.respond(DatabaseHelper.getBookmarks())
        }
        
        post("/api/v1/bookmarks") {
            val req = call.receive<BookmarkRequest>()
            DatabaseHelper.addBookmark(
                id = req.id,
                name = req.name,
                url = req.url,
                apiName = req.apiName,
                posterUrl = req.posterUrl,
                type = req.type
            )
            call.respond(mapOf("success" to true))
        }
        
        delete("/api/v1/bookmarks/{id}") {
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing path parameter 'id'")
            DatabaseHelper.removeBookmark(id)
            call.respond(mapOf("success" to true))
        }
        
        // --- 8. Watch History ---
        get("/api/v1/history") {
            call.respond(DatabaseHelper.getAllWatchHistory())
        }
        
        post("/api/v1/history") {
            val req = call.receive<WatchProgressRequest>()
            DatabaseHelper.saveWatchProgress(
                id = req.id,
                parentId = req.parentId,
                episodeNum = req.episodeNum,
                seasonNum = req.seasonNum,
                positionMs = req.positionMs,
                durationMs = req.durationMs,
                title = req.title,
                posterUrl = req.posterUrl,
                provider = req.provider,
                plot = req.plot,
                type = req.type,
                year = req.year,
                score = req.score
            )
            call.respond(mapOf("success" to true))
        }

        delete("/api/v1/history/{id}") {
            val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing path parameter 'id'")
            DatabaseHelper.removeWatchProgress(id)
            call.respond(mapOf("success" to true))
        }

        // --- 8.5 Server Downloads ---
        get("/api/v1/downloads") {
            call.respond(DatabaseHelper.getDownloads())
        }

        post("/api/v1/downloads") {
            val req = call.receive<DownloadRequest>()
            ServerDownloadManager.startDownload(req.id, req.title, req.url)
            call.respond(mapOf("success" to true))
        }

        delete("/api/v1/downloads") {
            val id = call.request.queryParameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing query parameter 'id'")
            ServerDownloadManager.cancelDownload(id)
            call.respond(mapOf("success" to true))
        }
        
        // --- 9. Dynamic Plugin Management ---
        // --- 8.8 Browser challenge sessions ---
        get("/api/v1/challenges") {
            val response = ChallengeClient.request("GET", "/sessions")
            call.respondText(response.body.decodeToString(), ContentType.Application.Json, HttpStatusCode.fromValue(response.status))
        }

        post("/api/v1/challenges") {
            val request = call.receive<ChallengeStartRequest>()
            if (!isSafeChallengeUrl(request.url)) {
                call.respond(HttpStatusCode.BadRequest, "Challenge URL must be a public HTTP(S) URL")
                return@post
            }
            val body = Json.encodeToString(request).encodeToByteArray()
            val response = ChallengeClient.request("POST", "/sessions", body)
            call.respondText(response.body.decodeToString(), ContentType.Application.Json, HttpStatusCode.fromValue(response.status))
        }

        get("/api/v1/challenges/{id}") {
            ChallengeCookieStore.expireIfNeeded()
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing challenge id")
            val response = ChallengeClient.request("GET", "/sessions/$id")
            call.respondText(response.body.decodeToString(), ContentType.Application.Json, HttpStatusCode.fromValue(response.status))
        }

        get("/api/v1/challenges/{id}/screenshot") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing challenge id")
            val response = ChallengeClient.request("GET", "/sessions/$id/screenshot")
            call.respondBytes(response.body, ContentType.Image.PNG, HttpStatusCode.fromValue(response.status))
        }

        post("/api/v1/challenges/{id}/click") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing challenge id")
            val request = call.receive<ChallengeClickRequest>()
            val response = ChallengeClient.request("POST", "/sessions/$id/click", Json.encodeToString(request).encodeToByteArray())
            call.respondText(response.body.decodeToString(), ContentType.Application.Json, HttpStatusCode.fromValue(response.status))
        }

        post("/api/v1/challenges/{id}/type") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing challenge id")
            val request = call.receive<ChallengeTypeRequest>()
            val response = ChallengeClient.request("POST", "/sessions/$id/type", Json.encodeToString(request).encodeToByteArray())
            call.respondText(response.body.decodeToString(), ContentType.Application.Json, HttpStatusCode.fromValue(response.status))
        }

        post("/api/v1/challenges/{id}/complete") {
            val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing challenge id")
            val response = ChallengeClient.request("POST", "/sessions/$id/complete", "{}".encodeToByteArray())
            if (response.status in 200..299 && response.body.decodeToString().contains("\"status\":\"ready\"")) {
                val cookies = ChallengeClient.request("GET", "/sessions/$id/cookies").body.decodeToString()
                val cookieHeader = Regex("""\"name\":\"([^\"]+)\",\"value\":\"([^\"]*)""").findAll(cookies)
                    .joinToString("; ") { "${it.groupValues[1]}=${it.groupValues[2]}" }
                val responseJson = response.body.decodeToString()
                val userAgent = Regex("""\"userAgent\":\"([^\"]+)""").find(responseJson)
                    ?.groupValues?.get(1)
                val host = Regex("""\"url\":\"https?://([^/\"]+)""").find(responseJson)
                    ?.groupValues?.get(1)
                val sourceUrl = Regex("""\"url\":\"([^\"]+)""").find(responseJson)
                    ?.groupValues?.get(1)
                // Store per-domain with source URL for proactive warmup
                CookieJarManager.setCookies(
                    host = host ?: "unknown",
                    cookieHeader = cookieHeader,
                    userAgent = userAgent,
                    sourceUrl = sourceUrl,
                )

                // Fire pending retry asynchronously so the response to the frontend is not delayed
                if (host != null) {
                    val retryUrl = PendingRetryStore.retrieve(host)
                    if (retryUrl != null) {
                        thread(name = "challenge-retry-$id") {
                            try {
                                val retryResponse = com.lagradost.cloudstream3.app.baseClient.newCall(
                                    okhttp3.Request.Builder().url(retryUrl).get().build()
                                ).execute()
                                val retryBody = retryResponse.body?.string() ?: ""
                                val retryContentType = retryResponse.header("Content-Type") ?: "text/plain"
                                RetryResultStore.store(id, retryResponse.code, retryBody, retryContentType)
                                Log.i("ChallengeRetry", "Retry succeeded for $id ($host)")
                            } catch (e: Exception) {
                                Log.e("ChallengeRetry", "Retry failed for $id ($host): ${e.message}")
                            }
                        }
                    }
                }
            }
            call.respondText(response.body.decodeToString(), ContentType.Application.Json, HttpStatusCode.fromValue(response.status))
        }

        get("/api/v1/challenges/{id}/retry-result") {
            val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing challenge id")
            val result = RetryResultStore.retrieve(id)
            if (result == null) {
                call.respond(HttpStatusCode.NotFound, "No retry result available yet for challenge $id")
                return@get
            }
            call.respondText(result.body, ContentType.Application.Json, HttpStatusCode.fromValue(result.status))
        }

        // --- 8.9 Cookie jar management ---
        /**
         * POST /api/v1/cookies - Share cookies from client (e.g., Android app).
         *
         * Allows the Android app to push cookies it solved locally (via WebView)
         * to the server. This avoids redundant challenge solving when the app
         * has already solved a Cloudflare challenge for a domain.
         *
         * Request body:
         * {
         *   "host": "example.com",
         *   "cookies": "cf_clearance=abc123; ...",
         *   "userAgent": "Mozilla/5.0 ...",
         *   "sourceUrl": "https://example.com/page"
         * }
         */
        @Serializable
        data class CookieShareRequest(
            val host: String,
            val cookies: String,
            val userAgent: String? = null,
            val sourceUrl: String? = null,
        )

        post("/api/v1/cookies") {
            try {
                val req = call.receive<CookieShareRequest>()
                if (req.host.isBlank() || req.cookies.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "host and cookies are required")
                    return@post
                }
                CookieJarManager.setCookies(req.host, req.cookies, req.userAgent, req.sourceUrl)
                Log.i("CookieShare", "Received cookies from client for ${req.host}")
                call.respondText(Gson().toJson(mapOf("success" to true, "message" to "Cookies stored for ${req.host}")), ContentType.Application.Json)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, "Invalid request: ${e.message}")
            }
        }

        get("/api/v1/cookies") {
            val hosts = CookieJarManager.getHosts().map { host ->
                val entry = CookieJarManager.getEntry(host)
                mapOf(
                    "host" to host,
                    "hasCookies" to (entry != null && !entry.isExpired),
                    "createdAt" to (entry?.createdAt ?: 0),
                    "expiresAt" to ((entry?.createdAt ?: 0) + (entry?.ttlMs ?: 0)),
                    "ttlMs" to (entry?.ttlMs ?: 0),
                    "userAgent" to (entry?.userAgent ?: ""),
                )
            }
            val result = mapOf("count" to hosts.size, "entries" to hosts)
            call.respondText(Gson().toJson(result), ContentType.Application.Json)
        }

        delete("/api/v1/cookies") {
            CookieJarManager.clear()
            call.respondText(Gson().toJson(mapOf("success" to true, "message" to "All cookies cleared")), ContentType.Application.Json)
        }

        delete("/api/v1/cookies/{host}") {
            val host = call.parameters["host"] ?: return@delete call.respondText(Gson().toJson(mapOf("error" to "Missing host parameter")), ContentType.Application.Json, HttpStatusCode.BadRequest)
            CookieJarManager.removeCookies(host)
            call.respondText(Gson().toJson(mapOf("success" to true, "message" to "Cookies cleared for $host")), ContentType.Application.Json)
        }

        get("/api/v1/plugins") {
            call.respond(ServerPluginManager.getPluginStatuses(ServerContext.pluginsDir))
        }

        post("/api/v1/plugins/{jarName}/enabled") {
            val jarName = call.parameters["jarName"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing plugin filename")
            try {
                val request = call.receive<PluginToggleRequest>()
                val status = ServerPluginManager.setPluginEnabled(ServerContext.pluginsDir, jarName, request.enabled)
                if (status == null) call.respond(HttpStatusCode.NotFound, "Plugin not found")
                else call.respond(status)
            } catch (e: Throwable) {
                Log.e("Plugins", "Error toggling plugin $jarName: ${e.message}")
                call.respond(HttpStatusCode.BadRequest, e.message ?: "Unable to toggle plugin")
            }
        }
        
        post("/api/v1/plugins/install") {
            try {
                val bytes = call.receive<ByteArray>()
                val tempFile = File(ServerContext.pluginsDir, "uploaded-plugin-${System.currentTimeMillis()}.jar")
                tempFile.writeBytes(bytes)
                
                val info = ServerPluginManager.loadPlugin(tempFile)
                call.respond(mapOf("success" to true, "plugin" to info.manifest))
            } catch (e: Exception) {
                Log.e("Plugins", "Error installing plugin: ${e.message}")
                call.respond(HttpStatusCode.InternalServerError, "Failed to load uploaded plugin: ${e.message}")
            }
        }

        post("/api/v1/log") {
            try {
                val req = call.receive<ClientLogRequest>()
                val prefix = buildString {
                    append("[Client]")
                    if (!req.platform.isNullOrBlank()) append(" [${req.platform}]")
                    if (!req.userAgent.isNullOrBlank()) append(" [${req.userAgent.take(60)}]")
                }
                val msg = "$prefix ${req.message}"
                if (req.level.uppercase() == "WARN" || req.level.uppercase() == "ERROR") {
                    Log.e(req.tag, msg)
                } else {
                    Log.i(req.tag, msg)
                }
            } catch (e: Exception) {
                // Ignore malformed logs
            }
            call.respond(HttpStatusCode.OK)
        }

        // Serve frontend static assets from resources
        staticResources("/", "web", index = "index.html")
    }
}
