package com.lagradost.cloudstream3.network

import java.util.concurrent.ConcurrentHashMap

/**
 * Stores the replayed HTTP response after a pending retry has been executed.
 * The frontend polls the retry-result endpoint to retrieve the data after
 * the challenge modal shows "ready".
 */
object RetryResultStore {
    private val results = ConcurrentHashMap<String, RetryResult>()

    data class RetryResult(
        val status: Int,
        val body: String,
        val contentType: String
    )

    fun store(id: String, status: Int, body: String, contentType: String) {
        results[id] = RetryResult(status, body, contentType)
    }

    fun retrieve(id: String): RetryResult? = results.remove(id)
}
