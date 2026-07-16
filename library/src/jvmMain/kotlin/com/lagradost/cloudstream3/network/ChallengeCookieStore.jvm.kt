package com.lagradost.cloudstream3.network

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.APIHolder

object ChallengeCookieStore {
    private var appliedAt = 0L
    private var originalHeaders: Map<String, String>? = null

    @Synchronized
    fun apply(cookieHeader: String, userAgent: String? = null, host: String? = null) {
        if (cookieHeader.isBlank()) return
        if (originalHeaders == null) originalHeaders = app.defaultHeaders
        app.defaultHeaders = app.defaultHeaders + buildMap {
            put("cookie", cookieHeader)
            if (!userAgent.isNullOrBlank()) put("user-agent", userAgent)
        }
        applyAnimePaheCompanion(cookieHeader, userAgent, host)
        appliedAt = System.currentTimeMillis()
    }

    private fun applyAnimePaheCompanion(cookieHeader: String, userAgent: String?, host: String?) {
        try {
            val companion = animePaheCompanion() ?: return
            companion.javaClass.methods.firstOrNull { it.name == "setCfCookies" }
                ?.invoke(companion, cookieHeader)
            userAgent?.takeIf { it.isNotBlank() }?.let { value ->
                companion.javaClass.methods.firstOrNull { it.name == "setCfUserAgent" }
                    ?.invoke(companion, value)
            }
            host?.takeIf { it.isNotBlank() }?.let { value ->
                companion.javaClass.methods.firstOrNull { it.name == "setCfCookieHost" }
                    ?.invoke(companion, value)
            }
        } catch (_: Throwable) {
            // Older or non-AnimePahe plugin builds do not expose this ABI.
        }
    }

    private fun clearAnimePaheCompanion() {
        try {
            val companion = animePaheCompanion() ?: return
            companion.javaClass.methods.firstOrNull { it.name == "setCfCookies" }
                ?.invoke(companion, null)
            companion.javaClass.methods.firstOrNull { it.name == "setCfUserAgent" }
                ?.invoke(companion, null)
            companion.javaClass.methods.firstOrNull { it.name == "setCfCookieHost" }
                ?.invoke(companion, null)
        } catch (_: Throwable) {
            // Older or non-AnimePahe plugin builds do not expose this ABI.
        }
    }

    private fun animePaheCompanion(): Any? {
        val providerLoader = APIHolder.allProviders
            .firstOrNull { it.name.equals("AnimePahe", ignoreCase = true) }
            ?.javaClass
            ?.classLoader
            ?: return null
        val pluginClass = Class.forName("com.phisher98.AnimePaheProviderPlugin", true, providerLoader)
        val companion = pluginClass.getField("Companion").get(null)
        return companion
    }

    @Synchronized
    fun expireIfNeeded() {
        if (appliedAt != 0L && System.currentTimeMillis() - appliedAt > 15 * 60 * 1000L) {
            originalHeaders?.let { app.defaultHeaders = it }
            clearAnimePaheCompanion()
            originalHeaders = null
            appliedAt = 0L
        }
    }
}
