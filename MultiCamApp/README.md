# MultiCam Live - Concurrent Multi-Camera & Dual Recording App

A native Android application built with Jetpack Compose, Material 3, and low-level Camera2 APIs that streams, previews, photographs, and records video from multiple camera sensors concurrently (Front, Rear Main, and Rear Auxiliary/Depth).

Inspired by the user experience and floating card architecture of [MoGe3DScanner](../MoGe3DScanner), **MultiCam Live** provides flexible multi-viewport streaming, reduced resolution presets for ISP bandwidth preservation, synchronized multi-frame photography, composite video recording with microphone audio, and optional GPS EXIF metadata tagging.

---

## 🌟 Key Features

1. **Concurrent Multi-Camera Streaming (Camera2 API)**:
   - Dynamic enumeration of all hardware and logical cameras (`CameraManager.getCameraIdList()` and `concurrentCameraIds`).
   - Supports any combination of active cameras:
     - **Dual Mode (Front + Back Main)**: Standard director / dual recording view.
     - **Dual Back Mode (Back Main + Back Aux)**: Simultaneous wide and auxiliary depth feeds.
     - **Front + Back Aux**: Creative multi-angle capture.
     - **Triple Mode (Back Main + Back Aux + Front)**: Attempts all 3 sensors concurrently at reduced resolutions.
   - **Hardware ISP Limit Protection**: If the device Image Signal Processor (ISP) reaches its hardware pipeline limit (e.g. `ERROR_MAX_CAMERAS_IN_USE`), the app safely catches the error, keeps existing streams running, and informs the user via the HUD without crashing.

2. **Redmi 13C 5G (MediaTek Dimensity 6100+) Hardware Considerations**:
   - **Rear Cameras**: 50 MP Main (wide) + 0.08 MP auxiliary depth lens.
   - **Front Camera**: 5 MP.
   - **SoC ISP Architecture**: MediaTek Dimensity 6100+ contains a **dual 14-bit ISP pipeline**, supporting up to 2 concurrent hardware image processing streams natively.
   - **Resolution Scaling**: Reduced resolution presets (`480p VGA`, `720p HD`, `1080p FHD`) reduce ISP memory bandwidth significantly, allowing dual/triple streaming to run coolly without thermal throttling.

3. **Multi-Viewport Screen Layouts (MoGe3DScanner UI Ideas)**:
   - **Side-by-Side (Split Vertical)**: Dual or multi-column split layout.
   - **Top / Bottom (Split Horizontal)**: Equal horizontal stacked split view.
   - **Floating Picture-in-Picture (PiP) Cards**:
     - Primary camera occupies the full screen.
     - Secondary and tertiary feeds float in elegant rounded cards (`RoundedCornerShape(14.dp)`).
     - **Draggable & Pinch-to-Zoom Resizable**: Uses pointer input gestures (`detectTransformGestures`) to freely reposition and scale anywhere on screen.
   - **Fullscreen Toggle (⛶)**: Dedicated expand button on every camera feed to inspect it in full screen with a one-tap exit button.

4. **Simultaneous Multi-Camera Photography (Clicking)**:
   - Tapping the shutter button captures synchronized frames from all active camera streams simultaneously.
   - **Individual Photos**: Saves full frames for each camera:
     - `multicam_Back_Main_<timestamp>.jpg`
     - `multicam_Front_Selfie_<timestamp>.jpg`
     - `multicam_Back_Aux_<timestamp>.jpg`
   - **Stitched Composite Photo**: Stitches all active feeds into a unified high-resolution composite photo (`multicam_composite_<timestamp>.jpg`) with camera labels, timestamps, and optional GPS coordinates.
   - Saved directly to Android MediaStore (`Pictures/MultiCam`).

5. **Synchronized Video Recording**:
   - Red recording button with live pulse animation and timer (`🔴 REC 00:15`).
   - Encodes composite synchronized video (side-by-side or multi-tile) with AAC audio from the microphone directly into an MP4 container.
   - Injects GPS metadata into the video container.
   - Saved directly to Android MediaStore (`Movies/MultiCam`).

6. **Optional GPS Tagging**:
   - Dedicated toggle switch in HUD: `📍 GPS: ON / OFF`.
   - Real-time GPS coordinates badge (`📍 12.9716° N, 77.5946° E • Alt: 920m (±3m)`).
   - Injects EXIF GPS metadata (`TAG_GPS_LATITUDE`, `TAG_GPS_LONGITUDE`, `TAG_GPS_ALTITUDE`, `TAG_GPS_TIMESTAMP`) into all captured photos.

7. **Hardware & ISP Diagnostics Dialog**:
   - Modal inspection sheet showing detected camera IDs, lens facing, physical IDs, logical multi-camera structures, and OS-reported concurrent combinations.

---

## 🏗️ Architecture

| Component | Path | Role |
|---|---|---|
| `MainActivity.kt` | `app/src/main/.../MainActivity.kt` | Runtime permission handling, theme configuration, entry point |
| `MultiCameraManager.kt` | `app/src/main/.../camera/MultiCameraManager.kt` | Camera2 enumeration, concurrent stream lifecycle, ISP error handling |
| `CameraDeviceInfo.kt` | `app/src/main/.../camera/CameraDeviceInfo.kt` | Data models for cameras, lens types, and resolution presets |
| `MultiCamPhotoCapture.kt` | `app/src/main/.../capture/MultiCamPhotoCapture.kt` | Simultaneous snapshot acquisition, composite image generation, EXIF GPS tagging |
| `MultiCamVideoRecorder.kt` | `app/src/main/.../capture/MultiCamVideoRecorder.kt` | Multi-stream video encoding with microphone audio and GPS metadata |
| `GpsLocationManager.kt` | `app/src/main/.../location/GpsLocationManager.kt` | GPS & network location listener and EXIF GPS coordinate formatter |
| `MultiCamScreen.kt` | `app/src/main/.../ui/MultiCamScreen.kt` | Jetpack Compose UI, HUD, layout modes, floating cards, shutter controls |
| `CameraFeedView.kt` | `app/src/main/.../ui/CameraFeedView.kt` | TextureView hardware renderer, status overlays, fullscreen expand toggle |
| `HardwareDiagnosticsDialog.kt` | `app/src/main/.../ui/HardwareDiagnosticsDialog.kt` | Hardware inspection dialog for cameras and SoC ISP diagnostics |

---

## 🚀 Building and Running

### Prerequisites
- Android SDK (API 34/35/36, build-tools)
- Java 17 or Java 21
- Gradle 9.1 (included via `./gradlew`)

### Build Debug APK
```bash
cd MultiCamApp
./gradlew assembleDebug
```
The resulting APK will be generated at:
`MultiCamApp/app/build/outputs/apk/debug/app-debug.apk`
and mirrored in:
`MultiCamApp/releases/multicam_live_v1.apk`

### Install via ADB
```bash
adb install -r MultiCamApp/releases/multicam_live_v1.apk
```
