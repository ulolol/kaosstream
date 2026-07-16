package com.lagradost.cloudstream3.server.challenge

import java.net.HttpURLConnection
import java.net.URL

data class ChallengeClientResponse(val status: Int, val contentType: String?, val body: ByteArray)

object ChallengeClient {
    private val baseUrl: String
        get() = (System.getenv("CS_CHALLENGE_URL") ?: "http://challenge-browser:3210").trimEnd('/')

    fun request(method: String, path: String, body: ByteArray? = null): ChallengeClientResponse {
        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Accept", "application/json")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body) }
        }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val responseBody = stream?.use { it.readBytes() } ?: ByteArray(0)
        return ChallengeClientResponse(status, connection.contentType, responseBody)
    }
}
