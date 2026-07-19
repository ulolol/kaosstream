package com.lagradost.cloudstream3.network

import com.lagradost.api.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File

/**
 * Per-domain cookie storage with file persistence.
 *
 * Replaces the global cookie-in-defaultHeaders approach with domain-isolated storage.
 * Each domain gets its own cookie jar entry with metadata (userAgent, expiry, creation time).
 * Cookies are auto-expired based on a configurable TTL (default 15 minutes).
 * Persisted to disk so cookies survive server restarts.
 */
object CookieJarManager {
    private const val TAG = "CookieJarManager"
    private const val COOKIE_FILE = "cookies.json"
    private const val DEFAULT_TTL_MS = 15 * 60 * 1000L // 15 minutes

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private var cookieFile: File? = null
    private val cookies = mutableMapOf<String, CookieJarEntry>()
    private var loaded = false

    @Serializable
    data class CookieJarEntry(
        /** The full cookie header string: "name1=value1; name2=value2" */
        val cookieHeader: String,
        /** User-Agent that was used when solving, may be null */
        val userAgent: String? = null,
        /** The original URL that was used to solve the challenge, for proactive warmup */
        val sourceUrl: String? = null,
        /** When this entry was created/applied (epoch ms) */
        val createdAt: Long = System.currentTimeMillis(),
        /** Time-to-live in ms, defaults to 15 minutes */
        val ttlMs: Long = DEFAULT_TTL_MS,
    ) {
        /** True if this entry has expired */
        val isExpired: Boolean
            get() = System.currentTimeMillis() - createdAt > ttlMs
    }

    /**
     * Initialize with the data directory for persistence.
     * Automatically loads any saved cookies from disk.
     */
    fun init(dataDir: File?) {
        if (dataDir != null) {
            dataDir.mkdirs()
            cookieFile = File(dataDir, COOKIE_FILE)
            load()
        }
        loaded = true
        Log.i(TAG, "CookieJarManager initialized with ${cookies.size} entries")
    }

    // --- Public API ---

    /**
     * Get cookies for a specific host.
     * Returns the cookie header string (e.g. "cf_clearance=abc; ...") or null if none found.
     * Automatically removes expired entries during lookup.
     */
    @Synchronized
    fun getCookies(host: String): String? {
        cleanExpired()
        val entry = cookies[normalizeHost(host)] ?: return null
        if (entry.isExpired) {
            cookies.remove(normalizeHost(host))
            save()
            return null
        }
        return entry.cookieHeader
    }

    /**
     * Get the user-agent that was stored for a specific host.
     */
    @Synchronized
    fun getUserAgent(host: String): String? {
        cleanExpired()
        return cookies[normalizeHost(host)]?.userAgent
    }

    /**
     * Store cookies for a specific host.
     */
    @Synchronized
    fun setCookies(host: String, cookieHeader: String, userAgent: String? = null, sourceUrl: String? = null, ttlMs: Long = DEFAULT_TTL_MS) {
        if (cookieHeader.isBlank()) return
        val normalized = normalizeHost(host)
        // Preserve any existing sourceUrl if not provided (e.g., when re-saving)
        val resolvedSourceUrl = sourceUrl ?: cookies[normalized]?.sourceUrl
        cookies[normalized] = CookieJarEntry(
            cookieHeader = cookieHeader,
            userAgent = userAgent,
            sourceUrl = resolvedSourceUrl,
            ttlMs = ttlMs
        )
        Log.i(TAG, "Stored cookies for $normalized")
        save()
    }

    /**
     * Remove cookies for a specific host.
     */
    @Synchronized
    fun removeCookies(host: String) {
        val removed = cookies.remove(normalizeHost(host))
        if (removed != null) {
            Log.i(TAG, "Removed cookies for $host")
            save()
        }
    }

    /**
     * Remove all expired entries.
     * Returns the number of entries removed.
     */
    @Synchronized
    fun cleanExpired(): Int {
        val before = cookies.size
        cookies.entries.removeAll { it.value.isExpired }
        val removed = before - cookies.size
        if (removed > 0) {
            Log.i(TAG, "Cleaned $removed expired cookie entries")
            save()
        }
        return removed
    }

    /**
     * Clear all cookies.
     */
    @Synchronized
    fun clear() {
        cookies.clear()
        save()
        Log.i(TAG, "Cleared all cookies")
    }

    /**
     * Get the number of stored cookie entries.
     */
    @Synchronized
    fun size(): Int = cookies.size

    /**
     * Get a copy of all stored hostnames.
     */
    @Synchronized
    fun getHosts(): Set<String> = cookies.keys.toSet()

    /**
     * Get the cookie JarEntry (including metadata) for a host.
     */
    @Synchronized
    fun getEntry(host: String): CookieJarEntry? = cookies[normalizeHost(host)]

    // --- Persistence ---

    @Synchronized
    private fun save() {
        val file = cookieFile ?: return
        try {
            val data = CookieJarData(cookies.mapValues { it.value })
            file.writeText(json.encodeToString(data))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cookies: ${e.message}")
        }
    }

    @Synchronized
    private fun load() {
        val file = cookieFile ?: return
        if (!file.exists()) return
        try {
            val data = json.decodeFromString<CookieJarData>(file.readText())
            cookies.clear()
            cookies.putAll(data.entries.mapValues { it.value })
            cleanExpired()
            Log.i(TAG, "Loaded ${cookies.size} cookie entries from ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cookies, starting fresh: ${e.message}")
            cookies.clear()
        }
    }

    // --- Helpers ---

    private fun normalizeHost(host: String): String {
        return host
            .substringBefore(":") // strip port
            .lowercase()
            .removePrefix("www.")
            .trim()
    }

    @Serializable
    private data class CookieJarData(
        val entries: Map<String, CookieJarEntry>
    )
}
