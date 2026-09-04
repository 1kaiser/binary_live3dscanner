package com.example.multicamapp.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.location.Location
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.example.multicamapp.location.GpsLocationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MultiCamPhotoCapture {

    private const val TAG = "MultiCamPhotoCapture"

    suspend fun captureSimultaneousPhotos(
        context: Context,
        cameraFrames: List<Pair<String, Bitmap>>, // displayName to Bitmap
        location: Location?,
        isLandscape: Boolean = true,
        customSessionId: String? = null,
        onProgress: (String) -> Unit
    ): List<String> = withContext(Dispatchers.IO) {
        val savedFiles = mutableListOf<String>()
        if (cameraFrames.isEmpty()) return@withContext savedFiles

        val timestamp = customSessionId ?: SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        // 1. Save individual photos
        for ((camName, bitmap) in cameraFrames) {
            val cleanName = camName.replace(Regex("[^a-zA-Z0-9_]"), "_")
            val fileName = "multicam_${cleanName}_$timestamp.jpg"
            val savedName = saveBitmapToMediaStore(context, bitmap, fileName, location)
            if (savedName != null) {
                savedFiles.add(savedName)
            }
        }

        // 2. Generate and save composite photo if 2 or more cameras
        if (cameraFrames.size >= 2) {
            val compositeBitmap = createCompositeBitmap(cameraFrames, location, isLandscape)
            val compFileName = "multicam_composite_$timestamp.jpg"
            val compSaved = saveBitmapToMediaStore(context, compositeBitmap, compFileName, location)
            if (compSaved != null) {
                savedFiles.add(compSaved)
            }
        }

        savedFiles
    }

    private fun createCompositeBitmap(
        frames: List<Pair<String, Bitmap>>,
        location: Location?,
        isLandscape: Boolean
    ): Bitmap {
        val totalWidth = if (isLandscape) 1920 else 1080
        val totalHeight = if (isLandscape) 1080 else 1920
        val composite = Bitmap.createBitmap(totalWidth, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composite)
        canvas.drawColor(Color.BLACK)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }
        val badgePaint = Paint().apply {
            color = Color.argb(170, 0, 0, 0)
            style = Paint.Style.FILL
        }

        val dstRect = Rect()

        if (isLandscape) {
            // Landscape: Side by Side (Left & Right)
            when (frames.size) {
                2 -> {
                    val halfW = totalWidth / 2
                    val (name1, bmp1) = frames[0]
                    dstRect.set(0, 0, halfW, totalHeight)
                    drawBitmapAspectFit(canvas, bmp1, dstRect)
                    drawLabel(canvas, name1, 24f, 50f, textPaint, badgePaint)

                    val (name2, bmp2) = frames[1]
                    dstRect.set(halfW, 0, totalWidth, totalHeight)
                    drawBitmapAspectFit(canvas, bmp2, dstRect)
                    drawLabel(canvas, name2, halfW + 24f, 50f, textPaint, badgePaint)
                }
                else -> {
                    val halfW = totalWidth / 2
                    val halfH = totalHeight / 2
                    val (name1, bmp1) = frames[0]
                    dstRect.set(0, 0, halfW, totalHeight)
                    drawBitmapAspectFit(canvas, bmp1, dstRect)
                    drawLabel(canvas, name1, 24f, 50f, textPaint, badgePaint)

                    val (name2, bmp2) = frames[1]
                    dstRect.set(halfW, 0, totalWidth, halfH)
                    drawBitmapAspectFit(canvas, bmp2, dstRect)
                    drawLabel(canvas, name2, halfW + 24f, 50f, textPaint, badgePaint)

                    val (name3, bmp3) = frames[2]
                    dstRect.set(halfW, halfH, totalWidth, totalHeight)
                    drawBitmapAspectFit(canvas, bmp3, dstRect)
                    drawLabel(canvas, name3, halfW + 24f, halfH + 50f, textPaint, badgePaint)
                }
            }
        } else {
            // Portrait: Top and Bottom (Stacked)
            when (frames.size) {
                2 -> {
                    val halfH = totalHeight / 2
                    val (name1, bmp1) = frames[0]
                    dstRect.set(0, 0, totalWidth, halfH)
                    drawBitmapAspectFit(canvas, bmp1, dstRect)
                    drawLabel(canvas, name1, 24f, 50f, textPaint, badgePaint)

                    val (name2, bmp2) = frames[1]
                    dstRect.set(0, halfH, totalWidth, totalHeight)
                    drawBitmapAspectFit(canvas, bmp2, dstRect)
                    drawLabel(canvas, name2, 24f, halfH + 50f, textPaint, badgePaint)
                }
                else -> {
                    val thirdH = totalHeight / 3
                    for (i in 0 until minOf(3, frames.size)) {
                        val (name, bmp) = frames[i]
                        dstRect.set(0, i * thirdH, totalWidth, (i + 1) * thirdH)
                        drawBitmapAspectFit(canvas, bmp, dstRect)
                        drawLabel(canvas, name, 24f, (i * thirdH) + 50f, textPaint, badgePaint)
                    }
                }
            }
        }

        // Overlay bottom GPS & Timestamp banner
        val timestampStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val footerText = if (location != null) {
            val latStr = String.format(Locale.US, "%.5f°", location.latitude)
            val lonStr = String.format(Locale.US, "%.5f°", location.longitude)
            val altStr = String.format(Locale.US, "%.0fm", location.altitude)
            "MultiCam Live • $timestampStr • 📍 $latStr, $lonStr (Alt: $altStr)"
        } else {
            "MultiCam Live • $timestampStr"
        }

        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFD54F.toInt()
            textSize = 26f
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        val footerW = footerPaint.measureText(footerText) + 30f
        canvas.drawRoundRect(20f, totalHeight - 65f, 20f + footerW, totalHeight - 15f, 8f, 8f, badgePaint)
        canvas.drawText(footerText, 35f, totalHeight - 28f, footerPaint)

        return composite
    }

    private fun drawLabel(
        canvas: Canvas,
        label: String,
        x: Float,
        y: Float,
        textPaint: Paint,
        badgePaint: Paint
    ) {
        val width = textPaint.measureText(label) + 24f
        canvas.drawRoundRect(x - 8f, y - 34f, x + width, y + 12f, 8f, 8f, badgePaint)
        canvas.drawText(label, x + 4f, y, textPaint)
    }

    private fun drawBitmapAspectFit(canvas: Canvas, bitmap: Bitmap, dst: Rect) {
        val bW = bitmap.width.toFloat()
        val bH = bitmap.height.toFloat()
        val dW = dst.width().toFloat()
        val dH = dst.height().toFloat()

        val scale = minOf(dW / bW, dH / bH)
        val targetW = (bW * scale).toInt()
        val targetH = (bH * scale).toInt()

        val left = dst.left + (dst.width() - targetW) / 2
        val top = dst.top + (dst.height() - targetH) / 2
        val fitRect = Rect(left, top, left + targetW, top + targetH)
        canvas.drawBitmap(bitmap, null, fitRect, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    private fun saveBitmapToMediaStore(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
        location: Location?
    ): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MultiCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        try {
            // First write to temp file so ExifInterface can read and update EXIF cleanly
            val tempFile = File(context.cacheDir, "temp_$fileName")
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            // Apply GPS tags if available
            if (location != null) {
                try {
                    val exif = ExifInterface(tempFile.absolutePath)
                    GpsLocationManager.applyGpsToExif(exif, location)
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding EXIF GPS tags to $fileName", e)
                }
            }

            // Write into MediaStore output stream
            resolver.openOutputStream(uri)?.use { out ->
                tempFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            tempFile.delete()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            Log.d(TAG, "Saved photo $fileName successfully")
            return fileName
        } catch (e: Exception) {
            Log.e(TAG, "Error saving bitmap $fileName to MediaStore", e)
            resolver.delete(uri, null, null)
            return null
        }
    }
}
