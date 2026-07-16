package android.net

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

abstract class Uri {
    abstract override fun toString(): String
    
    abstract val host: String?
    abstract val path: String?
    abstract val query: String?
    abstract val scheme: String?

    abstract fun getQueryParameter(key: String): String?
    abstract fun getQueryParameters(key: String): List<String>
    abstract fun buildUpon(): Builder

    class Builder {
        private var scheme: String? = null
        private var authority: String? = null
        private var path: String? = null
        private val queryParams = mutableListOf<Pair<String, String>>()
        private var fragment: String? = null

        fun scheme(scheme: String?): Builder {
            this.scheme = scheme
            return this
        }

        fun authority(authority: String?): Builder {
            this.authority = authority
            return this
        }

        fun path(path: String?): Builder {
            this.path = path
            return this
        }

        fun appendPath(newSegment: String): Builder {
            val p = this.path ?: ""
            this.path = if (p.endsWith("/")) p + newSegment else "$p/$newSegment"
            return this
        }

        fun appendQueryParameter(key: String, value: String): Builder {
            queryParams.add(key to value)
            return this
        }

        fun fragment(fragment: String?): Builder {
            this.fragment = fragment
            return this
        }

        fun build(): Uri {
            val q = if (queryParams.isNotEmpty()) {
                queryParams.joinToString("&") { 
                    "${URLEncoder.encode(it.first, "UTF-8")}=${URLEncoder.encode(it.second, "UTF-8")}" 
                }
            } else null
            
            val uri = URI(scheme, authority, path, q, fragment)
            return StringUri(uri.toString())
        }

        internal fun initFrom(uri: URI) {
            this.scheme = uri.scheme
            this.authority = uri.authority
            this.path = uri.path
            val q = uri.query
            if (!q.isNullOrEmpty()) {
                q.split("&").forEach { param ->
                    val parts = param.split("=", limit = 2)
                    if (parts.size == 2) {
                        queryParams.add(URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8"))
                    } else if (parts.size == 1) {
                        queryParams.add(URLDecoder.decode(parts[0], "UTF-8") to "")
                    }
                }
            }
            this.fragment = uri.fragment
        }
    }

    companion object {
        @JvmStatic
        fun parse(uriString: String): Uri {
            return StringUri(uriString)
        }
    }
}

class StringUri(private val uriString: String) : Uri() {
    private val parsed: URI? = try { URI(uriString) } catch (e: Exception) { null }

    override fun toString(): String = uriString
    override val host: String? get() = parsed?.host
    override val path: String? get() = parsed?.path
    override val query: String? get() = parsed?.query
    override val scheme: String? get() = parsed?.scheme

    override fun getQueryParameter(key: String): String? {
        val q = parsed?.query ?: return null
        return q.split("&")
            .map { it.split("=", limit = 2) }
            .firstOrNull { it.size == 2 && URLDecoder.decode(it[0], "UTF-8") == key }
            ?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
    }

    override fun getQueryParameters(key: String): List<String> {
        val q = parsed?.query ?: return emptyList()
        return q.split("&")
            .map { it.split("=", limit = 2) }
            .filter { it.size == 2 && URLDecoder.decode(it[0], "UTF-8") == key }
            .map { URLDecoder.decode(it[1], "UTF-8") }
    }

    override fun buildUpon(): Builder {
        val builder = Builder()
        if (parsed != null) {
            builder.initFrom(parsed)
        }
        return builder
    }
}
