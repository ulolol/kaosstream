package com.lagradost.cloudstream3.syncproviders

abstract class AuthRepo(open val api: AuthAPI) {
    val idPrefix get() = api.idPrefix
    val name get() = api.name
    val requiresLogin get() = api.requiresLogin
    fun isValidRedirectUrl(url: String) = api.isValidRedirectUrl(url)
    fun authData(): AuthData? = AccountManager.authData(api.idPrefix)
    fun authUser(): AuthUser? = authData()?.user
}
