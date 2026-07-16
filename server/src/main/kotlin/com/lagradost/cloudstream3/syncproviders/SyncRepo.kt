package com.lagradost.cloudstream3.syncproviders

class SyncRepo(override val api: SyncAPI) : AuthRepo(api) {
    val syncIdName get() = api.syncIdName
    var requireLibraryRefresh: Boolean
        get() = api.requireLibraryRefresh
        set(value) { api.requireLibraryRefresh = value }

    suspend fun updateStatus(id: String, newStatus: SyncAPI.AbstractSyncStatus): Result<Boolean> = runCatching {
        val auth = authData() ?: throw SyncUnavailableException("${api.name} requires authentication")
        api.updateStatus(auth, id, newStatus).also { requireLibraryRefresh = true }
    }
    suspend fun status(id: String): Result<SyncAPI.AbstractSyncStatus?> = runCatching {
        val auth = authData() ?: throw SyncUnavailableException("${api.name} requires authentication")
        api.status(auth, id)
    }
    suspend fun load(id: String): Result<SyncAPI.SyncResult?> = runCatching {
        val auth = authData() ?: throw SyncUnavailableException("${api.name} requires authentication")
        api.load(auth, id)
    }
    suspend fun library(): Result<SyncAPI.LibraryMetadata?> = runCatching {
        api.library(authData()) ?: throw SyncUnavailableException("${api.name} library is unavailable without authentication")
    }
}

class SyncUnavailableException(message: String) : IllegalStateException(message)
