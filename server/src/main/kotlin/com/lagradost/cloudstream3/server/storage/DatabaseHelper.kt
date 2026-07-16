package com.lagradost.cloudstream3.server.storage

import com.lagradost.api.Log
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

object DatabaseHelper {
    private const val TAG = "DatabaseHelper"
    private var connection: Connection? = null
    
    fun init(dbFile: File) {
        dbFile.parentFile.mkdirs()
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        Log.i(TAG, "Initializing database at $url")
        connection = DriverManager.getConnection(url)
        createTables()
    }
    
    @Synchronized
    private fun createTables() {
        val conn = connection ?: return
        conn.createStatement().use { stmt ->
            // Settings Table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """.trimIndent())
            
            // Bookmarks (Favorites) Table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS bookmarks (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    url TEXT NOT NULL,
                    api_name TEXT NOT NULL,
                    poster_url TEXT,
                    type TEXT,
                    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """.trimIndent())
            
            // Watch History / Progress Table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS watch_history (
                    id TEXT PRIMARY KEY,
                    parent_id TEXT,
                    episode_num INTEGER,
                    season_num INTEGER,
                    position_ms BIGINT NOT NULL,
                    duration_ms BIGINT NOT NULL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY(parent_id) REFERENCES bookmarks(id) ON DELETE CASCADE
                )
            """.trimIndent())

            // Downloads Table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS downloads (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    url TEXT NOT NULL,
                    save_path TEXT NOT NULL,
                    bytes_total BIGINT DEFAULT 0,
                    bytes_loaded BIGINT DEFAULT 0,
                    status TEXT NOT NULL,
                    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """.trimIndent())
            
            Log.i(TAG, "Tables initialized successfully.")
        }
    }
    
    @Synchronized
    fun useConnection(block: (Connection) -> Unit) {
        val conn = connection ?: throw IllegalStateException("Database not initialized")
        block(conn)
    }

    // --- Helper CRUD for Settings ---
    fun saveSetting(key: String, value: String) {
        useConnection { conn ->
            val sql = "INSERT INTO settings (key, value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP) ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=CURRENT_TIMESTAMP"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, value)
                stmt.executeUpdate()
            }
        }
    }

    fun getSetting(key: String): String? {
        var value: String? = null
        useConnection { conn ->
            val sql = "SELECT value FROM settings WHERE key = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, key)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    value = rs.getString("value")
                }
            }
        }
        return value
    }

    // --- Helper CRUD for Bookmarks ---
    fun addBookmark(id: String, name: String, url: String, apiName: String, posterUrl: String?, type: String?) {
        useConnection { conn ->
            val sql = "INSERT OR REPLACE INTO bookmarks (id, name, url, api_name, poster_url, type, added_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                stmt.setString(2, name)
                stmt.setString(3, url)
                stmt.setString(4, apiName)
                stmt.setString(5, posterUrl)
                stmt.setString(6, type)
                stmt.executeUpdate()
            }
        }
    }

    fun removeBookmark(id: String) {
        useConnection { conn ->
            val sql = "DELETE FROM bookmarks WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                stmt.executeUpdate()
            }
        }
    }

    fun getBookmarks(): List<BookmarkData> {
        val list = mutableListOf<BookmarkData>()
        useConnection { conn ->
            val sql = "SELECT id, name, url, api_name, poster_url, type FROM bookmarks ORDER BY added_at DESC"
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(sql)
                while (rs.next()) {
                    list.add(
                        BookmarkData(
                            id = rs.getString("id"),
                            name = rs.getString("name"),
                            url = rs.getString("url"),
                            apiName = rs.getString("api_name"),
                            posterUrl = rs.getString("poster_url"),
                            type = rs.getString("type")
                        )
                    )
                }
            }
        }
        return list
    }

    // --- Helper CRUD for Watch History ---
    fun saveWatchProgress(id: String, parentId: String?, episodeNum: Int?, seasonNum: Int?, positionMs: Long, durationMs: Long) {
        useConnection { conn ->
            val sql = "INSERT OR REPLACE INTO watch_history (id, parent_id, episode_num, season_num, position_ms, duration_ms, updated_at) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                stmt.setString(2, parentId)
                if (episodeNum != null) stmt.setInt(3, episodeNum) else stmt.setNull(3, java.sql.Types.INTEGER)
                if (seasonNum != null) stmt.setInt(4, seasonNum) else stmt.setNull(4, java.sql.Types.INTEGER)
                stmt.setLong(5, positionMs)
                stmt.setLong(6, durationMs)
                stmt.executeUpdate()
            }
        }
    }

    fun getWatchProgress(id: String): WatchProgressData? {
        var data: WatchProgressData? = null
        useConnection { conn ->
            val sql = "SELECT id, parent_id, episode_num, season_num, position_ms, duration_ms FROM watch_history WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    data = WatchProgressData(
                        id = rs.getString("id"),
                        parentId = rs.getString("parent_id"),
                        episodeNum = if (rs.getObject("episode_num") != null) rs.getInt("episode_num") else null,
                        seasonNum = if (rs.getObject("season_num") != null) rs.getInt("season_num") else null,
                        positionMs = rs.getLong("position_ms"),
                        durationMs = rs.getLong("duration_ms")
                    )
                }
            }
        }
        return data
    }

    fun getAllWatchHistory(): List<WatchProgressData> {
        val list = mutableListOf<WatchProgressData>()
        useConnection { conn ->
            val sql = "SELECT id, parent_id, episode_num, season_num, position_ms, duration_ms FROM watch_history ORDER BY updated_at DESC"
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(sql)
                while (rs.next()) {
                    list.add(
                        WatchProgressData(
                            id = rs.getString("id"),
                            parentId = rs.getString("parent_id"),
                            episodeNum = if (rs.getObject("episode_num") != null) rs.getInt("episode_num") else null,
                            seasonNum = if (rs.getObject("season_num") != null) rs.getInt("season_num") else null,
                            positionMs = rs.getLong("position_ms"),
                            durationMs = rs.getLong("duration_ms")
                        )
                    )
                }
            }
        }
        return list
    }

    // --- Helper CRUD for Downloads ---
    fun addDownload(id: String, title: String, url: String, savePath: String, bytesTotal: Long, bytesLoaded: Long, status: String) {
        useConnection { conn ->
            val sql = "INSERT OR REPLACE INTO downloads (id, title, url, save_path, bytes_total, bytes_loaded, status, added_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                stmt.setString(2, title)
                stmt.setString(3, url)
                stmt.setString(4, savePath)
                stmt.setLong(5, bytesTotal)
                stmt.setLong(6, bytesLoaded)
                stmt.setString(7, status)
                stmt.executeUpdate()
            }
        }
    }

    fun updateDownloadProgress(id: String, bytesLoaded: Long, status: String) {
        useConnection { conn ->
            val sql = "UPDATE downloads SET bytes_loaded = ?, status = ? WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, bytesLoaded)
                stmt.setString(2, status)
                stmt.setString(3, id)
                stmt.executeUpdate()
            }
        }
    }

    fun getDownloads(): List<DownloadRecord> {
        val list = mutableListOf<DownloadRecord>()
        useConnection { conn ->
            val sql = "SELECT id, title, url, save_path, bytes_total, bytes_loaded, status FROM downloads ORDER BY added_at DESC"
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(sql)
                while (rs.next()) {
                    list.add(
                        DownloadRecord(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            url = rs.getString("url"),
                            savePath = rs.getString("save_path"),
                            bytesTotal = rs.getLong("bytes_total"),
                            bytesLoaded = rs.getLong("bytes_loaded"),
                            status = rs.getString("status")
                        )
                    )
                }
            }
        }
        return list
    }

    fun deleteDownloadRecord(id: String) {
        useConnection { conn ->
            val sql = "DELETE FROM downloads WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                stmt.executeUpdate()
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class BookmarkData(
    val id: String,
    val name: String,
    val url: String,
    val apiName: String,
    val posterUrl: String?,
    val type: String?
)

@kotlinx.serialization.Serializable
data class WatchProgressData(
    val id: String,
    val parentId: String?,
    val episodeNum: Int?,
    val seasonNum: Int?,
    val positionMs: Long,
    val durationMs: Long
)

@kotlinx.serialization.Serializable
data class DownloadRecord(
    val id: String,
    val title: String,
    val url: String,
    val savePath: String,
    val bytesTotal: Long,
    val bytesLoaded: Long,
    val status: String
)
