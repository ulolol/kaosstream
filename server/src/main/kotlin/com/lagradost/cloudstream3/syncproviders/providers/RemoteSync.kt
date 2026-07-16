package com.lagradost.cloudstream3.syncproviders.providers

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.syncproviders.*
import com.lagradost.cloudstream3.ui.SyncWatchType
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

internal object RemoteSync {
    private val mapper = jacksonObjectMapper()
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()

    fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): JsonNode {
        val builder = HttpRequest.newBuilder(URI(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (key, value) -> builder.header(key, value) }
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "Sync provider HTTP ${response.statusCode()}" }
        return mapper.readTree(response.body())
    }

    fun get(url: String, token: String? = null): JsonNode {
        val builder = HttpRequest.newBuilder(URI(url)).GET()
        if (token != null) builder.header("Authorization", "Bearer $token")
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "Sync provider HTTP ${response.statusCode()}" }
        return mapper.readTree(response.body())
    }

    fun score(node: JsonNode?): Score? = node?.takeUnless { it.isNull }?.asDouble()?.let { Score.from10(it) }
    fun score(value: Double?): Score? = value?.let { Score.from10(it) }
    fun watchType(value: String?): SyncWatchType = when (value?.uppercase()) {
        "CURRENT", "WATCHING" -> SyncWatchType.WATCHING
        "COMPLETED" -> SyncWatchType.COMPLETED
        "DROPPED" -> SyncWatchType.DROPPED
        "PAUSED", "ONHOLD" -> SyncWatchType.ONHOLD
        "PLANNING", "PLANTOWATCH" -> SyncWatchType.PLANTOWATCH
        else -> SyncWatchType.NONE
    }
}
