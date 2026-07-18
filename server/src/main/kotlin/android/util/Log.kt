package android.util

import java.util.regex.Pattern

object Log {
    @JvmStatic fun d(tag: String, msg: String): Int { println("[$tag] DEBUG: $msg"); return 0 }
    @JvmStatic fun d(tag: String, msg: String, tr: Throwable): Int { println("[$tag] DEBUG: $msg - ${tr.message}"); tr.printStackTrace(); return 0 }
    @JvmStatic fun i(tag: String, msg: String): Int { println("[$tag] INFO: $msg"); return 0 }
    @JvmStatic fun i(tag: String, msg: String, tr: Throwable): Int { println("[$tag] INFO: $msg - ${tr.message}"); tr.printStackTrace(); return 0 }
    @JvmStatic fun w(tag: String, msg: String): Int { println("[$tag] WARN: $msg"); return 0 }
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable): Int { println("[$tag] WARN: $msg - ${tr.message}"); tr.printStackTrace(); return 0 }
    @JvmStatic fun e(tag: String, msg: String): Int { println("[$tag] ERROR: $msg"); return 0 }
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable): Int { println("[$tag] ERROR: $msg - ${tr.message}"); tr.printStackTrace(); return 0 }
    @JvmStatic fun v(tag: String, msg: String): Int { println("[$tag] VERBOSE: $msg"); return 0 }
    @JvmStatic fun v(tag: String, msg: String, tr: Throwable): Int { println("[$tag] VERBOSE: $msg - ${tr.message}"); tr.printStackTrace(); return 0 }
}

interface AttributeSet

class DisplayMetrics {
    var density: Float = 1.0f
    var widthPixels: Int = 1920
    var heightPixels: Int = 1080
}

class TypedValue {
    companion object {
        const val COMPLEX_UNIT_DIP = 1
        const val COMPLEX_UNIT_SP = 2
    }
}

object Patterns {
    @JvmField
    val WEB_URL: Pattern = Pattern.compile(
        "((?:http|https)://)?(?:[a-zA-Z0-9\\\\-]+\\\\.)+[a-zA-Z]{2,6}(?::\\\\d+)?(?:/[^\\\\s]*)?"
    )
}
