package com.lagradost.cloudstream3.utils

sealed class UiText {
    data class DynamicString(val value: String) : UiText() {
        override fun toString() = value
    }

    class StringResource(val resId: Int, val args: List<Any>) : UiText()
}

fun txt(value: String): UiText = UiText.DynamicString(value)
