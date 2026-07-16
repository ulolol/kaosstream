package com.lagradost.cloudstream3

import android.app.Application
import android.content.Context
import android.content.MockSharedPreferences
import com.lagradost.cloudstream3.utils.Event
import java.util.concurrent.ConcurrentHashMap

class CloudStreamApp : Application() {
    companion object {
        private val values = ConcurrentHashMap<String, Any?>()
        private var serverContext: Context? = null

        @JvmStatic
        fun getContext(): Context? = serverContext

        @JvmStatic
        fun setContext(context: Context?) {
            serverContext = context
        }

        @JvmStatic
        fun setKey(path: String, value: Any?) {
            if (value == null) values.remove(path) else values[path] = value
        }

        @JvmStatic
        fun <T> getKey(path: String, valueType: Class<T>): T? =
            valueType.cast(values[path])
    }
}
