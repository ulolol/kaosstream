package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import kotlin.reflect.KClass

// Short name for requests client to make it nicer to use
private val jsonResponseParser = object : ResponseParser {
    override fun <T : Any> parse(text: String, kClass: KClass<T>): T {
        return parseJson(text, kClass)
    }

    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? {
        return try {
            parse(text, kClass)
        } catch (_: Exception) {
            null
        }
    }

    override fun writeValueAsString(obj: Any): String {
        return obj.toJson()
    }
}

private val ytsInterceptor = okhttp3.Interceptor { chain ->
    val request = chain.request()
    val url = request.url
    val host = url.host
    if (host == "yts.bz" || host == "yts.gg" || host == "yts.mx") {
        val newUrl = url.newBuilder()
            .host("en.yts-official.biz")
            .build()
        val newRequest = request.newBuilder()
            .url(newUrl)
            .header("Host", "en.yts-official.biz")
            .build()
        chain.proceed(newRequest)
    } else {
        chain.proceed(request)
    }
}

/** The default networking helper. This helper performs SSL checks.
 * If you need to make requests to websites with invalid SSL certificates use insecureApp instead. */
var app = Requests(responseParser = jsonResponseParser).apply {
    defaultHeaders = mapOf("user-agent" to USER_AGENT)
    baseClient = baseClient.newBuilder().addInterceptor(ytsInterceptor).build()
}

/** Same as the default app networking helper, but this instance ignores SSL certificates.
 * This should NEVER be used for sensitive networking operations such as logins. Only use this when required. */
@UnsafeSSL
var insecureApp = Requests(responseParser = jsonResponseParser).apply {
    defaultHeaders = mapOf("user-agent" to USER_AGENT)
    baseClient = baseClient.newBuilder().addInterceptor(ytsInterceptor).build()
}

