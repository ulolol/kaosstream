package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.Event

class MainActivity {
    companion object {
        private val afterPluginsLoadedEvent = Event<Unit>()
        private val bookmarksUpdatedEvent = Event<Unit>()
        private val reloadLibraryEvent = Event<Unit>()
        private val reloadHomeEvent = Event<Unit>()

        @JvmStatic
        fun getAfterPluginsLoadedEvent(): Event<Unit> = afterPluginsLoadedEvent

        @JvmStatic
        fun getBookmarksUpdatedEvent(): Event<Unit> = bookmarksUpdatedEvent

        @JvmStatic
        fun getReloadLibraryEvent(): Event<Unit> = reloadLibraryEvent

        @JvmStatic
        fun getReloadHomeEvent(): Event<Unit> = reloadHomeEvent
    }
}
