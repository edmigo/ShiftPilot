package com.shiftpilot.woltdiagnostic

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationProbeService : NotificationListenerService() {
    override fun onListenerConnected() {
        DiagnosticStore.append(this, "NOTIF", "Notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!DiagnosticStore.matchesTarget(this, sbn.packageName)) return

        val e = sbn.notification.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val sub = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" | ") { it.toString() }
            .orEmpty()

        val combined = listOf(title, text, big, sub, lines)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" || ")

        DiagnosticStore.append(
            this,
            "NOTIF",
            "package=${sbn.packageName} text=${if (combined.isBlank()) "<no visible text>" else combined}"
        )
    }
}
