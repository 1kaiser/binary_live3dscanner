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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.zIndex
import com.example.multicamapp.camera.CameraAvailabilityState
import com.example.multicamapp.camera.CameraDeviceInfo
import com.example.multicamapp.camera.CameraStreamStatus
import com.example.multicamapp.camera.DeviceHardwareConcurrencyMode
import com.example.multicamapp.camera.MultiCameraManager
import com.example.multicamapp.camera.ResolutionPreset
import com.example.multicamapp.capture.MultiCamPhotoCapture
import com.example.multicamapp.capture.MultiCamVideoRecorder
import com.example.multicamapp.location.GpsLocationManager
import com.example.multicamapp.sync.QuickShareSyncManager
import com.example.multicamapp.sync.SyncNode
import com.example.multicamapp.sync.SyncRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiCamScreen(
    cameraManager: MultiCameraManager,
    locationManager: GpsLocationManager,
    videoRecorder: MultiCamVideoRecorder,
    syncManager: QuickShareSyncManager
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val discoveredCameras by cameraManager.discoveredCameras
    val selectedCameraIds by cameraManager.selectedCameraIds
    val currentPreset by cameraManager.currentResolutionPreset
    val concurrentSets by cameraManager.concurrentCameraSets

    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Auto Split is NOT active by default. Default is based on orientation:
    // Portrait -> Top / Bottom (SPLIT_HORIZONTAL)
    // Landscape -> Side by Side (SPLIT_VERTICAL)
    var layoutMode by remember {
        mutableStateOf(if (isLandscape) ViewLayoutMode.SPLIT_VERTICAL else ViewLayoutMode.SPLIT_HORIZONTAL)
    }
    var focusedFullscreenCameraId by remember { mutableStateOf<String?>(null) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    // Adapt manual mode if orientation changes
    androidx.compose.runtime.LaunchedEffect(isLandscape) {
        if (layoutMode == ViewLayoutMode.SPLIT_VERTICAL && !isLandscape) {
            layoutMode = ViewLayoutMode.SPLIT_HORIZONTAL
        } else if (layoutMode == ViewLayoutMode.SPLIT_HORIZONTAL && isLandscape) {
            layoutMode = ViewLayoutMode.SPLIT_VERTICAL
        }
    }

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

    // Quick Share Sync States
    val syncRole by syncManager.role.collectAsState()
    val connectedNodes by syncManager.connectedNodes.collectAsState()
    val isSearching by syncManager.isSearching.collectAsState()
    val syncStatusText by syncManager.statusText.collectAsState()
    var showQuickShareDialog by remember { mutableStateOf(false) }

    val activeCameras = remember(discoveredCameras, selectedCameraIds) {
        discoveredCameras.filter { selectedCameraIds.contains(it.cameraId) }
    }

    // Helper to grab FULL uncropped bitmaps from active TextureViews
    val getBitmapFrames = remember(cameraManager, activeCameras, isLandscape) {
        {
            activeCameras.filter { cam ->
                val status = cameraManager.streamStatuses[cam.cameraId]
                status != null && status.state != com.example.multicamapp.camera.CameraStreamState.ERROR
            }.mapNotNull { cam ->
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

    // Register incoming sync callbacks from Master / Worker
    DisposableEffect(syncManager) {
        syncManager.onSyncPhotoTrigger = { sessionId ->
            coroutineScope.launch {
                showShutterFlash = true
                val frames = getBitmapFrames()
                if (frames.isNotEmpty()) {
                    val saved = MultiCamPhotoCapture.captureSimultaneousPhotos(
                        context = context,
                        cameraFrames = frames,
                        location = locationManager.currentLocation.value,
                        isLandscape = isLandscape,
                        customSessionId = sessionId,
                        onProgress = { statusMessage = it }
                    )
                    statusMessage = "⚡ Sync Photo: Saved ${saved.size} photos ($sessionId)"
                }
                delay(100)
                showShutterFlash = false
            }
        }
        syncManager.onSyncRecordStartTrigger = { sessionId ->
            if (!videoRecorder.isRecording.value) {
                videoRecorder.startRecording(
                    bitmapsProvider = getBitmapFrames,
                    location = locationManager.currentLocation.value,
                    isLandscape = isLandscape,
                    customSessionId = sessionId,
                    onSuccess = { statusMessage = "⚡ Sync Recording started ($sessionId)" },
                    onError = { err -> statusMessage = "Record error: $err" }
                )
            }
        }
        syncManager.onSyncRecordStopTrigger = {
            if (videoRecorder.isRecording.value) {
                videoRecorder.stopRecording { savedUri ->
                    statusMessage = if (savedUri != null) "⚡ Sync Video saved" else "Recording stopped"
                }
            }
        }
        cameraManager.onCameraStateChanged = { mode, activeCams, availCams ->
            syncManager.broadcastDeviceStatus(mode, activeCams, availCams)
        }
        cameraManager.recomputeAvailabilityStates()
        onDispose {
            syncManager.onSyncPhotoTrigger = null
            syncManager.onSyncRecordStartTrigger = null
            syncManager.onSyncRecordStopTrigger = null
            cameraManager.onCameraStateChanged = null
        }
    }

    // Preserve camera feed composables across layout changes so TextureViews are never destroyed
    val cameraFeedContents = remember(discoveredCameras, focusedFullscreenCameraId) {
        discoveredCameras.associate { cam ->
            cam.cameraId to movableContentOf { modifier: Modifier, isFloating: Boolean ->
                CameraFeedView(
                    cameraInfo = cam,
                    cameraManager = cameraManager,
                    status = cameraManager.streamStatuses[cam.cameraId] ?: CameraStreamStatus(),
                    isFullscreen = focusedFullscreenCameraId == cam.cameraId,
                    onToggleFullscreen = {
                        focusedFullscreenCameraId = if (focusedFullscreenCameraId == cam.cameraId) null else cam.cameraId
                    },
                    modifier = modifier,
                    isFloating = isFloating
                )
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
            cameraFeedContents[focusedFullscreenCameraId]?.invoke(
                Modifier.fillMaxSize(),
                false
            )
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
                            .padding(top = 150.dp, bottom = 120.dp, start = 8.dp, end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (cam in activeCameras) {
                            cameraFeedContents[cam.cameraId]?.invoke(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                false
                            )
                        }
                    }
                }

                ViewLayoutMode.SPLIT_HORIZONTAL -> {
                    // Top / Bottom split
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 150.dp, bottom = 120.dp, start = 8.dp, end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (cam in activeCameras) {
                            cameraFeedContents[cam.cameraId]?.invoke(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                false
                            )
                        }
                    }
                }

                ViewLayoutMode.FLOATING_PIP -> {
                    // Primary camera takes full screen; others float in draggable & resizable PiP cards (MoGe3DScanner style)
                    val primaryCam = activeCameras.first()
                    val secondaryCams = activeCameras.drop(1)

                    cameraFeedContents[primaryCam.cameraId]?.invoke(
                        Modifier
                            .fillMaxSize()
                            .padding(top = 150.dp, bottom = 120.dp),
                        false
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
                                    cameraFeedContents[secCam.cameraId]?.invoke(
                                        Modifier.fillMaxSize(),
                                        true
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

                    // Quick Share Multi-Device Sync Chip
                    val syncChipColor = when (syncRole) {
                        SyncRole.HOST -> Color(0xFF004D40)
                        SyncRole.WORKER -> Color(0xFF0D47A1)
                        SyncRole.STANDALONE -> Color(0xFF2C2C2C)
                    }
                    val syncChipBorder = when (syncRole) {
                        SyncRole.HOST -> Color(0xFF00E676)
                        SyncRole.WORKER -> Color(0xFF448AFF)
                        SyncRole.STANDALONE -> Color.Gray.copy(alpha = 0.5f)
                    }
                    val syncChipText = when (syncRole) {
                        SyncRole.HOST -> "📡 HOST (${connectedNodes.size})"
                        SyncRole.WORKER -> if (connectedNodes.isNotEmpty()) "📡 NODE (SYNC)" else "📡 SEARCHING..."
                        SyncRole.STANDALONE -> "📡 SYNC"
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = syncChipColor,
                        border = androidx.compose.foundation.BorderStroke(1.dp, syncChipBorder),
                        modifier = Modifier.clickable { showQuickShareDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = syncChipText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (syncRole != SyncRole.STANDALONE) Color.White else Color.LightGray,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // CPU Multi-Core Chip
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF1A237E),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF536DFE)),
                        modifier = Modifier.clickable { showDiagnosticsDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🧠 CPU: 8C",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF8C9EFF),
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

            // Row 2: Camera Selection Chips & Hardware Concurrency Detector
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

                // Hardware ISP Concurrency Mode Badge
                val concurrencyMode by cameraManager.hardwareConcurrencyMode
                val isConcurrent = concurrencyMode == DeviceHardwareConcurrencyMode.CONCURRENT_MULTI_CAM
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isConcurrent) Color(0xFF004D40) else Color(0xFFE65100).copy(alpha = 0.25f),
                    border = BorderStroke(1.dp, if (isConcurrent) Color(0xFF00E676) else Color(0xFFFF9800))
                ) {
                    Text(
                        text = if (isConcurrent) "⚡ DUAL-ISP" else "📷 SINGLE-ISP",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isConcurrent) Color(0xFF69F0AE) else Color(0xFFFFB74D),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                discoveredCameras.forEach { cam ->
                    val isSel = selectedCameraIds.contains(cam.cameraId)
                    val availState = cameraManager.cameraAvailabilityStates[cam.cameraId]
                        ?: (if (isSel) CameraAvailabilityState.STREAMING else CameraAvailabilityState.AVAILABLE)

                    val chipLabel: String
                    val chipIcon: String
                    val chipBorderColor: Color
                    val chipContainerColor: Color
                    val chipLabelColor: Color

                    when (availState) {
                        CameraAvailabilityState.STREAMING -> {
                            chipLabel = "Cam ${cam.cameraId} (${cam.displayName.take(7)})"
                            chipIcon = "●"
                            chipBorderColor = Color(0xFF00E676)
                            chipContainerColor = Color(0xFF00695C)
                            chipLabelColor = Color.White
                        }
                        CameraAvailabilityState.AVAILABLE -> {
                            chipLabel = "Cam ${cam.cameraId} (+ADD)"
                            chipIcon = "+"
                            chipBorderColor = Color(0xFF00897B)
                            chipContainerColor = Color(0xFF212121)
                            chipLabelColor = Color(0xFF80CBC4)
                        }
                        CameraAvailabilityState.ISP_SWITCHABLE -> {
                            chipLabel = "Cam ${cam.cameraId} (SWITCH)"
                            chipIcon = "⇄"
                            chipBorderColor = Color(0xFFFF9800)
                            chipContainerColor = Color(0xFF262626)
                            chipLabelColor = Color(0xFFFFCC80)
                        }
                        CameraAvailabilityState.BUSY_EXTERNAL -> {
                            chipLabel = "Cam ${cam.cameraId} (BUSY)"
                            chipIcon = "⊘"
                            chipBorderColor = Color.DarkGray
                            chipContainerColor = Color(0xFF1B1B1B)
                            chipLabelColor = Color.Gray
                        }
                        CameraAvailabilityState.DISABLED -> {
                            chipLabel = "Cam ${cam.cameraId} (ERR)"
                            chipIcon = "⚠"
                            chipBorderColor = Color(0xFFD32F2F)
                            chipContainerColor = Color(0xFF1B1B1B)
                            chipLabelColor = Color(0xFFEF9A9A)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = chipContainerColor,
                        border = BorderStroke(1.dp, chipBorderColor),
                        modifier = Modifier
                            .height(28.dp)
                            .clickable(enabled = availState != CameraAvailabilityState.BUSY_EXTERNAL && availState != CameraAvailabilityState.DISABLED) {
                                if (availState == CameraAvailabilityState.ISP_SWITCHABLE) {
                                    cameraManager.switchToSingleCamera(cam.cameraId)
                                } else {
                                    cameraManager.toggleCameraSelection(cam.cameraId)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = chipIcon,
                                fontSize = 10.sp,
                                color = chipBorderColor,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = chipLabel,
                                fontSize = 10.sp,
                                color = chipLabelColor,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (availState == CameraAvailabilityState.STREAMING) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Row 3: Layout switcher
            // Rules:
            // 1. Side by side is NOT required in portrait mode (only in landscape)
            // 2. Both toggles (Top/Bottom & Side-by-Side) are NOT required when Auto Split is active
            // 3. When Auto Split is not active (default), based on screen orientation the valid toggle is shown
            val visibleModes = remember(layoutMode, isLandscape) {
                if (layoutMode == ViewLayoutMode.AUTO) {
                    listOf(ViewLayoutMode.AUTO, ViewLayoutMode.FLOATING_PIP)
                } else if (isLandscape) {
                    listOf(ViewLayoutMode.AUTO, ViewLayoutMode.SPLIT_VERTICAL, ViewLayoutMode.FLOATING_PIP)
                } else {
                    listOf(ViewLayoutMode.AUTO, ViewLayoutMode.SPLIT_HORIZONTAL, ViewLayoutMode.FLOATING_PIP)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "VIEW:",
                    fontSize = 10.sp,
                    color = Color.LightGray,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                visibleModes.forEach { mode ->
                    val active = layoutMode == mode
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (active) Color(0xFF1565C0) else Color(0xFF242424),
                        border = if (active) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF64B5F6)) else null,
                        modifier = Modifier.clickable {
                            layoutMode = if (layoutMode == mode && mode == ViewLayoutMode.AUTO) {
                                if (isLandscape) ViewLayoutMode.SPLIT_VERTICAL else ViewLayoutMode.SPLIT_HORIZONTAL
                            } else {
                                mode
                            }
                        }
                    ) {
                        Text(
                            text = mode.displayName,
                            fontSize = 9.5.sp,
                            color = if (active) Color.White else Color.LightGray,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Row 4: Resolution Presets (480p, 720p, 1080p)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RES: ",
                    fontSize = 10.sp,
                    color = Color.LightGray,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                ResolutionPreset.values().forEach { preset ->
                    val active = currentPreset == preset
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (active) Color(0xFFE65100) else Color(0xFF242424),
                        border = if (active) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFB74D)) else null,
                        modifier = Modifier.clickable { cameraManager.setResolutionPreset(preset) }
                    ) {
                        Text(
                            text = preset.displayName,
                            fontSize = 10.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) Color.White else Color.LightGray,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
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
                                    syncManager.triggerSynchronousRecordStop {
                                        videoRecorder.stopRecording { savedUri ->
                                            statusMessage = if (savedUri != null) "Saved video: $savedUri" else "Recording finalized"
                                        }
                                    }
                                } else {
                                    syncManager.triggerSynchronousRecordStart { sessionId ->
                                        videoRecorder.startRecording(
                                            bitmapsProvider = getBitmapFrames,
                                            location = locationManager.currentLocation.value,
                                            isLandscape = isLandscape,
                                            customSessionId = sessionId,
                                            onSuccess = { statusMessage = "Recording started ($sessionId)" },
                                            onError = { err -> statusMessage = "Record error: $err" }
                                        )
                                    }
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
                                syncManager.triggerSynchronousPhoto { sessionId ->
                                    coroutineScope.launch {
                                        showShutterFlash = true
                                        val frames = getBitmapFrames()
                                        if (frames.isNotEmpty()) {
                                            val saved = MultiCamPhotoCapture.captureSimultaneousPhotos(
                                                context = context,
                                                cameraFrames = frames,
                                                location = locationManager.currentLocation.value,
                                                isLandscape = isLandscape,
                                                customSessionId = sessionId,
                                                onProgress = { statusMessage = it }
                                            )
                                            statusMessage = "Saved ${saved.size} photos locally"
                                        } else {
                                            statusMessage = "No camera frames available"
                                        }
                                        delay(100)
                                        showShutterFlash = false
                                    }
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
                concurrencyMode = cameraManager.hardwareConcurrencyMode.value,
                availabilityStates = cameraManager.cameraAvailabilityStates,
                onDismiss = { showDiagnosticsDialog = false }
            )
        }

        // 7. Quick Share Multi-Device Sync Dialog
        if (showQuickShareDialog) {
            QuickShareDialog(
                syncManager = syncManager,
                role = syncRole,
                connectedNodes = connectedNodes,
                isSearching = isSearching,
                statusText = syncStatusText,
                onDismiss = { showQuickShareDialog = false }
            )
        }
    }
}

@Composable
fun QuickShareDialog(
    syncManager: QuickShareSyncManager,
    role: SyncRole,
    connectedNodes: List<SyncNode>,
    isSearching: Boolean,
    statusText: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "📡 Quick Share Multi-Device Sync",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = Color(0xFF69F0AE)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Wirelessly pair multiple Android phones using Quick Share (Nearby Connections P2P) for synchronized photo clicks & video recording.",
                    fontSize = 12.sp,
                    color = Color.LightGray
                )

                // Current Status Box
                Surface(
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF424242)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "STATUS: $statusText",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (role) {
                                SyncRole.HOST -> Color(0xFF69F0AE)
                                SyncRole.WORKER -> Color(0xFF82B1FF)
                                SyncRole.STANDALONE -> Color.White
                            },
                            fontFamily = FontFamily.Monospace
                        )
                        if (isSearching) {
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp),
                                color = Color(0xFF69F0AE)
                            )
                        }
                    }
                }

                // Paired Nodes List
                if (connectedNodes.isNotEmpty()) {
                    Text(
                        text = "Connected Devices (${connectedNodes.size}):",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    connectedNodes.forEach { node ->
                        Surface(
                            color = Color(0xFF263238),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "📱 ${node.name}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = if (node.clockOffsetMs != 0L) "Sync: ${node.clockOffsetMs}ms" else "Connected",
                                        fontSize = 11.sp,
                                        color = Color(0xFF80CBC4),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                if (node.concurrencyMode.isNotEmpty() || node.activeCameras.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val modeLabel = if (node.concurrencyMode.contains("CONCURRENT")) "⚡ Dual-ISP Concurrent" else "📷 Single-ISP Mode"
                                    val camsLabel = if (node.activeCameras.isNotEmpty()) "Active: Cam ${node.activeCameras.joinToString(", ")}" else "Waiting"
                                    Text(
                                        text = "$modeLabel • $camsLabel",
                                        fontSize = 10.sp,
                                        color = Color(0xFFFFD54F),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }

                // Mode Selection Buttons
                Text(
                    text = "Select Device Mode:",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { syncManager.startHost() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (role == SyncRole.HOST) Color(0xFF00E676) else Color(0xFF2E7D32)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (role == SyncRole.HOST) "✓ HOST" else "Be Host",
                            color = if (role == SyncRole.HOST) Color.Black else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = { syncManager.startWorker() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (role == SyncRole.WORKER) Color(0xFF448AFF) else Color(0xFF1565C0)
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (role == SyncRole.WORKER) "✓ WORKER" else "Be Worker",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                var customIpInput by remember { mutableStateOf("") }
                if (role == SyncRole.WORKER && connectedNodes.isEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = customIpInput,
                            onValueChange = { customIpInput = it },
                            placeholder = { Text("Host IP (e.g. 172.31.10.35)", fontSize = 11.sp, color = Color.Gray) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF448AFF),
                                unfocusedBorderColor = Color.DarkGray
                            )
                        )
                        Button(
                            onClick = { if (customIpInput.isNotBlank()) syncManager.connectToHostIp(customIpInput.trim()) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF448AFF)),
                            modifier = Modifier.height(50.dp)
                        ) {
                            Text("Connect", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (role != SyncRole.STANDALONE) {
                    OutlinedButton(
                        onClick = { syncManager.stopSync() },
                        modifier = Modifier.fillMaxWidth(),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252))
                    ) {
                        Text("Disconnect / Standalone", color = Color(0xFFFF5252), fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = Color(0xFF69F0AE), fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF121212)
    )
}
