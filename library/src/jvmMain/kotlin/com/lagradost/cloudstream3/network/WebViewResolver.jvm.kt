package com.lagradost.cloudstream3.network

import com.lagradost.cloudstream3.mvvm.debugException
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.nicehttp.requestCreator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import kotlinx.coroutines.delay
import java.net.HttpURLConnection
import java.net.URL

/**
 * When used as Interceptor additionalUrls cannot be returned, use WebViewResolver(...).resolveUsingWebView(...)
 * @param interceptUrl will stop the WebView when reaching this url.
 * @param additionalUrls this will make resolveUsingWebView also return all other requests matching the list of Regex.
 * @param userAgent if null then will use the default user agent
 * @param useOkhttp will try to use the okhttp client as much as possible, but this might cause some requests to fail. Disable for cloudflare.
 * @param script pass custom js to execute
 * @param scriptCallback will be called with the result from custom js
 * @param timeout close webview after timeout
 * */
actual class WebViewResolver actual constructor(
    interceptUrl: Regex,
    additionalUrls: List<Regex>,
    userAgent: String?,
    useOkhttp: Boolean,
    script: String?,
    scriptCallback: ((String) -> Unit)?,
    timeout: Long
) :
    Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return chain.proceed(request)
    }

    actual companion object {
        actual val DEFAULT_TIMEOUT = 60_000L
        actual var webViewUserAgent: String? = null
    }

    actual suspend fun resolveUsingWebView(
        url: String,
        referer: String?,
        method: String,
        requestCallBack: (Request) -> Boolean,
    ): Pair<Request?, List<Request>> =
        resolveUsingWebView(url, referer, emptyMap(), method, requestCallBack)

    actual suspend fun resolveUsingWebView(
        url: String,
        referer: String?,
        headers: Map<String, String>,
        method: String,
        requestCallBack: (Request) -> Boolean
    ): Pair<Request?, List<Request>> {
        return try {
            resolveUsingWebView(
                requestCreator(method, url, referer = referer, headers = headers), requestCallBack
            )
        } catch (e: java.lang.IllegalArgumentException) {
            logError(e)
            debugException { "ILLEGAL URL IN resolveUsingWebView!" }
            return null to emptyList()
        }
    }

    actual suspend fun resolveUsingWebView(
        request: Request,
        requestCallBack: (Request) -> Boolean
    ): Pair<Request?, List<Request>> {
        val baseUrl = (System.getenv("CS_CHALLENGE_URL") ?: "http://challenge-browser:3210").trimEnd('/')
        return try {
            val startPayload = "{\"url\":\"${request.url}\",\"userAgent\":${request.header("User-Agent")?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"}}"
            val start = challengeRequest(baseUrl, "POST", "/sessions", startPayload)
            val sessionId = Regex("""\"id\":\"([^\"]+)""").find(start)?.groupValues?.get(1)
                ?: return null to emptyList()
            val deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT
            while (System.currentTimeMillis() < deadline) {
                val status = challengeRequest(baseUrl, "GET", "/sessions/$sessionId")
                if (status.contains("\"status\":\"ready\"")) {
                    val finalUrl = Regex("""\"url\":\"([^\"]+)""").find(status)?.groupValues?.get(1) ?: request.url.toString()
                    val cookiesJson = challengeRequest(baseUrl, "GET", "/sessions/$sessionId/cookies")
                    val cookies = Regex("""\{[^{}]*\}""").findAll(cookiesJson).mapNotNull { match ->
                        val obj = match.value
                        val name = Regex("""\"name\"\s*:\s*\"([^\"]+)\"""").find(obj)?.groupValues?.get(1)
                        val value = Regex("""\"value\"\s*:\s*\"([^\"]*)\"""").find(obj)?.groupValues?.get(1)
                        if (name != null && value != null) "$name=$value" else null
                    }.joinToString("; ")
                    val host = URL(finalUrl).host
                    ChallengeCookieStore.apply(cookies, extractUserAgent(status), host, finalUrl)
                    val resolved = request.newBuilder().url(finalUrl).apply {
                        if (cookies.isNotBlank()) addHeader("Cookie", cookies)
                        extractUserAgent(status)?.let { addHeader("User-Agent", it) }
                    }.build()
                    requestCallBack(resolved)
                    return resolved to listOf(resolved)
                }
                delay(1000)
            }
            null to emptyList()
        } catch (error: Throwable) {
            logError(error)
            null to emptyList()
        }
    }

    private fun challengeRequest(baseUrl: String, method: String, path: String, payload: String? = null): String {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
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

    private fun extractUserAgent(status: String): String? =
        Regex("""\"userAgent\":\"([^\"]+)""").find(status)?.groupValues?.get(1)
}
