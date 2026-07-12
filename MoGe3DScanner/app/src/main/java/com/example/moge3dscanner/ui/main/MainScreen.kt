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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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

class InteractiveGLView(context: Context, val renderer: GLPointRenderer) : GLSurfaceView(context) {
    private var previousX: Float = 0f
    private var previousY: Float = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            renderer.zoom /= detector.scaleFactor
            if (renderer.zoom < 1.0f) renderer.zoom = 1.0f
            if (renderer.zoom > 10.0f) renderer.zoom = 10.0f
            requestRender()
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    private var isPanning = false
    private var previousMidX = 0f
    private var previousMidY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            isPanning = false
            return true
        }

        val pointerCount = event.pointerCount

        if (pointerCount == 2) {
            val midX = (event.getX(0) + event.getX(1)) / 2f
            val midY = (event.getY(0) + event.getY(1)) / 2f

            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    previousMidX = midX
                    previousMidY = midY
                    isPanning = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPanning) {
                        val dx = midX - previousMidX
                        val dy = midY - previousMidY

                        // Scale pan sensitivity by current zoom
                        val sensitivity = 0.0015f * renderer.zoom
                        renderer.panX += dx * sensitivity
                        renderer.panY -= dy * sensitivity // Flip Y axis
                        requestRender()
                    }
                    previousMidX = midX
                    previousMidY = midY
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    isPanning = false
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
                    isPanning = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isPanning) {
                        val dx = x - previousX
                        val dy = y - previousY

                        renderer.angleX += dx * 0.15f
                        renderer.angleY -= dy * 0.15f
                        requestRender()
                    }
                    previousX = x
                    previousY = y
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

    var statusText by remember { mutableStateOf("Initializing system...") }
    var activeAccelerator by remember { mutableStateOf("Detecting...") }
    
    // Hold latest positions and colors for exporting
    var lastPositions by remember { mutableStateOf<FloatArray?>(null) }
    var lastColors by remember { mutableStateOf<FloatArray?>(null) }

    // Merging accumulator and state
    val accumulator = remember { PointCloudAccumulator() }
    var isAccumulateEnabled by remember { mutableStateOf(false) }

    // Capture/Snapshot states
    var isContinuousScanning by remember { mutableStateOf(false) }
    var isProcessingFrame by remember { mutableStateOf(false) }
    val shouldTakeSnapshot = remember { AtomicBoolean(false) }

    // Dragable/resizable camera Pip states
    var pipOffset by remember { mutableStateOf(Offset(0f, 0f)) }
    var pipSizeMultiplier by remember { mutableStateOf(1f) }

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

        // 4. Floating Camera Preview (Picture-in-Picture window - Dragable & Resizable)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset { IntOffset(pipOffset.x.roundToInt(), pipOffset.y.roundToInt()) }
                .padding(bottom = (LocalConfiguration.current.screenHeightDp * 0.2f).dp + 16.dp, end = 16.dp)
                .size(width = (110 * pipSizeMultiplier).dp, height = (145 * pipSizeMultiplier).dp)
                .shadow(6.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
                .border(2.dp, Color.White, RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        pipOffset += pan
                        pipSizeMultiplier = (pipSizeMultiplier * zoom).coerceIn(0.5f, 3.0f)
                    }
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
                                            val model = interpreter
                                            if (model != null) {
                                                Log.d("Analyzer", "Running inference: setting isProcessingFrame = true")
                                                Handler(Looper.getMainLooper()).post {
                                                    isProcessingFrame = true
                                                }
                                                
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
                                                
                                                val result = model.runInference(rotatedBitmap, stride = 4)
                                                if (result != null) {
                                                    val positions = result.first
                                                    val colors = result.second
                                                    val numPoints = positions.size / 3
                                                    val rotatedPositions = FloatArray(positions.size)
                                                    val R = rotationMatrix.clone()
                                                    
                                                    for (j in 0 until numPoints) {
                                                        val xc = positions[j * 3]
                                                        val yc = positions[j * 3 + 1]
                                                        val zc = positions[j * 3 + 2]
                                                        
                                                        val xd = xc
                                                        val yd = -yc
                                                        val zd = -zc
                                                        
                                                        val xw = R[0] * xd + R[1] * yd + R[2] * zd
                                                        val yw = R[3] * xd + R[4] * yd + R[5] * zd
                                                        val zw = R[6] * xd + R[7] * yd + R[8] * zd
                                                        
                                                        rotatedPositions[j * 3] = xw
                                                        rotatedPositions[j * 3 + 1] = zw
                                                        rotatedPositions[j * 3 + 2] = -yw
                                                    }
                                                    
                                                    val accumulate = isContinuousScanning
                                                    accumulator.addFrame(rotatedPositions, colors, accumulate)
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
                                        } else {
                                            Handler(Looper.getMainLooper()).post {
                                                isProcessingFrame = false
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e("Analyzer", "Frame analysis failed", e)
                                        Log.d("Analyzer", "Exception: setting isProcessingFrame = false")
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
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageAnalyzer
                            )
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
            
            Text(
                text = "LIVE",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.Red,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
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
                        
                        // 1. Trigger new snapshot
                        shouldTakeSnapshot.set(true)
                        isProcessingFrame = true

                        // 2. Clear previous point cloud before starting new analysis
                        accumulator.clear()
                        renderer.updatePoints(FloatArray(0), FloatArray(0))
                        lastPositions = null
                        lastColors = null
                        
                        // 3. Run GLB export in the background on IO coroutine pool
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
            }
        }
    }
}

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

private fun exportGlb(positions: FloatArray, colors: FloatArray, latitude: Double? = null, longitude: Double? = null): ByteArray {
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
