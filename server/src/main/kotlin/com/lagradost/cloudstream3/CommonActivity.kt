package com.lagradost.cloudstream3

import android.app.Activity
import com.lagradost.cloudstream3.utils.UiText

/** Headless no-op UI callback surface used by compiled plugins. */
object CommonActivity {
    fun getActivity(): Activity? = null
    @JvmStatic fun showToast(message: String?, duration: Int? = null) = Unit
    @JvmStatic fun showToast(message: UiText?, duration: Int? = null) = Unit
    @JvmStatic fun showToast(activity: Activity?, message: String?, duration: Int? = null) = Unit
    @JvmStatic fun showToast(activity: Activity?, message: UiText, duration: Int) = Unit
}
