package android.content

abstract class Context {
    abstract fun getSharedPreferences(name: String, mode: Int): SharedPreferences

    companion object {
        const val MODE_PRIVATE: Int = 0
    }
}

class ClipData {
    class Item
}

class ClipboardManager

class ComponentName

class ContentResolver

interface DialogInterface {
    interface OnCancelListener
    interface OnClickListener {
        fun onClick(dialog: DialogInterface, which: Int)
    }
    interface OnDismissListener
    interface OnKeyListener
    interface OnMultiChoiceClickListener
    interface OnShowListener
}

open class Intent(action: String? = null) {
    fun putExtra(name: String, value: String?): Intent = this
    fun putExtra(name: String, value: Int): Intent = this
    fun putExtra(name: String, value: Boolean): Intent = this
    
    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
    }
}
