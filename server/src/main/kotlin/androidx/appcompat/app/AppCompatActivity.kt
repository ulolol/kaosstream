package androidx.appcompat.app

import android.app.Activity
import android.app.Dialog
import android.content.Context

open class AppCompatActivity : Activity()

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
