package com.lagradost.cloudstream3.server.plugins

import com.lagradost.api.Log
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import android.content.MockSharedPreferences
import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile

object ServerPluginManager {
    private const val TAG = "ServerPluginManager"
    private val loadedPlugins = mutableMapOf<String, LoadedPluginInfo>()
    
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    class LoadedPluginInfo(
        val manifest: BasePlugin.Manifest,
        val jarPath: String,
        val classLoader: URLClassLoader,
        val pluginInstance: BasePlugin
    )

    fun loadPlugin(jarFile: File): LoadedPluginInfo {
        Log.i(TAG, "Loading plugin from ${jarFile.absolutePath}")
        
        val jar = JarFile(jarFile)
        val entry = jar.getJarEntry("manifest.json") 
            ?: throw IllegalArgumentException("manifest.json not found inside JAR file: ${jarFile.name}")
            
        val manifestContent = jar.getInputStream(entry).bufferedReader().readText()
        val manifest = json.decodeFromString<BasePlugin.Manifest>(manifestContent)
        
        val pluginClassName = manifest.pluginClassName
            ?: throw IllegalArgumentException("pluginClassName not defined in manifest.json for ${jarFile.name}")
            
        val name = manifest.name
            ?: throw IllegalArgumentException("name not defined in manifest.json for ${jarFile.name}")

        // If plugin is already loaded, unload it first
        loadedPlugins[name]?.let {
            unloadPlugin(name)
        }

        // Create standard JVM class loader for loading JAR contents
        val classLoader = URLClassLoader(
            arrayOf(jarFile.toURI().toURL()),
            ServerPluginManager::class.java.classLoader
        )

        val pluginClass = classLoader.loadClass(pluginClassName)
        val pluginInstance = pluginClass.getDeclaredConstructor().newInstance() as BasePlugin
        
        pluginInstance.filename = jarFile.absolutePath
        if (pluginInstance is Plugin) {
            val mockContext = object : Context() {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    return MockSharedPreferences(name)
                }
            }
            pluginInstance.load(mockContext)
        } else {
            pluginInstance.load()
        }

        val info = LoadedPluginInfo(manifest, jarFile.absolutePath, classLoader, pluginInstance)
        loadedPlugins[name] = info
        
        Log.i(TAG, "Successfully loaded plugin '$name' with provider elements.")
        return info
    }

    fun unloadPlugin(name: String) {
        val info = loadedPlugins[name] ?: return
        Log.i(TAG, "Unloading plugin '$name'")
        try {
            info.pluginInstance.beforeUnload()
        } catch (t: Throwable) {
            Log.e(TAG, "Error in beforeUnload for plugin '$name': ${t.message}")
        }
        
        // Remove mappings from APIHolder
        val plugin = info.pluginInstance
        // APIHolder mappings logic inside app deletes based on provider mapping.
        // Let's remove providers registered by this plugin from APIHolder.allProviders
        val providersToRemove = com.lagradost.cloudstream3.APIHolder.allProviders.filter { it.sourcePlugin == plugin.filename }
        com.lagradost.cloudstream3.APIHolder.allProviders.removeAll(providersToRemove)
        providersToRemove.forEach { provider ->
            com.lagradost.cloudstream3.APIHolder.removePluginMapping(provider)
        }
        
        // Remove extractors registered by this plugin
        val extractorsToRemove = com.lagradost.cloudstream3.utils.extractorApis.filter { it.sourcePlugin == plugin.filename }
        com.lagradost.cloudstream3.utils.extractorApis.removeAll(extractorsToRemove)

        try {
            info.classLoader.close()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to close class loader for plugin '$name': ${t.message}")
        }
        
        loadedPlugins.remove(name)
        Log.i(TAG, "Successfully unloaded plugin '$name'")
    }

    fun loadAllPlugins(directory: File) {
        if (!directory.exists()) {
            directory.mkdirs()
        }

        // Copy bundled plugins from read-only internal folder to working folder
        val bundledDir = File("/app/bundled-plugins")
        if (bundledDir.exists() && bundledDir.isDirectory) {
            bundledDir.listFiles { file -> file.extension.lowercase() == "jar" }?.forEach { bundledJar ->
                val targetFile = File(directory, bundledJar.name)
                if (!targetFile.exists() || targetFile.length() != bundledJar.length()) {
                    Log.i(TAG, "Syncing bundled plugin: ${bundledJar.name}")
                    try {
                        bundledJar.copyTo(targetFile, overwrite = true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to sync bundled plugin ${bundledJar.name}: ${e.message}")
                    }
                }
            }
        }
        
        directory.listFiles { file -> file.extension.lowercase() == "jar" }?.forEach { jarFile ->
            try {
                loadPlugin(jarFile)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load plugin ${jarFile.name}: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    fun getLoadedPlugins(): List<BasePlugin.Manifest> {
        return loadedPlugins.values.map { it.manifest }
    }
}
