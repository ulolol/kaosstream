package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.UiText
import java.util.Date

abstract class SyncAPI : AuthAPI() {
    open var requireLibraryRefresh = true
    open var mainUrl = "NONE"
    open val supportedWatchTypes: Set<SyncWatchType> = SyncWatchType.entries.toSet()
    open val syncIdName: SyncIdName? = null
    open suspend fun updateStatus(auth: AuthData?, id: String, newStatus: AbstractSyncStatus): Boolean = false
    open suspend fun status(auth: AuthData?, id: String): AbstractSyncStatus? = null
    open suspend fun load(auth: AuthData?, id: String): SyncResult? = null
    open suspend fun search(auth: AuthData?, query: String): List<SyncSearchResult>? = null
    open suspend fun library(auth: AuthData?): LibraryMetadata? = null
    open fun urlToId(url: String): String? = null
    data class SyncSearchResult(override val name: String, override val apiName: String, var syncId: String, override val url: String, override var posterUrl: String?, override var type: TvType? = null, override var quality: SearchQuality? = null, override var posterHeaders: Map<String, String>? = null, override var id: Int? = null, override var score: Score? = null) : SearchResponse
    abstract class AbstractSyncStatus { abstract var status: SyncWatchType; abstract var score: Score?; abstract var watchedEpisodes: Int?; abstract var isFavorite: Boolean?; abstract var maxEpisodes: Int? }
    data class SyncStatus(override var status: SyncWatchType, override var score: Score?, override var watchedEpisodes: Int?, override var isFavorite: Boolean? = null, override var maxEpisodes: Int? = null) : AbstractSyncStatus()
    data class SyncResult(var id: String, var totalEpisodes: Int? = null, var title: String? = null, var publicScore: Score? = null, var duration: Int? = null, var synopsis: String? = null, var airStatus: ShowStatus? = null, var nextAiring: NextAiring? = null, var studio: List<String>? = null, var genres: List<String>? = null, var synonyms: List<String>? = null, var trailers: List<String>? = null, var isAdult: Boolean? = null, var posterUrl: String? = null, var backgroundPosterUrl: String? = null, var startDate: Long? = null, var endDate: Long? = null, var recommendations: List<SyncSearchResult>? = null, var nextSeason: SyncSearchResult? = null, var prevSeason: SyncSearchResult? = null, var actors: List<ActorData>? = null)
    data class Page(val title: UiText, var items: List<LibraryItem>) {
        fun sort(method: ListSorting?, query: String? = null) {
            items = when (method) {
                ListSorting.Query -> if (query == null) items else items.sortedBy { it.name.lowercase().indexOf(query.lowercase()).let { if (it < 0) Int.MAX_VALUE else it } }
                ListSorting.RatingHigh -> items.sortedByDescending { it.personalRating?.toInt(100) ?: 0 }
                ListSorting.RatingLow -> items.sortedBy { it.personalRating?.toInt(100) ?: 0 }
                ListSorting.AlphabeticalA -> items.sortedBy { it.name }
                ListSorting.AlphabeticalZ -> items.sortedByDescending { it.name }
                ListSorting.UpdatedNew -> items.sortedByDescending { it.lastUpdatedUnixTime }
                ListSorting.UpdatedOld -> items.sortedBy { it.lastUpdatedUnixTime }
                ListSorting.ReleaseDateNew -> items.sortedByDescending { it.releaseDate }
                ListSorting.ReleaseDateOld -> items.sortedBy { it.releaseDate }
                else -> items
            }
        }
    }
    data class LibraryMetadata(val allLibraryLists: List<LibraryList>, val supportedListSorting: Set<ListSorting>)
    data class LibraryList(val name: UiText, val items: List<LibraryItem>)
    data class LibraryItem(override val name: String, override val url: String, val syncId: String, val episodesCompleted: Int?, val episodesTotal: Int?, val personalRating: Score?, val lastUpdatedUnixTime: Long?, override val apiName: String, override var type: TvType?, override var posterUrl: String?, override var posterHeaders: Map<String, String>?, override var quality: SearchQuality?, val releaseDate: Date?, override var id: Int? = null, val plot: String? = null, override var score: Score? = null, val tags: List<String>? = null) : SearchResponse
}
