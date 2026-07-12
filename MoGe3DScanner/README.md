# MoGe3DScanner - Native Live 3D Scanner

A self-contained Android application that performs live 3D reconstruction from single-camera RGB images in real-time, utilizing the **MoGe** monocular geometry model running on-device.

![UI Screenshot](screenshot.png)

---

## 🌟 Key Features

1. **On-Device Monocular Depth Estimation**:
   Uses a quantized `moge_v2_fp16.tflite` model running locally via TensorFlow Lite, with support for GPU and CPU (XNNPACK) acceleration.
2. **Gravity-Aligned Coordinate Mapping**:
   Integrates the phone's orientation sensor vector (`Sensor.TYPE_ROTATION_VECTOR`) to calculate a thread-safe 3D rotation matrix. It automatically maps the camera-space point cloud coordinates to device space and rotates them to world space. This keeps the coordinate system aligned with physical gravity (keeping the scanned object upright regardless of how the phone is tilted).
3. **Multi-Frame Scan Merging**:
   Includes a thread-safe `PointCloudAccumulator` to accumulate and merge point clouds across multiple camera frames on the fly, with a FIFO-based point count cap (150,000 points) to maintain fluid rendering performance.
4. **GPS Metadata Tagging**:
   Retrieves location coordinates using Android's `LocationManager`. It embeds the GPS coordinates directly inside:
   * **PLY Export**: Custom header comments (`comment gps_latitude` and `comment gps_longitude`).
   * **GLB Export**: Standard `asset.extras` glTF JSON metadata fields.
5. **Continuous & Manual Capture Modes**:
   A Play/Pause toggle allows switching between continuous scanning and manual snapshot capturing. In manual snapshot mode, a camera shutter FAB triggers depth estimation for a single frame, significantly reducing battery and thermal load.
6. **Active Processing Spinner Overlay**:
   The manual snapshot button features a circular progress edge loader that animates dynamically during the exact duration of the TFLite depth inference execution, providing clear feedback to the user when a frame is being calculated.
7. **Dual Export Options**:
   * **Export PLY**: Exports the colored point cloud as an ASCII `.ply` file in the Downloads folder.
   * **Export GLB**: Exports the point cloud as a standard binary glTF `.glb` file, compatible with Blender and standard 3D viewers.

---

## 🏗️ Folder Structure

* **`app/src/main/java/.../ui/main/MainScreen.kt`**: Contains the main Compose layout, CameraX analysis hook, orientation sensor listener, and the PointCloudAccumulator.
* **`app/src/main/java/.../ui/main/MogeInterpreter.kt`**: Handles TFLite model loading, multi-output tensor binding (`runForMultipleInputsOutputs`), and grid subsampling.
* **`app/src/main/java/.../ui/main/GLPointRenderer.kt`**: A high-performance OpenGL ES 2.0 point cloud renderer featuring interactive gesture scaling and rotation.

---

## ⚙️ Compilation & Deployment

To compile the application and install it on your Android device via command-line:

```bash
# 1. Compile the debug APK
./gradlew assembleDebug --no-configuration-cache

# 2. Uninstall the previous package (if any)
adb uninstall com.example.moge3dscanner

# 3. Install the APK
adb install app/build/outputs/apk/debug/app-debug.apk

# 4. Launch the application
adb shell am start -n com.example.moge3dscanner/.MainActivity
```

---

## 📚 Citations & Acknowledgments

* **MoGe v1 & v2 Models**:
  This project utilizes the state-of-the-art monocular depth estimation and 3D geometry models developed by Microsoft.
  * *Code Repository*: [https://github.com/microsoft/MoGe](https://github.com/microsoft/MoGe)
* **3D Live Scanner Historical Legacy**:
  This work builds upon the historical timeline of mobile 3D scanning pioneered by **Luboš Vonásek**:
  > **2017-2021: 3D Live Scanner**
  > *3D Live Scanner, initially launched as 3D Scanner for ARCore and originally known as OpenConstructor for Tango, is a trailblazing Android application that pioneered the mobile 3D scanning space. It was among the very first apps designed to capture detailed 3D models of interiors, exteriors, individual objects, and even faces, bringing advanced scanning technology into the hands of everyday users.*
  > *Source*: [Luboš Vonásek Homepage](https://lvonasek.github.io/)
* **Android CLI & Antigravity CLI**:
  Development and rapid iteration of this native application were powered by Android Platform-Tools and the **Antigravity CLI** agent platform. The automated build, deployment, screenshot auditing, and remote device command executions allowed fast development cycles directly from the terminal.
