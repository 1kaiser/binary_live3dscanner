package com.example.multicamapp.camera

import android.hardware.camera2.CameraCharacteristics
import android.util.Size

enum class LensType {
    BACK_MAIN,
    FRONT,
    BACK_AUX,
    EXTERNAL
}

enum class ResolutionPreset(val displayName: String, val width: Int, val height: Int) {
    VGA_480P("480p (VGA)", 640, 480),
    HD_720P("720p (HD)", 1280, 720),
    FHD_1080P("1080p (FHD)", 1920, 1080)
}

enum class CameraAvailabilityState {
    STREAMING,          // Currently active and streaming preview frames
    AVAILABLE,          // Available to be toggled ON concurrently
    ISP_SWITCHABLE,     // Cannot stream concurrently due to ISP limits; clicking switches stream cleanly
    BUSY_EXTERNAL,      // In-use by external app or system service
    DISABLED            // Hardware unavailable or in error state
}

enum class DeviceHardwareConcurrencyMode(val label: String) {
    CONCURRENT_MULTI_CAM("DUAL-CAM ISP"),
    SINGLE_STREAM_ONLY("SINGLE-CAM ISP")
}

data class CameraDeviceInfo(
    val cameraId: String,
    val lensType: LensType,
    val displayName: String,
    val facing: Int, // CameraCharacteristics.LENS_FACING_*
    val sensorOrientation: Int,
    val physicalCameraIds: Set<String>,
    val isLogicalMultiCamera: Boolean,
    val supportedPreviewSizes: List<Size>,
    val maxResolution: Size?,
    val isHardwareLevelLegacy: Boolean
)

