package com.example.moge3dscanner.ui.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.hcusbsdk.jni.HCUSBSDKByJNI
import com.hcusbsdk.jni.StreamCallBack_JNI
import com.hcusbsdk.jni.USB_FRAME_INFO
import com.hcusbsdk.jni.USB_STREAM_CALLBACK_PARAM
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

class ThermalCameraManager(private val context: Context) {

    private val latestBitmap = AtomicReference<Bitmap?>(null)
    @Volatile private var isStreaming = false
    @Volatile private var sdkAvailable = false

    private val streamCallback = object : StreamCallBack_JNI() {
        override fun fStreamCallback_JNI(nDataType: Int, frameInfo: USB_FRAME_INFO) {
            try {
                val buf = frameInfo.pBuf ?: return
                val w = frameInfo.dwWidth
                val h = frameInfo.dwHeight
                if (w <= 0 || h <= 0 || buf.isEmpty()) return
                yuvToBitmap(buf, w, h)?.let { latestBitmap.set(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Frame processing failed", e)
            }
        }
    }

    init {
        sdkAvailable = try {
            HCUSBSDKByJNI.getInstance()
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Hikvision SDK not available: ${e.message}")
            false
        }
    }

    fun startStreaming(channel: Int = 0): Boolean {
        if (!sdkAvailable || isStreaming) return isStreaming
        return try {
            val sdk = HCUSBSDKByJNI.getInstance()
            val param = USB_STREAM_CALLBACK_PARAM().apply {
                dwSize = 8
                dwStreamType = 0
            }
            val result = sdk.USB_StartStreamCallback(channel, param, streamCallback)
            isStreaming = (result == 0)
            Log.d(TAG, "startStreaming ch=$channel result=$result streaming=$isStreaming")
            isStreaming
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming", e)
            false
        }
    }

    fun stopStreaming(channel: Int = 0) {
        if (!sdkAvailable || !isStreaming) return
        try {
            HCUSBSDKByJNI.getInstance().USB_StopChannel(channel, 0)
            isStreaming = false
            Log.d(TAG, "Streaming stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop streaming", e)
        }
    }

    fun captureFrame(): Bitmap? = latestBitmap.get()

    fun isStreaming(): Boolean = isStreaming

    fun isSdkAvailable(): Boolean = sdkAvailable

    private fun yuvToBitmap(yuv: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            // Pad to NV21 size if needed (Y plane + UV plane = w*h + w*h/2)
            val expectedSize = width * height * 3 / 2
            val data = if (yuv.size >= expectedSize) yuv else yuv.copyOf(expectedSize)
            val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
            BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        } catch (e: Exception) {
            Log.e(TAG, "YUV->Bitmap failed (w=$width h=$height bufLen=${yuv.size})", e)
            null
        }
    }

    companion object {
        private const val TAG = "ThermalCameraManager"
    }
}
