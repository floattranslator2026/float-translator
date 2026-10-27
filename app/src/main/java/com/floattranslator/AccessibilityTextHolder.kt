package com.floattranslator

object AccessibilityTextHolder {
    @Volatile
    var latestText: String = ""

    fun update(text: String) {
        if (text.isNotBlank()) latestText = text
    }
}
