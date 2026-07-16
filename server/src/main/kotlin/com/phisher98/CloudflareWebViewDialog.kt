package com.phisher98

import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.server.challenge.ChallengeClient
import kotlin.jvm.functions.Function1
import java.net.URL

/** JVM adapter for the Android-only AnimePahe Cloudflare dialog. */
class CloudflareWebViewDialog(
    private val targetUrl: String,
    private val onComplete: Function1<Boolean, Unit>,
    @Suppress("UNUSED_PARAMETER") private val autoStart: Boolean,
) {
    fun show(@Suppress("UNUSED_PARAMETER") manager: FragmentManager, @Suppress("UNUSED_PARAMETER") tag: String) {
        Thread({ solve() }, "cloudflare-challenge").apply {
            isDaemon = true
            start()
        }
    }

    private fun solve() {
        val result = try {
            val start = ChallengeClient.request("POST", "/sessions", "{\"url\":\"${targetUrl.replace("\"", "\\\"")}\"}".encodeToByteArray()).body.decodeToString()
            val id = Regex("""\"id\":\"([^\"]+)""").find(start)?.groupValues?.get(1) ?: return complete(false)
            val deadline = System.currentTimeMillis() + 120_000L
            while (System.currentTimeMillis() < deadline) {
                val status = ChallengeClient.request("GET", "/sessions/$id").body.decodeToString()
                if (status.contains("\"status\":\"ready\"")) {
                    val cookies = ChallengeClient.request("GET", "/sessions/$id/cookies").body.decodeToString()
                    val cookieHeader = Regex("""\{[^{}]*\}""").findAll(cookies).mapNotNull { match ->
                        val obj = match.value
                        val name = Regex("""\"name\"\s*:\s*\"([^\"]+)\"""").find(obj)?.groupValues?.get(1)
                        val value = Regex("""\"value\"\s*:\s*\"([^\"]*)\"""").find(obj)?.groupValues?.get(1)
                        if (name != null && value != null) "$name=$value" else null
                    }.joinToString("; ")
                    installPluginCookies(cookieHeader)
                    return complete(cookieHeader.isNotBlank())
                }
                Thread.sleep(1000)
            }
            false
        } catch (_: Throwable) {
            false
        }
        complete(result)
    }

    private fun installPluginCookies(cookieHeader: String) {
        val loader = Thread.currentThread().contextClassLoader
        val pluginClass = Class.forName("com.phisher98.AnimePaheProviderPlugin", true, loader)
        val companion = pluginClass.getField("Companion").get(null)
        val methods = companion.javaClass.methods
        methods.firstOrNull { it.name == "setCfCookies" }?.invoke(companion, cookieHeader)
        methods.firstOrNull { it.name == "setCfUserAgent" }?.invoke(companion, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36")
        methods.firstOrNull { it.name == "setCfCookieHost" }?.invoke(companion, URL(targetUrl).host)
    }

    private fun complete(success: Boolean): Unit = try { onComplete.invoke(success) } catch (_: Throwable) { }
}
