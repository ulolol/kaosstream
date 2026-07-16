package com.lagradost.cloudstream3.server

import java.io.File

object ServerContext {
    private val defaultDataDir = System.getenv("CS_DATA_DIR") ?: "./data"
    val dataDir = File(defaultDataDir)
    
    val configDir = File(dataDir, "config")
    val pluginsDir = File(dataDir, "plugins")
    val downloadsDir = File(dataDir, "downloads")
    
    val dbFile = File(configDir, "cloudstream.db")
    
    fun init() {
        dataDir.mkdirs()
        configDir.mkdirs()
        pluginsDir.mkdirs()
        downloadsDir.mkdirs()
    }
}
