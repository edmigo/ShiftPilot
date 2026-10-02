package com.shiftpilot.woltdiagnostic

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import java.nio.ByteBuffer
import kotlin.math.sqrt

class CaptureProbeService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_probe"
        private const val NOTIFICATION_ID = 9001
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())
    private var captured = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("ShiftPilot capture diagnostic")
                .setContentText("Open the Wolt offer screen. Testing capture in 8 seconds…")
                .setOngoing(true)
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            DiagnosticStore.append(this, "CAPTURE", "Screen capture permission was not granted")
            stopSelf()
            return START_NOT_STICKY
        }

        val mgr = getSystemService(MediaProjectionManager::class.java)
        projection = mgr.getMediaProjection(resultCode, resultData)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cleanup()
                stopSelf()
            }
        }, handler)

        DiagnosticStore.append(
            this,
            "CAPTURE",
            "Permission granted. Open Wolt now; frame analysis starts in 8 seconds. No image is saved."
        )

        handler.postDelayed({ startSingleFrameProbe() }, 8_000)
        handler.postDelayed({
            if (!captured) {
                DiagnosticStore.append(this, "CAPTURE", "No frame received within timeout")
                cleanup()
                stopSelf()
            }
        }, 15_000)

        return START_NOT_STICKY
    }

    private fun startSingleFrameProbe() {
        val (width, height, density) = displaySpec()
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ ir ->
            if (captured) {
                ir.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            captured = true
            try {
                val result = analyzeFrame(image.planes[0].buffer, width, height, image.planes[0].pixelStride, image.planes[0].rowStride)
                DiagnosticStore.append(this, "CAPTURE", result)
            } catch (e: Exception) {
                DiagnosticStore.append(this, "CAPTURE", "Frame analysis failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                image.close()
                handler.postDelayed({
                    cleanup()
                    stopSelf()
                }, 500)
            }
        }, handler)

        display = projection?.createVirtualDisplay(
            "ShiftPilotProbe",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            handler
        )
    }

    private fun displaySpec(): Triple<Int, Int, Int> {
        val density = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= 30) {
            val wm = getSystemService(WindowManager::class.java)
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width().coerceAtLeast(1), b.height().coerceAtLeast(1), density)
        } else {
            @Suppress("DEPRECATION")
            Triple(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels, density)
        }
    }

    private fun analyzeFrame(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelStride: Int,
        rowStride: Int
    ): String {
        // Analyze only the central 70% to reduce influence from status/navigation bars.
        val x0 = (width * 0.15).toInt()
        val x1 = (width * 0.85).toInt()
        val y0 = (height * 0.15).toInt()
        val y1 = (height * 0.85).toInt()
        val step = ((width.coerceAtMost(height)) / 80).coerceAtLeast(4)

        var n = 0
        var nonBlack = 0
        var sum = 0.0
        var sumSq = 0.0

        for (y in y0 until y1 step step) {
            for (x in x0 until x1 step step) {
                val offset = y * rowStride + x * pixelStride
                if (offset + 2 >= buffer.limit()) continue
                val r = buffer.get(offset).toInt() and 0xff
                val g = buffer.get(offset + 1).toInt() and 0xff
                val b = buffer.get(offset + 2).toInt() and 0xff
                val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
                if (luma > 18) nonBlack++
                sum += luma
                sumSq += luma * luma
                n++
            }
        }

        if (n == 0) return "Frame received, but no sample pixels were available"
        val avg = sum / n
        val variance = (sumSq / n - avg * avg).coerceAtLeast(0.0)
        val std = sqrt(variance)
        val nonBlackPct = nonBlack * 100.0 / n

        val verdict = when {
            nonBlackPct < 3.0 && avg < 20 -> "LIKELY BLOCKED / BLACK (secure-window behavior is possible)"
            std < 4.0 && avg < 35 -> "LIKELY BLANK / BLOCKED"
            else -> "VISIBLE FRAME RECEIVED (capture appears technically available on this screen)"
        }

        return "Screen capture result: $verdict; center_non_black=${"%.1f".format(nonBlackPct)}%; avg_luma=${"%.1f".format(avg)}; std_luma=${"%.1f".format(std)}. No screenshot was stored."
    }

    private fun cleanup() {
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Screen capture diagnostic",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
