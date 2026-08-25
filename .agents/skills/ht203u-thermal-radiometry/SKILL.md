---
name: ht203u-thermal-radiometry
description: >-
  Driver implementation, radiometric temperature extraction in Celsius, false-color palettes,
  non-overlapping dual preview cards, fullscreen toggle mode, and multi-sensor RGB+Thermal fusion
  for HT-203U, InfiRay, and HIKMICRO UVC thermal cameras (VID:0x2bdf PID:0x0102). Use when developing
  or debugging thermal camera streaming, UVC bulk transfers, radiometry equations, Ironbow LUTs,
  or Android USB Host integrations.
---

# HT-203U & UVC Thermal Camera Radiometry Guide

This skill provides the comprehensive reference, driver architecture, radiometric mathematics, and UI integration patterns for USB thermal cameras (such as the HT-203U, InfiRay T2/T3 cores, and HIKMICRO devices).

---

## 1. Hardware & USB Descriptor Profile

* **USB Identifiers**: `VID = 0x2bdf`, `PID = 0x0102` (`"Camera"` / HikCamera)
* **USB Class**: UVC 1.0 / 1.1 Video Class (`0x0E`)
  * **VideoControl (VC)**: Interface `#0` (`cls=0x0E`, `sub=0x01`), Extension Unit (XU) ID `10` (15 controls)
  * **VideoStreaming (VS)**: Interface `#1` (`cls=0x0E`, `sub=0x02`), Alternate Setting `0`
  * **Endpoint**: `EP 0x81` Bulk IN (`maxPacketSize = 512` bytes for USB 2.0 High-Speed)
* **Uncompressed Format (`YUY2` / `bpp=16`) Modes**:
  * `[1] 256x344` (Combined active frame + calibration rows)
  * `[2] 256x192` (Raw active microbolometer pixels: 49,152 values)
  * `[3] 256x196` (Active pixels + 4 metadata rows for Xtherm radiometry)
  * `[4] 256x400` (Stacked preview + raw counts)
  * `[8] 256x410` / `[9] 256x250`

---

## 2. UVC Bulk Stream Negotiation & Reader Pipeline

### Negotiation (PROBE / COMMIT)
```kotlin
// 1. Claim VC (Interface 0) and VS (Interface 1)
conn.claimInterface(vcInterface, true)
conn.claimInterface(vsInterface, true)

// 2. PROBE SET_CUR (0x21, 0x01, 0x0100)
val ctrl = ByteArray(34)
ctrl[0] = 0x01 // Keep dwFrameInterval
ctrl[2] = formatIndex.toByte()
ctrl[3] = frameIndex.toByte()
putU32(ctrl, 4, defaultInterval)
conn.controlTransfer(0x21, 0x01, 0x0100, vsIfNum, ctrl, 26, 1000)

// 3. PROBE GET_CUR (0xA1, 0x81, 0x0100) & COMMIT SET_CUR (0x21, 0x01, 0x0200)
val resp = ByteArray(34)
conn.controlTransfer(0xA1, 0x81, 0x0100, vsIfNum, resp, 34, 1000)
conn.controlTransfer(0x21, 0x01, 0x0200, vsIfNum, resp, 34, 1000)
```

### Reader Loop & Mode Auto-Fallback Watchdog
1. **Packet Boundary**: Read chunks using `conn.bulkTransfer(ep, chunk, chunk.size, timeout=200ms)`.
2. **Payload Header**: Check standard 2-12 byte UVC payload header:
   * Bit 0: `FID` (Frame ID toggle).
   * Bit 1: `EOF` (End of Frame).
   * Bit 6: `ERR` (Error flag).
3. **Resilience Watchdog**: If a negotiated mode produces persistent `-1` streak errors or `0` delivered frames within 2.0 seconds, immediately cycle to the next frame descriptor (`tryNextMode()`).

---

## 3. Radiometric Celsius (°C) Mathematics

### Direct Linear Microbolometer Model
For calibrated HIK/HT sensors:
$$\text{Temp}(^\circ\text{C}) = (\text{Raw}_{u16} - 4405) \times 0.0373349 + 3.9$$
* Calibration anchors:
  * `4405` raw count $\approx 3.9^\circ\text{C}$ (Ice bath point)
  * `6979` raw count $\approx 100.0^\circ\text{C}$ (Boiling water point)
  * Sensitivity $\approx 26.8$ counts per $^\circ\text{C}$

### Stacked Kelvin Mode (TC001 / Topdon)
$$\text{Temp}(^\circ\text{C}) = \frac{\text{Raw}_{u16}}{64.0} - 273.15$$

### Full Xtherm Radiometry (with FPA, Shutter & Atmospheric Correction)
Using metadata rows at offset `256 * 192`:
* Shutter temperature: $T_{\text{shutter}} = \text{Raw}_{\text{meta}}[1] / 10.0 - 273.15$
* FPA temperature: $T_{\text{fpa}} = 20.0 - (\text{Raw}_{\text{meta}}[1] - 8617.0) / 37.682$
* Planck-law temperature table generated per frame via `Xtherm.tempTable(meta)`.

---

## 4. False-Color Ironbow Colormap (8-Anchor LUT)

The professional Ironbow colormap maps normalized microbolometer intensity $v \in [0.0, 1.0]$ into ARGB pixels via 8 piecewise linear anchors:

| Position | Red | Green | Blue | Visual Representation |
| :--- | :--- | :--- | :--- | :--- |
| **0.00** | 0 | 0 | 10 | Deep Black / Blue (Coldest) |
| **0.15** | 20 | 0 | 90 | Dark Indigo |
| **0.30** | 90 | 0 | 120 | Purple |
| **0.45** | 180 | 0 | 100 | Magenta / Crimson |
| **0.60** | 230 | 60 | 20 | Bright Orange |
| **0.75** | 250 | 150 | 0 | Amber / Gold |
| **0.90** | 250 | 220 | 100 | Pale Yellow |
| **1.00** | 255 | 255 | 255 | Pure White (Hottest) |

### Contrast Normalization, Orientation & Crosshairs
1. Identify $p_{\min}$ and $p_{\max}$ across active frame.
2. Normalize index: $\text{idx} = \text{clamp}\left(\frac{v - p_{\min}}{p_{\max} - p_{\min}} \times 255, 0, 255\right)$.
3. **90° Upright Portrait Transposition**: Rotate raw landscape $256 \times 192$ array clockwise to $192 \times 256$ upright portrait buffer:
   $$\text{dstX} = \text{srcH} - 1 - y, \quad \text{dstY} = x$$
4. Render spot crosshairs:
   * 🔵 **Min spot** (`0xFF40A0FF`) at $(x_{\min}, y_{\min})$
   * 🔴 **Max spot** (`0xFFFF4040`) at $(x_{\max}, y_{\max})$
   * ⚪ **Center spot** (`0xFFFFFFFF`) at $(W/2, H/2)$

---

## 5. UI Architecture: Separate Floating Cards & Fullscreen Mode

1. **Non-Overlapping Layout**:
   * Present the **Thermal Live Feed** and **RGB Camera Live Feed** as two separate floating rounded cards with elevation shadows and crisp borders.
   * Arrange cards vertically on the screen edge using `Arrangement.spacedBy(14.dp)` within a draggable/pinch-resizable container.
2. **Dedicated Fullscreen Toggle (⛶)**:
   * Add a top-corner expand icon (`FullscreenExpandIcon`) on each preview card.
   * Clicking the button expands the corresponding feed to fullscreen with high-resolution fit and an exit button (`FullscreenExitIcon`) to restore normal preview mode.

---

## 6. Multi-Sensor 3D Fusion & Simultaneous Archival

When capturing 3D scans with simultaneous thermal data:
1. **Geometry Generation**: Extract monocular depth field $\mathbf{D}(x, y)$ from high-resolution RGB camera using foundation models (e.g. MoGe / Depth-Anything).
2. **Thermal Texture Mapping**: Scale and align the thermal bitmap onto the RGB coordinate space:
   $$\mathbf{C}_{\text{point}}(x, y) = \text{ThermalBitmap}\left(\frac{x}{W_{\text{rgb}}} W_{\text{th}}, \frac{y}{H_{\text{rgb}}} H_{\text{th}}\right)$$
3. **Simultaneous Archival**: On shutter click, save:
   * `moge_rgb_<ts>.png`: High-resolution RGB snapshot.
   * `moge_thermal_<ts>.png`: Processed false-color Ironbow thermal snapshot with temperature overlays.
   * `moge_thermal_<ts>.raw`: 16-bit raw sensor count buffer for radiometric temperature analysis.
   * `moge_scan_<ts>.glb`: 3D point cloud / mesh combining RGB depth estimation textured with thermal heatmap data.
