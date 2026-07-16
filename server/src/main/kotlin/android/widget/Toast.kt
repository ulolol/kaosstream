package android.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup

open class TextView(context: Context) : View(context) {
    var text: CharSequence = ""
    fun setText(text: CharSequence) { this.text = text }
    fun getText(): CharSequence = text
    interface OnEditorActionListener
}

open class Button(context: Context) : TextView(context)
open class EditText(context: Context) : TextView(context)

open class CompoundButton(context: Context) : Button(context) {
    interface OnCheckedChangeListener
}
open class CheckBox(context: Context) : CompoundButton(context)
open class RadioButton(context: Context) : CompoundButton(context)
open class RadioGroup(context: Context) : LinearLayout(context)

open class Switch(context: Context) : CompoundButton(context)

open class ImageView(context: Context) : View(context)
open class ProgressBar(context: Context) : View(context)

open class FrameLayout(context: Context) : ViewGroup() {
    class LayoutParams(width: Int, height: Int) : ViewGroup.LayoutParams(width, height)
}

open class LinearLayout(context: Context) : ViewGroup() {
    class LayoutParams(width: Int, height: Int) : ViewGroup.LayoutParams(width, height)
}

open class ScrollView(context: Context) : FrameLayout(context)
open class ListView(context: Context) : ViewGroup()

open class Spinner(context: Context) : View(context)
interface SpinnerAdapter

class ArrayAdapter<T>(context: Context, resource: Int, objects: Array<T>) : SpinnerAdapter

class Toast(context: Context) {
    fun show() {}
    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1
        @JvmStatic
        fun makeText(context: Context, text: CharSequence, duration: Int): Toast = Toast(context)
        @JvmStatic
        fun makeText(context: Context, resId: Int, duration: Int): Toast = Toast(context)
    }
}
