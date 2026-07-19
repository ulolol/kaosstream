package com.lagradost.cloudstream3.network

import com.lagradost.api.Log
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URL

/**
 * JVM-side OkHttp interceptor that detects and solves Cloudflare challenges.
 *
 * This is the server-side equivalent of the Android [CloudflareKiller].
 * It intercepts every HTTP response, checks if Cloudflare is blocking the request,
 * and if so, triggers the challenge browser to solve it.
 *
 * Once solved, cookies are stored per-domain in [CookieJarManager] and
 * automatically injected into subsequent requests to the same domain.
 */
class CloudflareKillerJvm : Interceptor {

    companion object {
        private const val TAG = "CloudflareKillerJvm"
        private val ERROR_CODES = listOf(403, 503)
        private val CLOUDFLARE_SERVERS = listOf("cloudflare-nginx", "cloudflare")

        // Headers whose presence indicates a challenge response
        private val CHALLENGE_HEADERS = listOf(
            "cf-chl-bypass",
            "cf-chl-out",
            "cf-ray",
        )
        private val CHALLENGE_HEADER_PREFIX = "cf-chl-"
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

            // Try to solve the challenge
            val solved = solveChallenge(request, host)
            if (solved) {
                // Retry with cookies
                val retryRequest = injectCookies(request, host)
                return chain.proceed(retryRequest)
            }
        }

        return response
    }

    /**
     * Check if the response indicates a Cloudflare challenge.
     */
    private fun isCloudflareChallenge(response: Response): Boolean {
        val code = response.code
        if (code !in ERROR_CODES) return false

        val server = response.header("Server") ?: return false
        if (server.lowercase() !in CLOUDFLARE_SERVERS) return false

        // Check for Cloudflare challenge headers as additional signal
        val hasChallengeHeader = response.headers.filter { header ->
            val name = header.first.lowercase()
            name.startsWith(CHALLENGE_HEADER_PREFIX) || name in CHALLENGE_HEADERS
        }.isNotEmpty()

        // Check body for challenge indicators (lightweight check)
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

    /**
     * Inject any stored cookies for the given host into the request.
     */
    private fun injectCookies(request: Request, host: String): Request {
        val cookies = CookieJarManager.getCookies(host) ?: return request
        val userAgent = CookieJarManager.getUserAgent(host)

        return request.newBuilder().apply {
            // Remove any existing Cookie header and add the stored one
            header("Cookie", cookies)
            if (userAgent != null) {
                header("User-Agent", userAgent)
            }
        }.build()
    }

    /**
     * Solve a Cloudflare challenge by delegating to the challenge browser.
     * Returns true if cookies were successfully obtained and stored.
     */
    private fun solveChallenge(request: Request, host: String): Boolean {
        return try {
            val baseUrl = (System.getenv("CS_CHALLENGE_URL")
                ?: "http://challenge-browser:3210").trimEnd('/')

            val url = request.url.toString()
            val userAgent = request.header("User-Agent")

            // Create a challenge session
            val uaPart = userAgent?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"
            val startPayload = "{\"url\":\"${url.replace("\"", "\\\"")}\",\"userAgent\":$uaPart}"

            val startResponse = challengeRequest(baseUrl, "POST", "/sessions", startPayload)
            val sessionId = Regex("\"id\":\"([^\"]+)").find(startResponse)?.groupValues?.get(1)
                ?: return false

            // Poll for completion
            val deadline = System.currentTimeMillis() + 60_000L
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1000)
                val statusResponse = challengeRequest(baseUrl, "GET", "/sessions/$sessionId")
                if (statusResponse.contains("\"status\":\"ready\"")) {
                    // Extract cookies
                    val cookiesJson = challengeRequest(baseUrl, "GET", "/sessions/$sessionId/cookies")
                    val cookieHeader = Regex("\"name\":\"([^\"]+)\",\"value\":\"([^\"]*)")
                        .findAll(cookiesJson)
                        .joinToString("; ") { "${it.groupValues[1]}=${it.groupValues[2]}" }

                    if (cookieHeader.isNotBlank()) {
                        val resolvedUserAgent = Regex("\"userAgent\":\"([^\"]+)")
                            .find(statusResponse)?.groupValues?.get(1)

                        CookieJarManager.setCookies(host, cookieHeader, resolvedUserAgent)
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

    private fun challengeRequest(baseUrl: String, method: String, path: String, payload: String? = null): String {
        val connection = java.net.URL("$baseUrl$path").openConnection() as java.net.HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 30_000
        if (payload != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(payload.encodeToByteArray()) }
        }
        val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        return stream?.bufferedReader()?.use { it.readText() } ?: ""
    }
}
