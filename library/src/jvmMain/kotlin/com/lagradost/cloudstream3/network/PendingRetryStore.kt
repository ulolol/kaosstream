package com.lagradost.cloudstream3.network

import java.util.concurrent.ConcurrentHashMap

/**
 * Stores failed HTTP request URLs per host so the server can replay them
 * after a Cloudflare challenge has been solved and cookies have been obtained.
 *
 * The interceptor [CloudflareKillerJvm] writes the original URL when it
 * detects a challenge. The server's challenge-complete handler reads and
 * removes the entry after storing cookies, then replays the request.
 */
object PendingRetryStore {
    private val pendingRetries = ConcurrentHashMap<String, String>()

    fun store(host: String, url: String) {
        pendingRetries[host] = url
    }

    fun retrieve(host: String): String? = pendingRetries.remove(host)
}
