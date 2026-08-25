package com.example.moge3dscanner.ui.main

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.moge3dscanner.thermal.BulkUvc
import com.example.moge3dscanner.thermal.UsbDesc
import com.example.moge3dscanner.thermal.Xtherm
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages USB thermal camera connections (HT-203U, InfiRay, HIKMICRO UVC devices)
 * using the native Android USB Host API and BulkUvc driver.
 */
class ThermalCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "ThermalCameraManager"
        private const val ACTION_USB_PERMISSION = "com.example.moge3dscanner.USB_PERMISSION"
    }

    private val usbManager by lazy { context.getSystemService(Context.USB_SERVICE) as UsbManager }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val latestBitmap = AtomicReference<Bitmap?>(null)
    @Volatile private var isStreaming = false
    @Volatile private var isConnected = false
    @Volatile private var tempStatusText = ""
    @Volatile private var minTempC = 0f
    @Volatile private var maxTempC = 0f
    @Volatile private var centerTempC = 0f

    private var usbConn: UsbDeviceConnection? = null
    private var bulk: BulkUvc? = null
    private var usbDevice: UsbDevice? = null
    private var modes: List<BulkUvc.FrameDesc> = emptyList()
    private var modeIdx = 0
    @Volatile private var curW = Xtherm.WIDTH
    @Volatile private var curH = Xtherm.FRAME_HEIGHT
    @Volatile private var radiometricSeen = false

    private var bitmap: Bitmap = Bitmap.createBitmap(Xtherm.WIDTH, Xtherm.HEIGHT, Bitmap.Config.ARGB_8888)
    private var pixels = IntArray(Xtherm.PIXELS)
    private val palette = Xtherm.ironPalette()
    private var frameU16 = ShortArray(256 * 520)
    private var tempTable: FloatArray? = null
    private var frameCount = 0L
    @Volatile private var framesReceived = 0L

    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "USB permission result: granted=$granted dev=${device?.productName}")
                    if (granted && device != null) {
                        openAndStart(device)
                    } else {
                        tempStatusText = "USB permission denied"
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "USB device attached")
                    scanAndConnect()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "USB device detached")
                    stopStreaming()
                    tempStatusText = "Device detached"
                }
            }
        }
    }

    init {
        registerReceiver()
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister USB receiver: ${e.message}")
        }
        receiverRegistered = false
    }

    private fun hasVideoInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.interfaceClass == 14) return true // UVC Video Class
        }
        return false
    }

    fun startStreaming(): Boolean {
        if (isStreaming) return true
        return scanAndConnect()
    }

    private fun scanAndConnect(): Boolean {
        val device = usbManager.deviceList.values.firstOrNull { hasVideoInterface(it) }
        if (device == null) {
            tempStatusText = "No USB thermal camera found"
            Log.w(TAG, "No UVC video camera device found in USB device list")
            return false
        }

        Log.i(TAG, "Found thermal device VID=0x%04x PID=0x%04x name=%s".format(
            device.vendorId, device.productId, device.productName ?: "Unknown"
        ))

        if (usbManager.hasPermission(device)) {
            openAndStart(device)
            return true
        } else {
            tempStatusText = "Requesting USB permission..."
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags
            )
            usbManager.requestPermission(device, pi)
            return false
        }
    }

    private fun openAndStart(device: UsbDevice) {
        stopStreamingInternal()
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            tempStatusText = "Failed to open USB device"
            Log.e(TAG, "usbManager.openDevice returned null")
            return
        }

        usbDevice = device
        usbConn = conn
        conn.rawDescriptors?.let { Log.d(TAG, UsbDesc.summarize(it)) }

        val uvc = BulkUvc(device, conn, ::onBulkFrame) { msg -> Log.d(TAG, msg) }
        bulk = uvc
        val all = uvc.parseDescriptors()
        if (all.isEmpty()) {
            tempStatusText = "No uncompressed frame descriptors found"
            Log.w(TAG, "No uncompressed frame descriptors in device")
            return
        }

        // Preference: stacked 256x392 first (image + thermal), then 256x196, 256x400, etc.
        val prio = mapOf(392 to 0, 196 to 1, 400 to 2, 200 to 3, 192 to 4)
        modes = all.sortedBy { (if (it.width == 256) 0 else 10) + (prio[it.height] ?: 9) }
        modeIdx = 0
        radiometricSeen = false
        isStreaming = true
        isConnected = true
        startMode("initial")
    }

    private fun startMode(reason: String) {
        val uvc = bulk ?: return
        val fd = modes.getOrNull(modeIdx) ?: return
        Log.i(TAG, "startMode ${fd.width}x${fd.height} ($reason)")
        curW = fd.width
        curH = fd.height
        framesReceived = 0
        frameCount = 0
        tempTable = null
        if (!uvc.start(fd)) {
            Log.e(TAG, "Mode ${fd.width}x${fd.height} failed to start")
            tempStatusText = "Mode ${fd.width}x${fd.height} failed"
        }
    }

    private fun onBulkFrame(frame: ByteBuffer) {
        try {
            processFrame(frame)
        } catch (t: Throwable) {
            Log.e(TAG, "Frame processing error: ${t.message}", t)
        }
    }

    private fun ensurePixels(n: Int) {
        if (pixels.size < n) pixels = IntArray(n)
    }

    private fun inRange(off: Int, lo: Int, hi: Int): Double {
        var hit = 0
        var total = 0
        var i = off
        val end = off + Xtherm.PIXELS
        while (i < end) {
            val v = frameU16[i].toInt() and 0xFFFF
            if (v in (lo - 256)..(hi + 256)) hit++
            total++
            i += 97
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }

    private fun processFrame(frame: ByteBuffer) {
        framesReceived++
        frameCount++

        val nU16 = frame.remaining() / 2
        if (nU16 < curW * 100) return
        if (frameU16.size < nU16) frameU16 = ShortArray(nU16)
        frame.order(ByteOrder.LITTLE_ENDIAN)
        frame.asShortBuffer().get(frameU16, 0, nU16)

        val w = curW
        val h = if (nU16 % w == 0) nU16 / w else curH

        // 1) Xtherm-style metadata (256-wide layouts)
        if (w == 256) {
            var k = 1
            while (k * 196 <= h) {
                val base = (k - 1) * 196
                val meta = Xtherm.parseMeta(frameU16, w * (base + Xtherm.HEIGHT))
                if (meta != null) {
                    val cands = ArrayList<Int>(3)
                    cands.add(w * base)
                    if (w * (base + 196) + Xtherm.PIXELS <= nU16) cands.add(w * (base + 196))
                    if (base >= 196) cands.add(w * (base - 196))
                    val best = cands.maxByOrNull { inRange(it, meta.minRaw, meta.maxRaw) } ?: (w * base)
                    renderXtherm(meta, best)
                    return
                }
                k++
            }
        }

        // 2) HIKMICRO stacked layout: one block rendered YUY2, other is raw thermal counts
        if (w == 256 && h >= 384) {
            fun chromaFrac(rowStart: Int): Double {
                var hit = 0; var total = 0
                var i = w * rowStart
                val end = i + Xtherm.PIXELS
                while (i < end) {
                    if (((frameU16[i].toInt() shr 8) and 0xFF) == 0x80) hit++
                    total++; i += 101
                }
                return hit.toDouble() / total
            }
            val f0 = chromaFrac(0)
            val f1 = chromaFrac(196)
            val rawRow = when {
                f0 < 0.2 && f1 > 0.8 -> 0
                f1 < 0.2 && f0 > 0.8 -> 196
                else -> -1
            }
            if (rawRow >= 0) {
                renderHikRaw(w * rawRow)
                return
            }
        }

        // 3) Stacked frame: bottom half raw Kelvin*64 (TC001 style)
        if (h >= 2 * 190) {
            val rows = h / 2
            val bottom = w * rows
            val count = w * minOf(rows, h - rows)
            var mn = 65535; var mx = 0
            for (i in bottom until bottom + count) {
                val v = frameU16[i].toInt() and 0xFFFF
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            if (mn in 14000..50000 && mx in 14000..50000 && mx > mn + 16) {
                renderK64(bottom, mn, mx, w, minOf(rows, h - rows))
                return
            }
        }

        // Fallback: simple luma display
        val rows = minOf(h, 192)
        ensurePixels(w * rows)
        for (i in 0 until w * rows) {
            val y = frameU16[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
        }
        updateOutputBitmap(w, rows)
    }

    private fun renderXtherm(meta: Xtherm.Meta, imageOff: Int) {
        if (tempTable == null || frameCount % 8 == 0L) {
            tempTable = Xtherm.tempTable(meta, false)
        }
        val table = tempTable ?: return
        val minRaw = meta.minRaw
        val span = (meta.maxRaw - minRaw).coerceAtLeast(1)
        ensurePixels(Xtherm.PIXELS)
        for (i in 0 until Xtherm.PIXELS) {
            val v = frameU16[imageOff + i].toInt() and 0xFFFF
            pixels[i] = palette[((v - minRaw) * 255 / span).coerceIn(0, 255)]
        }
        minTempC = table[meta.minRaw]
        centerTempC = table[meta.centerRaw]
        maxTempC = table[meta.maxRaw]
        tempStatusText = "%.1f°C (%.1f..%.1f)".format(centerTempC, minTempC, maxTempC)
        updateOutputBitmap(Xtherm.WIDTH, Xtherm.HEIGHT)
    }

    private fun hikToC(v: Int): Float = ((v - 4405) * 0.0373349 + 3.9).toFloat()

    private fun renderHikRaw(offset: Int) {
        val w = Xtherm.WIDTH
        val rows = Xtherm.HEIGHT
        var mn = 65535; var mx = 0
        for (i in 0 until w * rows) {
            val v = frameU16[offset + i].toInt() and 0xFFFF
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        val span = (mx - mn).coerceAtLeast(1)
        ensurePixels(w * rows)
        for (i in 0 until w * rows) {
            val v = frameU16[offset + i].toInt() and 0xFFFF
            pixels[i] = palette[((v - mn) * 255 / span).coerceIn(0, 255)]
        }
        val center = frameU16[offset + (rows / 2) * w + w / 2].toInt() and 0xFFFF
        minTempC = hikToC(mn)
        centerTempC = hikToC(center)
        maxTempC = hikToC(mx)
        tempStatusText = "%.1f°C (%.1f..%.1f)".format(centerTempC, minTempC, maxTempC)
        updateOutputBitmap(w, rows)
    }

    private fun renderK64(offset: Int, mn: Int, mx: Int, w: Int, rows: Int) {
        val span = (mx - mn).coerceAtLeast(1)
        val n = w * rows
        ensurePixels(n)
        for (i in 0 until n) {
            val v = frameU16[offset + i].toInt() and 0xFFFF
            pixels[i] = palette[((v - mn) * 255 / span).coerceIn(0, 255)]
        }
        val center = frameU16[offset + (rows / 2) * w + w / 2].toInt() and 0xFFFF
        fun k64(v: Int) = (v / 64.0 - 273.15).toFloat()
        minTempC = k64(mn)
        centerTempC = k64(center)
        maxTempC = k64(mx)
        tempStatusText = "%.1f°C (%.1f..%.1f)".format(centerTempC, minTempC, maxTempC)
        updateOutputBitmap(w, rows)
    }

    private fun updateOutputBitmap(w: Int, h: Int) {
        if (bitmap.width != w || bitmap.height != h) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        // Store a clean copy in latestBitmap for point-cloud coloring / snapshots
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        latestBitmap.set(copy)
    }

    private fun stopStreamingInternal() {
        isStreaming = false
        bulk?.close()
        bulk = null
        usbConn?.close()
        usbConn = null
        usbDevice = null
    }

    fun stopStreaming() {
        stopStreamingInternal()
        latestBitmap.set(null)
    }

    fun captureFrame(): Bitmap? = latestBitmap.get()

    fun isStreaming(): Boolean = isStreaming

    fun isSdkAvailable(): Boolean = true // Pure Android USB Host API, no native closed-source SDK required!

    fun isDeviceConnected(): Boolean = isConnected

    fun getTemperatureInfo(): String = tempStatusText

    fun getMinTempC(): Float = minTempC
    fun getMaxTempC(): Float = maxTempC
    fun getCenterTempC(): Float = centerTempC

    fun close() {
        stopStreaming()
        unregisterReceiver()
    }
}
