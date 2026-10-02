package com.shiftpilot.woltdiagnostic

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class CaptureProbeService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.shiftpilot.woltdiagnostic.STOP_COLLECTOR"
        private const val CHANNEL_ID = "screen_collector"
        private const val NOTIFICATION_ID = 9001
        private const val MIN_FRAME_INTERVAL_MS = 1_250L
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val ocrBusy = AtomicBoolean(false)
    private var lastFrameAt = 0L
    private var lastFingerprint: LongArray? = null
    private var lastTextHash = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("ShiftPilot Wolt Collector V2")
                .setContentText("Watching Wolt locally for order screens · no screenshots saved")
                .setOngoing(true)
                .build()
        )
        DiagnosticStore.setCollectorRunning(this, true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DiagnosticStore.append(this, "COLLECTOR", "Collector stopped by user")
            cleanup()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            DiagnosticStore.append(this, "COLLECTOR", "Screen capture permission was not granted")
            stopSelf()
            return START_NOT_STICKY
        }

        if (projection != null) return START_STICKY

        val mgr = getSystemService(MediaProjectionManager::class.java)
        projection = mgr.getMediaProjection(resultCode, resultData)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                DiagnosticStore.append(this@CaptureProbeService, "COLLECTOR", "MediaProjection stopped")
                cleanup()
                stopSelf()
            }
        }, handler)

        DiagnosticStore.append(
            this,
            "COLLECTOR",
            "Automatic collector started. Keep Accessibility enabled and open Wolt. OCR runs only while Wolt is foreground. Frames stay in memory and are not saved."
        )
        startContinuousCapture()
        return START_STICKY
    }

    private fun startContinuousCapture() {
        val (width, height, density) = displaySpec()
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader?.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameAt < MIN_FRAME_INTERVAL_MS) return@setOnImageAvailableListener
                if (!DiagnosticStore.isTargetForeground(this, 7_000)) return@setOnImageAvailableListener
                if (!ocrBusy.compareAndSet(false, true)) return@setOnImageAvailableListener

                lastFrameAt = now
                val bitmap = imageToBitmap(image, width, height)
                val fingerprint = frameFingerprint(bitmap)
                val previous = lastFingerprint
                lastFingerprint = fingerprint

                if (previous != null && fingerprintDistance(previous, fingerprint) < 5.0) {
                    bitmap.recycle()
                    ocrBusy.set(false)
                    return@setOnImageAvailableListener
                }

                runOcr(bitmap)
            } catch (e: Exception) {
                DiagnosticStore.append(this, "COLLECTOR", "Frame processing failed: ${e.javaClass.simpleName}: ${e.message}")
                ocrBusy.set(false)
            } finally {
                image.close()
            }
        }, handler)

        display = projection?.createVirtualDisplay(
            "ShiftPilotCollectorV2",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            handler
        )
    }

    private fun runOcr(bitmap: Bitmap) {
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { result -> handleOcr(result, bitmap.width, bitmap.height) }
            .addOnFailureListener { e ->
                DiagnosticStore.append(this, "OCR", "OCR failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            .addOnCompleteListener {
                bitmap.recycle()
                ocrBusy.set(false)
            }
    }

    private fun handleOcr(result: Text, width: Int, height: Int) {
        val lines = mutableListOf<OcrLine>()
        result.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val t = line.text.trim()
                if (t.isNotBlank()) {
                    val b = line.boundingBox
                    lines += OcrLine(t, b?.left ?: 0, b?.top ?: 0, b?.right ?: width, b?.bottom ?: height)
                }
            }
        }

        if (lines.isEmpty()) {
            DiagnosticStore.append(this, "OCR", "Wolt foreground frame received, but OCR found no Latin/numeric text")
            return
        }

        val normalized = lines.joinToString("\n") { it.text }.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
        val textHash = sha256(normalized)
        if (textHash == lastTextHash) return
        lastTextHash = textHash

        val candidate = WoltOrderParser.parse(lines, width, height)
        val rawSummary = lines.take(50).joinToString("\n") {
            "text=${it.text} box=${it.left},${it.top},${it.right},${it.bottom}"
        }
        DiagnosticStore.append(this, "OCR", "Recognized ${lines.size} lines\n$rawSummary")

        if (candidate != null) {
            val summary = candidate.pretty()
            DiagnosticStore.setLastCandidate(this, summary)
            DiagnosticStore.append(this, "ORDER", summary)
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == width) return padded

        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        padded.recycle()
        return cropped
    }

    private fun frameFingerprint(bitmap: Bitmap): LongArray {
        val sw = 16
        val sh = 16
        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        small.recycle()
        return pixels.map { c ->
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            (0.2126 * r + 0.7152 * g + 0.0722 * b).toLong()
        }.toLongArray()
    }

    private fun fingerprintDistance(a: LongArray, b: LongArray): Double {
        if (a.size != b.size || a.isEmpty()) return 999.0
        var sum = 0.0
        for (i in a.indices) sum += abs(a[i] - b[i]).toDouble()
        return sum / a.size
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

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun cleanup() {
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        DiagnosticStore.setCollectorRunning(this, false)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Wolt collector",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onDestroy() {
        cleanup()
        recognizer.close()
        super.onDestroy()
    }
}

data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

data class OrderCandidate(
    val payment: Double?,
    val distancesKm: List<Double>,
    val restaurantHint: String?,
    val addressHint: String?,
    val confidence: Double,
    val rawText: String
) {
    fun pretty(): String = buildString {
        append("AUTO ORDER CANDIDATE\n")
        append("payment=").append(payment?.let { "₪%.2f".format(Locale.US, it) } ?: "?").append('\n')
        append("distances_km=").append(if (distancesKm.isEmpty()) "?" else distancesKm.joinToString(", ") { "%.2f".format(Locale.US, it) }).append('\n')
        append("restaurant_hint=").append(restaurantHint ?: "?").append('\n')
        append("address_hint=").append(addressHint ?: "?").append('\n')
        append("confidence=").append("%.2f".format(Locale.US, confidence)).append('\n')
        append("raw=").append(rawText.take(700))
    }
}

object WoltOrderParser {
    private val amountRegex = Regex("(?:₪\\s*([0-9]+(?:[.,][0-9]{1,2})?)|([0-9]+(?:[.,][0-9]{1,2})?)\\s*(?:₪|ILS|NIS))", RegexOption.IGNORE_CASE)
    private val distanceRegex = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*(?:km|км|ק[\\\"״']?מ)", RegexOption.IGNORE_CASE)
    private val genericRegex = Regex("^(wolt|accept|reject|decline|delivery|pickup|order|new order|online|offline|map|minutes?|mins?|hours?|₪|ils|nis)$", RegexOption.IGNORE_CASE)
    private val addressWords = Regex("(street|st\\.?|road|rd\\.?|avenue|ave\\.?|boulevard|blvd|דרך|רחוב|שדרות|ул\\.?|улица|проспект)", RegexOption.IGNORE_CASE)

    fun parse(lines: List<OcrLine>, width: Int, height: Int): OrderCandidate? {
        val allText = lines.joinToString(" | ") { it.text }
        val amounts = amountRegex.findAll(allText).mapNotNull { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).replace(',', '.').toDoubleOrNull()
        }.filter { it in 1.0..1000.0 }.toList()

        val distances = distanceRegex.findAll(allText).mapNotNull { m ->
            m.groupValues[1].replace(',', '.').toDoubleOrNull()
        }.filter { it in 0.05..100.0 }.distinct().take(4).toList()

        val meaningful = lines.map { it.text.trim() }
            .filter { it.length >= 3 }
            .filterNot { genericRegex.matches(it.trim()) }
            .filterNot { amountRegex.containsMatchIn(it) }
            .filterNot { distanceRegex.containsMatchIn(it) }

        val restaurant = meaningful.firstOrNull { t ->
            val digits = t.count { it.isDigit() }
            digits <= 2 && t.any { it.isLetter() } && t.length in 3..80
        }

        val address = meaningful.firstOrNull { t ->
            addressWords.containsMatchIn(t) || (t.any { it.isDigit() } && t.any { it.isLetter() } && t.length in 5..120)
        }

        var confidence = 0.0
        if (amounts.isNotEmpty()) confidence += 0.45
        if (distances.isNotEmpty()) confidence += 0.20
        if (distances.size >= 2) confidence += 0.10
        if (!restaurant.isNullOrBlank()) confidence += 0.10
        if (!address.isNullOrBlank()) confidence += 0.05
        if (lines.size >= 5) confidence += 0.05
        if (allText.contains("wolt", ignoreCase = true) || allText.contains("accept", ignoreCase = true)) confidence += 0.05
        confidence = confidence.coerceAtMost(1.0)

        if (amounts.isEmpty() && distances.isEmpty()) return null

        return OrderCandidate(
            payment = amounts.firstOrNull(),
            distancesKm = distances,
            restaurantHint = restaurant,
            addressHint = address,
            confidence = confidence,
            rawText = allText
        )
    }
}
