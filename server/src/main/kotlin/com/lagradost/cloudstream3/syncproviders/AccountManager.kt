package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.SimklApi
import com.lagradost.cloudstream3.server.storage.DatabaseHelper

class AccountManager {
    companion object {
        private val aniListApi = AniListApi()
        private val simklApi = SimklApi()

        @JvmStatic
        fun getAniListApi(): AniListApi = aniListApi

        @JvmStatic
        fun getSimklApi(): SimklApi = simklApi

        const val APP_STRING = "cloudstreamapp"
        const val APP_STRING_REPO = "cloudstreamrepo"
        const val APP_STRING_PLAYER = "cloudstreamplayer"
        const val APP_STRING_SEARCH = "cloudstreamsearch"
        const val APP_STRING_RESUME_WATCHING = "cloudstreamcontinuewatching"
        const val APP_STRING_SHARE = "csshare"

        private val accounts = mutableMapOf<String, AuthData>()

        @JvmStatic
        fun authData(prefix: String): AuthData? {
            synchronized(accounts) { accounts[prefix] }?.let { return it }
            val id = DatabaseHelper.getSetting("sync.active.$prefix")?.toIntOrNull() ?: return null
            val stored = DatabaseHelper.getSyncAccount(prefix, id) ?: return null
            return AuthData(AuthUser(stored.name, stored.id, stored.picture), AuthToken(stored.access, stored.refresh, stored.accessExpires, stored.refreshExpires, stored.payload)).also { synchronized(accounts) { accounts[prefix] = it } }
        }

        @JvmStatic
        fun saveAuth(prefix: String, data: AuthData) {
            synchronized(accounts) { accounts[prefix] = data }
            DatabaseHelper.saveSetting("sync.active.$prefix", data.user.id.toString())
            DatabaseHelper.saveSyncAccount(prefix, data.user.id, data.user.name, data.user.profilePicture, data.token.accessToken, data.token.refreshToken, data.token.accessTokenLifetime, data.token.refreshTokenLifetime, data.token.payload)
        }
    }
}
