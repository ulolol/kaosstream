package android.app

import android.content.Context
import android.content.DialogInterface

open class Activity : Context() {
    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
        return android.content.MockSharedPreferences(name)
    }
}
open class Dialog

class ActivityManager {
    class MemoryInfo {
        @JvmField
        var totalMem: Long = 0L
    }
}

open class Application {
    interface ActivityLifecycleCallbacks
}

open class AlertDialog : Dialog() {
    class Builder(context: Context) {
        fun setTitle(title: CharSequence): Builder = this
        fun setMessage(message: CharSequence): Builder = this
        fun setPositiveButton(text: CharSequence, listener: DialogInterface.OnClickListener?): Builder = this
        fun setNegativeButton(text: CharSequence, listener: DialogInterface.OnClickListener?): Builder = this
        fun create(): AlertDialog = AlertDialog()
        fun show(): AlertDialog = AlertDialog()
    }
}
