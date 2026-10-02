package com.shiftpilot.woltdiagnostic

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticStore {
    private const val PREFS = "diag_prefs"
    private const val TARGET = "target_package"
    private const val FG_PACKAGE = "foreground_package"
    private const val FG_AT = "foreground_at"
    private const val COLLECTOR_RUNNING = "collector_running"
    private const val LAST_CANDIDATE = "last_candidate"
    private const val LOG_FILE = "diagnostic.log"
    private const val MAX_LOG_BYTES = 500_000L

    fun getTargetPackage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(TARGET, "com.wolt.courierapp")
            ?.trim()
            .orEmpty()

    fun setTargetPackage(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(TARGET, value.trim())
            .apply()
    }

    fun matchesTarget(context: Context, packageName: String?): Boolean {
        val pkg = packageName.orEmpty()
        if (pkg.isBlank()) return false

        val explicit = getTargetPackage(context)
        if (explicit.isNotBlank()) return pkg == explicit

        if (pkg.contains("wolt", ignoreCase = true)) return true

        return try {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            val label = context.packageManager.getApplicationLabel(info).toString()
            label.contains("wolt", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    fun noteForegroundPackage(context: Context, packageName: String?) {
        val pkg = packageName.orEmpty()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(FG_PACKAGE, pkg)
            .putLong(FG_AT, System.currentTimeMillis())
            .apply()
    }

    fun isTargetForeground(context: Context, maxAgeMs: Long = 6_000): Boolean {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pkg = p.getString(FG_PACKAGE, "").orEmpty()
        val at = p.getLong(FG_AT, 0L)
        return System.currentTimeMillis() - at <= maxAgeMs && matchesTarget(context, pkg)
    }

    fun setCollectorRunning(context: Context, running: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(COLLECTOR_RUNNING, running).apply()
    }

    fun isCollectorRunning(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(COLLECTOR_RUNNING, false)

    fun setLastCandidate(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(LAST_CANDIDATE, value).apply()
    }

    fun getLastCandidate(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LAST_CANDIDATE, "No order candidate yet.")
            .orEmpty()

    @Synchronized
    fun append(context: Context, source: String, message: String) {
        val file = File(context.filesDir, LOG_FILE)
        if (file.exists() && file.length() > MAX_LOG_BYTES) {
            val tail = file.readText().takeLast(220_000)
            file.writeText("[log trimmed]\n$tail\n")
        }

        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$ts [$source] ${sanitize(message)}\n"
        file.appendText(line)
    }

    fun read(context: Context): String {
        val file = File(context.filesDir, LOG_FILE)
        if (!file.exists()) return "No diagnostic events yet."
        return file.readText().takeLast(180_000)
    }

    fun clear(context: Context) {
        File(context.filesDir, LOG_FILE).delete()
        setLastCandidate(context, "No order candidate yet.")
    }

    private fun sanitize(raw: String): String {
        return raw
            .replace(Regex("(?<!\\d)(?:\\+?\\d[\\d ()-]{7,}\\d)(?!\\d)"), "[phone redacted]")
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
            .trim()
    }
}
