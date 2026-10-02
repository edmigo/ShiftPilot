package com.shiftpilot.woltdiagnostic

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var packageEdit: EditText
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private val captureRequestCode = 501

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        requestNotificationPermissionIfNeeded()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(Color.rgb(245, 247, 250))
        }

        root.addView(TextView(this).apply {
            text = "ShiftPilot · Wolt Diagnostic V1"
            textSize = 24f
            setTextColor(Color.rgb(17, 24, 39))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Purpose: verify what a courier's phone can legitimately expose from Wolt via notifications, Accessibility, and normal Android screen-capture permission. This app does not click, accept, reject, or bypass secure screens."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(8), 0, dp(14))
        })

        packageEdit = EditText(this).apply {
            hint = "Wolt package (leave empty for auto-detect by name)"
            setText(DiagnosticStore.getTargetPackage(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        root.addView(packageEdit, matchWrap())

        root.addView(button("Save target package") {
            DiagnosticStore.setTargetPackage(this, packageEdit.text.toString())
            Toast.makeText(this, "Target saved", Toast.LENGTH_SHORT).show()
            refresh()
        })

        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(30, 41, 59))
            setPadding(0, dp(10), 0, dp(10))
        }
        root.addView(statusView, matchWrap())

        root.addView(button("1 · Enable notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        root.addView(button("2 · Enable Accessibility diagnostic") {
            AlertDialog.Builder(this)
                .setTitle("Accessibility diagnostic")
                .setMessage("Enable only the ShiftPilot Wolt UI Probe service. It reads visible UI text for this diagnostic and never performs clicks or gestures.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .show()
        })

        root.addView(button("3 · Test screen capture in 8 seconds") {
            AlertDialog.Builder(this)
                .setTitle("Screen capture test")
                .setMessage("Android will ask for normal screen-capture permission. After approving it, immediately open the Wolt order/offer screen. ShiftPilot analyzes one frame after 8 seconds and stores only statistics — not the screenshot. If Wolt marks the screen secure, the result should look black/blocked.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Start") { _, _ -> requestCapture() }
                .show()
        })

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionRow.addView(button("Clear log") {
            DiagnosticStore.clear(this)
            refresh()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        actionRow.addView(button("Share report") {
            shareReport()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        root.addView(actionRow, matchWrap())

        root.addView(TextView(this).apply {
            text = "Live diagnostic log"
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(17, 24, 39))
            setPadding(0, dp(16), 0, dp(6))
        })

        logView = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.rgb(17, 24, 39))
            setBackgroundColor(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(logView, matchWrap())
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

    private fun requestCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), captureRequestCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != captureRequestCode) return
        if (resultCode != RESULT_OK || data == null) {
            DiagnosticStore.append(this, "CAPTURE", "User cancelled screen capture permission")
            return
        }

        val service = Intent(this, CaptureProbeService::class.java).apply {
            putExtra(CaptureProbeService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureProbeService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        Toast.makeText(this, "Now open Wolt. Test runs in 8 seconds.", Toast.LENGTH_LONG).show()
    }

    private fun refresh() {
        val notif = notificationListenerEnabled()
        val access = accessibilityEnabled()
        val target = DiagnosticStore.getTargetPackage(this)
        statusView.text = buildString {
            append("Notification listener: ").append(if (notif) "ON" else "OFF").append('\n')
            append("Accessibility probe: ").append(if (access) "ON" else "OFF").append('\n')
            append("Target: ").append(if (target.isBlank()) "auto-detect Wolt" else target)
        }
        logView.text = DiagnosticStore.read(this)
    }

    private fun notificationListenerEnabled(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        return nm.isNotificationListenerAccessGranted(
            ComponentName(this, NotificationProbeService::class.java)
        )
    }

    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val target = ComponentName(this, AccessibilityProbeService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(target, ignoreCase = true) }
    }

    private fun shareReport() {
        val report = DiagnosticStore.read(this)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "ShiftPilot Wolt diagnostic report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(i, "Share diagnostic report"))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 601)
        }
    }

    private fun button(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { click() }
        isAllCaps = false
    }

    private fun button(text: String, click: () -> Unit, lp: LinearLayout.LayoutParams): Button =
        button(text, click).also { it.layoutParams = lp }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
