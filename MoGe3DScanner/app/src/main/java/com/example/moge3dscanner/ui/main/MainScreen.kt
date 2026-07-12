package com.example.moge3dscanner.ui.main

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Environment
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
import androidx.compose.ui.graphics.Color
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
import java.util.concurrent.Executors
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) return true

        val x: Float = event.x
        val y: Float = event.y

        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                val dx: Float = x - previousX
                val dy: Float = y - previousY

                renderer.angleX += dx * 0.15f
                renderer.angleY += dy * 0.15f
                requestRender()
            }
        }
        previousX = x
        previousY = y
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
    var isContinuousScanning by remember { mutableStateOf(true) }
    var isProcessingFrame by remember { mutableStateOf(false) }
    var shouldTakeSnapshot by remember { mutableStateOf(false) }

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
            statusText = "Ready.\nAccelerator: ${loadedInterpreter.activeAccelerator}\nPoint cloud active."
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF7F6F2))
            .systemBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Viewports Layout (Camera top half, Point Cloud bottom half)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Camera viewport
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black)
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
                                            val runAnalysis = isContinuousScanning || shouldTakeSnapshot
                                            if (runAnalysis) {
                                                shouldTakeSnapshot = false
                                                isProcessingFrame = true

                                                val width = imageProxy.width
                                                val height = imageProxy.height
                                                val plane = imageProxy.planes[0]
                                                val buffer = plane.buffer
                                                
                                                // Copy pixels to bitmap
                                                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                                bitmap.copyPixelsFromBuffer(buffer)
                                                
                                                // Handle rotation
                                                val rotation = imageProxy.imageInfo.rotationDegrees
                                                val rotatedBitmap = if (rotation != 0) {
                                                    val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                                                    Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
                                                } else {
                                                    bitmap
                                                }
                                                
                                                val result = interpreter?.runInference(rotatedBitmap, stride = 4)
                                                if (result != null) {
                                                    val positions = result.first
                                                    val colors = result.second
                                                    
                                                    // 1. Rotate points based on current device orientation
                                                    val rotatedPositions = FloatArray(positions.size)
                                                    val R = FloatArray(9)
                                                    synchronized(rotationMatrix) {
                                                        System.arraycopy(rotationMatrix, 0, R, 0, 9)
                                                    }
                                                    
                                                    for (j in 0 until positions.size / 3) {
                                                        val xc = positions[j * 3]
                                                        val yc = positions[j * 3 + 1]
                                                        val zc = positions[j * 3 + 2]
                                                        
                                                        // Map camera space to device coordinates
                                                        val xd = xc
                                                        val yd = -yc
                                                        val zd = -zc
                                                        
                                                        // Apply device rotation matrix (P_world = R * P_device)
                                                        val xw = R[0] * xd + R[1] * yd + R[2] * zd
                                                        val yw = R[3] * xd + R[4] * yd + R[5] * zd
                                                        val zw = R[6] * xd + R[7] * yd + R[8] * zd
                                                        
                                                        // Map world coordinates to OpenGL conventions (Z is height -> Y is height, etc.)
                                                        rotatedPositions[j * 3] = xw
                                                        rotatedPositions[j * 3 + 1] = zw
                                                        rotatedPositions[j * 3 + 2] = -yw
                                                    }
                                                    
                                                    // 2. Accumulate/merge point cloud
                                                    val accumulate = isAccumulateEnabled
                                                    accumulator.addFrame(rotatedPositions, colors, accumulate)
                                                    val (mergedPositions, mergedColors) = accumulator.getPositionsAndColors()
                                                    
                                                    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
                                                    var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                                                    var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
                                                    for (j in 0 until mergedPositions.size / 3) {
                                                        val px = mergedPositions[j * 3]
                                                        val py = mergedPositions[j * 3 + 1]
                                                        val pz = mergedPositions[j * 3 + 2]
                                                        if (px < minX) minX = px; if (px > maxX) maxX = px
                                                        if (py < minY) minY = py; if (py > maxY) maxY = py
                                                        if (pz < minZ) minZ = pz; if (pz > maxZ) maxZ = pz
                                                    }
                                                    Log.i("MainScreen", "Point cloud generated! Points: ${mergedPositions.size / 3}. Range X: [$minX, $maxX], Y: [$minY, $maxY], Z: [$minZ, $maxZ]")
                                                    
                                                    lastPositions = mergedPositions
                                                    lastColors = mergedColors
                                                    
                                                    renderer.updatePoints(mergedPositions, mergedColors)
                                                }
                                                isProcessingFrame = false
                                            }
                                        } catch (e: Exception) {
                                            Log.e("Analyzer", "Frame analysis failed", e)
                                            isProcessingFrame = false
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
                                statusText = "Scanning in real-time.\nAccelerator: $activeAccelerator"
                            } catch (exc: Exception) {
                                Log.e("CameraX", "Use case binding failed", exc)
                                statusText = "Camera error: ${exc.message}"
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
                
                // Label
                Text(
                    text = "CAMERA FEED",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFF737378),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .background(Color(0xFFF7F6F2).copy(alpha = 0.95f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )

                // Continuous Scanning Play/Pause toggle
                IconButton(
                    onClick = { isContinuousScanning = !isContinuousScanning },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(Color(0xFFF7F6F2).copy(alpha = 0.95f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isContinuousScanning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Toggle Scan Mode",
                        tint = Color(0xFF956820)
                    )
                }

                // Snap Button
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isProcessingFrame) {
                        CircularProgressIndicator(
                            color = Color(0xFF956820),
                            strokeWidth = 4.dp,
                            modifier = Modifier.size(68.dp)
                        )
                    }
                    FloatingActionButton(
                        onClick = {
                            if (!isProcessingFrame) {
                                shouldTakeSnapshot = true
                                isProcessingFrame = true
                            }
                        },
                        containerColor = if (isContinuousScanning) Color(0xFF956820).copy(alpha = 0.6f) else Color(0xFF956820),
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Capture Snapshot"
                        )
                    }
                }
            }

            // 3D Point Cloud Viewport
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
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

                // Label
                Text(
                    text = "3D POINT CLOUD",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Color(0xFF737378),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .background(Color(0xFFF7F6F2).copy(alpha = 0.95f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // 1. Merging Controls Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Merge Scans",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF1C1B1F)
                )
                Switch(
                    checked = isAccumulateEnabled,
                    onCheckedChange = { isAccumulateEnabled = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF956820),
                        checkedTrackColor = Color(0xFFE5D5C0)
                    )
                )
            }
            
            OutlinedButton(
                onClick = {
                    accumulator.clear()
                    renderer.updatePoints(FloatArray(0), FloatArray(0))
                    lastPositions = null
                    lastColors = null
                    Toast.makeText(context, "Scan cleared", Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF956820)),
                border = BorderStroke(1.dp, Color(0xFF956820)),
                modifier = Modifier.height(36.dp)
            ) {
                Text(
                    text = "Clear Merged",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // 2. Export Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Export PLY Button
            Button(
                onClick = {
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
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF956820)),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text(
                    text = "Export PLY",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color.White
                )
            }

            // Export GLB Button
            Button(
                onClick = {
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
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF956820)),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text(
                    text = "Export GLB",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color.White
                )
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
