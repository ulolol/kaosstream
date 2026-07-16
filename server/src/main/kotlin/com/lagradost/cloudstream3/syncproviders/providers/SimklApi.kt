package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.syncproviders.*

open class SimklApi : SyncAPI() {
    override var name = "Simkl"
    override val idPrefix = "SIMKL"
    override var mainUrl = "https://simkl.com"
    override val syncIdName = SyncIdName.Simkl
    override val requiresLogin = true

    private val clientId = System.getenv("SIMKL_CLIENT_ID")
    private fun available() = !clientId.isNullOrBlank()

    override suspend fun user(token: AuthToken?): AuthUser? {
        val access = token?.accessToken ?: return null
        if (!available()) return null
        val node = RemoteSync.get("https://api.simkl.com/users/settings", access)
        return AuthUser(node["user"]?.get("name")?.asText(), node["user"]?.get("id")?.asInt() ?: return null, node["user"]?.get("avatar")?.asText())
    }

    override suspend fun library(auth: AuthData?): LibraryMetadata? {
        if (auth?.token?.accessToken == null || !available()) return null
        return null
    }

    override suspend fun status(auth: AuthData?, id: String): AbstractSyncStatus? = null
    override suspend fun updateStatus(auth: AuthData?, id: String, newStatus: AbstractSyncStatus): Boolean = false
    override suspend fun load(auth: AuthData?, id: String): SyncResult? = null
    override suspend fun search(auth: AuthData?, query: String): List<SyncSearchResult>? = null
}
