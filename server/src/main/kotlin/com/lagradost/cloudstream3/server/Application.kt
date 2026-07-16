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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

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
    val episodes: List<EpisodeDto>
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
    val subtitles: List<SubtitleDto>
)

@Serializable
data class SubtitleDto(
    val lang: String,
    val url: String,
    val langTag: String?,
    val headers: Map<String, String>?
)

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
    val durationMs: Long
)

fun main() {
    val port = System.getenv("CS_PORT")?.toIntOrNull() ?: 2106
    
    // Initialize context and DB directories
    ServerContext.init()
    DatabaseHelper.init(ServerContext.dbFile)
    
    // Initialise all built-in providers
    APIHolder.initAll()
    
    // Load dynamic plugins
    ServerPluginManager.loadAllPlugins(ServerContext.pluginsDir)
    
    Log.i("Application", "Starting server on port $port...")
    embeddedServer(Netty, port = port) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        })
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
    }
    
    routing {
        // --- 1. Health Checks ---
        get("/health") {
            call.respond(mapOf("status" to "OK", "version" to "1.0.0"))
        }
        
        // --- 2. Providers ---
        get("/api/v1/providers") {
            val list = APIHolder.allProviders.map { mapOf("name" to it.name, "url" to it.mainUrl) }
            call.respond(list)
        }
        
        // --- 3. Search ---
        get("/api/v1/search") {
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
            
            val results = coroutineScope {
                apisToSearch.map { api ->
                    async {
                        try {
                            api.search(query)?.map { response ->
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
                        } catch (e: Exception) {
                            Log.e("Search", "Error searching ${api.name}: ${e.message}")
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
            
            call.respond(results)
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
            
            val details = try {
                provider.load(url)
            } catch (e: Exception) {
                Log.e("Load", "Error loading details for $url: ${e.message}")
                null
            }
            
            if (details == null) {
                call.respond(HttpStatusCode.InternalServerError, "Failed to load metadata")
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
                episodes = episodesList
            )
            
            call.respond(dto)
        }
        
        // --- 5. Resolve Direct Streaming Links ---
        post("/api/v1/links") {
            val req = call.receive<LinkRequest>()
            val provider = APIHolder.getApiFromNameNull(req.provider)
            if (provider == null) {
                call.respond(HttpStatusCode.NotFound, "Provider '${req.provider}' not found")
                return@post
            }
            
            val linksList = Collections.synchronizedList(mutableListOf<ExtractorLink>())
            val subtitlesList = Collections.synchronizedList(mutableListOf<SubtitleDto>())
            
            try {
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
            } catch (e: Exception) {
                Log.e("Links", "Error loading links for ${req.data}: ${e.message}")
            }
            
            call.respond(LinkResponse(links = linksList.toList(), subtitles = subtitlesList.toList()))
        }
        
        // --- 6. Streaming HTTP range CORS Proxy ---
        get("/api/v1/proxy") {
            val targetUrl = call.request.queryParameters["url"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing parameter 'url'")
            val referer = call.request.queryParameters["referer"]
            
            val url = URL(targetUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            
            // Forward HTTP Range request header if client requested bytes range
            val range = call.request.headers["Range"]
            if (range != null) {
                connection.setRequestProperty("Range", range)
            }
            if (referer != null) {
                connection.setRequestProperty("Referer", referer)
            }
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            
            connection.connect()
            
            val status = connection.responseCode
            val contentType = connection.contentType ?: "video/mp4"
            val contentLength = connection.contentLengthLong
            
            call.response.status(HttpStatusCode.fromValue(status))
            call.response.headers.append("Content-Type", contentType)
            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.response.headers.append("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
            call.response.headers.append("Accept-Ranges", "bytes")
            
            connection.headerFields.forEach { (key, value) ->
                if (key != null && (key.equals("Content-Range", ignoreCase = true) || key.equals("Content-Length", ignoreCase = true))) {
                    call.response.headers.append(key, value.joinToString(","))
                }
            }
            
            call.respondOutputStream(ContentType.parse(contentType), HttpStatusCode.fromValue(status)) {
                connection.inputStream.use { input ->
                    input.copyTo(this)
                }
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
                durationMs = req.durationMs
            )
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
        get("/api/v1/plugins") {
            call.respond(ServerPluginManager.getLoadedPlugins())
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

        // Serve frontend static assets from resources
        staticResources("/", "web", index = "index.html")
    }
}
