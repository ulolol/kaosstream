package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.MockSharedPreferences
import com.lagradost.cloudstream3.server.storage.DatabaseHelper
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

object DataStore {
    @JvmField
    val mapper: Any? = null

    fun getFolderName(folder: String, path: String): String {
        return "${folder}/${path}"
    }

    fun getSharedPrefs(context: Context): SharedPreferences {
        return MockSharedPreferences("cloudstream")
    }

    fun getDefaultSharedPrefs(context: Context): SharedPreferences {
        return getSharedPrefs(context)
    }

    fun Context.containsKey(path: String): Boolean {
        return DatabaseHelper.getSetting(path) != null
    }

    fun Context.containsKey(folder: String, path: String): Boolean {
        return containsKey(getFolderName(folder, path))
    }

    fun Context.removeKey(path: String) {
        DatabaseHelper.saveSetting(path, "")
    }

    fun Context.removeKey(folder: String, path: String) {
        removeKey(getFolderName(folder, path))
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> Context.setKey(path: String, value: T) {
        if (value == null) {
            removeKey(path)
            return
        }
        try {
            val jsonStr = when (value) {
                is String -> value
                is Number -> value.toString()
                is Boolean -> value.toString()
                else -> {
                    val serializer = Json.serializersModule.serializer(value::class.java)
                    Json.encodeToString(serializer, value)
                }
            }
            DatabaseHelper.saveSetting(path, jsonStr)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun <T> Context.setKey(folder: String, path: String, value: T) {
        setKey(getFolderName(folder, path), value)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> Context.getKey(path: String, valueType: Class<T>): T? {
        val stored = DatabaseHelper.getSetting(path) ?: return null
        if (stored.isEmpty()) return null
        try {
            return when (valueType) {
                String::class.java -> stored as T
                Int::class.java -> stored.toIntOrNull() as T?
                Long::class.java -> stored.toLongOrNull() as T?
                Double::class.java -> stored.toDoubleOrNull() as T?
                Boolean::class.java -> (stored == "true") as T?
                else -> {
                    val deserializer = Json.serializersModule.serializer(valueType)
                    Json.decodeFromString(deserializer, stored) as T?
                }
            }
        } catch (e: Exception) {
            return null
        }
    }

    inline fun <reified T : Any> Context.getKey(path: String, defVal: T? = null): T? {
        return getKey(path, T::class.java) ?: defVal
    }

    inline fun <reified T : Any> Context.getKey(folder: String, path: String, defVal: T? = null): T? {
        return getKey(getFolderName(folder, path), defVal)
    }
}
