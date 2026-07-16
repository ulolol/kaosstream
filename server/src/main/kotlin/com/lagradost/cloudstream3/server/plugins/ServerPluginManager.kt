package com.lagradost.cloudstream3.server.plugins

import com.lagradost.api.Log
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import android.content.MockSharedPreferences
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.server.storage.DatabaseHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
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

    @Serializable
    data class PluginStatus(
        val jarName: String,
        val name: String,
        val pluginClassName: String?,
        val version: Int?,
        val enabled: Boolean,
        val loaded: Boolean,
        val embedded: Boolean,
    )

    private fun settingKey(jarName: String) = "plugin.enabled.$jarName"

    private fun isEnabled(jarName: String): Boolean =
        DatabaseHelper.getSetting(settingKey(jarName))?.toBooleanStrictOrNull() ?: true

    private fun readManifest(jarFile: File): BasePlugin.Manifest? = try {
        JarFile(jarFile).use { jar ->
            val entry = jar.getJarEntry("manifest.json") ?: return null
            json.decodeFromString<BasePlugin.Manifest>(jar.getInputStream(entry).bufferedReader().readText())
        }
    } catch (e: Throwable) {
        Log.e(TAG, "Failed to read manifest from ${jarFile.name}: ${e.message}")
        null
    }

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
            val mockContext = object : AppCompatActivity() {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    return MockSharedPreferences(name)
                }
            }
            CloudStreamApp.setContext(mockContext)
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
            if (!isEnabled(jarFile.name)) {
                Log.i(TAG, "Skipping disabled plugin: ${jarFile.name}")
                return@forEach
            }
            try {
                loadPlugin(jarFile)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load plugin ${jarFile.name}: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    fun getPluginStatuses(directory: File): List<PluginStatus> {
        val embeddedNames = File("/app/bundled-plugins").listFiles { file -> file.extension.lowercase() == "jar" }
            ?.map { it.name }?.toSet() ?: emptySet()
        return directory.listFiles { file -> file.extension.lowercase() == "jar" }
            ?.mapNotNull { jarFile ->
                val manifest = readManifest(jarFile) ?: return@mapNotNull null
                val name = manifest.name ?: jarFile.nameWithoutExtension
                PluginStatus(
                    jarName = jarFile.name,
                    name = name,
                    pluginClassName = manifest.pluginClassName,
                    version = manifest.version,
                    enabled = isEnabled(jarFile.name),
                    loaded = loadedPlugins.containsKey(name),
                    embedded = jarFile.name in embeddedNames,
                )
            }?.sortedBy { it.name } ?: emptyList()
    }

    fun setPluginEnabled(directory: File, jarName: String, enabled: Boolean): PluginStatus? {
        val base = directory.canonicalFile
        val jarFile = File(directory, jarName).canonicalFile
        require(jarFile.parentFile == base && jarFile.extension.equals("jar", ignoreCase = true)) { "Invalid plugin filename" }
        require(jarFile.exists()) { "Plugin not found" }

        DatabaseHelper.saveSetting(settingKey(jarFile.name), enabled.toString())
        val manifest = readManifest(jarFile) ?: throw IllegalArgumentException("Invalid plugin manifest")
        val name = manifest.name ?: jarFile.nameWithoutExtension
        if (enabled) {
            if (!loadedPlugins.containsKey(name)) loadPlugin(jarFile)
        } else {
            unloadPlugin(name)
        }
        return getPluginStatuses(directory).firstOrNull { it.jarName == jarFile.name }
    }
}
