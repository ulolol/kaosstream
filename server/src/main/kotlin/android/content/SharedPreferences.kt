package android.content

import com.lagradost.cloudstream3.server.storage.DatabaseHelper

interface SharedPreferences {
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }

    fun getAll(): Map<String, *>
    fun getString(key: String, defValue: String?): String?
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(): Editor
    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)
    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)

    interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String)
    }
}

class MockSharedPreferences(private val name: String) : SharedPreferences {
    private class MockEditor(private val name: String) : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            DatabaseHelper.saveSetting("${name}_${key}", value ?: "")
            return this
        }
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            val str = values?.joinToString(",") ?: ""
            DatabaseHelper.saveSetting("${name}_${key}", str)
            return this
        }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            DatabaseHelper.saveSetting("${name}_${key}", value.toString())
            return this
        }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            DatabaseHelper.saveSetting("${name}_${key}", value.toString())
            return this
        }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            DatabaseHelper.saveSetting("${name}_${key}", value.toString())
            return this
        }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            DatabaseHelper.saveSetting("${name}_${key}", value.toString())
            return this
        }
        override fun remove(key: String): SharedPreferences.Editor {
            DatabaseHelper.saveSetting("${name}_${key}", "")
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            return this
        }
        override fun commit(): Boolean {
            return true
        }
        override fun apply() {}
    }

    override fun getAll(): Map<String, *> = emptyMap<String, Any>()
    override fun getString(key: String, defValue: String?): String? {
        val stored = DatabaseHelper.getSetting("${name}_${key}")
        return if (stored.isNullOrEmpty()) defValue else stored
    }
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        val stored = DatabaseHelper.getSetting("${name}_${key}")
        return if (stored.isNullOrEmpty()) defValues else stored.split(",").toSet()
    }
    override fun getInt(key: String, defValue: Int): Int {
        val stored = DatabaseHelper.getSetting("${name}_${key}")
        return stored?.toIntOrNull() ?: defValue
    }
    override fun getLong(key: String, defValue: Long): Long {
        val stored = DatabaseHelper.getSetting("${name}_${key}")
        return stored?.toLongOrNull() ?: defValue
    }
    override fun getFloat(key: String, defValue: Float): Float {
        val stored = DatabaseHelper.getSetting("${name}_${key}")
        return stored?.toFloatOrNull() ?: defValue
    }
    override fun getBoolean(key: String, defValue: Boolean): Boolean {
        val stored = DatabaseHelper.getSetting("${name}_${key}")
        return if (stored.isNullOrEmpty()) defValue else stored == "true"
    }
    override fun contains(key: String): Boolean {
        return DatabaseHelper.getSetting("${name}_${key}") != null
    }
    override fun edit(): SharedPreferences.Editor {
        return MockEditor(name)
    }
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {}
}
