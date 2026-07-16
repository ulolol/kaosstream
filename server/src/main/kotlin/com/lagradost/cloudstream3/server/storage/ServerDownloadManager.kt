package com.lagradost.cloudstream3.server.storage

import com.lagradost.api.Log
import com.lagradost.cloudstream3.server.ServerContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object ServerDownloadManager {
    private const val TAG = "ServerDownloadManager"
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val downloadScope = CoroutineScope(Dispatchers.IO)

    fun startDownload(id: String, title: String, url: String) {
        if (activeJobs.containsKey(id)) {
            Log.w(TAG, "Download already active for id: $id")
            return
        }

        val job = downloadScope.launch {
            val safeTitle = title.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val extension = "mp4"
            val targetFile = File(ServerContext.downloadsDir, "$safeTitle.$extension")
            
            Log.i(TAG, "Starting download: '$title' to ${targetFile.absolutePath}")
            DatabaseHelper.addDownload(
                id = id,
                title = title,
                url = url,
                savePath = targetFile.absolutePath,
                bytesTotal = 0,
                bytesLoaded = 0,
                status = "Downloading"
            )

            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw java.io.IOException("Server returned HTTP response code: $responseCode")
                }

                val contentType = connection.contentType.orEmpty().lowercase()
                if (url.contains(".m3u8", ignoreCase = true) || contentType.contains("mpegurl") || contentType.contains("vnd.apple.mpegurl")) {
                    throw java.io.IOException("HLS downloads are not supported yet; choose a direct MP4 source")
                }

                val contentLength = connection.contentLengthLong
                DatabaseHelper.addDownload(
                    id = id,
                    title = title,
                    url = url,
                    savePath = targetFile.absolutePath,
                    bytesTotal = contentLength,
                    bytesLoaded = 0,
                    status = "Downloading"
                )

                connection.inputStream.use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytesLoaded = 0L
                        var lastUpdate = System.currentTimeMillis()

                        while (true) {
                            bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            totalBytesLoaded += bytesRead

                            // Limit database writes to once per 1.5 seconds
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 1500) {
                                DatabaseHelper.updateDownloadProgress(id, totalBytesLoaded, "Downloading")
                                lastUpdate = now
                            }
                        }
                        
                        // Final success save
                        DatabaseHelper.updateDownloadProgress(id, totalBytesLoaded, "Completed")
                        Log.i(TAG, "Completed download for '$title'")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for '$title': ${e.message}")
                DatabaseHelper.updateDownloadProgress(id, 0, "Failed")
                // Cleanup partially downloaded file
                if (targetFile.exists()) {
                    targetFile.delete()
                }
            } finally {
                activeJobs.remove(id)
            }
        }

        activeJobs[id] = job
    }

    fun cancelDownload(id: String) {
        val job = activeJobs.remove(id)
        if (job != null) {
            job.cancel()
            Log.i(TAG, "Cancelled download job for id: $id")
        }
        
        // Find record and delete local file
        val records = DatabaseHelper.getDownloads()
        val record = records.find { it.id == id }
        if (record != null) {
            val file = File(record.savePath)
            if (file.exists()) {
                file.delete()
            }
            DatabaseHelper.deleteDownloadRecord(id)
        }
    }
}
