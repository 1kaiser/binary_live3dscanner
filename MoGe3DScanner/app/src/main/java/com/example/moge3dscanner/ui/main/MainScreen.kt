package com.example.moge3dscanner.ui.main

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.zIndex
import androidx.compose.material3.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.BorderStroke
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.graphics.Bitmap as AndroidBitmap

private fun saveSnapshotBitmap(context: Context, bitmap: Bitmap, fileName: String) {
    try {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    } catch (e: Exception) {
        Log.e("MainScreen", "Failed to save snapshot $fileName", e)
    }
}

private fun saveSnapshotRaw(context: Context, rawData: ShortArray, fileName: String) {
    try {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out ->
                val buf = ByteBuffer.allocate(rawData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                buf.asShortBuffer().put(rawData)
                out.write(buf.array())
            }
        }
    } catch (e: Exception) {
        Log.e("MainScreen", "Failed to save raw $fileName", e)
    }
}

enum class FullscreenMode {
    NONE, THERMAL, CAMERA
}

enum class PointCloudColorMode {
    FUSED, RGB, THERMAL
}

@Composable
fun FullscreenExpandIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier.size(14.dp)) {
        val stroke = 2.dp.toPx()
        val len = size.width * 0.35f
        val w = size.width
        val h = size.height
        // Top-Left
        drawLine(color, Offset(0f, 0f), Offset(len, 0f), stroke)
        drawLine(color, Offset(0f, 0f), Offset(0f, len), stroke)
        // Top-Right
        drawLine(color, Offset(w, 0f), Offset(w - len, 0f), stroke)
        drawLine(color, Offset(w, 0f), Offset(w, len), stroke)
        // Bottom-Left
        drawLine(color, Offset(0f, h), Offset(len, h), stroke)
        drawLine(color, Offset(0f, h), Offset(0f, h - len), stroke)
        // Bottom-Right
        drawLine(color, Offset(w, h), Offset(w - len, h), stroke)
        drawLine(color, Offset(w, h), Offset(w, h - len), stroke)
    }
}

@Composable
fun FullscreenExitIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier = modifier.size(14.dp)) {
        val stroke = 2.dp.toPx()
        val len = size.width * 0.35f
        val w = size.width
        val h = size.height
        val m = size.width * 0.22f
        // Inward corners
        drawLine(color, Offset(m, m), Offset(m + len, m), stroke)
        drawLine(color, Offset(m, m), Offset(m, m + len), stroke)
        drawLine(color, Offset(w - m, m), Offset(w - m - len, m), stroke)
        drawLine(color, Offset(w - m, m), Offset(w - m, m + len), stroke)
        drawLine(color, Offset(m, h - m), Offset(m + len, h - m), stroke)
        drawLine(color, Offset(m, h - m), Offset(m, h - m - len), stroke)
        drawLine(color, Offset(w - m, h - m), Offset(w - m - len, h - m), stroke)
        drawLine(color, Offset(w - m, h - m), Offset(w - m, h - m - len), stroke)
    }
}

@Composable
fun GeminiSparkleLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f

        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx, 0f)
            cubicTo(cx, cy * 0.45f, cx * 0.55f, cy, 0f, cy)
            cubicTo(cx * 0.55f, cy, cx, cy * 1.55f, cx, h)
            cubicTo(cx, cy * 1.55f, cx * 1.45f, cy, w, cy)
            cubicTo(cx * 1.45f, cy, cx, cy * 0.45f, cx, 0f)
            close()
        }

        val brush = androidx.compose.ui.graphics.Brush.linearGradient(
            colors = listOf(
                Color(0xFF4285F4), // Google Blue
                Color(0xFF9B72CB), // Gemini Purple
                Color(0xFFD96570)  // Coral Red
            ),
            start = Offset(0f, 0f),
            end = Offset(w, h)
        )
        drawPath(path, brush)
    }
}

@Composable
fun AntigravityLogo(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx, h * 0.15f)
            lineTo(w * 0.85f, h * 0.85f)
            lineTo(cx, h * 0.62f)
            lineTo(w * 0.15f, h * 0.85f)
            close()
        }

        val brush = androidx.compose.ui.graphics.Brush.linearGradient(
            colors = listOf(
                Color(0xFF7C4DFF), // Antigravity Purple
                Color(0xFF00E5FF)  // Antigravity Cyan
            ),
            start = Offset(0f, 0f),
            end = Offset(w, h)
        )
        drawPath(path, brush)
    }
}

class InteractiveGLView(context: Context, val renderer: GLPointRenderer) : GLSurfaceView(context) {
    private var previousX: Float = 0f
    private var previousY: Float = 0f

    private var previousMidX = 0f
    private var previousMidY = 0f
    private var isTwoFingerGesture = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scale = detector.scaleFactor
            if (scale > 0.01f) {
                renderer.targetZoom = (renderer.targetZoom / scale).coerceIn(0.5f, 15.0f)
                requestRender()
            }
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        renderer.requestRenderListener = { requestRender() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        val pointerCount = event.pointerCount

        if (pointerCount >= 2) {
            val midX = (event.getX(0) + event.getX(1)) / 2f
            val midY = (event.getY(0) + event.getY(1)) / 2f

            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    isTwoFingerGesture = true
                    previousMidX = midX
                    previousMidY = midY
                    renderer.yawVelocity = 0f
                    renderer.pitchVelocity = 0f
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isTwoFingerGesture && !scaleDetector.isInProgress) {
                        val dx = midX - previousMidX
                        val dy = midY - previousMidY

                        // 2-finger pan scaled by current distance
                        val sensitivity = 0.002f * renderer.zoom
                        renderer.targetPanX += dx * sensitivity
                        renderer.targetPanY -= dy * sensitivity

                        previousMidX = midX
                        previousMidY = midY
                        requestRender()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    isTwoFingerGesture = false
                    val actionIndex = event.actionIndex
                    val remainingIndex = if (actionIndex == 0) 1 else 0
                    if (remainingIndex < event.pointerCount) {
                        previousX = event.getX(remainingIndex)
                        previousY = event.getY(remainingIndex)
                    }
                }
            }
        } else if (pointerCount == 1) {
            val x = event.x
            val y = event.y

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    previousX = x
                    previousY = y
                    isTwoFingerGesture = false
                    renderer.isTouching = true
                    renderer.yawVelocity = 0f
                    renderer.pitchVelocity = 0f
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isTwoFingerGesture && !scaleDetector.isInProgress) {
                        val dx = x - previousX
                        val dy = y - previousY

                        val rotSensitivity = 0.35f
                        val dYaw = dx * rotSensitivity
                        val dPitch = dy * rotSensitivity

                        renderer.targetYaw += dYaw
                        renderer.targetPitch = (renderer.targetPitch + dPitch).coerceIn(-85f, 85f)

                        renderer.yawVelocity = dYaw * 0.4f
                        renderer.pitchVelocity = dPitch * 0.4f

                        requestRender()
                    }
                    previousX = x
                    previousY = y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    renderer.isTouching = false
                    isTwoFingerGesture = false
                    requestRender()
                }
            }
        }
        return true
    }
}

@Composable
fun MainScreen(
    onShowFileChooser: Any? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val renderer = remember { GLPointRenderer() }
    var interpreter by remember { mutableStateOf<MogeInterpreter?>(null) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    // Hold a reference to the GL view so we can call requestRender() from any button
    val glViewRef = remember { mutableStateOf<InteractiveGLView?>(null) }

    var statusText by remember { mutableStateOf("Initializing system...") }
    var activeAccelerator by remember { mutableStateOf("Detecting...") }
    
    // Hold latest positions and colors for exporting
    var lastPositions by remember { mutableStateOf<FloatArray?>(null) }
    var lastColors by remember { mutableStateOf<FloatArray?>(null) }

    // Merging accumulator and state
    val accumulator = remember { PointCloudAccumulator() }
    var isAccumulateEnabled by remember { mutableStateOf(false) }
    var isMultiMode by remember { mutableStateOf(false) }
    var hasFirstFrame by remember { mutableStateOf(false) }
    val firstFrameRotationMatrix = remember { FloatArray(9) }
    val captureRotationMatrix = remember { FloatArray(9) }
    val isMultiModeSnapshot = remember { AtomicBoolean(false) }

    // Capture/Snapshot states
    var isContinuousScanning by remember { mutableStateOf(false) }
    var isProcessingFrame by remember { mutableStateOf(false) }
    val shouldTakeSnapshot = remember { AtomicBoolean(false) }

    var isRecordDatasetMode by remember { mutableStateOf(false) }
    val isRecordDatasetModeActive = remember { AtomicBoolean(false) }
    val currentDatasetDirRef = remember { java.util.concurrent.atomic.AtomicReference<java.io.File?>(null) }
    val datasetFrameCountRef = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    var isDatasetProcessorOpen by remember { mutableStateOf(false) }
    var activeColorMode by remember { mutableStateOf(PointCloudColorMode.FUSED) }
    var lastTripleResult by remember { mutableStateOf<TripleReconstructionResult?>(null) }

    var isViewingModel by remember { mutableStateOf(false) }
    var modelBase64 by remember { mutableStateOf("") }
    var modelGlbBytes by remember { mutableStateOf<ByteArray?>(null) }

    var isFlashlightOn by remember { mutableStateOf(false) }
    var cameraInstance by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }

    val thermalManager = remember { ThermalCameraManager(context) }
    var isThermalEnabled by remember { mutableStateOf(false) }
    var lastThermalBitmap by remember { mutableStateOf<AndroidBitmap?>(null) }

    // 4-Corner Perspective Calibration States
    var isCalibrationMode by remember { mutableStateOf(false) }
    var activeCalibration by remember { mutableStateOf(ThermalCalibrationManager.getActiveCalibration(context)) }
    val activeCalibrationRef = remember { java.util.concurrent.atomic.AtomicReference(activeCalibration) }
    LaunchedEffect(activeCalibration) { activeCalibrationRef.set(activeCalibration) }

    LaunchedEffect(isFlashlightOn) {
        cameraInstance?.cameraControl?.enableTorch(isFlashlightOn)
    }

    // Dragable/resizable camera Pip states
    var pipOffset by remember { mutableStateOf(Offset(0f, 0f)) }
    var pipSizeMultiplier by remember { mutableStateOf(1f) }
    var fullscreenMode by remember { mutableStateOf(FullscreenMode.NONE) }

    // Live inference stopwatch state
    var inferenceTimeMs by remember { mutableStateOf(0L) }

    LaunchedEffect(isProcessingFrame) {
        if (isProcessingFrame) {
            val startTime = System.currentTimeMillis()
            while (true) {
                inferenceTimeMs = System.currentTimeMillis() - startTime
                kotlinx.coroutines.delay(50)
            }
        } else {
            inferenceTimeMs = 0L
        }
    }

    // Sensor setup
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val rotationSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    val rotationMatrix = remember { FloatArray(9).apply { 
        this[0] = 1f; this[4] = 1f; this[8] = 1f // Identity matrix
    } }

    // GPS / Location setup
    val locationManager = remember { context.getSystemService(Context.LOCATION_SERVICE) as LocationManager }
    var currentLatitude by remember { mutableStateOf<Double?>(null) }
    var currentLongitude by remember { mutableStateOf<Double?>(null) }

    DisposableEffect(locationManager) {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                currentLatitude = location.latitude
                currentLongitude = location.longitude
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        
        try {
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            
            if (isNetworkEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    1f,
                    listener
                )
            } else if (isGpsEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000L,
                    1f,
                    listener
                )
            }
            
            val lastKnownGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastKnownNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val bestLocation = when {
                lastKnownGps != null && lastKnownNetwork != null -> {
                    if (lastKnownGps.time > lastKnownNetwork.time) lastKnownGps else lastKnownNetwork
                }
                lastKnownGps != null -> lastKnownGps
                else -> lastKnownNetwork
            }
            if (bestLocation != null) {
                currentLatitude = bestLocation.latitude
                currentLongitude = bestLocation.longitude
            }
        } catch (e: SecurityException) {
            Log.e("MainScreen", "Location permission missing or denied", e)
        } catch (e: Exception) {
            Log.e("MainScreen", "Failed to start location updates", e)
        }
        
        onDispose {
            try {
                locationManager.removeUpdates(listener)
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    LaunchedEffect(Unit) {
        statusText = "Loading 3D AI Model..."
        try {
            val loadedInterpreter = withContext(Dispatchers.IO) {
                MogeInterpreter(context)
            }
            interpreter = loadedInterpreter
            activeAccelerator = loadedInterpreter.activeAccelerator
            statusText = "Ready"
        } catch (e: Exception) {
            Log.e("MainScreen", "Failed to load MogeInterpreter", e)
            statusText = "Failed to load model: ${e.message}"
        }
    }

    val currentInterpreter by rememberUpdatedState(interpreter)
    DisposableEffect(Unit) {
        val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                val R = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(R, event.values)
                synchronized(rotationMatrix) {
                    System.arraycopy(R, 0, rotationMatrix, 0, 9)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        if (rotationSensor != null) {
            sensorManager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        }

        onDispose {
            cameraExecutor.shutdown()
            currentInterpreter?.close()
            sensorManager.unregisterListener(sensorListener)
            thermalManager.close()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF7F6F2))
    ) {
        // 1. Full-screen 3D Point Cloud Viewport (in the background)
        AndroidView(
            factory = { ctx ->
                InteractiveGLView(ctx, renderer).apply {
                    renderer.onNewPointsListener = {
                        requestRender()
                    }
                    onResume()
                    glViewRef.value = this  // store reference for reset button
                }
            },
            update = { glView ->
                glView.requestRender()
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Status & GPS Info Overlay (semi-transparent, top-left overlay inside 3D view)
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp)
                .background(Color(0xFFF7F6F2).copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Gemini 3.7 & Google Antigravity Version Branding Badge
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                GeminiSparkleLogo(modifier = Modifier.size(11.dp))
                Text(
                    text = "Gemini 3.7",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A73E8)
                )
                Text(
                    text = "•",
                    fontSize = 8.sp,
                    color = Color.Gray
                )
                AntigravityLogo(modifier = Modifier.size(11.dp))
                Text(
                    text = "Antigravity v2.0",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF7C4DFF)
                )
            }

            val gpsText = if (currentLatitude != null && currentLongitude != null) {
                String.format("GPS: Lat %.4f, Lon %.4f", currentLatitude, currentLongitude)
            } else {
                "GPS: Searching..."
            }
            Text(
                text = gpsText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1C1B1F)
            )
            Text(
                text = "3D POINT CLOUD • $statusText ($activeAccelerator)",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = Color(0xFF535358)
            )
            // Row 1: Reset View and Multi Mode
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    text = "↺  Reset View",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = Color(0xFF956820),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        renderer.resetAngles()
                        glViewRef.value?.requestRender()
                    }
                )
                Text(
                    text = if (isMultiMode) "✓  Multi Mode" else "☐  Multi Mode",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = if (isMultiMode) Color(0xFF4CAF50) else Color(0xFF956820),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        isMultiMode = !isMultiMode
                        if (!isMultiMode) {
                            hasFirstFrame = false
                            // Clear accumulator to start fresh in single scan mode
                            accumulator.clear()
                            renderer.updatePoints(FloatArray(0), FloatArray(0))
                            lastPositions = null
                            lastColors = null
                        }
                    }
                )
            }
            // Row 2: Dataset Rec, Process Dataset and Flash
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    text = if (isRecordDatasetMode) "✓  Dataset Rec" else "☐  Dataset Rec",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = if (isRecordDatasetMode) Color(0xFF4CAF50) else Color(0xFF956820),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        isRecordDatasetMode = !isRecordDatasetMode
                        isRecordDatasetModeActive.set(isRecordDatasetMode)
                        if (!isRecordDatasetMode) {
                            currentDatasetDirRef.set(null)
                            datasetFrameCountRef.set(0)
                        }
                    }
                )
                Text(
                    text = "⚡  Process Dataset",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = Color(0xFF00E5FF),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        isDatasetProcessorOpen = true
                    }
                )
                Text(
                    text = if (isFlashlightOn) "✓  Flash" else "☐  Flash",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = if (isFlashlightOn) Color(0xFF4CAF50) else Color(0xFF956820),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        isFlashlightOn = !isFlashlightOn
                    }
                )
            }
            // Row 3: Thermal Snap
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                val tempInfo = thermalManager.getTemperatureInfo()
                val thermalLabel = when {
                    isThermalEnabled && thermalManager.isStreaming() -> {
                        if (tempInfo.isNotEmpty()) "✓  Thermal ($tempInfo)" else "✓  Thermal Snap"
                    }
                    isThermalEnabled && !thermalManager.isStreaming() -> "…  Thermal Connecting"
                    else -> "☐  Thermal Snap"
                }
                Text(
                    text = thermalLabel,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = when {
                        isThermalEnabled && thermalManager.isStreaming() -> Color(0xFF4CAF50)
                        isThermalEnabled && !thermalManager.isStreaming() -> Color(0xFFE5A020)
                        else -> Color(0xFF956820)
                    },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        isThermalEnabled = !isThermalEnabled
                        if (isThermalEnabled) thermalManager.startStreaming()
                        else { thermalManager.stopStreaming(); lastThermalBitmap = null }
                    }
                )
            }
            // Row 4: 4-Corner Perspective Calibration
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Text(
                    text = if (isCalibrationMode) "🎯  Calibrating..." else "🎯  4-Corner Calibrate",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = if (isCalibrationMode) Color(0xFF00E5FF) else Color(0xFF1A73E8),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        if (!isThermalEnabled) {
                            isThermalEnabled = true
                            thermalManager.startStreaming()
                        }
                        isCalibrationMode = !isCalibrationMode
                    }
                )
            }
            // Row 5: 3D Color Mode (Fused / RGB / Thermal)
            if (lastPositions != null || lastTripleResult != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = "3D Mode:",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        color = Color.Gray
                    )
                    Text(
                        text = if (activeColorMode == PointCloudColorMode.FUSED) "● Fused" else "○ Fused",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        color = if (activeColorMode == PointCloudColorMode.FUSED) Color(0xFF00E5FF) else Color(0xFF956820),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            activeColorMode = PointCloudColorMode.FUSED
                            val triple = lastTripleResult
                            if (triple != null) {
                                renderer.updatePoints(triple.fused.first, triple.fused.second)
                                glViewRef.value?.requestRender()
                            }
                        }
                    )
                    Text(
                        text = if (activeColorMode == PointCloudColorMode.RGB) "● RGB" else "○ RGB",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        color = if (activeColorMode == PointCloudColorMode.RGB) Color(0xFF4CAF50) else Color(0xFF956820),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            activeColorMode = PointCloudColorMode.RGB
                            val triple = lastTripleResult
                            if (triple != null) {
                                renderer.updatePoints(triple.rgb.first, triple.rgb.second)
                                glViewRef.value?.requestRender()
                            }
                        }
                    )
                    Text(
                        text = if (activeColorMode == PointCloudColorMode.THERMAL) "● Thermal" else "○ Thermal",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        color = if (activeColorMode == PointCloudColorMode.THERMAL) Color(0xFFFF5252) else Color(0xFF956820),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            activeColorMode = PointCloudColorMode.THERMAL
                            val triple = lastTripleResult
                            if (triple != null) {
                                renderer.updatePoints(triple.thermal.first, triple.thermal.second)
                                glViewRef.value?.requestRender()
                            }
                        }
                    )
                }
            }
        }

        // 3. Play/Pause Continuous Scanning Mode (top-right overlay)
        IconButton(
            onClick = {
                isContinuousScanning = !isContinuousScanning
                if (!isContinuousScanning) {
                    isProcessingFrame = false
                }
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
                .background(Color(0xFFF7F6F2).copy(alpha = 0.85f), CircleShape)
                .size(36.dp)
        ) {
            Icon(
                imageVector = if (isContinuousScanning) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "Toggle Scan Mode",
                tint = Color(0xFF956820),
                modifier = Modifier.size(20.dp)
            )
        }

        // 4. Fullscreen Mode Overlays (when expanded via Fullscreen button)
        if (fullscreenMode == FullscreenMode.THERMAL) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .zIndex(200f)
            ) {
                val liveThermal = thermalManager.liveThermalBitmap.value
                if (liveThermal != null) {
                    Image(
                        bitmap = liveThermal.asImageBitmap(),
                        contentDescription = "Fullscreen Thermal Preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Thermal Camera Connecting...", color = Color.White, fontFamily = FontFamily.Monospace)
                    }
                }

                // Fullscreen Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "THERMAL LIVE • ${thermalManager.getTemperatureInfo()}",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFFF9800),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    IconButton(
                        onClick = { fullscreenMode = FullscreenMode.NONE },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.Black.copy(alpha = 0.75f), CircleShape)
                    ) {
                        FullscreenExitIcon(color = Color.White)
                    }
                }
            }
        }

        // 4b. Floating Separate Non-Overlapping Preview Boxes (Draggable & Resizable)
        val cardWidth = (115 * pipSizeMultiplier).dp
        val cardHeight = (145 * pipSizeMultiplier).dp

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset(pipOffset.x.roundToInt(), pipOffset.y.roundToInt()) }
                .padding(bottom = (LocalConfiguration.current.screenHeightDp * 0.2f).dp + 16.dp, end = 16.dp)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        pipOffset += pan
                        pipSizeMultiplier = (pipSizeMultiplier * zoom).coerceIn(0.5f, 3.0f)
                    }
                },
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // TOP BOX: Thermal Camera Live Feed (Separated floating card)
            if (isThermalEnabled && fullscreenMode != FullscreenMode.THERMAL) {
                Box(
                    modifier = Modifier
                        .size(width = cardWidth, height = cardHeight)
                        .shadow(6.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF141414))
                        .border(2.dp, Color.White, RoundedCornerShape(14.dp))
                ) {
                    val liveThermal = thermalManager.liveThermalBitmap.value
                    if (liveThermal != null) {
                        Image(
                            bitmap = liveThermal.asImageBitmap(),
                            contentDescription = "Thermal Camera Live Feed",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Thermal\nConnecting...",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFFFB74D),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // Fullscreen Expand Button (Top-Left, matching diagram)
                    IconButton(
                        onClick = { fullscreenMode = FullscreenMode.THERMAL },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .size(26.dp)
                            .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                    ) {
                        FullscreenExpandIcon(color = Color.White)
                    }

                    // Temperature info badge (Bottom-Start)
                    val tempBadge = if (thermalManager.getTemperatureInfo().isNotEmpty()) {
                        "IR • ${thermalManager.getTemperatureInfo()}"
                    } else {
                        "THERMAL"
                    }
                    Text(
                        text = tempBadge,
                        fontSize = 7.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFFF9800),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(2.dp))
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                    )
                }
            }

            // BOTTOM BOX: Camera Feed Live (Separated floating card, or fullscreen / calibration mode)
            val isCameraFullscreen = (fullscreenMode == FullscreenMode.CAMERA) || isCalibrationMode
            Box(
                modifier = if (isCameraFullscreen) {
                    Modifier
                        .fillMaxSize()
                        .zIndex(if (isCalibrationMode) 300f else 200f)
                        .background(Color.Black)
                } else {
                    Modifier
                        .size(width = cardWidth, height = cardHeight)
                        .shadow(6.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black)
                        .border(2.dp, Color.White, RoundedCornerShape(14.dp))
                }
            ) {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                        
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            
                            val imageAnalyzer = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                .build()
                                .also { analyzer ->
                                    analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                                        try {
                                            if (isContinuousScanning || shouldTakeSnapshot.compareAndSet(true, false)) {
                                                 val width = imageProxy.width
                                                 val height = imageProxy.height
                                                 val plane = imageProxy.planes[0]
                                                 val buffer = plane.buffer
                                                 
                                                 val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                                 bitmap.copyPixelsFromBuffer(buffer)
                                                 
                                                 val rotation = imageProxy.imageInfo.rotationDegrees
                                                 val rotatedBitmap = if (rotation != 0) {
                                                     val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                                                     Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
                                                 } else {
                                                     bitmap
                                                 }

                                                 // Save both feeds when snapshot is captured
                                                 val ts = System.currentTimeMillis()
                                                 saveSnapshotBitmap(context, rotatedBitmap, "moge_rgb_$ts.png")
                                                 if (isThermalEnabled) {
                                                     val thermalBmp = thermalManager.captureFrame()
                                                     if (thermalBmp != null) {
                                                         saveSnapshotBitmap(context, thermalBmp, "moge_thermal_$ts.png")
                                                     }
                                                     val thermalRaw = thermalManager.captureRaw()
                                                     if (thermalRaw != null) {
                                                         saveSnapshotRaw(context, thermalRaw, "moge_thermal_$ts.raw")
                                                     }
                                                 }

                                                 val R_i = synchronized(captureRotationMatrix) { captureRotationMatrix.clone() }
                                                 val R_0 = synchronized(firstFrameRotationMatrix) { firstFrameRotationMatrix.clone() }
                                                 val R_0_T = transpose3x3(R_0)
                                                 val R_rel = multiply3x3(R_0_T, R_i)

                                                 if (isRecordDatasetModeActive.get()) {
                                                     val dir = currentDatasetDirRef.get()
                                                     if (dir != null) {
                                                         val frameIndex = datasetFrameCountRef.getAndIncrement()
                                                         val thermalBmp = if (isThermalEnabled) (lastThermalBitmap ?: thermalManager.captureFrame()) else null
                                                         val thermalRaw = if (isThermalEnabled) thermalManager.captureRaw() else null
                                                         saveDatasetFrame(context, dir, frameIndex, rotatedBitmap, thermalBmp, thermalRaw, FloatArray(0), R_rel)
                                                         writeStateFile(dir, frameIndex + 1, 518, 518)
                                                     }
                                                     Handler(Looper.getMainLooper()).post {
                                                         isProcessingFrame = false
                                                     }
                                                 } else {
                                                     val model = interpreter
                                                     if (model != null) {
                                                         Log.d("Analyzer", "Running inference: setting isProcessingFrame = true")
                                                         Handler(Looper.getMainLooper()).post {
                                                            isProcessingFrame = true
                                                         }
                                                         val thermalBmp = if (isThermalEnabled) lastThermalBitmap else null
                                                         val colorBitmap = if (thermalBmp != null) {
                                                             ThermalCalibrationManager.createFusedColorBitmap(
                                                                 rgbBitmap = rotatedBitmap,
                                                                 thermalBitmap = thermalBmp,
                                                                 calibration = activeCalibrationRef.get(),
                                                                 alpha = 1.0f
                                                             )
                                                         } else {
                                                             rotatedBitmap
                                                         }
                                                         val result = model.runInferenceWithColor(rotatedBitmap, colorBitmap, stride = 4)
                                                         if (result != null) {
                                                             val positions = result.first
                                                             val colors = result.second
                                                             val numPoints = positions.size / 3
                                                             val glPositions = FloatArray(positions.size)
                                                             for (j in 0 until numPoints) {
                                                                 glPositions[j * 3]     =  positions[j * 3]
                                                                 glPositions[j * 3 + 1] = -positions[j * 3 + 1]
                                                                 glPositions[j * 3 + 2] = -positions[j * 3 + 2]
                                                             }
                                                             if (isMultiModeSnapshot.get()) {
                                                                 for (j in 0 until numPoints) {
                                                                     rotatePoint3x3(glPositions, j * 3, R_rel)
                                                                 }
                                                             }
                                                             val accumulate = isContinuousScanning || isMultiModeSnapshot.get()
                                                             accumulator.addFrame(glPositions, colors, accumulate)
                                                             val (mergedPositions, mergedColors) = accumulator.getPositionsAndColors()
                                                             Handler(Looper.getMainLooper()).post {
                                                                 lastPositions = mergedPositions
                                                                 lastColors = mergedColors
                                                                 renderer.updatePoints(mergedPositions, mergedColors)
                                                             }
                                                         }
                                                         Log.d("Analyzer", "Finished inference: setting isProcessingFrame = false")
                                                         Handler(Looper.getMainLooper()).post {
                                                             isProcessingFrame = false
                                                         }
                                                     } else {
                                                         Log.d("Analyzer", "Model null: setting isProcessingFrame = false")
                                                         Handler(Looper.getMainLooper()).post {
                                                             isProcessingFrame = false
                                                         }
                                                     }
                                                 }
                                            } else {
                                                Handler(Looper.getMainLooper()).post {
                                                    isProcessingFrame = false
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.e("Analyzer", "Frame analysis failed", e)
                                            Handler(Looper.getMainLooper()).post {
                                                isProcessingFrame = false
                                            }
                                        } finally {
                                            imageProxy.close()
                                        }
                                    }
                                }

                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                            try {
                                cameraProvider.unbindAll()
                                val camera = cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    preview,
                                    imageAnalyzer
                                )
                                cameraInstance = camera
                                camera.cameraControl.enableTorch(isFlashlightOn)
                                statusText = "Scanning"
                            } catch (exc: Exception) {
                                Log.e("CameraX", "Use case binding failed", exc)
                                statusText = "Camera error"
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (isCalibrationMode) {
                    ThermalCalibrationInteractiveOverlay(
                        liveThermalBitmap = thermalManager.liveThermalBitmap.value,
                        initialCalibration = activeCalibration,
                        onSaveAndClose = { newCal ->
                            activeCalibration = newCal
                            isCalibrationMode = false
                        },
                        onClose = {
                            isCalibrationMode = false
                        }
                    )
                } else {
                    // Fullscreen Expand/Exit Button (Top-Left, matching diagram)
                    IconButton(
                        onClick = { 
                            fullscreenMode = if (isCameraFullscreen) FullscreenMode.NONE else FullscreenMode.CAMERA 
                        },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(if (isCameraFullscreen) 16.dp else 6.dp)
                            .size(if (isCameraFullscreen) 36.dp else 26.dp)
                            .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                    ) {
                        if (isCameraFullscreen) {
                            FullscreenExitIcon(color = Color.White)
                        } else {
                            FullscreenExpandIcon(color = Color.White)
                        }
                    }

                    // Status Badge (Bottom-Start)
                    Text(
                        text = "LIVE",
                        fontSize = 8.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Red,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // 5. Bottom Control Panel (PLY, GLB, Shutter side-by-side using bottom 20% of screen height)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.18f)
                .align(Alignment.BottomCenter)
                .background(Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Export PLY Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .shadow(2.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.5.dp, Color.Black, RoundedCornerShape(16.dp))
                    .clickable {
                        val positions = lastPositions
                        val colors = lastColors
                        if (positions != null && colors != null) {
                            try {
                                val plyData = exportPly(positions, colors, currentLatitude, currentLongitude)
                                val contentValues = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, "moge_scan_${System.currentTimeMillis()}.ply")
                                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                                }
                                val resolver = context.contentResolver
                                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                                if (uri != null) {
                                    resolver.openOutputStream(uri)?.use { outputStream ->
                                        outputStream.write(plyData.toByteArray())
                                    }
                                    val gpsTag = if (currentLatitude != null && currentLongitude != null) " (GPS tagged)" else ""
                                    Toast.makeText(context, "PLY saved to Downloads!$gpsTag", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Failed to create PLY file.", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "No scan data available yet.", Toast.LENGTH_SHORT).show()
                        }
                    }
            ) {
                Text(
                    text = "ply",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }

            // Export GLB Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .shadow(2.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .border(1.5.dp, Color.Black, RoundedCornerShape(16.dp))
                    .clickable {
                        val positions = lastPositions
                        val colors = lastColors
                        if (positions != null && colors != null) {
                            try {
                                val glbData = exportGlb(positions, colors, currentLatitude, currentLongitude)
                                val contentValues = ContentValues().apply {
                                    put(MediaStore.MediaColumns.DISPLAY_NAME, "moge_scan_${System.currentTimeMillis()}.glb")
                                    put(MediaStore.MediaColumns.MIME_TYPE, "model/gltf-binary")
                                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                                }
                                val resolver = context.contentResolver
                                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                                if (uri != null) {
                                    resolver.openOutputStream(uri)?.use { outputStream ->
                                        outputStream.write(glbData)
                                    }
                                    val gpsTag = if (currentLatitude != null && currentLongitude != null) " (GPS tagged)" else ""
                                    Toast.makeText(context, "GLB saved to Downloads!$gpsTag", Toast.LENGTH_SHORT).show()

                                    // Open Filament native 3D preview
                                    modelGlbBytes = glbData
                                    isViewingModel = true
                                } else {
                                    Toast.makeText(context, "Failed to create GLB file.", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "No scan data available yet.", Toast.LENGTH_SHORT).show()
                        }
                    }
            ) {
                Text(
                    text = "glb",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }

            // Camera Shutter Button (Rounded, Auto-clears prior data on click, Red dashed border when processing)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .shadow(2.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .then(
                        if (isProcessingFrame) {
                            Modifier.drawBehind {
                                val stroke = Stroke(
                                    width = 3.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 0f)
                                )
                                drawRoundRect(
                                    color = Color.Red,
                                    style = stroke,
                                    cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx())
                                )
                            }
                        } else {
                            Modifier.border(1.5.dp, Color.Black, RoundedCornerShape(16.dp))
                        }
                    )
                    .clickable(enabled = !isProcessingFrame) {
                        val positions = lastPositions
                        val colors = lastColors
                        val isMulti = isMultiMode
                        isMultiModeSnapshot.set(isMulti)

                        val R = synchronized(rotationMatrix) { rotationMatrix.clone() }
                        synchronized(captureRotationMatrix) {
                            System.arraycopy(R, 0, captureRotationMatrix, 0, 9)
                        }

                        if (!isMulti || !hasFirstFrame) {
                            synchronized(firstFrameRotationMatrix) {
                                System.arraycopy(R, 0, firstFrameRotationMatrix, 0, 9)
                            }
                            hasFirstFrame = true

                            // Calculate gravityAlignMatrix for the renderer based on first frame
                            val grav4x4 = FloatArray(16)
                            grav4x4[0]=R[0]; grav4x4[1]=R[3]; grav4x4[2]=R[6]; grav4x4[3]=0f
                            grav4x4[4]=R[1]; grav4x4[5]=R[4]; grav4x4[6]=R[7]; grav4x4[7]=0f
                            grav4x4[8]=R[2]; grav4x4[9]=R[5]; grav4x4[10]=R[8]; grav4x4[11]=0f
                            grav4x4[12]=0f; grav4x4[13]=0f; grav4x4[14]=0f; grav4x4[15]=1f
                            System.arraycopy(grav4x4, 0, renderer.gravityAlignMatrix, 0, 16)
                        }

                        // Initialize dataset directory if Record Dataset Mode is active
                        if (isRecordDatasetMode) {
                            if (currentDatasetDirRef.get() == null) {
                                val baseDir = context.getExternalFilesDir("datasets")
                                val dir = java.io.File(baseDir, "dataset_${System.currentTimeMillis()}").apply { mkdirs() }
                                currentDatasetDirRef.set(dir)
                                datasetFrameCountRef.set(0)
                                writeStateFile(dir, 0, 518, 518)
                                writeRotationFile(dir, 0f)
                                val cal = activeCalibrationRef.get()
                                try {
                                    java.io.File(dir, "calibration.json").writeText(cal.toJson())
                                } catch (e: Exception) {
                                    Log.e("DatasetRec", "Failed to write calibration.json", e)
                                }
                                Toast.makeText(context, "Recording dataset to ${dir.name}!", Toast.LENGTH_SHORT).show()
                            }
                        }

                        // 2. Capture thermal frame if enabled
                        if (isThermalEnabled) {
                            lastThermalBitmap = thermalManager.captureFrame()
                        }

                        // 3. Trigger new snapshot
                        shouldTakeSnapshot.set(true)
                        isProcessingFrame = true

                        // 3. Handle single vs multi mode clearing and auto-export
                        if (!isMulti) {
                            accumulator.clear()
                            renderer.updatePoints(FloatArray(0), FloatArray(0))
                            lastPositions = null
                            lastColors = null

                            if (positions != null && colors != null) {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val glbData = exportGlb(positions, colors, currentLatitude, currentLongitude)
                                        val contentValues = ContentValues().apply {
                                            put(MediaStore.MediaColumns.DISPLAY_NAME, "moge_scan_${System.currentTimeMillis()}.glb")
                                            put(MediaStore.MediaColumns.MIME_TYPE, "model/gltf-binary")
                                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                                        }
                                        val resolver = context.contentResolver
                                        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                                        if (uri != null) {
                                            resolver.openOutputStream(uri)?.use { outputStream ->
                                                outputStream.write(glbData)
                                            }
                                            withContext(Dispatchers.Main) {
                                                val gpsTag = if (currentLatitude != null && currentLongitude != null) " (GPS tagged)" else ""
                                                Toast.makeText(context, "Saved previous scan to GLB!$gpsTag", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("Shutter", "Auto-export GLB failed", e)
                                    }
                                }
                            }
                        }
                    }
            ) {
                if (isProcessingFrame) {
                    Text(
                        text = String.format("%.1fs", inferenceTimeMs / 1000f),
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.Red,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = "Capture Snapshot",
                        tint = Color.Black,
                        modifier = Modifier.size(28.dp)
                    )
                }
            } // end shutter Box
        } // end Row

        // 6. Native Google Filament 3D Viewer overlay (Zero HTML / Zero WebView)
        if (isViewingModel && modelGlbBytes != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF7F6F2))
            ) {
                AndroidView(
                    factory = { ctx ->
                        Filament3DViewer(ctx).apply {
                            modelGlbBytes?.let { bytes -> loadGlbData(bytes) }
                        }
                    },
                    update = { viewer ->
                        modelGlbBytes?.let { bytes -> viewer.loadGlbData(bytes) }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Top Bar in Filament 3D Viewer overlay
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mode Switcher inside Filament 3D Viewer
                    if (lastTripleResult != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "🌈 Fused",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeColorMode == PointCloudColorMode.FUSED) Color(0xFF00E5FF) else Color.Gray,
                                modifier = Modifier.clickable {
                                    activeColorMode = PointCloudColorMode.FUSED
                                    val triple = lastTripleResult
                                    if (triple != null) {
                                        modelGlbBytes = exportGlb(triple.fused.first, triple.fused.second)
                                    }
                                }
                            )
                            Text(
                                text = "📷 RGB",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeColorMode == PointCloudColorMode.RGB) Color(0xFF4CAF50) else Color.Gray,
                                modifier = Modifier.clickable {
                                    activeColorMode = PointCloudColorMode.RGB
                                    val triple = lastTripleResult
                                    if (triple != null) {
                                        modelGlbBytes = exportGlb(triple.rgb.first, triple.rgb.second)
                                    }
                                }
                            )
                            Text(
                                text = "🌡️ Thermal",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (activeColorMode == PointCloudColorMode.THERMAL) Color(0xFFFF5252) else Color.Gray,
                                modifier = Modifier.clickable {
                                    activeColorMode = PointCloudColorMode.THERMAL
                                    val triple = lastTripleResult
                                    if (triple != null) {
                                        modelGlbBytes = exportGlb(triple.thermal.first, triple.thermal.second)
                                    }
                                }
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    // Close Button
                    IconButton(
                        onClick = { isViewingModel = false },
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.Black.copy(alpha = 0.75f), CircleShape)
                    ) {
                        FullscreenExitIcon(color = Color.White)
                    }
                }
            }
        }

        // 7. Offline Dataset Post-Processing Dialog
        if (isDatasetProcessorOpen) {
            DatasetProcessorDialog(
                interpreter = interpreter,
                onModelReconstructed = { tripleResult ->
                    lastTripleResult = tripleResult
                    val (positions, colors) = when (activeColorMode) {
                        PointCloudColorMode.FUSED -> tripleResult.fused
                        PointCloudColorMode.RGB -> tripleResult.rgb
                        PointCloudColorMode.THERMAL -> tripleResult.thermal
                    }
                    lastPositions = positions
                    lastColors = colors
                    renderer.updatePoints(positions, colors)
                    glViewRef.value?.requestRender()
                },
                onDismiss = { isDatasetProcessorOpen = false }
            )
        }
    } // end outer Box
} // end MainScreen

private fun exportPly(positions: FloatArray, colors: FloatArray, latitude: Double? = null, longitude: Double? = null): String {
    val sb = java.lang.StringBuilder()
    val numPoints = positions.size / 3
    sb.append("ply\n")
    sb.append("format ascii 1.0\n")
    if (latitude != null && longitude != null) {
        sb.append("comment gps_latitude: $latitude\n")
        sb.append("comment gps_longitude: $longitude\n")
    }
    sb.append("element vertex $numPoints\n")
    sb.append("property float x\n")
    sb.append("property float y\n")
    sb.append("property float z\n")
    sb.append("property uchar red\n")
    sb.append("property uchar green\n")
    sb.append("property uchar blue\n")
    sb.append("end_header\n")

    for (i in 0 until numPoints) {
        val px = positions[i * 3]
        val py = positions[i * 3 + 1]
        val pz = positions[i * 3 + 2]

        val r = (colors[i * 3] * 255).toInt().coerceIn(0, 255)
        val g = (colors[i * 3 + 1] * 255).toInt().coerceIn(0, 255)
        val b = (colors[i * 3 + 2] * 255).toInt().coerceIn(0, 255)

        sb.append("$px $py $pz $r $g $b\n")
    }
    return sb.toString()
}

fun exportGlb(positions: FloatArray, colors: FloatArray, latitude: Double? = null, longitude: Double? = null): ByteArray {
    val numPoints = positions.size / 3
    
    // Compute bounding box for POSITION accessor
    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
    for (i in 0 until numPoints) {
        val x = positions[i * 3]
        val y = positions[i * 3 + 1]
        val z = positions[i * 3 + 2]
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
    }
    if (numPoints == 0) {
        minX = 0f; maxX = 0f
        minY = 0f; maxY = 0f
        minZ = 0f; maxZ = 0f
    }

    val binLength = numPoints * 24 // 12 bytes for pos, 12 bytes for col
    
    val gpsMetadata = if (latitude != null && longitude != null) {
        """,
      "extras": {
        "gps_latitude": $latitude,
        "gps_longitude": $longitude
      }"""
    } else {
        ""
    }

    val jsonStr = """
    {
      "asset": {
        "version": "2.0",
        "generator": "MoGe3DScanner"$gpsMetadata
      },
      "scene": 0,
      "scenes": [
        {
          "nodes": [0]
        }
      ],
      "nodes": [
        {
          "mesh": 0
        }
      ],
      "meshes": [
        {
          "primitives": [
            {
              "attributes": {
                "POSITION": 0,
                "COLOR_0": 1
              },
              "mode": 0
            }
          ]
        }
      ],
      "accessors": [
        {
          "bufferView": 0,
          "componentType": 5126,
          "count": $numPoints,
          "type": "VEC3",
          "min": [$minX, $minY, $minZ],
          "max": [$maxX, $maxY, $maxZ]
        },
        {
          "bufferView": 1,
          "componentType": 5126,
          "count": $numPoints,
          "type": "VEC3"
        }
      ],
      "bufferViews": [
        {
          "buffer": 0,
          "byteOffset": 0,
          "byteLength": ${numPoints * 12},
          "target": 34962
        },
        {
          "buffer": 0,
          "byteOffset": ${numPoints * 12},
          "byteLength": ${numPoints * 12},
          "target": 34962
        }
      ],
      "buffers": [
        {
          "byteLength": $binLength
        }
      ]
    }
    """.trimIndent()

    val jsonBytes = jsonStr.toByteArray(Charsets.UTF_8)
    val jsonPadding = (4 - (jsonBytes.size % 4)) % 4
    val paddedJsonLength = jsonBytes.size + jsonPadding

    val binPadding = (4 - (binLength % 4)) % 4
    val paddedBinLength = binLength + binPadding

    val totalLength = 12 + 8 + paddedJsonLength + 8 + paddedBinLength

    val buffer = ByteBuffer.allocate(totalLength).apply {
        order(ByteOrder.LITTLE_ENDIAN)
        
        // 1. GLB Header
        putInt(0x46546C67) // magic "glTF"
        putInt(2)          // version 2
        putInt(totalLength)
        
        // 2. Chunk 0: JSON
        putInt(paddedJsonLength)
        putInt(0x4E4F534A) // type "JSON"
        put(jsonBytes)
        for (i in 0 until jsonPadding) {
            put(0x20.toByte()) // space padding
        }

        // 3. Chunk 1: BIN
        putInt(paddedBinLength)
        putInt(0x004E4942) // type "BIN"
        
        // Write positions to BIN chunk
        for (i in 0 until numPoints) {
            putFloat(positions[i * 3])
            putFloat(positions[i * 3 + 1])
            putFloat(positions[i * 3 + 2])
        }
        // Write colors to BIN chunk
        for (i in 0 until numPoints) {
            putFloat(colors[i * 3])
            putFloat(colors[i * 3 + 1])
            putFloat(colors[i * 3 + 2])
        }
        
        // Padding for BIN chunk
        for (i in 0 until binPadding) {
            put(0.toByte())
        }
    }
    
    return buffer.array()
}

class PointCloudAccumulator {
    private val maxPoints = 150000
    private val positions = ArrayList<Float>()
    private val colors = ArrayList<Float>()

    @Synchronized
    fun addFrame(newPositions: FloatArray, newColors: FloatArray, accumulate: Boolean) {
        if (!accumulate) {
            positions.clear()
            colors.clear()
        }
        
        // Add new points
        for (i in newPositions.indices) {
            positions.add(newPositions[i])
        }
        for (i in newColors.indices) {
            colors.add(newColors[i])
        }
        
        // Trim if exceeds maxPoints
        val currentPoints = positions.size / 3
        if (currentPoints > maxPoints) {
            val pointsToRemove = currentPoints - maxPoints
            val elementsToRemove = pointsToRemove * 3
            if (elementsToRemove < positions.size) {
                // Remove elements from the beginning of the list (FIFO)
                positions.subList(0, elementsToRemove).clear()
                colors.subList(0, elementsToRemove).clear()
            }
        }
    }

    @Synchronized
    fun clear() {
        positions.clear()
        colors.clear()
    }

    @Synchronized
    fun getPositionsAndColors(): Pair<FloatArray, FloatArray> {
        val posArray = FloatArray(positions.size)
        val colArray = FloatArray(colors.size)
        for (i in positions.indices) {
            posArray[i] = positions[i]
        }
        for (i in colors.indices) {
            colArray[i] = colors[i]
        }
        return Pair(posArray, colArray)
    }
}

fun rotatePoint3x3(p: FloatArray, offset: Int, R: FloatArray) {
    val x = p[offset]
    val y = p[offset + 1]
    val z = p[offset + 2]
    p[offset]     = R[0] * x + R[1] * y + R[2] * z
    p[offset + 1] = R[3] * x + R[4] * y + R[5] * z
    p[offset + 2] = R[6] * x + R[7] * y + R[8] * z
}

private fun multiply3x3(A: FloatArray, B: FloatArray): FloatArray {
    val C = FloatArray(9)
    C[0] = A[0]*B[0] + A[1]*B[3] + A[2]*B[6]
    C[1] = A[0]*B[1] + A[1]*B[4] + A[2]*B[7]
    C[2] = A[0]*B[2] + A[1]*B[5] + A[2]*B[8]

    C[3] = A[3]*B[0] + A[4]*B[3] + A[5]*B[6]
    C[4] = A[3]*B[1] + A[4]*B[4] + A[5]*B[7]
    C[5] = A[3]*B[2] + A[4]*B[5] + A[5]*B[8]

    C[6] = A[6]*B[0] + A[7]*B[3] + A[8]*B[6]
    C[7] = A[6]*B[1] + A[7]*B[4] + A[8]*B[7]
    C[8] = A[6]*B[2] + A[7]*B[5] + A[8]*B[8]
    return C
}

private fun transpose3x3(A: FloatArray): FloatArray {
    val T = FloatArray(9)
    T[0] = A[0]; T[1] = A[3]; T[2] = A[6]
    T[3] = A[1]; T[4] = A[4]; T[5] = A[7]
    T[6] = A[2]; T[7] = A[5]; T[8] = A[8]
    return T
}

private fun saveDatasetFrame(
    context: Context,
    datasetDir: java.io.File,
    frameIndex: Int,
    bitmap: Bitmap,
    thermalBitmap: Bitmap?,
    thermalRaw: ShortArray?,
    rawPositions: FloatArray,
    R_rel: FloatArray
) {
    try {
        val prefix = String.format(java.util.Locale.US, "%08d", frameIndex)

        // 1. Save RGB Image (.jpg)
        val imageFile = java.io.File(datasetDir, "$prefix.jpg")
        java.io.FileOutputStream(imageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }

        // 2. Save Thermal Image (.png)
        if (thermalBitmap != null) {
            val thermalFile = java.io.File(datasetDir, "${prefix}_thermal.png")
            java.io.FileOutputStream(thermalFile).use { out ->
                thermalBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }

        // 3. Save Raw Radiometric Counts (.raw)
        if (thermalRaw != null) {
            val rawFile = java.io.File(datasetDir, "${prefix}_thermal.raw")
            java.io.FileOutputStream(rawFile).use { out ->
                val bb = java.nio.ByteBuffer.allocate(thermalRaw.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (s in thermalRaw) bb.putShort(s)
                out.write(bb.array())
            }
        }

        // 4. Save Point Cloud (.pcl) if present
        val numPoints = rawPositions.size / 3
        if (numPoints > 0) {
            val pclFile = java.io.File(datasetDir, "$prefix.pcl")
            java.io.FileOutputStream(pclFile).use { out ->
                val byteBuffer = java.nio.ByteBuffer.allocate(4 + numPoints * 16).apply {
                    order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    putInt(numPoints)
                    for (i in 0 until numPoints) {
                        putFloat(rawPositions[i * 3])
                        putFloat(rawPositions[i * 3 + 1])
                        putFloat(rawPositions[i * 3 + 2])
                        putFloat(1.0f) // Confidence
                    }
                }
                out.write(byteBuffer.array())
            }
        }

        // 3. Save Matrices (.mat)
        val r = R_rel
        val matFile = java.io.File(datasetDir, "$prefix.mat")
        matFile.printWriter().use { pw ->
            // Matrix 1 (COLOR_CAMERA)
            pw.printf(java.util.Locale.US, "%f %f %f 0.000000\n", r[0], -r[1], -r[2])
            pw.printf(java.util.Locale.US, "%f %f %f 0.000000\n", r[3], -r[4], -r[5])
            pw.printf(java.util.Locale.US, "%f %f %f 0.000000\n", r[6], -r[7], -r[8])
            pw.printf(java.util.Locale.US, "0.000000 0.000000 0.000000 1.000000\n")

            // Matrix 2 (OPENGL_CAMERA)
            pw.printf(java.util.Locale.US, "%f %f %f 0.000000\n", r[0], -r[1], -r[2])
            pw.printf(java.util.Locale.US, "%f %f %f 0.000000\n", r[3], -r[4], -r[5])
            pw.printf(java.util.Locale.US, "%f %f %f 0.000000\n", r[6], -r[7], -r[8])
            pw.printf(java.util.Locale.US, "0.000000 0.000000 0.000000 1.000000\n")

            // Matrix 3 (SCREEN_CAMERA)
            pw.printf(java.util.Locale.US, "%f %f %f 0.000000\n", r[0], r[3], r[6])
            pw.printf(java.util.Locale.US, "%f %f %f 0.000000\n", -r[1], -r[4], -r[7])
            pw.printf(java.util.Locale.US, "%f %f %f 0.000000\n", -r[2], -r[5], -r[8])
            pw.printf(java.util.Locale.US, "0.000000 0.000000 0.000000 1.000000\n")
        }
    } catch (e: Exception) {
        Log.e("DatasetRec", "Failed to save frame $frameIndex", e)
    }
}

private fun writeStateFile(datasetDir: java.io.File, count: Int, width: Int, height: Int) {
    try {
        val stateFile = java.io.File(datasetDir, "state.txt")
        stateFile.printWriter().use { pw ->
            pw.printf(java.util.Locale.US, "%d %d %d 259.000000 259.000000 500.000000 500.000000\n", count, width, height)
        }
    } catch (e: Exception) {
        Log.e("DatasetRec", "Failed to write state.txt", e)
    }
}

private fun writeRotationFile(datasetDir: java.io.File, yaw: Float) {
    try {
        val rotFile = java.io.File(datasetDir, "rotation.txt")
        rotFile.printWriter().use { pw ->
            pw.printf(java.util.Locale.US, "%f\n", yaw)
        }
    } catch (e: Exception) {
        Log.e("DatasetRec", "Failed to write rotation.txt", e)
    }
}

private fun getModelViewerHtml(base64Data: String): String {
    return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
            <script type="module" src="https://ajax.googleapis.com/ajax/libs/model-viewer/3.5.0/model-viewer.min.js"></script>
            <style>
                * { box-sizing: border-box; margin: 0; padding: 0; }
                body, html { width: 100%; height: 100%; overflow: hidden; background-color: #F7F6F2; }
                model-viewer {
                    width: 100%;
                    height: 100%;
                    --poster-color: transparent;
                    background-color: #F7F6F2;
                }
            </style>
        </head>
        <body>
            <model-viewer 
                id="mainViewer"
                src="data:model/gltf-binary;base64,$base64Data" 
                camera-controls 
                camera-orbit="0deg 75deg 105%"
                shadow-intensity="1" 
                interaction-prompt="auto"
                ar 
                ar-modes="scene-viewer webxr quick-look"
                alt="3D Reconstructed Model">
            </model-viewer>
        </body>
        </html>
    """.trimIndent()
}
