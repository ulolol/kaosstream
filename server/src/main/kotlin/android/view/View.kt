package android.view

import android.content.Context

open class View(context: Context?)
open class ViewGroup : View(null) {
    open class LayoutParams(width: Int, height: Int)
}
