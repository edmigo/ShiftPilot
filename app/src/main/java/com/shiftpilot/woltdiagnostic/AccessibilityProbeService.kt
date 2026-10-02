package com.shiftpilot.woltdiagnostic

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest
import java.util.ArrayDeque

class AccessibilityProbeService : AccessibilityService() {
    private var lastHash = ""
    private var lastLogAt = 0L

    override fun onServiceConnected() {
        DiagnosticStore.append(this, "A11Y", "Accessibility probe connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isNotBlank()) DiagnosticStore.noteForegroundPackage(this, pkg)
        if (!DiagnosticStore.matchesTarget(this, pkg)) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastLogAt < 900) return

        val root = rootInActiveWindow ?: return
        val snapshot = snapshotTree(root)
        val hash = sha256(snapshot)
        if (hash == lastHash) return

        lastHash = hash
        lastLogAt = now
        DiagnosticStore.append(
            this,
            "A11Y",
            "package=$pkg event=${event?.eventType} eventText=${event?.text?.joinToString(" | ").orEmpty()}\n$snapshot"
        )
    }

    override fun onInterrupt() {
        DiagnosticStore.append(this, "A11Y", "Accessibility probe interrupted")
    }

    private fun snapshotTree(root: AccessibilityNodeInfo): String {
        data class Item(val node: AccessibilityNodeInfo, val depth: Int)

        val q = ArrayDeque<Item>()
        q.add(Item(root, 0))
        val out = ArrayList<String>()
        var seen = 0

        while (q.isNotEmpty() && seen < 700 && out.size < 160) {
            val item = q.removeFirst()
            val n = item.node
            seen++

            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val id = n.viewIdResourceName.orEmpty()
            val cls = n.className?.toString()?.orEmpty()

            if (text.isNotBlank() || desc.isNotBlank() || id.isNotBlank()) {
                val indent = "  ".repeat(item.depth.coerceAtMost(8))
                out += "$indent[$cls] id=${id.ifBlank { "-" }} text=${text.ifBlank { "-" }} desc=${desc.ifBlank { "-" }} clickable=${n.isClickable}"
            }

            if (item.depth < 14) {
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { q.add(Item(it, item.depth + 1)) }
                }
            }
        }

        if (out.isEmpty()) return "<accessibility tree contains no readable text/ids>"
        return out.joinToString("\n")
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
