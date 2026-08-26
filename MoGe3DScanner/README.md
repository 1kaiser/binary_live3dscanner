# MoGe3DScanner - Native Live 3D & Thermal Scanner

<table align="center" width="100%">
  <tr>
    <td width="33.33%" align="center" valign="middle">
      <a href="https://blog.google/technology/google-deepmind/gemini-model-updates-february-2025/">
        <img src="assets/gemini_3_7_flash.png" alt="Gemini 3.7 Flash" width="100%" />
      </a>
    </td>
    <td width="33.33%" align="center" valign="middle">
      <a href="https://antigravity.google/press">
        <img src="assets/antigravity_card.png" alt="Google Antigravity" width="100%" />
      </a>
    </td>
    <td width="33.33%" align="center" valign="middle">
      <a href="https://developer.android.com/blog/posts/android-cli-build-android-apps-3x-faster-using-any-agent">
        <img src="assets/android_cli_banner.png" alt="Android CLI: Build Android apps 3x faster using any agent" width="100%" />
      </a>
    </td>
  </tr>
</table>

A self-contained Android application that performs live 3D reconstruction from single-camera RGB images and radiometric thermal cameras in real-time, utilizing the **MoGe** monocular geometry model running entirely on-device.

Crafted with **[Gemini 3.7](https://blog.google/technology/google-deepmind/gemini-model-updates-february-2025/)**, **[Google Antigravity](https://antigravity.google/press)**, and built 3x faster using the official **[Android CLI](https://developer.android.com/blog/posts/android-cli-build-android-apps-3x-faster-using-any-agent)**.

---

## 🌟 Key Features

1. **On-Device Monocular Depth Estimation**:
   Uses a quantized `moge_v2_fp16.tflite` model running locally via TensorFlow Lite, with support for GPU delegation and CPU (XNNPACK) fallback.

2. **Thermal Radiometry & Celsius (°C) Integration**:
   - Seamlessly connects to UVC thermal cameras (HT-203U, InfiRay T2/T3, HIKMICRO `VID:0x2bdf PID:0x0102`) using the standard Android USB Host API.
   - Strict `formatIndex == 1` (`YUY2` 16-bit uncompressed) descriptor negotiation ensuring raw 16-bit microbolometer data ($49,152$ uint16s).
   - Real-time Celsius temperature computation with min/max/center spot tracking.
   - 8-anchor false-color **Ironbow** colormap palette.
   - $90^\circ$ clockwise upright portrait rotation to match the phone's native camera orientation.

3. **Separate Non-Overlapping Floating Preview Cards & Fullscreen Mode**:
   - **Top Floating Card**: Live Thermal Camera feed with real-time temperature stats (`IR • XX.X°C (Min: YY.Y°C, Max: ZZ.Z°C)`).
   - **Bottom Floating Card**: Live RGB Camera feed with `LIVE` status badge.
   - **Fullscreen Toggle (⛶)**: Dedicated expand button on each preview card allowing instant full-screen inspection with a one-tap exit button.
   - Draggable and pinch-to-zoom resizable across the screen without overlapping.

4. **Interactive 4-Corner (A, B, C, D) Perspective Calibration & Homography**:
   - Dedicated **4-Corner Calibrate** button in HUD.
   - Overlays live thermal camera stream with adjustable opacity directly over the full-screen RGB camera view.
   - **4 Draggable Handles**: (A: Cyan Top-Left, B: Gold Top-Right, C: Red Bottom-Right, D: Green Bottom-Left) to visually align thermal heat features with real-world objects.
   - **Stream Rotation (⟳)**: Cycles $0^\circ, 90^\circ, 180^\circ, 270^\circ$ to handle any USB-C cable or physical dongle orientation.
   - **One-Tap JSON Archival**: Saves timestamped JSON (`moge_calibration_<timestamp>.json`) and active config to `/sdcard/Download/`.
   - **Zero-Distortion 3D Point Cloud Texture Mapping**: MoGe infers depth geometry solely from high-resolution RGB frames; the calibrated perspective homography matrix $\mathbf{H}$ projects false-color Ironbow thermal temperature data directly onto the corresponding 3D vertices without retraining the depth model.

5. **Gravity-Aligned Point Cloud Orientation**:
   At the moment the shutter is tapped, the live `TYPE_ROTATION_VECTOR` sensor matrix is captured and converted to a 4×4 OpenGL column-major matrix. This matrix becomes the **base orientation** of the rendered point cloud — so the scene always appears physically upright (gravity pointing down) immediately after a scan, regardless of how the phone was held.

6. **Simultaneous Multi-Sensor Snapshot Archival**:
   Tapping the shutter button automatically captures and archives all data to `/sdcard/Download/`:
   - `moge_rgb_<timestamp>.png`: Full-resolution RGB photo.
   - `moge_thermal_<timestamp>.png`: Colorized thermal heatmap snapshot.
   - `moge_thermal_<timestamp>.raw`: Raw 16-bit radiometric microbolometer counts.
   - `moge_calibration_<timestamp>.json`: Calibrated 4-corner perspective and rotation metadata.
   - `moge_scan_<timestamp>.glb`: 3D point cloud mesh textured with thermal data.

7. **Turntable Orbital Controls**:
   - **Single-finger drag left/right**: Spins the model around its world-vertical Y-axis.
   - **Single-finger drag up/down**: Tilts the model toward/away from the viewer (no perpendicular roll).
   - **Two-finger pinch**: Zoom in/out.
   - **Two-finger pan**: Translate the model in screen space.
   - **↺ Reset button**: Instantly snaps the view back to the gravity-aligned default orientation, clearing any user-applied rotation and pan.

8. **Multi-Frame Scan Accumulator**:
   A thread-safe `PointCloudAccumulator` merges point clouds from multiple frames on-the-fly with a FIFO cap of 150,000 points for fluid OpenGL ES 2.0 rendering.

9. **GPS Metadata Tagging**:
   Retrieves live location via `LocationManager` and embeds GPS coordinates in:
   * **PLY**: `comment gps_latitude` / `comment gps_longitude` headers.
   * **GLB**: `asset.extras` JSON fields in the glTF binary.

10. **High-Speed Sensor Dataset Recording & Offline 3D Batch Reconstruction**:
    - **Zero-Lag Live Capture (`Dataset Rec`)**: Bypasses heavy neural network inference during continuous scanning, capturing synchronized RGB frames (`$prefix.jpg`), false-color Ironbow thermal images (`${prefix}_thermal.png`), raw 16-bit uint16 microbolometer buffers (`${prefix}_thermal.raw`), 3D IMU orientation matrices (`$prefix.mat`), and `calibration.json` at native sensor frame rates without GPU thermal throttling.
    - **Offline Batch Reconstruction Engine (`⚡ Process Dataset`)**: Dedicated dialog and post-processor (`DatasetBatchProcessor.kt`) that iterates through recorded datasets, computes metric depth fields from RGB frames via MoGe TFLite, projects synchronized thermal textures using the calibrated homography matrix $\mathbf{H}$, and automatically exports merged 3D models (`moge_batch_<name>_<ts>.glb`) directly to `/sdcard/Download/`.

---

## 🏗️ Architecture

| File | Role |
|---|---|
| `MainScreen.kt` | Compose UI, CameraX analyzer, sensor listener, orbital gesture handler, dual floating cards, fullscreen overlays, GPS, export |
| `DatasetBatchProcessor.kt` | Offline batch processor iterating recorded RGB+Thermal datasets, running MoGe depth inference, applying IMU transforms & exporting merged 3D GLBs |
| `DatasetProcessorDialog.kt` | Compose UI dialog for browsing recorded datasets, triggering offline reconstruction, and tracking progress |
| `ThermalCalibrationManager.kt` | 4-corner perspective transform, `setPolyToPoly` homography, JSON serialization & persistent calibration storage |
| `ThermalCalibrationOverlay.kt` | Interactive full-screen calibration UI, draggable corner handles (A/B/C/D), neon guide lines, rotation & opacity controls |
| `MogeInterpreter.kt` | TFLite model loading, `runForMultipleInputsOutputs`, NIO buffer management |
| `GLPointRenderer.kt` | OpenGL ES 2.0 renderer; `gravityAlignMatrix`, `resetAngles()`, turntable rotation |
| `ThermalCameraManager.kt` | Thermal UVC capture manager, radiometric frame decoding, Celsius conversions, and 90° upright portrait rotation |
| `thermal/` (`BulkUvc`, `Xtherm`, `UsbDesc`) | Standard Android USB Host UVC driver, InfiRay/HT-203U radiometry, and 8-anchor Ironbow LUT |

---

## ⚙️ Android CLI Build & Deployment

Built with the official **[Android CLI](https://developer.android.com/blog/posts/android-cli-build-android-apps-3x-faster-using-any-agent)** and Command-Line Tools:

```bash
# 1. Build debug APK via Gradle
./gradlew assembleDebug

# 2. Wireless ADB Connection & Clean Installation
adb connect <PHONE_IP>:<PORT>
PKG=$(adb shell pm list packages com.example.moge3dscanner | grep moge || true)
if [ -n "$PKG" ]; then
    adb uninstall com.example.moge3dscanner
fi
adb install -t -g app/build/outputs/apk/debug/app-debug.apk

# 3. Grant Runtime Permissions & Launch
adb shell pm grant com.example.moge3dscanner android.permission.CAMERA
adb shell pm grant com.example.moge3dscanner android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.example.moge3dscanner android.permission.ACCESS_COARSE_LOCATION
adb shell am start -n com.example.moge3dscanner/.MainActivity
```

---

## 📚 Citations & References

* **Google DeepMind & Gemini 3.7**:
  * *Gemini 3.7 Release*: [https://blog.google/technology/google-deepmind/gemini-model-updates-february-2025/](https://blog.google/technology/google-deepmind/gemini-model-updates-february-2025/)
  * *Google Antigravity Press Assets & Lockup*: [https://antigravity.google/press](https://antigravity.google/press)

* **Android Developer Tools & CLI**:
  * *Android CLI Article*: [https://developer.android.com/blog/posts/android-cli-build-android-apps-3x-faster-using-any-agent](https://developer.android.com/blog/posts/android-cli-build-android-apps-3x-faster-using-any-agent)
  * *Android Developer Tools*: [https://developer.android.com/tools](https://developer.android.com/tools)

* **MoGe v1 & v2 Models**:
  State-of-the-art monocular geometry estimation by Microsoft Research.
  * *Repository*: [https://github.com/microsoft/MoGe](https://github.com/microsoft/MoGe)

* **HT203U Thermal Camera Integration**:
  UVC-over-bulk USB streaming and InfiRay / Xtherm radiometry ported from **HT203U-Thermal** and **R_e/thermal**:
  * *Repository*: [https://github.com/cfbird/HT203U-Thermal](https://github.com/cfbird/HT203U-Thermal)
  * *Thermal Processing & Colormap*: [https://github.com/1kaiser/R_e/tree/main/thermal](https://github.com/1kaiser/R_e/tree/main/thermal)
  * *Radiometric calibration math*: [stawel/ht301_hacklib](https://github.com/stawel/ht301_hacklib)

* **3D Live Scanner Historical Legacy**:
  This work builds upon the mobile 3D scanning tradition pioneered by **Luboš Vonásek**:
  * *Repository*: [https://github.com/lvonasek/binary_live3dscanner](https://github.com/lvonasek/binary_live3dscanner)
