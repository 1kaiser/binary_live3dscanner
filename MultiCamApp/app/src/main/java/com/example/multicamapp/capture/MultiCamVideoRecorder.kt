package com.example.multicamapp.capture

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.location.Location
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MultiCamVideoRecorder(private val context: Context) {

    val isRecording = mutableStateOf(false)
    val recordingSeconds = mutableIntStateOf(0)

    private var mediaRecorder: MediaRecorder? = null
    private var recordingThread: Thread? = null
    private val shouldStopRecording = AtomicBoolean(false)
    private var tempOutputFile: File? = null
    private var recordStartTime = 0L
    private var currentSessionId: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isRecording.value) {
                val elapsed = ((System.currentTimeMillis() - recordStartTime) / 1000).toInt()
                recordingSeconds.intValue = elapsed
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private val badgePaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    @SuppressLint("MissingPermission")
    fun startRecording(
        bitmapsProvider: () -> List<Pair<String, Bitmap>>,
        location: Location?,
        isLandscape: Boolean = true,
        customSessionId: String? = null,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isRecording.value) return
        currentSessionId = customSessionId

        try {
            val width = if (isLandscape) 1280 else 720
            val height = if (isLandscape) 720 else 1280
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val tempDir = context.cacheDir
            tempOutputFile = File(tempDir, "temp_rec_$timestamp.mp4")

            @Suppress("DEPRECATION")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            mediaRecorder = recorder

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setVideoSize(width, height)
            recorder.setVideoFrameRate(30)
            recorder.setVideoEncodingBitRate(6_000_000)
            recorder.setAudioEncodingBitRate(128_000)
            recorder.setAudioSamplingRate(44100)

            if (location != null) {
                recorder.setLocation(location.latitude.toFloat(), location.longitude.toFloat())
            }

            recorder.setOutputFile(tempOutputFile!!.absolutePath)
            recorder.prepare()

            val surface: Surface = recorder.surface
            recorder.start()

            isRecording.value = true
            recordStartTime = System.currentTimeMillis()
            recordingSeconds.intValue = 0
            mainHandler.post(timerRunnable)

            shouldStopRecording.set(false)

            recordingThread = Thread({
                val frameDurationMs = 33L // ~30 FPS
                val dstRect = Rect()

                while (!shouldStopRecording.get()) {
                    val frameStart = System.currentTimeMillis()
                    var canvas: Canvas? = null

                    try {
                        canvas = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            try {
                                surface.lockHardwareCanvas()
                            } catch (e: Exception) {
                                surface.lockCanvas(null)
                            }
                        } else {
                            surface.lockCanvas(null)
                        }

                        if (canvas != null) {
                            canvas.drawColor(Color.BLACK)
                            val frames = bitmapsProvider()

                            if (frames.isNotEmpty()) {
                                if (isLandscape) {
                                    // Landscape: Side by Side
                                    when (frames.size) {
                                        1 -> {
                                            val (name, bmp) = frames[0]
                                            dstRect.set(0, 0, width, height)
                                            drawBitmapAspectFit(canvas, bmp, dstRect)
                                            drawCameraLabel(canvas, name, 20f, 40f)
                                        }
                                        2 -> {
                                            val halfW = width / 2
                                            val (name1, bmp1) = frames[0]
                                            dstRect.set(0, 0, halfW, height)
                                            drawBitmapAspectFit(canvas, bmp1, dstRect)
                                            drawCameraLabel(canvas, name1, 20f, 40f)

                                            val (name2, bmp2) = frames[1]
                                            dstRect.set(halfW, 0, width, height)
                                            drawBitmapAspectFit(canvas, bmp2, dstRect)
                                            drawCameraLabel(canvas, name2, halfW + 20f, 40f)
                                        }
                                        else -> {
                                            val halfW = width / 2
                                            val halfH = height / 2

                                            val (name1, bmp1) = frames[0]
                                            dstRect.set(0, 0, halfW, height)
                                            drawBitmapAspectFit(canvas, bmp1, dstRect)
                                            drawCameraLabel(canvas, name1, 20f, 40f)

                                            val (name2, bmp2) = frames[1]
                                            dstRect.set(halfW, 0, width, halfH)
                                            drawBitmapAspectFit(canvas, bmp2, dstRect)
                                            drawCameraLabel(canvas, name2, halfW + 20f, 40f)

                                            val (name3, bmp3) = frames[2]
                                            dstRect.set(halfW, halfH, width, height)
                                            drawBitmapAspectFit(canvas, bmp3, dstRect)
                                            drawCameraLabel(canvas, name3, halfW + 20f, halfH + 40f)
                                        }
                                    }
                                } else {
                                    // Portrait: Top and Bottom Stacked
                                    when (frames.size) {
                                        1 -> {
                                            val (name, bmp) = frames[0]
                                            dstRect.set(0, 0, width, height)
                                            drawBitmapAspectFit(canvas, bmp, dstRect)
                                            drawCameraLabel(canvas, name, 20f, 40f)
                                        }
                                        2 -> {
                                            val halfH = height / 2
                                            val (name1, bmp1) = frames[0]
                                            dstRect.set(0, 0, width, halfH)
                                            drawBitmapAspectFit(canvas, bmp1, dstRect)
                                            drawCameraLabel(canvas, name1, 20f, 40f)

                                            val (name2, bmp2) = frames[1]
                                            dstRect.set(0, halfH, width, height)
                                            drawBitmapAspectFit(canvas, bmp2, dstRect)
                                            drawCameraLabel(canvas, name2, 20f, halfH + 40f)
                                        }
                                        else -> {
                                            val thirdH = height / 3
                                            for (i in 0 until minOf(3, frames.size)) {
                                                val (name, bmp) = frames[i]
                                                dstRect.set(0, i * thirdH, width, (i + 1) * thirdH)
                                                drawBitmapAspectFit(canvas, bmp, dstRect)
                                                drawCameraLabel(canvas, name, 20f, (i * thirdH) + 40f)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error drawing frame to recorder surface", e)
                    } finally {
                        if (canvas != null) {
                            try {
                                surface.unlockCanvasAndPost(canvas)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error unlocking canvas", e)
                            }
                        }
                    }

                    val elapsed = System.currentTimeMillis() - frameStart
                    val sleepMs = frameDurationMs - elapsed
                    if (sleepMs > 0) {
                        try {
                            Thread.sleep(sleepMs)
                        } catch (ignored: InterruptedException) {
                            break
                        }
                    }
                }
            }, "MultiCamVideoEncodingThread").apply {
                start()
            }

            onSuccess("Recording started")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            isRecording.value = false
            mainHandler.removeCallbacks(timerRunnable)
            onError(e.message ?: "Failed to start recording")
        }
    }

    private fun drawCameraLabel(canvas: Canvas, label: String, x: Float, y: Float) {
        val width = textPaint.measureText(label) + 20f
        canvas.drawRoundRect(x - 6f, y - 24f, x + width, y + 8f, 6f, 6f, badgePaint)
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

    fun stopRecording(onFinished: (String?) -> Unit) {
        if (!isRecording.value) {
            onFinished(null)
            return
        }

        shouldStopRecording.set(true)
        isRecording.value = false
        mainHandler.removeCallbacks(timerRunnable)

        Thread({
            try {
                recordingThread?.join(1000)
                recordingThread = null

                mediaRecorder?.apply {
                    try { stop() } catch (e: Exception) { Log.w(TAG, "Recorder stop error", e) }
                    try { reset() } catch (ignored: Exception) {}
                    try { release() } catch (ignored: Exception) {}
                }
                mediaRecorder = null

                val tempFile = tempOutputFile
                if (tempFile != null && tempFile.exists() && tempFile.length() > 0) {
                    val savedUri = saveToMediaStore(tempFile)
                    tempFile.delete()
                    mainHandler.post { onFinished(savedUri) }
                } else {
                    mainHandler.post { onFinished(null) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error finalizing recording", e)
                mainHandler.post { onFinished(null) }
            }
        }, "FinalizeRecordingThread").start()
    }

    private fun saveToMediaStore(sourceFile: File): String? {
        val timestamp = currentSessionId ?: SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "multicam_video_$timestamp.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/MultiCam")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null

        try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Log.d(TAG, "Saved video to MediaStore: $uri ($fileName)")
            return fileName
        } catch (e: Exception) {
            Log.e(TAG, "Error saving video to MediaStore", e)
            resolver.delete(uri, null, null)
            return null
        }
    }

    fun onDestroy() {
        if (isRecording.value) {
            stopRecording {}
        }
    }

    companion object {
        private const val TAG = "MultiCamVideoRecorder"
    }
}
