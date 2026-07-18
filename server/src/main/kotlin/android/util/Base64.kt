package android.util

object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray {
        var encoder = if ((flags and URL_SAFE) != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if ((flags and NO_PADDING) != 0) {
            encoder = encoder.withoutPadding()
        }
        return encoder.encode(input)
    }

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        var encoder = if ((flags and URL_SAFE) != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if ((flags and NO_PADDING) != 0) {
            encoder = encoder.withoutPadding()
        }
        return encoder.encodeToString(input)
    }

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray {
        val decoder = if ((flags and URL_SAFE) != 0) java.util.Base64.getUrlDecoder() else java.util.Base64.getDecoder()
        val cleaned = if ((flags and NO_PADDING) != 0 || !str.endsWith("=")) {
            try {
                decoder.decode(str)
            } catch (e: IllegalArgumentException) {
                val padded = str + "=".repeat((4 - str.length % 4) % 4)
                decoder.decode(padded)
            }
        } else {
            decoder.decode(str)
        }
        return cleaned
    }

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray {
        return decode(String(input, Charsets.UTF_8), flags)
    }
}
