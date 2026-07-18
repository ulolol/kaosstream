package android.os

object Build {
    object VERSION {
        @JvmField val SDK_INT = 30
        @JvmField val CODENAME = "REL"
    }
    @JvmField val MODEL = "Server JVM"
    @JvmField val MANUFACTURER = "Generic"
    @JvmField val BRAND = "Generic"
}

class Bundle {
    private val map = mutableMapOf<String, Any?>()

    fun putString(key: String, value: String?) { map[key] = value }
    fun getString(key: String): String? = map[key] as? String
    fun getString(key: String, defaultValue: String): String = (map[key] as? String) ?: defaultValue

    fun putInt(key: String, value: Int) { map[key] = value }
    fun getInt(key: String): Int = map[key] as? Int ?: 0
    fun getInt(key: String, defaultValue: Int): Int = map[key] as? Int ?: defaultValue

    fun putBoolean(key: String, value: Boolean) { map[key] = value }
    fun getBoolean(key: String): Boolean = map[key] as? Boolean ?: false
    fun getBoolean(key: String, defaultValue: Boolean): Boolean = map[key] as? Boolean ?: defaultValue

    fun containsKey(key: String): Boolean = map.containsKey(key)
    fun remove(key: String) { map.remove(key) }
    fun clear() { map.clear() }
}

interface Parcelable {
    fun describeContents(): Int
    fun writeToParcel(dest: Parcel, flags: Int)

    interface Creator<T> {
        fun createFromParcel(source: Parcel): T
        fun newArray(size: Int): Array<T?>
    }
}

class Parcel
