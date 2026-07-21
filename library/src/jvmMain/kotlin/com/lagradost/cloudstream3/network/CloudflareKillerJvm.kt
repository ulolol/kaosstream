package com.lagradost.cloudstream3.network

import com.lagradost.api.Log
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * JVM-side OkHttp interceptor that detects and solves Cloudflare challenges.
 *
 * This is the server-side equivalent of the Android [CloudflareKiller].
 * It intercepts every HTTP response, checks if Cloudflare is blocking the request,
 * and if so, triggers the challenge browser to solve it.
 *
 * Features:
 * - Per-domain cookie injection from [CookieJarManager]
 * - Cloudflare detection via response headers + body hints
 * - Automatic challenge solving via challenge-browser service
 * - **Challenge deduplication**: concurrent requests to the same domain
 *   share a single solve attempt, avoiding redundant browser sessions
 */
class CloudflareKillerJvm : Interceptor {

    companion object {
        private const val TAG = "CloudflareKillerJvm"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")
        private val CHALLENGE_HEADER_PREFIX = "cf-chl-"
        private const val CHALLENGE_TIMEOUT_MS = 30_000L
        private const val REQUEST_TIMEOUT_MS = 30_000

        // Deduplication: tracks in-flight challenge solves per host
        // When a solve is in progress, other requests for the same host
        // wait on this future instead of starting a new session
        private val inFlightChallenges = ConcurrentHashMap<String, CompletableFuture<Boolean>>()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        // Step 1: Inject any existing cookies for this domain
        val cookedRequest = injectCookies(request, host)

        // Step 2: Proceed with the request
        val response = chain.proceed(cookedRequest)

        // Step 3: Check if Cloudflare is blocking us
        if (isCloudflareChallenge(response)) {
            Log.i(TAG, "Cloudflare challenge detected for $host")
            response.close()

            // Step 4: Solve the challenge (with deduplication)
            val solved = solveChallengeDeduplicated(request, host)
            if (solved) {
                // Step 5: Retry with cookies
                val retryRequest = injectCookies(request, host)
                return chain.proceed(retryRequest)
            }
        }

        return response
    }

    // --- Challenge deduplication ---

    /**
     * Solve a challenge for the given host, deduplicating concurrent attempts.
     * If another request is already solving a challenge for this host,
     * this call will wait for that result instead of starting a new session.
     */
    private fun solveChallengeDeduplicated(request: Request, host: String): Boolean {
        val future = inFlightChallenges.computeIfAbsent(host) { _ ->
            CompletableFuture.supplyAsync {
                try {
                    solveChallenge(request, host)
                } catch (e: Exception) {
                    Log.e(TAG, "Challenge solve failed for $host: ${e.message}")
                    false
                }
            }
        }

        return try {
            val result = future.get(CHALLENGE_TIMEOUT_MS + 5_000, TimeUnit.MILLISECONDS)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wait for challenge result for $host: ${e.message}")
            inFlightChallenges.remove(host)
            false
        } finally {
            // Only remove if we were the one who created it
            // (future.isDone means we can safely remove)
            if (future.isDone) {
                inFlightChallenges.remove(host, future)
            }
        }
    }

    // --- Cloudflare detection ---

    /**
     * Check if the response indicates a Cloudflare challenge.
     */
    private fun isCloudflareChallenge(response: Response): Boolean {
        val code = response.code
        if (code !in ERROR_CODES) return false

        val server = response.header("Server") ?: return false
        if (server.lowercase() !in CLOUDFLARE_SERVERS) return false

        // Check for Cloudflare challenge headers
        val hasChallengeHeader = response.headers.filter { header ->
            header.first.lowercase().startsWith(CHALLENGE_HEADER_PREFIX)
        }.isNotEmpty()

        // Check body for challenge indicators (lightweight peek)
        val bodyHint = if (!hasChallengeHeader) {
            try {
                val body = response.peekBody(2048).string().lowercase()
                body.contains("cf-chl-bypass") ||
                body.contains("challenge-platform") ||
                body.contains("just a moment") ||
                body.contains("checking your browser") ||
                body.contains("attention required") ||
                body.contains("verify you are human")
            } catch (_: Exception) {
                false
            }
        } else true

        return hasChallengeHeader || bodyHint
    }

    // --- Cookie injection ---

    /**
     * Inject any stored cookies for the given host into the request.
     */
    private fun injectCookies(request: Request, host: String): Request {
        val cookies = CookieJarManager.getCookies(host) ?: return request
        val userAgent = CookieJarManager.getUserAgent(host)

        return request.newBuilder().apply {
            header("Cookie", cookies)
            if (userAgent != null) {
                header("User-Agent", userAgent)
            }
        }.build()
    }

    // --- Challenge solving ---

    /**
     * Solve a Cloudflare challenge by delegating to the challenge-browser service.
     * Returns true if cookies were successfully obtained and stored.
     */
    private fun solveChallenge(request: Request, host: String): Boolean {
        return try {
            val baseUrl = (System.getenv("CS_CHALLENGE_URL")
                ?: "http://challenge-browser:3210").trimEnd('/')

            val url = request.url.toString()
            val userAgent = request.header("User-Agent")

            // Store pending retry so the server can replay the request after cookies are obtained
            PendingRetryStore.store(host, url)

            // Create a challenge session
            val uaPart = userAgent?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"
            val startPayload = "{\"url\":\"${url.replace("\"", "\\\"")}\",\"userAgent\":$uaPart}"

            val startResponse = httpRequest(baseUrl, "POST", "/sessions", startPayload)
            val sessionId = Regex("\"id\":\"([^\"]+)").find(startResponse)?.groupValues?.get(1)
                ?: return false

            // Poll for completion
            val deadline = System.currentTimeMillis() + CHALLENGE_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1000)
                val statusResponse = httpRequest(baseUrl, "GET", "/sessions/$sessionId")
                if (statusResponse.contains("\"status\":\"ready\"")) {
                    // Extract cookies
                    val cookiesJson = httpRequest(baseUrl, "GET", "/sessions/$sessionId/cookies")
                    val cookieHeader = Regex("\"name\":\"([^\"]+)\",\"value\":\"([^\"]*)")
                        .findAll(cookiesJson)
                        .joinToString("; ") { "${it.groupValues[1]}=${it.groupValues[2]}" }

                    if (cookieHeader.isNotBlank()) {
                        val resolvedUserAgent = Regex("\"userAgent\":\"([^\"]+)")
                            .find(statusResponse)?.groupValues?.get(1)

                        CookieJarManager.setCookies(host, cookieHeader, resolvedUserAgent, url)
                        Log.i(TAG, "Solved Cloudflare challenge for $host")
                        return true
                    }
                    return false
                }
            }
            Log.w(TAG, "Challenge timed out for $host")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to solve challenge for $host: ${e.message}")
            false
        }
    }

    private fun httpRequest(baseUrl: String, method: String, path: String, payload: String? = null): String {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = REQUEST_TIMEOUT_MS
        if (payload != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(payload.encodeToByteArray()) }
        }
        val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }
}
