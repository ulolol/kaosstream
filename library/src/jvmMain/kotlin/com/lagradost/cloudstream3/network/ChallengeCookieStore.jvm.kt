package com.lagradost.cloudstream3.network

import com.lagradost.api.Log
import com.lagradost.cloudstream3.APIHolder
import java.io.File

/**
 * Backward-compatible wrapper around [CookieJarManager].
 *
 * Previously injected cookies globally into [app.defaultHeaders], causing cross-domain
 * cookie leakage. Now delegates to [CookieJarManager] for per-domain storage.
 *
 * The [apply] method is kept for backward compatibility but should be replaced
 * with direct [CookieJarManager.setCookies] calls in new code.
 */
object ChallengeCookieStore {
    private const val TAG = "ChallengeCookieStore"
    private const val DEFAULT_TTL_MS = 15 * 60 * 1000L // 15 minutes

    // Track which hosts we've applied cookies to, for expireIfNeeded compatibility
    private val appliedHosts = mutableSetOf<String>()

    /**
     * Initialize the cookie store with a data directory for persistence.
     * Must be called once at server startup.
     */
    fun init(dataDir: File?) {
        CookieJarManager.init(dataDir)
    }

    /**
     * Apply cookies for a specific host.
     *
     * @param cookieHeader The full cookie header string (e.g. "cf_clearance=abc; ...")
     * @param userAgent The user agent to use for this host
     * @param host The hostname these cookies belong to (required for per-domain isolation)
     * @param sourceUrl The original URL that triggered the challenge, used for proactive warmup
     */
    @Synchronized
    fun apply(cookieHeader: String, userAgent: String? = null, host: String? = null, sourceUrl: String? = null) {
        if (cookieHeader.isBlank()) return

        val resolvedHost = host?.takeIf { it.isNotBlank() }
            ?: run {
                Log.w(TAG, "apply() called without host - cookies will be stored with fallback key")
                "unknown"
            }

        // Store per-domain (sourceUrl enables proactive cookie warmup)
        CookieJarManager.setCookies(resolvedHost, cookieHeader, userAgent, sourceUrl, DEFAULT_TTL_MS)
        appliedHosts.add(resolvedHost)

        // Also apply to AnimePahe plugin via reflection (plugin-specific ABI)
        applyAnimePaheCompanion(cookieHeader, userAgent, resolvedHost)
    }

    /**
     * Called periodically to expire old cookies.
     * Delegates to [CookieJarManager.cleanExpired].
     */
    @Synchronized
    fun expireIfNeeded() {
        val removed = CookieJarManager.cleanExpired()
        if (removed > 0) {
            clearAnimePaheCompanion()
        }
    }

    /**
     * Get stored cookies for a host. Returns null if no cookies or expired.
     */
    fun getCookies(host: String): String? = CookieJarManager.getCookies(host)

    /**
     * Get stored user agent for a host.
     */
    fun getUserAgent(host: String): String? = CookieJarManager.getUserAgent(host)

    // --- AnimePahe plugin ABI ---

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
        return try {
            val pluginClass = Class.forName("com.phisher98.AnimePaheProviderPlugin", true, providerLoader)
            pluginClass.getField("Companion").get(null)
        } catch (_: Throwable) {
            null
        }
    }
}
