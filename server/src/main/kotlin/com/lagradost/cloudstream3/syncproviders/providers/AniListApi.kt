package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.*
import com.lagradost.cloudstream3.server.storage.DatabaseHelper
import com.lagradost.cloudstream3.ui.SyncWatchType

open class AniListApi : SyncAPI() {
    override var name = "AniList"
    override val idPrefix = "ANILIST"
    override var mainUrl = "https://anilist.co"
    override val syncIdName = SyncIdName.Anilist
    override val requiresLogin = true
    override val redirectUrlIdentifier = "anilistlogin"
    override val hasOAuth2 = true

    override fun loginRequest() = AuthLoginPage("https://anilist.co/api/v2/oauth/authorize?client_id=${System.getenv("ANILIST_CLIENT_ID") ?: ""}&response_type=token")
    override suspend fun login(redirectUrl: String, payload: String?): AuthToken? {
        val values = AuthAPI.splitRedirectUrl(redirectUrl)
        return values["access_token"]?.let { AuthToken(it, accessTokenLifetime = values["expires_in"]?.toLongOrNull()?.plus(System.currentTimeMillis() / 1000)) }
    }

    private fun query(body: String, auth: AuthData? = null) = RemoteSync.post("https://graphql.anilist.co", body, auth?.token?.accessToken?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())

    override suspend fun user(token: AuthToken?): AuthUser? {
        val access = token?.accessToken ?: return null
        val node = query("{\"query\":\"query { Viewer { id name avatar { large } } }\"}", AuthData(AuthUser(null, 0), token))["data"]["Viewer"]
        if (node.isMissingNode || node.isNull) return null
        return AuthUser(node["name"]?.asText(), node["id"]?.asInt() ?: return null, node["avatar"]?.get("large")?.asText())
    }

    override suspend fun status(auth: AuthData?, id: String): AbstractSyncStatus? {
        val user = auth?.user ?: return null
        DatabaseHelper.getSyncStatus(idPrefix, user.id, id)?.let { return SyncStatus(SyncWatchType.fromInternalId(it.status), RemoteSync.score(it.score?.toDouble()), it.watched, it.favorite, it.maxEpisodes) }
        val node = query("{\"query\":\"query { Media(id: $id) { mediaListEntry { status score progress private } } }\"", auth)["data"]["Media"]["mediaListEntry"]
        if (node.isMissingNode || node.isNull) return null
        return SyncStatus(RemoteSync.watchType(node["status"]?.asText()), RemoteSync.score(node["score"]), node["progress"]?.asInt(), node["private"]?.asBoolean())
    }

    override suspend fun updateStatus(auth: AuthData?, id: String, newStatus: AbstractSyncStatus): Boolean {
        val token = auth?.token?.accessToken ?: return false
        val status = when (newStatus.status) { SyncWatchType.WATCHING -> "CURRENT"; SyncWatchType.COMPLETED -> "COMPLETED"; SyncWatchType.ONHOLD -> "PAUSED"; SyncWatchType.DROPPED -> "DROPPED"; SyncWatchType.PLANTOWATCH -> "PLANNING"; else -> "PLANNING" }
        val score = newStatus.score?.toDouble(10)?.toString() ?: "null"
        query("{\"query\":\"mutation { SaveMediaListEntry(mediaId: $id, status: $status, progress: ${newStatus.watchedEpisodes ?: 0}, score: $score) { id } }\"}", AuthData(auth.user, AuthToken(token)))
        DatabaseHelper.saveSyncStatus(idPrefix, auth.user.id, id, newStatus.status.internalId, newStatus.score?.toInt(10), newStatus.watchedEpisodes, newStatus.isFavorite, newStatus.maxEpisodes)
        return true
    }

    override suspend fun search(auth: AuthData?, query: String): List<SyncSearchResult>? {
        val escaped = query.replace("\\", "\\\\").replace("\"", "\\\"")
        val items = RemoteSync.post("https://graphql.anilist.co", "{\"query\":\"query { Page(perPage: 20) { media(search: \\\"$escaped\\\") { id title { romaji english native } coverImage { large } siteUrl averageScore } } }\"}")["data"]["Page"]["media"] ?: return emptyList()
        return items.map { SyncSearchResult(it["title"]?.get("romaji")?.asText() ?: "", name, it["id"].asText(), it["siteUrl"]?.asText() ?: "", it["coverImage"]?.get("large")?.asText(), score = RemoteSync.score(it["averageScore"]?.asDouble()?.div(10))) }
    }

    override suspend fun load(auth: AuthData?, id: String): SyncResult? {
        val node = query("{\"query\":\"query { Media(id: $id) { id title { romaji } description episodes averageScore coverImage { large extraLarge } genres status startDate { year month day } } }\"", auth)["data"]["Media"]
        if (node.isMissingNode || node.isNull) return null
        return SyncResult(id, node["episodes"]?.takeUnless { it.isNull }?.asInt(), node["title"]?.get("romaji")?.asText(), RemoteSync.score(node["averageScore"]?.asDouble()?.div(10)), synopsis = node["description"]?.asText(), genres = node["genres"]?.map { it.asText() }, posterUrl = node["coverImage"]?.get("large")?.asText(), backgroundPosterUrl = node["coverImage"]?.get("extraLarge")?.asText())
    }

    override suspend fun library(auth: AuthData?): LibraryMetadata? {
        val user = auth?.user ?: return null
        val node = query("{\"query\":\"query { MediaListCollection(userId: ${user.id}, type: ANIME) { lists { name entries { media { id title { romaji } siteUrl coverImage { large } episodes } progress score status updatedAt } } } }\"", auth)["data"]["MediaListCollection"]["lists"] ?: return null
        val lists = node.map { list -> LibraryList(com.lagradost.cloudstream3.utils.UiText.DynamicString(list["name"]?.asText() ?: "Library"), list["entries"]?.map { entry -> val media = entry["media"]; LibraryItem(media["title"]?.get("romaji")?.asText() ?: "", media["siteUrl"]?.asText() ?: "", media["id"].asText(), entry["progress"]?.asInt(), media["episodes"]?.asInt(), RemoteSync.score(entry["score"]?.asDouble()?.div(10)), entry["updatedAt"]?.asLong(), name, null, media["coverImage"]?.get("large")?.asText(), null, null, null) } ?: emptyList()) }
        return LibraryMetadata(lists, emptySet())
    }

    override fun urlToId(url: String) = Regex("anilist\\.co/anime/(\\d+)").find(url)?.groupValues?.get(1)

    // ABI DTOs referenced by compiled provider plugins.
    data class Title(val romaji: String? = null, val english: String? = null, val native: String? = null)
    data class CoverImage(val large: String? = null, val extraLarge: String? = null)
    data class LikePageInfo(val total: Int? = null, val currentPage: Int? = null, val lastPage: Int? = null, val perPage: Int? = null, val hasNextPage: Boolean? = null)
    data class SeasonNextAiringEpisode(val episode: Int? = null, val airingAt: Long? = null)
    data class RecommendationConnection(val nodes: List<Any>? = null)
    data class Recommendation(val media: Any? = null)
    data class RecommendationEdge(val node: Recommendation? = null)
    data class RecommendedMedia(val media: Any? = null)
    data class MediaCoverImage(val large: String? = null, val extraLarge: String? = null)
    data class MediaTitle(val romaji: String? = null, val english: String? = null, val native: String? = null)
}
