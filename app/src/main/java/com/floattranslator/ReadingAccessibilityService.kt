package com.floattranslator

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ReadingAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val sb = StringBuilder()
        collect(root, sb)
        if (sb.isNotEmpty()) AccessibilityTextHolder.update(sb.toString())
    }

    override fun onInterrupt() {}

    private fun collect(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int = 0) {
        if (depth > 100) return
        try {
            node.text?.let { if (it.isNotBlank()) sb.append(it).append("\n") }
            for (i in 0 until node.childCount) {
                try {
                    node.getChild(i)?.let { collect(it, sb, depth + 1) }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }
}
