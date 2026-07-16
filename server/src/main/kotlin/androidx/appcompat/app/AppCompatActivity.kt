package androidx.appcompat.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import androidx.fragment.app.FragmentManager

open class AppCompatActivity : Activity() {
    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
        return android.content.MockSharedPreferences(name)
    }

    open fun getSupportFragmentManager(): FragmentManager = FragmentManager()
}

open class AlertDialog : Dialog() {
    class Builder(context: Context) {
        fun setTitle(title: CharSequence): Builder = this
        fun setMessage(message: CharSequence): Builder = this
        fun setPositiveButton(text: CharSequence, listener: android.content.DialogInterface.OnClickListener?): Builder = this
        fun setNegativeButton(text: CharSequence, listener: android.content.DialogInterface.OnClickListener?): Builder = this
        fun create(): AlertDialog = AlertDialog()
        fun show(): AlertDialog = AlertDialog()
    }
}
