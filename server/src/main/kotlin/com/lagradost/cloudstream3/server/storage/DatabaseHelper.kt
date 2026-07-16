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
                    title TEXT,
                    poster_url TEXT,
                    provider TEXT,
                    plot TEXT,
                    type TEXT,
                    year INTEGER,
                    score REAL,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
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

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sync_accounts (
                    provider TEXT NOT NULL,
                    user_id INTEGER NOT NULL,
                    user_name TEXT,
                    profile_picture TEXT,
                    access_token TEXT,
                    refresh_token TEXT,
                    access_expires INTEGER,
                    refresh_expires INTEGER,
                    payload TEXT,
                    PRIMARY KEY(provider, user_id)
                )
            """.trimIndent())
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS sync_status (
                    provider TEXT NOT NULL,
                    user_id INTEGER NOT NULL,
                    item_id TEXT NOT NULL,
                    status INTEGER NOT NULL,
                    score INTEGER,
                    watched_episodes INTEGER,
                    favorite INTEGER,
                    max_episodes INTEGER,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(provider, user_id, item_id)
                )
            """.trimIndent())

            // Safe upgrades for existing watch_history tables
            try { stmt.execute("ALTER TABLE watch_history ADD COLUMN title TEXT") } catch (_: Exception) {}
            try { stmt.execute("ALTER TABLE watch_history ADD COLUMN poster_url TEXT") } catch (_: Exception) {}
            try { stmt.execute("ALTER TABLE watch_history ADD COLUMN provider TEXT") } catch (_: Exception) {}
            try { stmt.execute("ALTER TABLE watch_history ADD COLUMN plot TEXT") } catch (_: Exception) {}
            try { stmt.execute("ALTER TABLE watch_history ADD COLUMN type TEXT") } catch (_: Exception) {}
            try { stmt.execute("ALTER TABLE watch_history ADD COLUMN year INTEGER") } catch (_: Exception) {}
            try { stmt.execute("ALTER TABLE watch_history ADD COLUMN score REAL") } catch (_: Exception) {}
            
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

    fun saveSyncAccount(provider: String, userId: Int, name: String?, picture: String?, access: String?, refresh: String?, accessExpires: Long?, refreshExpires: Long?, payload: String?) {
        useConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO sync_accounts(provider,user_id,user_name,profile_picture,access_token,refresh_token,access_expires,refresh_expires,payload)
                VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(provider,user_id) DO UPDATE SET
                user_name=excluded.user_name,profile_picture=excluded.profile_picture,access_token=excluded.access_token,
                refresh_token=excluded.refresh_token,access_expires=excluded.access_expires,refresh_expires=excluded.refresh_expires,payload=excluded.payload
            """.trimIndent()).use { stmt ->
                listOf(provider, userId, name, picture, access, refresh, accessExpires, refreshExpires, payload).forEachIndexed { index, value -> stmt.setObject(index + 1, value) }
                stmt.executeUpdate()
            }
        }
    }

    fun getSyncAccount(provider: String, userId: Int): SyncAccountData? {
        var result: SyncAccountData? = null
        useConnection { conn ->
            conn.prepareStatement("SELECT * FROM sync_accounts WHERE provider=? AND user_id=?").use { stmt ->
                stmt.setString(1, provider); stmt.setInt(2, userId)
                stmt.executeQuery().use { rs -> if (rs.next()) result = SyncAccountData(rs.getInt("user_id"), rs.getString("user_name"), rs.getString("profile_picture"), rs.getString("access_token"), rs.getString("refresh_token"), rs.getObject("access_expires")?.let { (it as Number).toLong() }, rs.getObject("refresh_expires")?.let { (it as Number).toLong() }, rs.getString("payload")) }
            }
        }
        return result
    }

    fun saveSyncStatus(provider: String, userId: Int, itemId: String, status: Int, score: Int?, watched: Int?, favorite: Boolean?, maxEpisodes: Int?) {
        useConnection { conn ->
            conn.prepareStatement("""
                INSERT INTO sync_status VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(provider,user_id,item_id) DO UPDATE SET
                status=excluded.status,score=excluded.score,watched_episodes=excluded.watched_episodes,favorite=excluded.favorite,max_episodes=excluded.max_episodes,updated_at=excluded.updated_at
            """.trimIndent()).use { stmt ->
                listOf(provider, userId, itemId, status, score, watched, favorite?.let { if (it) 1 else 0 }, maxEpisodes, System.currentTimeMillis()).forEachIndexed { index, value -> stmt.setObject(index + 1, value) }
                stmt.executeUpdate()
            }
        }
    }

    fun getSyncStatus(provider: String, userId: Int, itemId: String): SyncStatusData? {
        var result: SyncStatusData? = null
        useConnection { conn ->
            conn.prepareStatement("SELECT * FROM sync_status WHERE provider=? AND user_id=? AND item_id=?").use { stmt ->
                stmt.setString(1, provider); stmt.setInt(2, userId); stmt.setString(3, itemId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        result = SyncStatusData(
                            status = rs.getInt("status"),
                            score = rs.getObject("score")?.let { (it as Number).toInt() },
                            watched = rs.getObject("watched_episodes")?.let { (it as Number).toInt() },
                            favorite = rs.getObject("favorite")?.let { (it as Number).toInt() != 0 },
                            maxEpisodes = rs.getObject("max_episodes")?.let { (it as Number).toInt() }
                        )
                    }
                }
            }
        }
        return result
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
    fun saveWatchProgress(
        id: String,
        parentId: String?,
        episodeNum: Int?,
        seasonNum: Int?,
        positionMs: Long,
        durationMs: Long,
        title: String? = null,
        posterUrl: String? = null,
        provider: String? = null,
        plot: String? = null,
        type: String? = null,
        year: Int? = null,
        score: Double? = null
    ) {
        useConnection { conn ->
            val sql = "INSERT OR REPLACE INTO watch_history (id, parent_id, episode_num, season_num, position_ms, duration_ms, title, poster_url, provider, plot, type, year, score, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                stmt.setString(2, parentId)
                if (episodeNum != null) stmt.setInt(3, episodeNum) else stmt.setNull(3, java.sql.Types.INTEGER)
                if (seasonNum != null) stmt.setInt(4, seasonNum) else stmt.setNull(4, java.sql.Types.INTEGER)
                stmt.setLong(5, positionMs)
                stmt.setLong(6, durationMs)
                stmt.setString(7, title)
                stmt.setString(8, posterUrl)
                stmt.setString(9, provider)
                stmt.setString(10, plot)
                stmt.setString(11, type)
                if (year != null) stmt.setInt(12, year) else stmt.setNull(12, java.sql.Types.INTEGER)
                if (score != null) stmt.setDouble(13, score) else stmt.setNull(13, java.sql.Types.REAL)
                stmt.executeUpdate()
            }
        }
    }

    private fun findMovieUrlFromLinks(linksJson: String, provider: String?): String? {
        if (!linksJson.startsWith("[")) return null
        try {
            val list = kotlinx.serialization.json.Json.decodeFromString<List<String>>(linksJson)
            return list.firstOrNull { link ->
                !link.contains("hubdrive") &&
                !link.contains("pixel") &&
                !link.contains("buzz") &&
                !link.contains("telegram") &&
                !link.contains("file") &&
                !link.contains("download") &&
                !link.contains("proxy") &&
                !link.contains("stream")
            }
        } catch (_: Exception) {
            return null
        }
    }

    fun getWatchProgress(id: String): WatchProgressData? {
        var data: WatchProgressData? = null
        useConnection { conn ->
            val sql = "SELECT id, parent_id, episode_num, season_num, position_ms, duration_ms, title, poster_url, provider, plot, type, year, score, updated_at FROM watch_history WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    var parentId = rs.getString("parent_id")
                    val provider = rs.getString("provider")
                    if (parentId.isNullOrEmpty() && id.startsWith("[")) {
                        val cleaned = findMovieUrlFromLinks(id, provider)
                        if (!cleaned.isNullOrEmpty()) {
                            parentId = cleaned
                            try {
                                conn.prepareStatement("UPDATE watch_history SET parent_id = ? WHERE id = ?").use { updateStmt ->
                                    updateStmt.setString(1, cleaned)
                                    updateStmt.setString(2, id)
                                    updateStmt.executeUpdate()
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    data = WatchProgressData(
                        id = id,
                        parentId = parentId,
                        episodeNum = if (rs.getObject("episode_num") != null) rs.getInt("episode_num") else null,
                        seasonNum = if (rs.getObject("season_num") != null) rs.getInt("season_num") else null,
                        positionMs = rs.getLong("position_ms"),
                        durationMs = rs.getLong("duration_ms"),
                        title = rs.getString("title"),
                        posterUrl = rs.getString("poster_url"),
                        provider = provider,
                        plot = rs.getString("plot"),
                        type = rs.getString("type"),
                        year = if (rs.getObject("year") != null) rs.getInt("year") else null,
                        score = if (rs.getObject("score") != null) rs.getDouble("score") else null,
                        updatedAt = rs.getString("updated_at")
                    )
                }
            }
        }
        return data
    }

    fun getAllWatchHistory(): List<WatchProgressData> {
        val list = mutableListOf<WatchProgressData>()
        useConnection { conn ->
            val sql = "SELECT id, parent_id, episode_num, season_num, position_ms, duration_ms, title, poster_url, provider, plot, type, year, score, updated_at FROM watch_history ORDER BY updated_at DESC"
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(sql)
                while (rs.next()) {
                    val id = rs.getString("id")
                    var parentId = rs.getString("parent_id")
                    val provider = rs.getString("provider")
                    if (parentId.isNullOrEmpty() && id.startsWith("[")) {
                        val cleaned = findMovieUrlFromLinks(id, provider)
                        if (!cleaned.isNullOrEmpty()) {
                            parentId = cleaned
                            try {
                                conn.prepareStatement("UPDATE watch_history SET parent_id = ? WHERE id = ?").use { updateStmt ->
                                    updateStmt.setString(1, cleaned)
                                    updateStmt.setString(2, id)
                                    updateStmt.executeUpdate()
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    list.add(
                        WatchProgressData(
                            id = id,
                            parentId = parentId,
                            episodeNum = if (rs.getObject("episode_num") != null) rs.getInt("episode_num") else null,
                            seasonNum = if (rs.getObject("season_num") != null) rs.getInt("season_num") else null,
                            positionMs = rs.getLong("position_ms"),
                            durationMs = rs.getLong("duration_ms"),
                            title = rs.getString("title"),
                            posterUrl = rs.getString("poster_url"),
                            provider = provider,
                            plot = rs.getString("plot"),
                            type = rs.getString("type"),
                            year = if (rs.getObject("year") != null) rs.getInt("year") else null,
                            score = if (rs.getObject("score") != null) rs.getDouble("score") else null,
                            updatedAt = rs.getString("updated_at")
                        )
                    )
                }
            }
        }
        return list
    }

    fun removeWatchProgress(id: String) {
        useConnection { conn ->
            val sql = "DELETE FROM watch_history WHERE id = ?"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, id)
                stmt.executeUpdate()
            }
        }
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
    val durationMs: Long,
    val title: String? = null,
    val posterUrl: String? = null,
    val provider: String? = null,
    val plot: String? = null,
    val type: String? = null,
    val year: Int? = null,
    val score: Double? = null,
    val updatedAt: String? = null
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

data class SyncAccountData(val id: Int, val name: String?, val picture: String?, val access: String?, val refresh: String?, val accessExpires: Long?, val refreshExpires: Long?, val payload: String?)
data class SyncStatusData(val status: Int, val score: Int?, val watched: Int?, val favorite: Boolean?, val maxEpisodes: Int?)
