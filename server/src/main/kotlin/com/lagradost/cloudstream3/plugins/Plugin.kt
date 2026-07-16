package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources
import com.lagradost.cloudstream3.actions.VideoClickAction

abstract class Plugin : BasePlugin() {
    open fun load(context: Context) {
        load()
    }
    fun registerVideoClickAction(element: VideoClickAction) {
        // No-op on server
    }
    var resources: Resources? = null
    var openSettings: ((context: Context) -> Unit)? = null
}
