package com.lagradost.cloudstream3.syncproviders

data class AuthLoginPage(val url: String, val payload: String? = null)
data class AuthToken(val accessToken: String? = null, val refreshToken: String? = null, val accessTokenLifetime: Long? = null, val refreshTokenLifetime: Long? = null, val payload: String? = null) {
    fun isAccessTokenExpired(marginSec: Long = 10) = accessTokenLifetime != null && System.currentTimeMillis() / 1000 + marginSec >= accessTokenLifetime
    fun isRefreshTokenExpired(marginSec: Long = 10) = refreshTokenLifetime != null && System.currentTimeMillis() / 1000 + marginSec >= refreshTokenLifetime
}
data class AuthUser(val name: String?, val id: Int, val profilePicture: String? = null, val profilePictureHeaders: Map<String, String>? = null)
data class AuthData(val user: AuthUser, val token: AuthToken)
data class AuthPinData(val deviceCode: String, val userCode: String, val verificationUrl: String, val expiresIn: Int, val interval: Int)
data class AuthLoginRequirement(val password: Boolean = false, val username: Boolean = false, val email: Boolean = false, val server: Boolean = false)
data class AuthLoginResponse(val password: String?, val username: String?, val email: String?, val server: String?)

abstract class AuthAPI {
    open var name = "NONE"
    open val idPrefix = "NONE"
    open val icon: Int? = null
    open val requiresLogin = true
    open val createAccountUrl: String? = null
    open val redirectUrlIdentifier: String? = null
    open val hasOAuth2 = false
    open val hasPin = false
    open val hasInApp = false
    open val inAppLoginRequirement: AuthLoginRequirement? = null
    open fun isValidRedirectUrl(url: String) = redirectUrlIdentifier?.let { url.contains("/$it") } ?: false
    open suspend fun login(redirectUrl: String, payload: String?): AuthToken? = null
    open fun loginRequest(): AuthLoginPage? = null
    open suspend fun pinRequest(): AuthPinData? = null
    open suspend fun refreshToken(token: AuthToken): AuthToken? = null
    open suspend fun login(payload: AuthPinData): AuthToken? = null
    open suspend fun login(form: AuthLoginResponse): AuthToken? = null
    open suspend fun user(token: AuthToken?): AuthUser? = null
    open suspend fun invalidateToken(token: AuthToken): Nothing = throw NotImplementedError("Token invalidation is not supported")

    companion object {
        @JvmStatic fun splitRedirectUrl(url: String): Map<String, String> = url.substringAfter('?', "").split('&').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }
}
