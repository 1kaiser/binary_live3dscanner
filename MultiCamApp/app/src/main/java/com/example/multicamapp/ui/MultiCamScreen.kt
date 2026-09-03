package com.example.multicamapp.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.multicamapp.camera.CameraDeviceInfo
import com.example.multicamapp.camera.CameraStreamStatus
import com.example.multicamapp.camera.MultiCameraManager
import com.example.multicamapp.camera.ResolutionPreset
import com.example.multicamapp.capture.MultiCamPhotoCapture
import com.example.multicamapp.capture.MultiCamVideoRecorder
import com.example.multicamapp.location.GpsLocationManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiCamScreen(
    cameraManager: MultiCameraManager,
    locationManager: GpsLocationManager,
    videoRecorder: MultiCamVideoRecorder
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val discoveredCameras by cameraManager.discoveredCameras
    val selectedCameraIds by cameraManager.selectedCameraIds
    val currentPreset by cameraManager.currentResolutionPreset
    val concurrentSets by cameraManager.concurrentCameraSets

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    var layoutMode by remember { mutableStateOf(ViewLayoutMode.AUTO) }
    var focusedFullscreenCameraId by remember { mutableStateOf<String?>(null) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    val effectiveLayout = when (layoutMode) {
        ViewLayoutMode.AUTO -> if (isLandscape) ViewLayoutMode.SPLIT_VERTICAL else ViewLayoutMode.SPLIT_HORIZONTAL
        else -> layoutMode
    }

    // Floating card states (for FLOATING_PIP mode, matching MoGe3DScanner)
    var pipOffset by remember { mutableStateOf(Offset(0f, 0f)) }
    var pipSizeMultiplier by remember { mutableFloatStateOf(1.0f) }

    // Shutter flash animation
    var showShutterFlash by remember { mutableStateOf(false) }

    // Status message / toast
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val activeCameras = remember(discoveredCameras, selectedCameraIds) {
        discoveredCameras.filter { selectedCameraIds.contains(it.cameraId) }
    }

    // Helper to grab FULL uncropped bitmaps from active TextureViews
    val getBitmapFrames = remember(cameraManager, activeCameras, isLandscape) {
        {
            activeCameras.mapNotNull { cam ->
                val tv = cameraManager.getTextureView(cam.cameraId)
                val streamSize = cameraManager.streamStatuses[cam.cameraId]?.activeSize
                    ?: android.util.Size(1280, 720)
                val (reqW, reqH) = if (isLandscape) {
                    Pair(streamSize.width, streamSize.height)
                } else {
                    Pair(streamSize.height, streamSize.width)
                }
                val fullBmp = tv?.getBitmap(reqW, reqH) ?: tv?.bitmap
                if (fullBmp != null) Pair(cam.displayName, fullBmp) else null
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F))
    ) {
        // 1. Camera Feeds Layout
        if (focusedFullscreenCameraId != null) {
            // Fullscreen focus mode
            val focusedCam = discoveredCameras.firstOrNull { it.cameraId == focusedFullscreenCameraId }
            if (focusedCam != null) {
                CameraFeedView(
                    cameraInfo = focusedCam,
                    cameraManager = cameraManager,
                    status = cameraManager.streamStatuses[focusedCam.cameraId] ?: CameraStreamStatus(),
                    isFullscreen = true,
                    onToggleFullscreen = { focusedFullscreenCameraId = null },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else if (activeCameras.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No cameras selected.\nPlease enable at least one camera below.",
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            when (effectiveLayout) {
                ViewLayoutMode.SPLIT_VERTICAL -> {
                    // Left / Right split
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 100.dp, bottom = 120.dp, start = 8.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (cam in activeCameras) {
                            CameraFeedView(
                                cameraInfo = cam,
                                cameraManager = cameraManager,
                                status = cameraManager.streamStatuses[cam.cameraId] ?: CameraStreamStatus(),
                                isFullscreen = false,
                                onToggleFullscreen = { focusedFullscreenCameraId = cam.cameraId },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }

                ViewLayoutMode.SPLIT_HORIZONTAL -> {
                    // Top / Bottom split
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 100.dp, bottom = 120.dp, start = 8.dp, end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (cam in activeCameras) {
                            CameraFeedView(
                                cameraInfo = cam,
                                cameraManager = cameraManager,
                                status = cameraManager.streamStatuses[cam.cameraId] ?: CameraStreamStatus(),
                                isFullscreen = false,
                                onToggleFullscreen = { focusedFullscreenCameraId = cam.cameraId },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )
                        }
                    }
                }

                ViewLayoutMode.FLOATING_PIP -> {
                    // Primary camera takes full screen; others float in draggable & resizable PiP cards (MoGe3DScanner style)
                    val primaryCam = activeCameras.first()
                    val secondaryCams = activeCameras.drop(1)

                    CameraFeedView(
                        cameraInfo = primaryCam,
                        cameraManager = cameraManager,
                        status = cameraManager.streamStatuses[primaryCam.cameraId] ?: CameraStreamStatus(),
                        isFullscreen = false,
                        onToggleFullscreen = { focusedFullscreenCameraId = primaryCam.cameraId },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 100.dp, bottom = 120.dp)
                    )

                    // Floating cards for secondary/tertiary cameras
                    if (secondaryCams.isNotEmpty()) {
                        val cardWidth = (130 * pipSizeMultiplier).dp
                        val cardHeight = (165 * pipSizeMultiplier).dp

                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset { IntOffset(pipOffset.x.roundToInt(), pipOffset.y.roundToInt()) }
                                .padding(bottom = 135.dp, end = 16.dp)
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        pipOffset += pan
                                        pipSizeMultiplier = (pipSizeMultiplier * zoom).coerceIn(0.6f, 2.5f)
                                    }
                                },
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            for (secCam in secondaryCams) {
                                Box(
                                    modifier = Modifier
                                        .size(width = cardWidth, height = cardHeight)
                                        .shadow(8.dp, RoundedCornerShape(14.dp))
                                ) {
                                    CameraFeedView(
                                        cameraInfo = secCam,
                                        cameraManager = cameraManager,
                                        status = cameraManager.streamStatuses[secCam.cameraId] ?: CameraStreamStatus(),
                                        isFullscreen = false,
                                        onToggleFullscreen = { focusedFullscreenCameraId = secCam.cameraId },
                                        modifier = Modifier.fillMaxSize(),
                                        isFloating = true
                                    )
                                }
                            }
                        }
                    }
                }
                else -> {}
            }
        }

        // 2. Shutter Flash Overlay
        AnimatedVisibility(
            visible = showShutterFlash,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize().zIndex(999f)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
            )
        }

        // 3. Top HUD Bar & Controls
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 28.dp, start = 12.dp, end = 12.dp)
                .zIndex(500f)
        ) {
            // Row 1: App Title, GPS Toggle, Diagnostics
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "MultiCam",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = " LIVE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF69F0AE),
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // GPS Toggle Chip
                    val gpsOn = locationManager.isGpsEnabled.value
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (gpsOn) Color(0xFF1B5E20) else Color(0xFF2C2C2C),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (gpsOn) Color(0xFF81C784) else Color.Gray.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.clickable {
                            locationManager.enableGps(!gpsOn)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = "GPS",
                                tint = if (gpsOn) Color(0xFF81C784) else Color.LightGray,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (gpsOn) "GPS ON" else "GPS OFF",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (gpsOn) Color.White else Color.LightGray,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // HW Acceleration (GPU/ISP) Toggle Chip
                    val hwAccelOn = cameraManager.isHwAccelerationEnabled.value
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (hwAccelOn) Color(0xFF004D40) else Color(0xFF2C2C2C),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (hwAccelOn) Color(0xFF00E676) else Color.Gray.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.clickable {
                            cameraManager.toggleHwAcceleration(!hwAccelOn)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (hwAccelOn) "⚡ GPU/ISP" else "⚡ HW OFF",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (hwAccelOn) Color(0xFF69F0AE) else Color.LightGray,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Diagnostics Button
                    IconButton(
                        onClick = { showDiagnosticsDialog = true },
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color(0xFF2C2C2C), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Hardware Info",
                            tint = Color(0xFF90CAF9),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Row 2: Camera Selection Chips (Any combination!)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CAMS:",
                    fontSize = 10.sp,
                    color = Color.LightGray,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )

                for (cam in discoveredCameras) {
                    val isSel = selectedCameraIds.contains(cam.cameraId)
                    FilterChip(
                        selected = isSel,
                        onClick = { cameraManager.toggleCameraSelection(cam.cameraId) },
                        label = {
                            Text(
                                text = "Cam ${cam.cameraId} (${cam.displayName.take(8)})",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF00695C),
                            selectedLabelColor = Color.White,
                            containerColor = Color(0xFF212121),
                            labelColor = Color.Gray
                        ),
                        modifier = Modifier.height(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Row 3: Layout switcher & Resolution Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Layout Switcher
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ViewLayoutMode.values().forEach { mode ->
                        val active = layoutMode == mode
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) Color(0xFF1565C0) else Color(0xFF242424),
                            modifier = Modifier.clickable { layoutMode = mode }
                        ) {
                            Text(
                                text = mode.displayName,
                                fontSize = 9.5.sp,
                                color = if (active) Color.White else Color.Gray,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Resolution presets
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ResolutionPreset.values().forEach { preset ->
                        val active = currentPreset == preset
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) Color(0xFFE65100) else Color(0xFF242424),
                            modifier = Modifier.clickable { cameraManager.setResolutionPreset(preset) }
                        ) {
                            Text(
                                text = preset.displayName.substringBefore(" "),
                                fontSize = 9.5.sp,
                                color = if (active) Color.White else Color.Gray,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // Live GPS Status readout if enabled
            if (locationManager.isGpsEnabled.value) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "📍 ${locationManager.statusText.value}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFFFD54F),
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // 4. Bottom Controls: Shutter Button & Video Recording Button
        val isRecording by videoRecorder.isRecording
        val recordingSeconds by videoRecorder.recordingSeconds

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 24.dp)
                .zIndex(500f),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(36.dp)
            ) {
                // Video Recording Button
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(if (isRecording) Color(0xFFB71C1C) else Color(0xFF263238))
                            .border(2.dp, if (isRecording) Color.Red else Color.White.copy(alpha = 0.6f), CircleShape)
                            .clickable {
                                if (isRecording) {
                                    videoRecorder.stopRecording { savedUri ->
                                        statusMessage = if (savedUri != null) "Saved video: $savedUri" else "Recording finalized"
                                    }
                                } else {
                                    videoRecorder.startRecording(
                                        bitmapsProvider = getBitmapFrames,
                                        location = locationManager.currentLocation.value,
                                        isLandscape = isLandscape,
                                        onSuccess = { statusMessage = "Recording started" },
                                        onError = { err -> statusMessage = "Record error: $err" }
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isRecording) {
                            // Red square stop icon
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(Color.White, RoundedCornerShape(4.dp))
                            )
                        } else {
                            // Red circle record icon
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .background(Color(0xFFFF1744), CircleShape)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isRecording) {
                            val mins = recordingSeconds / 60
                            val secs = recordingSeconds % 60
                            String.format("🔴 %02d:%02d", mins, secs)
                        } else {
                            "VIDEO"
                        },
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (isRecording) Color(0xFFFF5252) else Color.LightGray,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Shutter Button (Photography / Clicking)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(74.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                            .border(3.dp, Color.White, CircleShape)
                            .padding(5.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable {
                                coroutineScope.launch {
                                    showShutterFlash = true
                                    val frames = getBitmapFrames()
                                    if (frames.isNotEmpty()) {
                                        val saved = MultiCamPhotoCapture.captureSimultaneousPhotos(
                                            context = context,
                                            cameraFrames = frames,
                                            location = locationManager.currentLocation.value,
                                            isLandscape = isLandscape,
                                            onProgress = { statusMessage = it }
                                        )
                                        statusMessage = "Saved ${saved.size} photos to Pictures/MultiCam"
                                    } else {
                                        statusMessage = "No camera frames available"
                                    }
                                    delay(100)
                                    showShutterFlash = false
                                }
                            }
                    )

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "PHOTO",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 5. Toast / Status Message Banner
        statusMessage?.let { msg ->
            LaunchedEffect(msg) {
                delay(3500)
                statusMessage = null
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.85f),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF69F0AE)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 110.dp)
                    .zIndex(700f)
            ) {
                Text(
                    text = msg,
                    fontSize = 11.sp,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        // 6. Diagnostics Dialog
        if (showDiagnosticsDialog) {
            HardwareDiagnosticsDialog(
                cameras = discoveredCameras,
                concurrentSets = concurrentSets,
                onDismiss = { showDiagnosticsDialog = false }
            )
        }
    }
}
