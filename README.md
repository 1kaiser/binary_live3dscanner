# Mobile 3D Scanning & Computer Vision Suite

A comprehensive collection of on-device 3D reconstruction, multi-sensor computer vision, thermal radiometry, and augmented reality applications for Android.

---

## 🌟 Featured Applications

### 1. [MoGe3DScanner](./MoGe3DScanner) — Live 3D & Thermal Scanner
* **On-Device Monocular Metric 3D Geometry**: Powered by **MoGe-3 LiteRT INT8** (ViT-L) running locally with GPU / CPU XNNPACK delegation, predicting metric 3D point clouds in true meters.
* **2D Thermal Frame Fusion**: Registered 16-bit radiometric thermal heatmap over RGB frames via hardware-accelerated 4-corner perspective homography (`moge_fused_<ts>.png`).
* **Triple 3D GLB Generation**: Simultaneous generation and real-time in-app switching between **Fused 3D**, **Pure Thermal 3D**, and **Pure RGB 3D** models.
* **Native 3D Viewers**: Turntable orbital controls rendered via **Google Filament** and Google **`<model-viewer>`**.
* 📥 **[Download MoGe3DScanner v2.0 APK](https://github.com/1kaiser/binary_live3dscanner/releases/tag/v2.0)**

### 2. [MultiCamApp](./MultiCamApp) — Concurrent Multi-Camera & Dual Recording
* Streams, photographs, and records from multiple camera sensors concurrently (Front, Rear Main, Rear Aux/Depth) using Camera2 APIs.
* Non-overlapping floating PiP cards and split viewports.
* 📥 **[Download MultiCam Live v1.0 APK](https://github.com/1kaiser/binary_live3dscanner/releases/tag/multicam-v1.0)**

---

## 📦 Releases

Pre-compiled APKs and historical release archives are hosted under [GitHub Releases](https://github.com/1kaiser/binary_live3dscanner/releases):

| Application | Latest Version | Release Page |
| :--- | :--- | :--- |
| **MoGe3DScanner** | **v2.0** | [Release v2.0](https://github.com/1kaiser/binary_live3dscanner/releases/tag/v2.0) |
| **MultiCam Live** | **v1.0** | [Release multicam-v1.0](https://github.com/1kaiser/binary_live3dscanner/releases/tag/multicam-v1.0) |
| **Historical Archives** | **v3 – v32** | [All Releases](https://github.com/1kaiser/binary_live3dscanner/releases) |

---

## 🛠️ Quick Start

### Building MoGe3DScanner via Android CLI / Gradle

```bash
cd MoGe3DScanner

# 1. Download MoGe-3 LiteRT INT8 model (324 MB) from Hugging Face
./download_models.sh

# 2. Build Debug APK
./gradlew assembleDebug

# Output APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## 📚 Acknowledgments & References

* **Google DeepMind & Gemini 3.7**: [Gemini Model Updates](https://blog.google/technology/google-deepmind/gemini-model-updates-february-2025/)
* **Google Antigravity**: [Antigravity Press](https://antigravity.google/press)
* **Microsoft Research MoGe**: [MoGe Repository](https://github.com/microsoft/MoGe)
* **MoGe-3 LiteRT Quantized Models**: Hosted on Hugging Face at [`1kaiser/moge3-litert`](https://huggingface.co/1kaiser/moge3-litert)
* **3D Live Scanner Legacy**: Originally pioneered by [Luboš Vonásek](https://github.com/lvonasek/binary_live3dscanner)
