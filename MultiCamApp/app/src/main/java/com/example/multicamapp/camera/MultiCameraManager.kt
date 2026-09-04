package com.example.multicamapp.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class CameraStreamState {
    STOPPED,
    STARTING,
    STREAMING,
    ERROR
}

data class CameraStreamStatus(
    val state: CameraStreamState = CameraStreamState.STOPPED,
    val errorMessage: String? = null,
    val fps: Int = 0,
    val activeSize: Size = Size(1280, 720)
)

class MultiCameraManager(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Background thread for Camera2 operations
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // Discovered devices
    val discoveredCameras = mutableStateOf<List<CameraDeviceInfo>>(emptyList())
    val concurrentCameraSets = mutableStateOf<List<Set<String>>>(emptyList())

    // Selected cameras to stream (Key: cameraId, Value: isSelected)
    val selectedCameraIds = mutableStateOf<Set<String>>(emptySet())

    // Live status of each camera stream
    val streamStatuses = mutableStateMapOf<String, CameraStreamStatus>()

    // Current resolution preset
    val currentResolutionPreset = mutableStateOf(ResolutionPreset.HD_720P)

    // Hardware (GPU/ISP) acceleration toggle
    val isHwAccelerationEnabled = mutableStateOf(true)

    // Active sessions
    private val activeDevices = ConcurrentHashMap<String, CameraDevice>()
    private val activeSessions = ConcurrentHashMap<String, CameraCaptureSession>()
    private val activeSurfaces = ConcurrentHashMap<String, Surface>()
    private val activeTextureViews = ConcurrentHashMap<String, TextureView>()
    private val isOpening = ConcurrentHashMap<String, AtomicBoolean>()

    // Frame counters for FPS tracking
    private val frameCounts = ConcurrentHashMap<String, Int>()
    private val lastFpsTimes = ConcurrentHashMap<String, Long>()

    init {
        startBackgroundThread()
        discoverCameras()
    }

    private fun startBackgroundThread() {
        cameraThread = HandlerThread("MultiCamThread").apply {
            start()
            cameraHandler = Handler(looper)
        }
    }

    private fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join()
            cameraThread = null
            cameraHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping camera thread", e)
        }
    }

    fun discoverCameras() {
        try {
            val list = mutableListOf<CameraDeviceInfo>()
            val ids = cameraManager.cameraIdList

            var backCount = 0
            var frontCount = 0

            for (id in ids) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
                    val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

                    val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        chars.physicalCameraIds
                    } else {
                        emptySet()
                    }
                    val isLogical = physicalIds.isNotEmpty()

                    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val previewSizes = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()

                    val maxRes = previewSizes.maxByOrNull { it.width * it.height }

                    val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    val isLegacy = hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

                    val lensType: LensType
                    val displayName: String

                    when (facing) {
                        CameraCharacteristics.LENS_FACING_BACK -> {
                            if (backCount == 0) {
                                lensType = LensType.BACK_MAIN
                                displayName = "Back Main (Cam $id)"
                            } else {
                                lensType = LensType.BACK_AUX
                                displayName = "Back Aux #$backCount (Cam $id)"
                            }
                            backCount++
                        }
                        CameraCharacteristics.LENS_FACING_FRONT -> {
                            lensType = LensType.FRONT
                            displayName = if (frontCount == 0) "Front Selfie (Cam $id)" else "Front #$frontCount (Cam $id)"
                            frontCount++
                        }
                        else -> {
                            lensType = LensType.EXTERNAL
                            displayName = "External (Cam $id)"
                        }
                    }

                    list.add(
                        CameraDeviceInfo(
                            cameraId = id,
                            lensType = lensType,
                            displayName = displayName,
                            facing = facing,
                            sensorOrientation = sensorOrientation,
                            physicalCameraIds = physicalIds,
                            isLogicalMultiCamera = isLogical,
                            supportedPreviewSizes = previewSizes,
                            maxResolution = maxRes,
                            isHardwareLevelLegacy = isLegacy
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error querying camera characteristics for ID: $id", e)
                }
            }
            discoveredCameras.value = list

            // Check concurrent camera sets (Android 11+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val concurrent = cameraManager.concurrentCameraIds.toList()
                    concurrentCameraSets.value = concurrent
                    Log.d(TAG, "Concurrent camera combinations reported: $concurrent")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get concurrentCameraIds", e)
                }
            }

            // Default selection: If device supports concurrent cameras, select Back + Front.
            // If device DOES NOT support concurrent cameras (like Pixel 3a), default to 1 camera (Back Main)
            val defaultSelection = mutableSetOf<String>()
            val mainBack = list.firstOrNull { it.lensType == LensType.BACK_MAIN }
            val front = list.firstOrNull { it.lensType == LensType.FRONT }

            val canRunConcurrent = concurrentCameraSets.value.any { set ->
                mainBack != null && front != null && set.contains(mainBack.cameraId) && set.contains(front.cameraId)
            }

            if (canRunConcurrent) {
                if (mainBack != null) defaultSelection.add(mainBack.cameraId)
                if (front != null) defaultSelection.add(front.cameraId)
            } else {
                // Device does not support concurrent cameras (e.g. Pixel 3a): default to Main Back
                if (mainBack != null) {
                    defaultSelection.add(mainBack.cameraId)
                } else if (list.isNotEmpty()) {
                    defaultSelection.add(list.first().cameraId)
                }
            }

            selectedCameraIds.value = defaultSelection
            Log.d(TAG, "Default camera selection: $defaultSelection (supportsConcurrent=$canRunConcurrent)")

        } catch (e: Exception) {
            Log.e(TAG, "Error discovering cameras", e)
        }
    }

    fun toggleCameraSelection(cameraId: String) {
        val current = selectedCameraIds.value.toMutableSet()
        if (current.contains(cameraId)) {
            if (current.size > 1) {
                current.remove(cameraId)
                selectedCameraIds.value = current
                reopenActiveStreams()
            }
        } else {
            val hasConcurrentCapability = concurrentCameraSets.value.isNotEmpty()
            val isComboSupported = hasConcurrentCapability && concurrentCameraSets.value.any { it.containsAll(current + cameraId) }
            if (!isComboSupported) {
                // Device does not support concurrent cameras (like Pixel 3a) or combo unsupported:
                // seamlessly switch to this camera
                switchToSingleCamera(cameraId)
            } else {
                current.add(cameraId)
                selectedCameraIds.value = current
                reopenActiveStreams()
            }
        }
    }

    fun switchToSingleCamera(cameraId: String) {
        selectedCameraIds.value = setOf(cameraId)
        reopenActiveStreams()
    }

    fun setResolutionPreset(preset: ResolutionPreset) {
        if (currentResolutionPreset.value == preset) return
        currentResolutionPreset.value = preset
        reopenActiveStreams()
    }

    fun restartCameraStream(cameraId: String) {
        reopenActiveStreams()
    }

    private fun openSelectedCamerasConcurrently() {
        val selected = selectedCameraIds.value
        for (id in selected) {
            val tv = activeTextureViews[id]
            val st = tv?.surfaceTexture
            if (st != null && !activeDevices.containsKey(id)) {
                openCamera(id, st)
            }
        }
    }

    fun reopenActiveStreams() {
        cameraHandler?.removeCallbacksAndMessages(null)
        cameraHandler?.post {
            for (id in activeDevices.keys().toList()) {
                closeCamera(id)
            }
            cameraHandler?.postDelayed({
                openSelectedCamerasConcurrently()
            }, 300)
        }
    }

    fun toggleHwAcceleration(enabled: Boolean) {
        isHwAccelerationEnabled.value = enabled
        val handler = cameraHandler ?: return
        handler.post {
            for ((id, session) in activeSessions) {
                val camera = activeDevices[id] ?: continue
                val surface = activeSurfaces[id] ?: continue
                try {
                    val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        applyHwAcceleration(id, this, enabled)
                    }
                    session.setRepeatingRequest(builder.build(), null, handler)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to toggle HW acceleration for camera $id", e)
                }
            }
        }
    }

    private fun applyHwAcceleration(cameraId: String, builder: CaptureRequest.Builder, enabled: Boolean) {
        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val videoStabModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
            if (enabled) {
                if (videoStabModes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
                    builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
                }
                builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
            } else {
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply HW acceleration to camera $cameraId", e)
        }
    }

    fun registerTextureView(cameraId: String, textureView: TextureView) {
        activeTextureViews[cameraId] = textureView
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.d(TAG, "onSurfaceTextureAvailable for camera $cameraId (${width}x${height})")
                if (selectedCameraIds.value.contains(cameraId)) {
                    cameraHandler?.post {
                        if (!activeDevices.containsKey(cameraId)) {
                            openCamera(cameraId, surface)
                        }
                    }
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                Log.d(TAG, "onSurfaceTextureDestroyed for camera $cameraId")
                closeCamera(cameraId)
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                updateFps(cameraId)
            }
        }

        if (textureView.isAvailable && textureView.surfaceTexture != null) {
            val st = textureView.surfaceTexture!!
            if (selectedCameraIds.value.contains(cameraId)) {
                cameraHandler?.post {
                    if (!activeDevices.containsKey(cameraId)) {
                        openCamera(cameraId, st)
                    }
                }
            }
        }
    }

    fun unregisterTextureView(cameraId: String) {
        activeTextureViews.remove(cameraId)
        activeSurfaces.remove(cameraId)?.release()
        closeCamera(cameraId)
    }

    fun getTextureView(cameraId: String): TextureView? = activeTextureViews[cameraId]

    private fun onTextureAvailable(cameraId: String, surfaceTexture: SurfaceTexture, viewWidth: Int, viewHeight: Int) {
        if (!selectedCameraIds.value.contains(cameraId)) return
        val selected = selectedCameraIds.value
        val allReady = selected.all { id -> activeTextureViews[id]?.isAvailable == true }
        if (allReady) {
            cameraHandler?.post {
                openSelectedCamerasConcurrently()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera(cameraId: String, surfaceTexture: SurfaceTexture) {
        val handler = cameraHandler ?: return
        if (activeDevices.containsKey(cameraId)) {
            Log.d(TAG, "Camera $cameraId is already open")
            return
        }

        val openingFlag = isOpening.computeIfAbsent(cameraId) { AtomicBoolean(false) }
        if (!openingFlag.compareAndSet(false, true)) {
            Log.d(TAG, "Camera $cameraId is currently in opening sequence")
            return
        }

        streamStatuses[cameraId] = CameraStreamStatus(state = CameraStreamState.STARTING)

        val targetSize = chooseOptimalSize(cameraId, currentResolutionPreset.value)
        surfaceTexture.setDefaultBufferSize(targetSize.width, targetSize.height)
        activeSurfaces.remove(cameraId)?.release()
        val surface = Surface(surfaceTexture)
        activeSurfaces[cameraId] = surface

        try {
            Log.d(TAG, "Requesting openCamera for ID: $cameraId with size ${targetSize.width}x${targetSize.height}")
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    openingFlag.set(false)
                    activeDevices[cameraId] = camera
                    Log.d(TAG, "Camera $cameraId opened successfully")
                    createCameraCaptureSession(cameraId, camera, surface, targetSize)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    openingFlag.set(false)
                    Log.w(TAG, "Camera $cameraId disconnected")
                    closeCamera(cameraId)
                    streamStatuses[cameraId] = CameraStreamStatus(
                        state = CameraStreamState.STOPPED,
                        errorMessage = "Disconnected"
                    )
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    openingFlag.set(false)
                    val errorMsg = when (error) {
                        ERROR_CAMERA_IN_USE -> "Camera already in use"
                        ERROR_MAX_CAMERAS_IN_USE -> "ISP limit reached: max concurrent cameras open"
                        ERROR_CAMERA_DISABLED -> "Camera disabled by policy"
                        ERROR_CAMERA_DEVICE -> "Camera device error"
                        ERROR_CAMERA_SERVICE -> "Camera service error"
                        else -> "Camera error code $error"
                    }
                    Log.e(TAG, "Camera $cameraId onError: $errorMsg")
                    closeCamera(cameraId)
                    streamStatuses[cameraId] = CameraStreamStatus(
                        state = CameraStreamState.ERROR,
                        errorMessage = errorMsg
                    )
                }
            }, handler)
        } catch (e: SecurityException) {
            openingFlag.set(false)
            Log.e(TAG, "SecurityException opening camera $cameraId", e)
            streamStatuses[cameraId] = CameraStreamStatus(
                state = CameraStreamState.ERROR,
                errorMessage = "Permission missing"
            )
        } catch (e: Exception) {
            openingFlag.set(false)
            Log.e(TAG, "Exception opening camera $cameraId", e)
            streamStatuses[cameraId] = CameraStreamStatus(
                state = CameraStreamState.ERROR,
                errorMessage = e.message ?: "Failed to open camera"
            )
        }
    }

    private fun createCameraCaptureSession(
        cameraId: String,
        camera: CameraDevice,
        surface: Surface,
        size: Size
    ) {
        val handler = cameraHandler ?: return
        try {
            val tv = activeTextureViews[cameraId]
            val effectiveSurface = if (surface.isValid) {
                surface
            } else {
                val st = tv?.surfaceTexture
                if (st != null) {
                    st.setDefaultBufferSize(size.width, size.height)
                    val fresh = Surface(st)
                    activeSurfaces[cameraId] = fresh
                    fresh
                } else null
            }

            if (effectiveSurface == null || !effectiveSurface.isValid) {
                Log.w(TAG, "Cannot create capture session for camera $cameraId: surface is invalid or abandoned")
                closeCamera(cameraId)
                streamStatuses[cameraId] = CameraStreamStatus(
                    state = CameraStreamState.ERROR,
                    errorMessage = "Preview surface unavailable"
                )
                return
            }

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(effectiveSurface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                } else if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                }
                applyHwAcceleration(cameraId, this, isHwAccelerationEnabled.value)
            }

            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(effectiveSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!activeDevices.containsKey(cameraId)) return
                        activeSessions[cameraId] = session
                        try {
                            session.setRepeatingRequest(
                                previewRequestBuilder.build(),
                                object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureFailed(
                                        session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        failure: CaptureFailure
                                    ) {
                                        Log.e(TAG, "Camera $cameraId repeating onCaptureFailed: reason=${failure.reason}")
                                    }
                                },
                                handler
                            )
                            streamStatuses[cameraId] = CameraStreamStatus(
                                state = CameraStreamState.STREAMING,
                                activeSize = size
                            )
                            Log.d(TAG, "Camera $cameraId streaming configured at ${size.width}x${size.height}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start repeating preview for camera $cameraId", e)
                            streamStatuses[cameraId] = CameraStreamStatus(
                                state = CameraStreamState.ERROR,
                                errorMessage = "Preview request failed"
                            )
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "CaptureSession configuration failed for camera $cameraId")
                        streamStatuses[cameraId] = CameraStreamStatus(
                            state = CameraStreamState.ERROR,
                            errorMessage = "Session configure failed"
                        )
                    }
                },
                handler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating capture session for camera $cameraId", e)
            streamStatuses[cameraId] = CameraStreamStatus(
                state = CameraStreamState.ERROR,
                errorMessage = e.message
            )
        }
    }


    fun closeCamera(cameraId: String) {
        try {
            activeSessions[cameraId]?.apply {
                try { stopRepeating() } catch (ignored: Exception) {}
                try { close() } catch (ignored: Exception) {}
            }
            activeSessions.remove(cameraId)

            activeDevices[cameraId]?.apply {
                try { close() } catch (ignored: Exception) {}
            }
            activeDevices.remove(cameraId)

            // Do NOT call release() on TextureView's Surface as it destroys the underlying SurfaceTexture
            activeSurfaces.remove(cameraId)

            streamStatuses[cameraId] = CameraStreamStatus(state = CameraStreamState.STOPPED)
            isOpening[cameraId]?.set(false)
            Log.d(TAG, "Camera $cameraId closed cleanly")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera $cameraId", e)
        }
    }

    fun closeAllCameras() {
        val openIds = activeDevices.keys().toList()
        for (id in openIds) {
            closeCamera(id)
        }
    }

    private fun chooseOptimalSize(cameraId: String, preset: ResolutionPreset): Size {
        val device = discoveredCameras.value.firstOrNull { it.cameraId == cameraId }
        val supported = device?.supportedPreviewSizes ?: return Size(preset.width, preset.height)

        // Find exact or closest matching aspect ratio and resolution
        val targetRatio = preset.width.toDouble() / preset.height.toDouble()
        val closest = supported.minByOrNull { size ->
            val ratio = size.width.toDouble() / size.height.toDouble()
            val ratioDiff = kotlin.math.abs(ratio - targetRatio)
            val sizeDiff = kotlin.math.abs(size.width - preset.width) + kotlin.math.abs(size.height - preset.height)
            ratioDiff * 1000 + sizeDiff
        }
        return closest ?: Size(preset.width, preset.height)
    }

    private fun updateFps(cameraId: String) {
        val now = System.currentTimeMillis()
        val lastTime = lastFpsTimes.computeIfAbsent(cameraId) { now }
        val count = (frameCounts[cameraId] ?: 0) + 1
        frameCounts[cameraId] = count

        if (now - lastTime >= 1000) {
            val fps = (count * 1000 / (now - lastTime)).toInt()
            frameCounts[cameraId] = 0
            lastFpsTimes[cameraId] = now

            val current = streamStatuses[cameraId] ?: CameraStreamStatus()
            if (current.state == CameraStreamState.STREAMING) {
                streamStatuses[cameraId] = current.copy(fps = fps)
            }
        }
    }

    fun onDestroy() {
        closeAllCameras()
        stopBackgroundThread()
    }

    companion object {
        private const val TAG = "MultiCameraManager"
    }
}
