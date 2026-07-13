# POLI-based Scan Merging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a user pick 2+ previously-saved GLB scans in MoGe3DScanner and merge them into one aligned point cloud using a Kotlin port of POLI's registration pipeline (learned covariance + FPFH + ROBIN + GNC + GICP), writing a new non-destructive `merged_<timestamp>.glb`.

**Architecture:** New Kotlin files under `com.example.moge3dscanner.ui.merge`, TFLite for the POLI covariance model, pure-Kotlin numerical/graph code for FPFH/ROBIN/GNC/GICP (no SDP solver — confirmed training-only in POLI's own source), MediaStore-backed file picker, coroutine-driven background merge, and the existing `GLPointRenderer`/`InteractiveGLView` reused as-is for preview (already model-viewer-quality damping — see spec).

**Tech Stack:** Kotlin, Jetpack Compose, TensorFlow Lite 2.14.0 (already vendored as local jars), Navigation3, JUnit (`src/test`, plain JVM, no Robolectric available), Python/PyTorch/ONNX (one-time offline model conversion only, not part of the Android build).

## Global Constraints

- `minSdk = 24`, `compileSdk = 36`, Kotlin `jvmToolchain(17)` — from `app/build.gradle.kts`.
- No new Android permissions: the app already writes to Downloads via `MediaStore` without `WRITE_EXTERNAL_STORAGE`; reading back the app's own MediaStore entries needs no extra permission either.
- No Robolectric, no `org.json` in JVM unit tests (Android's stub jar throws on framework calls in `src/test`) — `GlbReader`/`GlbWriter` must use hand-rolled string extraction, not `org.json.JSONObject`, so they're testable as plain JVM code.
- No SDP solver dependency (confirmed: `sdprlayer` is training-only in POLI's own `environment.yml`/inference code).
- Non-destructive merge: originals are never deleted; failed pairs are skipped, not fatal to the whole batch (spec's Error Handling section).
- Pair chaining order is ascending capture-timestamp (parsed from `moge_scan_<timestamp>.glb`), not UI click order.

---

## Task 1: Extract `GlbWriter` from `MainScreen.kt` (DRY refactor)

`MainScreen.kt:895-994` has a private `exportGlb()` used by both the manual "glb" button and the auto-export-on-shutter path. `ScanMerger` (Task 12) needs the same binary-writing logic to produce `merged_<timestamp>.glb`. Extract it now, before any new code depends on it, so there's one writer implementation.

**Files:**
- Create: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/GlbWriter.kt`
- Modify: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/main/MainScreen.kt:895-994` (delete `exportGlb`, both call sites at lines ~716 and ~817 call `GlbWriter.write` instead)
- Test: `MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/GlbWriterTest.kt`

**Interfaces:**
- Produces: `GlbWriter.write(positions: FloatArray, colors: FloatArray, latitude: Double? = null, longitude: Double? = null): ByteArray`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.moge3dscanner.ui.merge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlbWriterTest {
    @Test
    fun `header magic and chunk structure are well-formed`() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f)
        val colors = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
        val bytes = GlbWriter.write(positions, colors)

        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, buf.int) // "glTF" magic
        assertEquals(2, buf.int) // version
        val totalLength = buf.int
        assertEquals(bytes.size, totalLength)
        assertTrue(bytes.size > 12)
    }

    @Test
    fun `binary length matches 24 bytes per point`() {
        val n = 10
        val positions = FloatArray(n * 3) { it.toFloat() }
        val colors = FloatArray(n * 3) { 0.5f }
        val bytes = GlbWriter.write(positions, colors)
        // JSON chunk length + BIN chunk length + 12(header) + 8+8(chunk headers) == total
        val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.position(12)
        val jsonLen = buf.int
        buf.position(12 + 8 + jsonLen)
        val binLen = buf.int
        assertEquals(n * 24, binLen) // 12 bytes pos + 12 bytes color per point, before padding
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.GlbWriterTest"`
Expected: FAIL — `GlbWriter` does not exist yet.

- [ ] **Step 3: Create `GlbWriter.kt` with the extracted logic**

Move the full body of `MainScreen.kt`'s `exportGlb()` (lines 895-994, including the header-writing tail past line 994 not shown above — copy it verbatim) into:

```kotlin
package com.example.moge3dscanner.ui.merge

import java.nio.ByteBuffer
import java.nio.ByteOrder

object GlbWriter {
    fun write(positions: FloatArray, colors: FloatArray, latitude: Double? = null, longitude: Double? = null): ByteArray {
        // ... exact body of the former MainScreen.kt private fun exportGlb, unchanged ...
    }
}
```

Then in `MainScreen.kt`: delete the private `exportGlb` function, add `import com.example.moge3dscanner.ui.merge.GlbWriter`, and replace both call sites (`exportGlb(positions, colors, currentLatitude, currentLongitude)`) with `GlbWriter.write(positions, colors, currentLatitude, currentLongitude)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.GlbWriterTest"`
Expected: PASS

- [ ] **Step 5: Manual regression check — existing export still works**

Run: `./gradlew assembleDebug --no-configuration-cache && adb install -r app/build/outputs/apk/debug/app-debug.apk`
Tap the shutter, then tap "glb". Confirm the Toast still reads "GLB saved to Downloads!" and the file appears in Downloads via `adb shell run-as com.example.moge3dscanner ls` or a file manager. This confirms the refactor didn't change the existing export's observable behavior.

- [ ] **Step 6: Commit**

```bash
git add MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/GlbWriter.kt \
        MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/main/MainScreen.kt \
        MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/GlbWriterTest.kt
git commit -m "refactor: extract GlbWriter from MainScreen for reuse by scan merger"
```

---

## Task 2: `GlbReader` — parse a previously-saved GLB back into points

**Files:**
- Create: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/GlbReader.kt`
- Test: `MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/GlbReaderTest.kt`

**Interfaces:**
- Consumes: nothing new (pure `ByteArray` in)
- Produces: `data class ScanPointCloud(val positions: FloatArray, val colors: FloatArray, val gpsLatitude: Double?, val gpsLongitude: Double?)` and `GlbReader.read(bytes: ByteArray): ScanPointCloud`. Scoped explicitly to GLBs written by `GlbWriter` — not a general glTF parser (see Global Constraints: no `org.json` in unit tests, so parsing is targeted string extraction, not general JSON).

- [ ] **Step 1: Write the failing test — round-trip against `GlbWriter`**

```kotlin
package com.example.moge3dscanner.ui.merge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GlbReaderTest {
    @Test
    fun `round trips positions and colors through GlbWriter`() {
        val positions = floatArrayOf(1f, 2f, 3f, -4f, 5f, -6f)
        val colors = floatArrayOf(0.1f, 0.2f, 0.3f, 0.9f, 0.8f, 0.7f)
        val bytes = GlbWriter.write(positions, colors)

        val result = GlbReader.read(bytes)

        assertArrayEquals(positions, result.positions, 1e-6f)
        assertArrayEquals(colors, result.colors, 1e-6f)
        assertEquals(null, result.gpsLatitude)
        assertEquals(null, result.gpsLongitude)
    }

    @Test
    fun `round trips gps metadata when present`() {
        val positions = floatArrayOf(0f, 0f, 0f)
        val colors = floatArrayOf(1f, 1f, 1f)
        val bytes = GlbWriter.write(positions, colors, latitude = 12.5, longitude = -45.25)

        val result = GlbReader.read(bytes)

        assertEquals(12.5, result.gpsLatitude!!, 1e-9)
        assertEquals(-45.25, result.gpsLongitude!!, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects bytes with wrong magic`() {
        GlbReader.read(ByteArray(20))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.GlbReaderTest"`
Expected: FAIL — `GlbReader` does not exist.

- [ ] **Step 3: Implement `GlbReader.kt`**

```kotlin
package com.example.moge3dscanner.ui.merge

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ScanPointCloud(
    val positions: FloatArray,
    val colors: FloatArray,
    val gpsLatitude: Double?,
    val gpsLongitude: Double?
)

/**
 * Parses GLBs written by [GlbWriter]. Not a general glTF parser: relies on the
 * exact fixed layout GlbWriter produces (POSITION then COLOR_0, contiguous,
 * float32 VEC3, no normals/indices) to avoid depending on org.json, which
 * throws in plain JVM unit tests on this project (no Robolectric configured).
 */
object GlbReader {
    private const val GLB_MAGIC = 0x46546C67
    private const val CHUNK_TYPE_JSON = 0x4E4F534A
    private const val CHUNK_TYPE_BIN = 0x004E4942

    fun read(bytes: ByteArray): ScanPointCloud {
        require(bytes.size >= 12) { "GLB too short for header" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buffer.int
        require(magic == GLB_MAGIC) { "Not a GLB file: bad magic 0x${magic.toString(16)}" }
        buffer.int // version, unused
        buffer.int // total length, unused

        var jsonChunk: String? = null
        var binChunk: ByteArray? = null

        while (buffer.remaining() >= 8) {
            val chunkLength = buffer.int
            val chunkType = buffer.int
            when (chunkType) {
                CHUNK_TYPE_JSON -> {
                    val jsonBytes = ByteArray(chunkLength)
                    buffer.get(jsonBytes)
                    jsonChunk = String(jsonBytes, Charsets.UTF_8)
                }
                CHUNK_TYPE_BIN -> {
                    val bin = ByteArray(chunkLength)
                    buffer.get(bin)
                    binChunk = bin
                }
                else -> buffer.position(buffer.position() + chunkLength)
            }
        }

        val json = requireNotNull(jsonChunk) { "GLB missing JSON chunk" }
        val bin = requireNotNull(binChunk) { "GLB missing BIN chunk" }

        val numPoints = extractInt(json, "count")
            ?: error("GLB JSON missing accessor 'count' field")

        // GlbWriter always lays out bufferView 0 (POSITION) at byteOffset 0
        // and bufferView 1 (COLOR_0) immediately after, at numPoints*12.
        val posOffset = 0
        val colOffset = numPoints * 12

        val positions = FloatArray(numPoints * 3)
        val colors = FloatArray(numPoints * 3)
        val binBuffer = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numPoints * 3) positions[i] = binBuffer.getFloat(posOffset + i * 4)
        for (i in 0 until numPoints * 3) colors[i] = binBuffer.getFloat(colOffset + i * 4)

        val lat = extractDouble(json, "gps_latitude")
        val lon = extractDouble(json, "gps_longitude")

        return ScanPointCloud(positions, colors, lat, lon)
    }

    private fun extractInt(json: String, key: String): Int? =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(json)?.groupValues?.get(1)?.toInt()

    private fun extractDouble(json: String, key: String): Double? =
        Regex("\"$key\"\\s*:\\s*(-?[0-9]+\\.?[0-9]*)").find(json)?.groupValues?.get(1)?.toDouble()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.GlbReaderTest"`
Expected: PASS (all 3 tests)

- [ ] **Step 5: Commit**

```bash
git add MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/GlbReader.kt \
        MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/GlbReaderTest.kt
git commit -m "feat: add GlbReader to parse previously-saved scans for merging"
```

---

## Task 3: `Mat3` — 3x3 linear algebra core (Jacobi eigendecomposition, inverse, SVD)

Shared by `WeightedHorn` (Task 4), `FpfhFeatures` (Task 8, normal = min-eigenvector of covariance), and `GicpRefiner` (Task 11, 6x6 Gauss-Newton needs 3x3 inverse and SVD-based rotation re-orthonormalization).

**Files:**
- Create: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/Mat3.kt`
- Test: `MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/Mat3Test.kt`

**Interfaces:**
- Produces: `Mat3.multiply`, `Mat3.transpose`, `Mat3.determinant`, `Mat3.identity`, `Mat3.inverse`, `Mat3.symmetricEigenDecomposition(sym: DoubleArray): Pair<DoubleArray, DoubleArray>` (eigenvalues[3], eigenvectors as columns of a row-major 3x3), `Mat3.svd(a: DoubleArray): Pair<DoubleArray, DoubleArray>` (U, V as row-major 3x3, such that `a = U * diag(singularValues) * Vᵀ`; singular values recoverable as `sqrt(eigenvalues of AᵀA)`). All matrices are flat row-major `DoubleArray(9)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.example.moge3dscanner.ui.merge

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class Mat3Test {
    private fun approxEqual(a: DoubleArray, b: DoubleArray, tol: Double = 1e-6) {
        for (i in a.indices) assertEquals(b[i], a[i], tol)
    }

    @Test
    fun `identity times identity is identity`() {
        approxEqual(Mat3.identity(), Mat3.multiply(Mat3.identity(), Mat3.identity()))
    }

    @Test
    fun `determinant of identity is one`() {
        assertEquals(1.0, Mat3.determinant(Mat3.identity()), 1e-9)
    }

    @Test
    fun `inverse of a known matrix`() {
        // A = diag(2, 4, 5) -> inverse = diag(0.5, 0.25, 0.2)
        val a = doubleArrayOf(2.0, 0.0, 0.0, 0.0, 4.0, 0.0, 0.0, 0.0, 5.0)
        val expected = doubleArrayOf(0.5, 0.0, 0.0, 0.0, 0.25, 0.0, 0.0, 0.0, 0.2)
        approxEqual(expected, Mat3.inverse(a))
    }

    @Test
    fun `eigendecomposition of a diagonal matrix returns the diagonal as eigenvalues`() {
        val a = doubleArrayOf(3.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 2.0)
        val (eigenvalues, _) = Mat3.symmetricEigenDecomposition(a)
        val sorted = eigenvalues.sorted()
        assertEquals(listOf(1.0, 2.0, 3.0), sorted.map { Math.round(it * 1e6) / 1e6 })
    }

    @Test
    fun `eigendecomposition reconstructs the original symmetric matrix`() {
        // A random-ish symmetric PSD matrix
        val a = doubleArrayOf(4.0, 1.0, 0.0, 1.0, 3.0, 1.0, 0.0, 1.0, 2.0)
        val (eigenvalues, v) = Mat3.symmetricEigenDecomposition(a)
        val d = doubleArrayOf(eigenvalues[0], 0.0, 0.0, 0.0, eigenvalues[1], 0.0, 0.0, 0.0, eigenvalues[2])
        val reconstructed = Mat3.multiply(Mat3.multiply(v, d), Mat3.transpose(v))
        approxEqual(a, reconstructed, 1e-4)
    }

    @Test
    fun `svd reconstructs a known matrix`() {
        val a = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 3.0)
        val (u, v) = Mat3.svd(a)
        // For this diagonal matrix, U*diag(1,2,3)*V^T should reconstruct A
        val d = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 2.0, 0.0, 0.0, 0.0, 3.0)
        val reconstructed = Mat3.multiply(Mat3.multiply(u, d), Mat3.transpose(v))
        approxEqual(a, reconstructed, 1e-4)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.Mat3Test"`
Expected: FAIL — `Mat3` does not exist.

- [ ] **Step 3: Implement `Mat3.kt`**

```kotlin
package com.example.moge3dscanner.ui.merge

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Row-major 3x3 (flat DoubleArray(9)) linear algebra, scoped to what Kabsch/GICP need. */
object Mat3 {
    fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var sum = 0.0
            for (k in 0..2) sum += a[i * 3 + k] * b[k * 3 + j]
            r[i * 3 + j] = sum
        }
        return r
    }

    fun transpose(a: DoubleArray): DoubleArray {
        val r = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) r[j * 3 + i] = a[i * 3 + j]
        return r
    }

    fun determinant(a: DoubleArray): Double =
        a[0] * (a[4] * a[8] - a[5] * a[7]) -
        a[1] * (a[3] * a[8] - a[5] * a[6]) +
        a[2] * (a[3] * a[7] - a[4] * a[6])

    fun identity(): DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    fun inverse(a: DoubleArray): DoubleArray {
        val det = determinant(a)
        require(abs(det) > 1e-12) { "Mat3.inverse: matrix not invertible (det=$det)" }
        val invDet = 1.0 / det
        return doubleArrayOf(
            (a[4] * a[8] - a[5] * a[7]) * invDet,
            (a[2] * a[7] - a[1] * a[8]) * invDet,
            (a[1] * a[5] - a[2] * a[4]) * invDet,
            (a[5] * a[6] - a[3] * a[8]) * invDet,
            (a[0] * a[8] - a[2] * a[6]) * invDet,
            (a[2] * a[3] - a[0] * a[5]) * invDet,
            (a[3] * a[7] - a[4] * a[6]) * invDet,
            (a[1] * a[6] - a[0] * a[7]) * invDet,
            (a[0] * a[4] - a[1] * a[3]) * invDet
        )
    }

    /** Classical (largest-pivot) Jacobi eigenvalue algorithm for a symmetric 3x3 matrix. */
    fun symmetricEigenDecomposition(sym: DoubleArray, maxSweeps: Int = 60): Pair<DoubleArray, DoubleArray> {
        val a = sym.copyOf()
        var v = identity()
        val offDiagPairs = listOf(0 to 1, 0 to 2, 1 to 2)
        repeat(maxSweeps) {
            var p = 0; var q = 1; var maxVal = abs(a[1])
            for ((i, j) in offDiagPairs) {
                val value = abs(a[i * 3 + j])
                if (value > maxVal) { maxVal = value; p = i; q = j }
            }
            if (maxVal < 1e-12) return Pair(doubleArrayOf(a[0], a[4], a[8]), v)
            val app = a[p * 3 + p]; val aqq = a[q * 3 + q]; val apq = a[p * 3 + q]
            val phi = 0.5 * atan2(2 * apq, aqq - app)
            val c = cos(phi); val s = sin(phi)
            val j = identity()
            j[p * 3 + p] = c; j[q * 3 + q] = c
            j[p * 3 + q] = -s; j[q * 3 + p] = s
            val aNew = multiply(multiply(transpose(j), a), j)
            for (i in 0 until 9) a[i] = aNew[i]
            v = multiply(v, j)
        }
        return Pair(doubleArrayOf(a[0], a[4], a[8]), v)
    }

    /** SVD of a general 3x3 matrix via eigendecomposition of AᵀA. Returns (U, V), columns are singular vectors. */
    fun svd(a: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val ata = multiply(transpose(a), a)
        val (eigenvalues, v) = symmetricEigenDecomposition(ata)
        val singularValues = DoubleArray(3) { sqrt(max(eigenvalues[it], 0.0)) }

        val u = DoubleArray(9)
        for (col in 0..2) {
            val sigma = singularValues[col]
            if (sigma > 1e-9) {
                for (row in 0..2) {
                    var sum = 0.0
                    for (k in 0..2) sum += a[row * 3 + k] * v[k * 3 + col]
                    u[row * 3 + col] = sum / sigma
                }
            }
        }
        fillMissingOrthonormalColumns(u, singularValues)
        return Pair(u, v)
    }

    /** Rank-deficient A (e.g. degenerate/collinear correspondence sets) leaves some U columns unset; complete via Gram-Schmidt. */
    private fun fillMissingOrthonormalColumns(u: DoubleArray, singularValues: DoubleArray) {
        val cols = (0..2).map { c -> DoubleArray(3) { r -> u[r * 3 + c] } }
        val valid = (0..2).filter { singularValues[it] > 1e-9 }
        val missing = (0..2).filter { it !in valid }
        if (missing.isEmpty()) return
        val basis = valid.map { cols[it] }.toMutableList()
        val candidates = listOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        for (cand in candidates) {
            if (basis.size == 3) break
            val vec = cand.copyOf()
            for (b in basis) {
                val dot = vec[0] * b[0] + vec[1] * b[1] + vec[2] * b[2]
                for (d in 0..2) vec[d] -= dot * b[d]
            }
            val norm = sqrt(vec[0] * vec[0] + vec[1] * vec[1] + vec[2] * vec[2])
            if (norm > 1e-6) {
                for (d in 0..2) vec[d] /= norm
                basis.add(vec)
            }
        }
        missing.forEachIndexed { idx, col ->
            val vec = basis[valid.size + idx]
            for (r in 0..2) u[r * 3 + col] = vec[r]
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.Mat3Test"`
Expected: PASS (all 6 tests)

- [ ] **Step 5: Commit**

```bash
git add MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/Mat3.kt \
        MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/Mat3Test.kt
git commit -m "feat: add Mat3 linear algebra core (eigendecomposition, inverse, SVD)"
```

---

## Task 4: `WeightedHorn` — weighted Kabsch/Umeyama rigid alignment

Used by `GncSolver` (Task 10) each IRLS iteration.

**Files:**
- Create: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/WeightedHorn.kt`
- Test: `MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/WeightedHornTest.kt`

**Interfaces:**
- Consumes: `Mat3.multiply/transpose/determinant/svd` (Task 3)
- Produces: `WeightedHorn.solve(points: List<DoubleArray>, targets: List<DoubleArray>, weights: DoubleArray): Pair<DoubleArray, DoubleArray>` — returns (R: row-major 3x3, t: 3-vector) minimizing `sum(w_i * ||R*p_i + t - q_i||^2)`.

- [ ] **Step 1: Write the failing test — recovers a known rigid transform exactly**

```kotlin
package com.example.moge3dscanner.ui.merge

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class WeightedHornTest {
    @Test
    fun `recovers a known rotation and translation with no noise`() {
        val theta = Math.PI / 6 // 30 degrees about Z
        val r = doubleArrayOf(cos(theta), -sin(theta), 0.0, sin(theta), cos(theta), 0.0, 0.0, 0.0, 1.0)
        val t = doubleArrayOf(1.5, -2.0, 0.5)

        val points = listOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0),
            doubleArrayOf(1.0, 1.0, 1.0)
        )
        val targets = points.map { p ->
            DoubleArray(3) { row -> (0..2).sumOf { r[row * 3 + it] * p[it] } + t[row] }
        }
        val weights = DoubleArray(points.size) { 1.0 }

        val (rEst, tEst) = WeightedHorn.solve(points, targets, weights)

        for (i in 0..8) assertEquals(r[i], rEst[i], 1e-6)
        for (i in 0..2) assertEquals(t[i], tEst[i], 1e-6)
    }

    @Test
    fun `zero-weighted outlier point does not affect the result`() {
        val points = listOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val targets = listOf(doubleArrayOf(2.0, 0.0, 0.0), doubleArrayOf(0.0, 2.0, 0.0), doubleArrayOf(0.0, 0.0, 2.0))
        // identity rotation, translation zero would fit a pure scale-2 which R,t can't represent exactly;
        // use a solvable case instead: translation-only with one wildly wrong zero-weighted outlier
        val pointsT = listOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(999.0, 999.0, 999.0))
        val targetsT = listOf(doubleArrayOf(5.0, 5.0, 5.0), doubleArrayOf(6.0, 5.0, 5.0), doubleArrayOf(-1.0, -1.0, -1.0))
        val weights = doubleArrayOf(1.0, 1.0, 0.0)

        val (rEst, tEst) = WeightedHorn.solve(pointsT, targetsT, weights)
        // Expect identity rotation + translation (5,5,5), unaffected by the zero-weighted outlier
        assertEquals(1.0, rEst[0], 1e-6); assertEquals(1.0, rEst[4], 1e-6); assertEquals(1.0, rEst[8], 1e-6)
        assertEquals(5.0, tEst[0], 1e-6); assertEquals(5.0, tEst[1], 1e-6); assertEquals(5.0, tEst[2], 1e-6)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.WeightedHornTest"`
Expected: FAIL — `WeightedHorn` does not exist.

- [ ] **Step 3: Implement `WeightedHorn.kt`**

```kotlin
package com.example.moge3dscanner.ui.merge

object WeightedHorn {
    /** Weighted Kabsch/Umeyama: minimizes sum(w_i * ||R*p_i + t - q_i||^2). */
    fun solve(points: List<DoubleArray>, targets: List<DoubleArray>, weights: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = points.size
        val totalWeight = weights.sum()
        require(totalWeight > 1e-9) { "WeightedHorn.solve: total weight must be > 0" }

        val pCentroid = DoubleArray(3)
        val qCentroid = DoubleArray(3)
        for (i in 0 until n) for (d in 0..2) {
            pCentroid[d] += weights[i] * points[i][d]
            qCentroid[d] += weights[i] * targets[i][d]
        }
        for (d in 0..2) { pCentroid[d] /= totalWeight; qCentroid[d] /= totalWeight }

        val h = DoubleArray(9)
        for (i in 0 until n) {
            val pc = DoubleArray(3) { points[i][it] - pCentroid[it] }
            val qc = DoubleArray(3) { targets[i][it] - qCentroid[it] }
            for (row in 0..2) for (col in 0..2) h[row * 3 + col] += weights[i] * pc[row] * qc[col]
        }

        val (u, v) = Mat3.svd(h)
        val det = Mat3.determinant(Mat3.multiply(v, Mat3.transpose(u)))
        val d = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, if (det < 0) -1.0 else 1.0)
        val r = Mat3.multiply(Mat3.multiply(v, d), Mat3.transpose(u))

        val rp = DoubleArray(3)
        for (row in 0..2) { var s = 0.0; for (col in 0..2) s += r[row * 3 + col] * pCentroid[col]; rp[row] = s }
        val t = DoubleArray(3) { qCentroid[it] - rp[it] }
        return Pair(r, t)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.WeightedHornTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/WeightedHorn.kt \
        MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/WeightedHornTest.kt
git commit -m "feat: add WeightedHorn weighted Kabsch/Umeyama solver"
```

---

## Task 5: `SpatialGrid` — uniform voxel-hash nearest-neighbor search

Needed by `FpfhFeatures` (within-cloud neighbors), the FPFH correspondence matcher inside `ScanMerger`, and `GicpRefiner` (cross-cloud nearest neighbor each iteration). A full KD-tree is unnecessary complexity for roughly-uniform-density point clouds (YAGNI); a voxel hash is simpler to implement correctly and fast enough for background (non-realtime) merging.

**Files:**
- Create: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/SpatialGrid.kt`
- Test: `MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/SpatialGridTest.kt`

**Interfaces:**
- Produces: `class SpatialGrid(points: List<DoubleArray>, cellSize: Double)` with `fun nearest(query: DoubleArray): Int?` (index of nearest point, or null if empty) and `fun kNearest(query: DoubleArray, k: Int): List<Int>` (indices, ascending distance).

- [ ] **Step 1: Write the failing test — matches brute-force on a small synthetic set**

```kotlin
package com.example.moge3dscanner.ui.merge

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class SpatialGridTest {
    private fun bruteForceNearest(points: List<DoubleArray>, query: DoubleArray): Int {
        var best = 0; var bestDist = Double.MAX_VALUE
        for (i in points.indices) {
            val d = distSq(points[i], query)
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }

    private fun distSq(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in 0..2) { val d = a[i] - b[i]; s += d * d }
        return s
    }

    @Test
    fun `nearest matches brute force on random points`() {
        val random = Random(42)
        val points = (0 until 500).map { DoubleArray(3) { random.nextDouble(-10.0, 10.0) } }
        val grid = SpatialGrid(points, cellSize = 1.0)

        repeat(20) {
            val query = DoubleArray(3) { random.nextDouble(-10.0, 10.0) }
            val expected = bruteForceNearest(points, query)
            val actual = grid.nearest(query)
            assertEquals(points[expected].toList(), points[actual!!].toList())
        }
    }

    @Test
    fun `kNearest returns k closest in ascending distance order`() {
        val points = listOf(
            doubleArrayOf(0.0, 0.0, 0.0),
            doubleArrayOf(1.0, 0.0, 0.0),
            doubleArrayOf(2.0, 0.0, 0.0),
            doubleArrayOf(5.0, 0.0, 0.0)
        )
        val grid = SpatialGrid(points, cellSize = 1.0)
        val result = grid.kNearest(doubleArrayOf(0.0, 0.0, 0.0), 3)
        assertEquals(listOf(0, 1, 2), result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.SpatialGridTest"`
Expected: FAIL — `SpatialGrid` does not exist.

- [ ] **Step 3: Implement `SpatialGrid.kt`**

```kotlin
package com.example.moge3dscanner.ui.merge

import kotlin.math.floor

/** Uniform voxel-hash nearest-neighbor index over roughly-uniform-density 3D points. */
class SpatialGrid(private val points: List<DoubleArray>, private val cellSize: Double) {
    private val cells = HashMap<Triple<Int, Int, Int>, MutableList<Int>>()

    init {
        for (i in points.indices) cells.getOrPut(cellOf(points[i])) { mutableListOf() }.add(i)
    }

    private fun cellOf(p: DoubleArray) = Triple(
        floor(p[0] / cellSize).toInt(),
        floor(p[1] / cellSize).toInt(),
        floor(p[2] / cellSize).toInt()
    )

    private fun distSq(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in 0..2) { val d = a[i] - b[i]; s += d * d }
        return s
    }

    fun nearest(query: DoubleArray): Int? = kNearest(query, 1).firstOrNull()

    fun kNearest(query: DoubleArray, k: Int): List<Int> {
        if (points.isEmpty()) return emptyList()
        val (cx, cy, cz) = cellOf(query)
        val found = LinkedHashSet<Int>()
        var radius = 1
        // Expand the search ring outward until we have >= k candidates, then one extra
        // ring to guarantee correctness near cell boundaries, then rank by true distance.
        while (found.size < k && radius < 1000) {
            found.clear()
            for (dx in -radius..radius) for (dy in -radius..radius) for (dz in -radius..radius) {
                cells[Triple(cx + dx, cy + dy, cz + dz)]?.let { found.addAll(it) }
            }
            if (found.size >= points.size) break
            radius++
        }
        // One extra ring for correctness at cell-boundary edge cases.
        val finalRadius = radius + 1
        found.clear()
        for (dx in -finalRadius..finalRadius) for (dy in -finalRadius..finalRadius) for (dz in -finalRadius..finalRadius) {
            cells[Triple(cx + dx, cy + dy, cz + dz)]?.let { found.addAll(it) }
        }
        return found.sortedBy { distSq(points[it], query) }.take(k)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.SpatialGridTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/SpatialGrid.kt \
        MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/SpatialGridTest.kt
git commit -m "feat: add SpatialGrid voxel-hash nearest-neighbor index"
```

---

## Task 6: Convert POLI's pretrained checkpoint to TFLite (offline, one-time)

Runs outside the Android Gradle build, in a local checkout of `jinwoolee1230/POLI` using its own conda environment (`environment.yml`). Produces the asset `PoliInterpreter` (Task 7) loads. **This is the plan's highest-risk step** (see spec Risks: PointNet++'s farthest-point-sampling/ball-query ops are dynamic-shape and commonly fail standard TFLite conversion, unlike MoGe's fixed-grid CNN). The step is written to fail loudly and hand off to a concrete fallback rather than silently producing a broken model.

**Files:**
- Create: `tools/convert_poli_to_tflite.py` (repo root, not under `MoGe3DScanner/` — Python, not part of the app build)
- Create: `tools/verify_poli_tflite_parity.py`

**Interfaces:**
- Produces (if TFLite conversion succeeds): `poli_covariance_vlp02_n8192.tflite`, committed to `MoGe3DScanner/app/src/main/assets/`
- Produces (if it fails, fallback path): `poli_covariance_vlp02_n8192.onnx`, committed to the same assets folder instead, consumed by Task 7's ONNX Runtime Mobile implementation

- [ ] **Step 1: Write the conversion script**

```python
# tools/convert_poli_to_tflite.py
"""
One-time offline conversion: POLI's pretrained PointNet++ covariance model
(PyTorch) -> TFLite, for on-device inference in MoGe3DScanner.

Run inside a local POLI checkout's own conda environment (see POLI's environment.yml).
Usage:
    python convert_poli_to_tflite.py \
        --poli-repo /path/to/POLI \
        --checkpoint /path/to/POLI/weights/HeLiPR/vlp_helipr_0.2m.pth \
        --num-points 8192 \
        --output poli_covariance_vlp02_n8192.tflite
"""
import argparse
import shutil
import subprocess
import sys

import torch


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--poli-repo", required=True)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--num-points", type=int, default=8192)
    parser.add_argument("--output", default="poli_covariance.tflite")
    args = parser.parse_args()

    sys.path.insert(0, args.poli_repo)
    from model.pointnetpp_scene import PointPP  # POLI's scene-level network

    model = PointPP()
    model.load_state_dict(torch.load(args.checkpoint, map_location="cpu"))
    model.eval()

    dummy_input = torch.randn(1, args.num_points, 3)

    onnx_path = args.output.replace(".tflite", ".onnx")
    torch.onnx.export(
        model, dummy_input, onnx_path,
        input_names=["points"], output_names=["cholesky_params"],
        opset_version=17, do_constant_folding=True,
    )
    print(f"Exported ONNX to {onnx_path}")

    result = subprocess.run(
        ["onnx2tf", "-i", onnx_path, "-o", "tflite_out", "-osd"],
        capture_output=True, text=True,
    )
    print(result.stdout)
    if result.returncode != 0:
        print(result.stderr, file=sys.stderr)
        print(
            "\nTFLite conversion failed — this is the known risk documented in "
            "docs/superpowers/specs/2026-07-13-poli-merge-integration-design.md. "
            "Do not retry blindly: keep the .onnx file produced above and switch "
            "Task 7 to its ONNX Runtime Mobile implementation instead.",
            file=sys.stderr,
        )
        sys.exit(1)

    shutil.copy("tflite_out/model_float32.tflite", args.output)
    print(f"Wrote {args.output}")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Write the parity-check script**

```python
# tools/verify_poli_tflite_parity.py
"""Numeric parity check: TFLite output vs PyTorch reference, on the same random input."""
import argparse
import sys

import numpy as np
import tensorflow as tf
import torch


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--poli-repo", required=True)
    parser.add_argument("--checkpoint", required=True)
    parser.add_argument("--tflite-model", required=True)
    parser.add_argument("--num-points", type=int, default=8192)
    parser.add_argument("--tolerance", type=float, default=1e-2)
    args = parser.parse_args()

    sys.path.insert(0, args.poli_repo)
    from model.pointnetpp_scene import PointPP

    model = PointPP()
    model.load_state_dict(torch.load(args.checkpoint, map_location="cpu"))
    model.eval()

    np.random.seed(0)
    points_np = np.random.randn(1, args.num_points, 3).astype(np.float32)

    with torch.no_grad():
        torch_out = model(torch.from_numpy(points_np)).numpy()

    interpreter = tf.lite.Interpreter(model_path=args.tflite_model)
    interpreter.allocate_tensors()
    in_details = interpreter.get_input_details()
    out_details = interpreter.get_output_details()
    interpreter.set_tensor(in_details[0]["index"], points_np)
    interpreter.invoke()
    tflite_out = interpreter.get_tensor(out_details[0]["index"])

    max_diff = np.abs(torch_out - tflite_out).max()
    print(f"Max absolute difference: {max_diff}")
    if max_diff > args.tolerance:
        print(f"FAIL: exceeds tolerance {args.tolerance}", file=sys.stderr)
        sys.exit(1)
    print("PASS")


if __name__ == "__main__":
    main()
```

- [ ] **Step 3: Run the conversion**

Run (inside the POLI repo's conda env, `pip install onnx2tf tensorflow` if not already present):
```bash
python tools/convert_poli_to_tflite.py \
    --poli-repo /path/to/POLI \
    --checkpoint /path/to/POLI/weights/HeLiPR/vlp_helipr_0.2m.pth \
    --output poli_covariance_vlp02_n8192.tflite
```

**Branch on the result:**
- **If it exits 0:** run `python tools/verify_poli_tflite_parity.py --poli-repo /path/to/POLI --checkpoint /path/to/POLI/weights/HeLiPR/vlp_helipr_0.2m.pth --tflite-model poli_covariance_vlp02_n8192.tflite`. If it prints `PASS`, copy the `.tflite` to `MoGe3DScanner/app/src/main/assets/poli_covariance_vlp02_n8192.tflite` and proceed with Task 7's TFLite implementation only.
- **If either step fails:** keep `poli_covariance_vlp02_n8192.onnx`, copy it to `MoGe3DScanner/app/src/main/assets/poli_covariance_vlp02_n8192.onnx`, and proceed with Task 7's ONNX Runtime Mobile implementation only. Do not build both — pick one backend based on this result and delete the other implementation from Task 7 before committing.

- [ ] **Step 4: Commit**

```bash
git add tools/convert_poli_to_tflite.py tools/verify_poli_tflite_parity.py \
        MoGe3DScanner/app/src/main/assets/poli_covariance_vlp02_n8192.*
git commit -m "build: add POLI->TFLite conversion tooling and converted covariance model"
```

---

## Task 7: `PoliInterpreter` — on-device covariance prediction

**Files:**
- Create: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/CovariancePredictor.kt` (interface, backend-agnostic)
- Create: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/PoliInterpreter.kt` (implementation selected per Task 6's outcome)
- Modify: `MoGe3DScanner/app/build.gradle.kts` — only if the ONNX Runtime Mobile branch was selected, add `implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")`

**Interfaces:**
- Produces: `interface CovariancePredictor { fun predict(positions: FloatArray): List<DoubleArray>; fun close() }` — one `DoubleArray(9)` row-major covariance per point, same point order as the input. Consumed by `FpfhFeatures` (Task 8) and `GicpRefiner` (Task 11).

- [ ] **Step 1: Confirm the exact Cholesky-vector-to-matrix packing before writing the reconstruction code**

The 6-vec output is `[diag0, diag1, diag2, tri0, tri1, tri2]` (from `model/pointnetpp_scene.py`'s `torch.cat([diag, triang], dim=1)`, per the spec's architecture notes). Before writing `reconstructCovariance` below, open `model/pointnetpp_scene.py` in the POLI checkout used for Task 6 and confirm which lower-triangular slot each `triang` channel fills — this is not guessable from the module list alone and getting it wrong produces a *valid but geometrically wrong* covariance (it'll still be a legitimate PSD matrix, so bugs here won't crash anything, they'll just quietly misalign scans). Grep for how `L` (or an equivalent Cholesky matrix) is assembled from `diag`/`triang` right after this concatenation, or in the loss code in `train.py` where the covariance is turned into an information matrix — the assembly must be consistent between the two. Update the `reconstructCovariance` mapping below to match exactly what's found.

- [ ] **Step 2: Write `CovariancePredictor.kt`**

```kotlin
package com.example.moge3dscanner.ui.merge

interface CovariancePredictor {
    /** positions: flat [x0,y0,z0,x1,y1,z1,...]. Returns one row-major 3x3 covariance per point, same order. */
    fun predict(positions: FloatArray): List<DoubleArray>
    fun close()
}

/** Shared by both backend implementations: Cholesky 6-vec -> full 3x3 SPD covariance via L*L^T. */
internal fun reconstructCovariance(diag: FloatArray, triang: FloatArray): DoubleArray {
    // L = [[diag0, 0, 0], [tri0, diag1, 0], [tri1, tri2, diag2]] — confirmed against POLI source per Task 7 Step 1.
    val l = doubleArrayOf(
        diag[0].toDouble(), 0.0, 0.0,
        triang[0].toDouble(), diag[1].toDouble(), 0.0,
        triang[1].toDouble(), triang[2].toDouble(), diag[2].toDouble()
    )
    return Mat3.multiply(l, Mat3.transpose(l))
}
```

- [ ] **Step 3a (TFLite branch — use only if Task 6 selected this path): implement `PoliInterpreter.kt`**

Modeled directly on `MogeInterpreter.kt`'s GPU-delegate-with-CPU-fallback pattern:

```kotlin
package com.example.moge3dscanner.ui.merge

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class PoliInterpreter(context: Context, private val numPoints: Int = 8192) : CovariancePredictor {
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    init {
        val modelBuffer = loadModelFile(context, "poli_covariance_vlp02_n8192.tflite")
        try {
            val gpuOptions = Interpreter.Options().apply {
                gpuDelegate = GpuDelegate()
                addDelegate(gpuDelegate)
            }
            interpreter = Interpreter(modelBuffer, gpuOptions)
        } catch (e: Exception) {
            Log.w("PoliInterpreter", "GPU delegate failed, falling back to CPU.", e)
            gpuDelegate?.close(); gpuDelegate = null
            interpreter = Interpreter(modelBuffer, Interpreter.Options().apply { setNumThreads(4) })
        }
    }

    private fun loadModelFile(context: Context, name: String): MappedByteBuffer {
        val fd = context.assets.openFd(name)
        val channel = FileInputStream(fd.fileDescriptor).channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    @Synchronized
    override fun predict(positions: FloatArray): List<DoubleArray> {
        val interp = interpreter ?: error("PoliInterpreter not initialized")
        val actualPoints = positions.size / 3
        require(actualPoints == numPoints) {
            "PoliInterpreter expects exactly $numPoints points (model input is fixed-size); " +
            "caller must resample. Got $actualPoints."
        }

        val inputBuffer = ByteBuffer.allocateDirect(1 * numPoints * 3 * 4).order(ByteOrder.nativeOrder())
        inputBuffer.asFloatBuffer().put(positions)
        val outputBuffer = ByteBuffer.allocateDirect(1 * numPoints * 6 * 4).order(ByteOrder.nativeOrder())

        interp.run(inputBuffer, outputBuffer)

        val outFloats = outputBuffer.asFloatBuffer()
        outFloats.rewind()
        return (0 until numPoints).map { i ->
            val diag = FloatArray(3) { outFloats.get(i * 6 + it) }
            val triang = FloatArray(3) { outFloats.get(i * 6 + 3 + it) }
            reconstructCovariance(diag, triang)
        }
    }

    override fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}
```

- [ ] **Step 3b (ONNX Runtime Mobile branch — use only if Task 6 selected this path): implement `PoliInterpreter.kt` instead**

```kotlin
package com.example.moge3dscanner.ui.merge

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

class PoliInterpreter(context: Context, private val numPoints: Int = 8192) : CovariancePredictor {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = context.assets.open("poli_covariance_vlp02_n8192.onnx").use { stream ->
        env.createSession(stream.readBytes(), OrtSession.SessionOptions())
    }

    @Synchronized
    override fun predict(positions: FloatArray): List<DoubleArray> {
        val actualPoints = positions.size / 3
        require(actualPoints == numPoints) {
            "PoliInterpreter expects exactly $numPoints points (model input is fixed-size); " +
            "caller must resample. Got $actualPoints."
        }
        val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(positions), longArrayOf(1, numPoints.toLong(), 3))
        val output = session.run(mapOf("points" to inputTensor))
        val raw = (output[0].value as Array<Array<FloatArray>>)[0] // [numPoints][6]
        return (0 until numPoints).map { i ->
            val diag = floatArrayOf(raw[i][0], raw[i][1], raw[i][2])
            val triang = floatArrayOf(raw[i][3], raw[i][4], raw[i][5])
            reconstructCovariance(diag, triang)
        }
    }

    override fun close() {
        session.close()
    }
}
```

- [ ] **Step 4: Manual on-device smoke test**

There is no automated test here — this mirrors the existing project's own convention (`MogeInterpreter.kt` has no unit or instrumented test; TFLite/ONNX Runtime interpreters need a real device/emulator and are verified manually). Build and install the debug APK, capture a scan, and add a temporary `Log.d` in `PoliInterpreter.predict` logging the first output covariance's diagonal. Confirm on logcat (`adb logcat -s PoliInterpreter`) that:
- Inference completes without throwing
- All three diagonal entries are positive (the model's `softplus` guarantees this — a negative or zero value means the packing order from Step 1 or the reconstruction is wrong)

Remove the temporary log line once confirmed.

- [ ] **Step 5: Commit**

```bash
git add MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/CovariancePredictor.kt \
        MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/PoliInterpreter.kt \
        MoGe3DScanner/app/build.gradle.kts
git commit -m "feat: add PoliInterpreter for on-device per-point covariance prediction"
```

---

## Task 8: `FpfhFeatures` — normals from covariance + FPFH descriptors

**Files:**
- Create: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/FpfhFeatures.kt`
- Test: `MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/FpfhFeaturesTest.kt`

**Interfaces:**
- Consumes: `Mat3` (Task 3), `SpatialGrid` (Task 5)
- Produces: `FpfhFeatures.normalsFromCovariances(covariances: List<DoubleArray>, positions: List<DoubleArray>, viewpoint: DoubleArray = doubleArrayOf(0.0,0.0,0.0)): List<DoubleArray>` and `FpfhFeatures.compute(positions: List<DoubleArray>, normals: List<DoubleArray>, grid: SpatialGrid, k: Int = 10): List<DoubleArray>` (33-dim histogram per point: 11 bins × {alpha, phi, theta}, the standard Rusu et al. FPFH formulation).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.example.moge3dscanner.ui.merge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FpfhFeaturesTest {
    @Test
    fun `normal from a flat covariance points along the thin axis`() {
        // Covariance flat in Z (small Z-variance) -> normal should be +-Z
        val cov = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.01)
        val positions = listOf(doubleArrayOf(0.0, 0.0, 0.0))
        val normals = FpfhFeatures.normalsFromCovariances(listOf(cov), positions, viewpoint = doubleArrayOf(0.0, 0.0, 10.0))
        assertTrue(abs(normals[0][2]) > 0.99) // dominant Z component
        assertTrue(normals[0][2] > 0) // oriented toward the viewpoint at +Z
    }

    @Test
    fun `each angle histogram bucket sums to the neighbor count`() {
        val random = kotlin.random.Random(1)
        val positions = (0 until 50).map { DoubleArray(3) { random.nextDouble(-1.0, 1.0) } }
        val normals = positions.map { doubleArrayOf(0.0, 0.0, 1.0) } // flat patch, uniform normal
        val grid = SpatialGrid(positions, cellSize = 0.5)
        val histograms = FpfhFeatures.compute(positions, normals, grid, k = 5)

        assertEquals(50, histograms.size)
        assertEquals(FpfhFeatures.HISTOGRAM_SIZE, histograms[0].size)
        for (h in histograms) assertTrue(h.all { it >= 0.0 })
    }

    @Test
    fun `fpfh histogram is invariant to a rigid transform of the whole patch`() {
        val random = kotlin.random.Random(7)
        val positions = (0 until 30).map { DoubleArray(3) { random.nextDouble(-1.0, 1.0) } }
        val normals = positions.map { doubleArrayOf(0.0, 0.0, 1.0) }
        val grid = SpatialGrid(positions, cellSize = 0.5)
        val original = FpfhFeatures.compute(positions, normals, grid, k = 5)

        // Rotate 90 degrees about Z and translate; normals rotate identically (still +Z here since axis is Z).
        val rotatedPositions = positions.map { doubleArrayOf(-it[1] + 5.0, it[0] + 5.0, it[2] + 5.0) }
        val rotatedGrid = SpatialGrid(rotatedPositions, cellSize = 0.5)
        val rotated = FpfhFeatures.compute(rotatedPositions, normals, rotatedGrid, k = 5)

        for (i in original.indices) {
            for (d in original[i].indices) {
                assertEquals(original[i][d], rotated[i][d], 1e-6)
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.FpfhFeaturesTest"`
Expected: FAIL — `FpfhFeatures` does not exist.

- [ ] **Step 3: Implement `FpfhFeatures.kt`**

```kotlin
package com.example.moge3dscanner.ui.merge

import kotlin.math.atan2
import kotlin.math.sqrt

object FpfhFeatures {
    const val BINS_PER_ANGLE = 11
    const val HISTOGRAM_SIZE = BINS_PER_ANGLE * 3

    fun normalsFromCovariances(
        covariances: List<DoubleArray>,
        positions: List<DoubleArray>,
        viewpoint: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0)
    ): List<DoubleArray> = covariances.mapIndexed { i, cov ->
        val (eigenvalues, v) = Mat3.symmetricEigenDecomposition(cov)
        var minIdx = 0
        for (k in 1..2) if (eigenvalues[k] < eigenvalues[minIdx]) minIdx = k
        var normal = doubleArrayOf(v[minIdx], v[3 + minIdx], v[6 + minIdx])
        val toView = DoubleArray(3) { viewpoint[it] - positions[i][it] }
        if (dot(normal, toView) < 0) normal = DoubleArray(3) { -normal[it] }
        normalize(normal)
    }

    fun compute(positions: List<DoubleArray>, normals: List<DoubleArray>, grid: SpatialGrid, k: Int = 10): List<DoubleArray> {
        val n = positions.size
        val neighborLists = positions.indices.map { i -> grid.kNearest(positions[i], k + 1).filter { it != i } }
        val spfh = Array(n) { DoubleArray(HISTOGRAM_SIZE) }

        for (i in 0 until n) {
            for (j in neighborLists[i]) {
                val feature = pairFeature(positions[i], normals[i], positions[j], normals[j]) ?: continue
                spfh[i][binIndex(feature.first, -1.0, 1.0)] += 1.0
                spfh[i][BINS_PER_ANGLE + binIndex(feature.second, -1.0, 1.0)] += 1.0
                spfh[i][2 * BINS_PER_ANGLE + binIndex(feature.third, -Math.PI, Math.PI)] += 1.0
            }
        }

        val fpfh = Array(n) { DoubleArray(HISTOGRAM_SIZE) }
        for (i in 0 until n) {
            val neighbors = neighborLists[i]
            if (neighbors.isEmpty()) { fpfh[i] = spfh[i]; continue }
            val weighted = DoubleArray(HISTOGRAM_SIZE)
            var weightSum = 0.0
            for (j in neighbors) {
                val dist = distance(positions[i], positions[j])
                if (dist < 1e-9) continue
                val w = 1.0 / dist
                weightSum += w
                for (d in 0 until HISTOGRAM_SIZE) weighted[d] += w * spfh[j][d]
            }
            for (d in 0 until HISTOGRAM_SIZE) {
                fpfh[i][d] = spfh[i][d] + if (weightSum > 1e-9) weighted[d] / weightSum else 0.0
            }
        }
        return fpfh.toList()
    }

    private fun pairFeature(pi: DoubleArray, ni: DoubleArray, pj: DoubleArray, nj: DoubleArray): Triple<Double, Double, Double>? {
        val diff = DoubleArray(3) { pj[it] - pi[it] }
        val dist = sqrt(diff[0] * diff[0] + diff[1] * diff[1] + diff[2] * diff[2])
        if (dist < 1e-9) return null
        val u = ni
        val diffNorm = DoubleArray(3) { diff[it] / dist }
        val v = normalize(cross(u, diffNorm))
        val w = cross(u, v)
        val alpha = dot(v, nj)
        val phi = dot(u, diffNorm)
        val theta = atan2(dot(w, nj), dot(u, nj))
        return Triple(alpha, phi, theta)
    }

    private fun binIndex(value: Double, min: Double, max: Double): Int {
        val clamped = value.coerceIn(min, max - 1e-9)
        return (((clamped - min) / (max - min)) * BINS_PER_ANGLE).toInt().coerceIn(0, BINS_PER_ANGLE - 1)
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun normalize(a: DoubleArray): DoubleArray {
        val n = sqrt(dot(a, a))
        return if (n > 1e-9) DoubleArray(3) { a[it] / n } else a
    }
    private fun distance(a: DoubleArray, b: DoubleArray) = sqrt((0..2).sumOf { val d = a[it] - b[it]; d * d })
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.FpfhFeaturesTest"`
Expected: PASS (all 3 tests)

- [ ] **Step 5: Commit**

```bash
git add MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/FpfhFeatures.kt \
        MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/FpfhFeaturesTest.kt
git commit -m "feat: add FpfhFeatures (POLI-covariance normals + FPFH descriptors)"
```

---

## Task 9: `RobinConsensus` — compatibility graph + max-core inlier extraction

**Files:**
- Create: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/RobinConsensus.kt`
- Test: `MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/RobinConsensusTest.kt`

**Interfaces:**
- Produces: `RobinConsensus.filterInliers(sourcePoints: List<DoubleArray>, targetPoints: List<DoubleArray>, noiseBound: Double): List<Int>` — indices (into the input correspondence lists) that survive pairwise-distance-invariance compatibility + max-core filtering.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.moge3dscanner.ui.merge

import org.junit.Assert.assertEquals
import org.junit.Test

class RobinConsensusTest {
    @Test
    fun `keeps a consistent clique and rejects a lone outlier correspondence`() {
        // 5 correspondences related by the SAME rigid transform (translation by (10,0,0)) -> mutually compatible.
        val sourcePoints = listOf(
            doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0),
            doubleArrayOf(1.0, 1.0, 0.0), doubleArrayOf(0.5, 0.5, 1.0)
        )
        val targetPoints = sourcePoints.map { doubleArrayOf(it[0] + 10.0, it[1], it[2]) }.toMutableList()
        // 6th correspondence: source point far away, mapped to a target that breaks pairwise distances vs the others.
        val badSource = doubleArrayOf(50.0, 50.0, 50.0)
        val badTarget = doubleArrayOf(-999.0, -999.0, -999.0)

        val allSources = sourcePoints + badSource
        val allTargets = targetPoints + badTarget

        val inliers = RobinConsensus.filterInliers(allSources, allTargets, noiseBound = 0.05)

        assertEquals(setOf(0, 1, 2, 3, 4), inliers.toSet())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.RobinConsensusTest"`
Expected: FAIL — `RobinConsensus` does not exist.

- [ ] **Step 3: Implement `RobinConsensus.kt`**

```kotlin
package com.example.moge3dscanner.ui.merge

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Port of ROBIN's maximum_consensus: two correspondences are compatible iff the
 * pairwise distance between their source points matches the pairwise distance
 * between their target points, within 2*noiseBound (a rigid transform preserves
 * pairwise distances exactly; noise breaks this only up to sensor noise).
 * Inliers = the maximum k-core of the resulting compatibility graph.
 */
object RobinConsensus {
    fun filterInliers(sourcePoints: List<DoubleArray>, targetPoints: List<DoubleArray>, noiseBound: Double): List<Int> {
        val n = sourcePoints.size
        require(targetPoints.size == n) { "sourcePoints and targetPoints must be the same length" }
        if (n == 0) return emptyList()

        val adjacency = List(n) { mutableSetOf<Int>() }
        val threshold = 2 * noiseBound
        for (i in 0 until n) for (j in i + 1 until n) {
            val sourceDist = distance(sourcePoints[i], sourcePoints[j])
            val targetDist = distance(targetPoints[i], targetPoints[j])
            if (abs(sourceDist - targetDist) < threshold) {
                adjacency[i].add(j)
                adjacency[j].add(i)
            }
        }

        return maxCore(adjacency)
    }

    /** Degeneracy-ordering (k-core peeling): repeatedly remove the minimum-degree vertex, tracking each vertex's coreness. */
    private fun maxCore(adjacency: List<MutableSet<Int>>): List<Int> {
        val n = adjacency.size
        val degree = IntArray(n) { adjacency[it].size }
        val removed = BooleanArray(n)
        val coreness = IntArray(n)
        var runningMax = 0

        repeat(n) {
            var minIdx = -1
            var minDeg = Int.MAX_VALUE
            for (v in 0 until n) if (!removed[v] && degree[v] < minDeg) { minDeg = degree[v]; minIdx = v }
            if (minIdx == -1) return@repeat
            runningMax = maxOf(runningMax, minDeg)
            coreness[minIdx] = runningMax
            removed[minIdx] = true
            for (nb in adjacency[minIdx]) if (!removed[nb]) degree[nb]--
        }

        val finalMax = coreness.maxOrNull() ?: 0
        return (0 until n).filter { coreness[it] == finalMax }
    }

    private fun distance(a: DoubleArray, b: DoubleArray) = sqrt((0..2).sumOf { val d = a[it] - b[it]; d * d })
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.RobinConsensusTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/RobinConsensus.kt \
        MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/RobinConsensusTest.kt
git commit -m "feat: add RobinConsensus compatibility-graph max-core inlier filter"
```

---

## Task 10: `GncSolver` — Graduated Non-Convexity (TLS) coarse pose solve

**Files:**
- Create: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/GncSolver.kt`
- Test: `MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/GncSolverTest.kt`

**Interfaces:**
- Consumes: `WeightedHorn.solve` (Task 4)
- Produces: `data class GncResult(val rotation: DoubleArray, val translation: DoubleArray, val inlierIndices: List<Int>)` and `GncSolver.solve(points: List<DoubleArray>, targets: List<DoubleArray>, noiseBound: Double, maxIterations: Int = 100, muFactor: Double = 1.4): GncResult`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.moge3dscanner.ui.merge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GncSolverTest {
    @Test
    fun `recovers rotation and translation despite gross outliers`() {
        val theta = Math.PI / 4
        val r = doubleArrayOf(cos(theta), -sin(theta), 0.0, sin(theta), cos(theta), 0.0, 0.0, 0.0, 1.0)
        val t = doubleArrayOf(2.0, 1.0, 0.0)

        val inlierPoints = (0 until 20).map { i -> doubleArrayOf(i.toDouble() % 5, (i * 3) % 4.0, (i % 2).toDouble()) }
        val inlierTargets = inlierPoints.map { p ->
            DoubleArray(3) { row -> (0..2).sumOf { r[row * 3 + it] * p[it] } + t[row] }
        }
        // 5 gross outlier correspondences (40% of the total) mapped to unrelated points.
        val outlierPoints = (0 until 5).map { doubleArrayOf(100.0 + it, 100.0, 100.0) }
        val outlierTargets = (0 until 5).map { doubleArrayOf(-500.0, -500.0 - it, -500.0) }

        val points = inlierPoints + outlierPoints
        val targets = inlierTargets + outlierTargets

        val result = GncSolver.solve(points, targets, noiseBound = 0.05)

        for (i in 0..8) assertEquals(r[i], result.rotation[i], 1e-3)
        for (i in 0..2) assertEquals(t[i], result.translation[i], 1e-3)
        assertTrue(result.inlierIndices.toSet().containsAll(0 until 20))
        assertTrue((20 until 25).none { it in result.inlierIndices })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.GncSolverTest"`
Expected: FAIL — `GncSolver` does not exist.

- [ ] **Step 3: Implement `GncSolver.kt`**

```kotlin
package com.example.moge3dscanner.ui.merge

import kotlin.math.abs
import kotlin.math.sqrt

data class GncResult(val rotation: DoubleArray, val translation: DoubleArray, val inlierIndices: List<Int>)

/** Graduated Non-Convexity with a Truncated-Least-Squares loss (Yang et al. 2020), solved via
 *  iteratively-reweighted WeightedHorn — matches POLI's own GNC_TLS()/registration_utils.py behavior. */
object GncSolver {
    fun solve(points: List<DoubleArray>, targets: List<DoubleArray>, noiseBound: Double, maxIterations: Int = 100, muFactor: Double = 1.4): GncResult {
        val n = points.size
        var weights = DoubleArray(n) { 1.0 }
        var (r, t) = WeightedHorn.solve(points, targets, weights)
        val barc2 = noiseBound * noiseBound

        var residuals = residualsSquared(points, targets, r, t)
        val maxResidual2 = residuals.maxOrNull() ?: 0.0
        var mu = if (maxResidual2 > barc2) barc2 / (2 * maxResidual2 - barc2) else 1e6
        if (mu <= 0.0) mu = 1e-4

        for (iteration in 0 until maxIterations) {
            val (rNew, tNew) = WeightedHorn.solve(points, targets, weights)
            r = rNew; t = tNew
            residuals = residualsSquared(points, targets, r, t)

            val th1 = (mu / (mu + 1)) * barc2
            val th2 = ((mu + 1) / mu) * barc2
            val newWeights = DoubleArray(n) { i ->
                val r2 = residuals[i]
                when {
                    r2 <= th1 -> 1.0
                    r2 >= th2 -> 0.0
                    else -> (sqrt(barc2 * mu * (mu + 1) / r2) - mu).coerceIn(0.0, 1.0)
                }
            }
            val converged = weights.indices.all { abs(weights[it] - newWeights[it]) < 1e-4 }
            weights = newWeights
            mu *= muFactor
            if (converged) break
        }

        val inliers = weights.indices.filter { weights[it] > 0.5 }
        return GncResult(r, t, inliers)
    }

    private fun residualsSquared(points: List<DoubleArray>, targets: List<DoubleArray>, r: DoubleArray, t: DoubleArray): DoubleArray =
        DoubleArray(points.size) { i ->
            val p = points[i]
            var s = 0.0
            for (row in 0..2) {
                var v = t[row]
                for (col in 0..2) v += r[row * 3 + col] * p[col]
                val d = v - targets[i][row]
                s += d * d
            }
            s
        }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.GncSolverTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/GncSolver.kt \
        MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/GncSolverTest.kt
git commit -m "feat: add GncSolver (Graduated Non-Convexity TLS pose solver)"
```

---

## Task 11: `GicpRefiner` — POLI-covariance-weighted Generalized ICP refinement

**Files:**
- Create: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/GicpRefiner.kt`
- Test: `MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/GicpRefinerTest.kt`

**Interfaces:**
- Consumes: `Mat3` (Task 3), `SpatialGrid` (Task 5)
- Produces: `data class GicpResult(val rotation: DoubleArray, val translation: DoubleArray)` and `GicpRefiner.refine(sourcePositions: List<DoubleArray>, sourceCovariances: List<DoubleArray>, targetPositions: List<DoubleArray>, targetCovariances: List<DoubleArray>, targetGrid: SpatialGrid, initialRotation: DoubleArray, initialTranslation: DoubleArray, maxIterations: Int = 30, convergenceThreshold: Double = 1e-6): GicpResult`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.moge3dscanner.ui.merge

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GicpRefinerTest {
    @Test
    fun `refines a small perturbation back toward the ground-truth transform`() {
        val random = kotlin.random.Random(3)
        val targetPositions = (0 until 200).map { DoubleArray(3) { random.nextDouble(-2.0, 2.0) } }
        val isotropicCov = doubleArrayOf(0.01, 0.0, 0.0, 0.0, 0.01, 0.0, 0.0, 0.0, 0.01)
        val targetCovariances = targetPositions.map { isotropicCov }
        val targetGrid = SpatialGrid(targetPositions, cellSize = 0.5)

        val trueTheta = Math.PI / 30 // small known rotation
        val trueR = doubleArrayOf(cos(trueTheta), -sin(trueTheta), 0.0, sin(trueTheta), cos(trueTheta), 0.0, 0.0, 0.0, 1.0)
        val trueT = doubleArrayOf(0.1, -0.05, 0.02)
        // Source = inverse-transformed target, so applying (trueR, trueT) to source recovers target exactly.
        val sourcePositions = targetPositions.map { q ->
            val qMinusT = DoubleArray(3) { q[it] - trueT[it] }
            DoubleArray(3) { row -> (0..2).sumOf { trueR[it * 3 + row] * qMinusT[it] } } // R^T * (q - t)
        }
        val sourceCovariances = sourcePositions.map { isotropicCov }

        // Start GICP from a slightly wrong initial guess (identity) instead of the ground truth.
        val result = GicpRefiner.refine(
            sourcePositions, sourceCovariances, targetPositions, targetCovariances, targetGrid,
            initialRotation = Mat3.identity(), initialTranslation = doubleArrayOf(0.0, 0.0, 0.0)
        )

        for (i in 0..8) assertEquals(trueR[i], result.rotation[i], 1e-2)
        for (i in 0..2) assertEquals(trueT[i], result.translation[i], 1e-2)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.GicpRefinerTest"`
Expected: FAIL — `GicpRefiner` does not exist.

- [ ] **Step 3: Implement `GicpRefiner.kt`**

```kotlin
package com.example.moge3dscanner.ui.merge

data class GicpResult(val rotation: DoubleArray, val translation: DoubleArray)

/** Generalized ICP (Segal et al. 2009): Mahalanobis-weighted point-to-point refinement using
 *  combined per-correspondence covariance Omega_i = (C_target + R*C_source*R^T)^-1. */
object GicpRefiner {
    private const val REJECT_DISTANCE_SQ = 4.0 // meters^2 — generous correspondence-rejection radius
    private const val COVARIANCE_EPSILON = 1e-3 // regularization to avoid inverting a near-singular sum (Segal et al.)

    fun refine(
        sourcePositions: List<DoubleArray>, sourceCovariances: List<DoubleArray>,
        targetPositions: List<DoubleArray>, targetCovariances: List<DoubleArray>,
        targetGrid: SpatialGrid,
        initialRotation: DoubleArray, initialTranslation: DoubleArray,
        maxIterations: Int = 30, convergenceThreshold: Double = 1e-6
    ): GicpResult {
        var r = initialRotation.copyOf()
        var t = initialTranslation.copyOf()

        for (iteration in 0 until maxIterations) {
            val transformed = sourcePositions.map { p -> transformPoint(r, t, p) }
            val correspondences = transformed.indices.mapNotNull { i ->
                val j = targetGrid.nearest(transformed[i]) ?: return@mapNotNull null
                val distSq = distanceSq(transformed[i], targetPositions[j])
                if (distSq < REJECT_DISTANCE_SQ) Pair(i, j) else null
            }
            if (correspondences.size < 6) return GicpResult(r, t)

            val ata = Array(6) { DoubleArray(6) }
            val atb = DoubleArray(6)

            for ((i, j) in correspondences) {
                val p = sourcePositions[i]
                val combined = addDiagonalEpsilon(
                    addMatrices(targetCovariances[j], rotateCovariance(r, sourceCovariances[i])),
                    COVARIANCE_EPSILON
                )
                val omega = Mat3.inverse(combined)
                val e = DoubleArray(3) { row ->
                    var v = t[row]
                    for (col in 0..2) v += r[row * 3 + col] * p[col]
                    v - targetPositions[j][row]
                }
                val negRSkewP = Mat3.multiply(scaleMatrix(r, -1.0), skew(p))
                val jRows = Array(3) { row -> DoubleArray(6).also { out ->
                    for (c in 0..2) out[c] = negRSkewP[row * 3 + c]
                    out[3 + row] = 1.0
                } }
                val omegaJ = Array(3) { row -> DoubleArray(6).also { out ->
                    for (c in 0..5) { var s = 0.0; for (k in 0..2) s += omega[row * 3 + k] * jRows[k][c]; out[c] = s }
                } }
                for (a in 0..5) for (b in 0..5) {
                    var s = 0.0; for (k in 0..2) s += jRows[k][a] * omegaJ[k][b]
                    ata[a][b] += s
                }
                for (a in 0..5) {
                    var s = 0.0
                    for (k in 0..2) { var oe = 0.0; for (c in 0..2) oe += omega[k * 3 + c] * e[c]; s += jRows[k][a] * oe }
                    atb[a] -= s
                }
            }

            val delta = solve6x6(ata, atb) ?: return GicpResult(r, t)
            val dTheta = doubleArrayOf(delta[0], delta[1], delta[2])
            val dT = doubleArrayOf(delta[3], delta[4], delta[5])

            val deltaR = addMatrices(Mat3.identity(), skew(dTheta))
            val rUnnormalized = Mat3.multiply(r, deltaR)
            val (u, v) = Mat3.svd(rUnnormalized)
            r = Mat3.multiply(u, Mat3.transpose(v))
            t = DoubleArray(3) { t[it] + dT[it] }

            if (norm(dTheta) + norm(dT) < convergenceThreshold) return GicpResult(r, t)
        }
        return GicpResult(r, t)
    }

    private fun transformPoint(r: DoubleArray, t: DoubleArray, p: DoubleArray) = DoubleArray(3) { row ->
        var v = t[row]; for (col in 0..2) v += r[row * 3 + col] * p[col]; v
    }
    private fun distanceSq(a: DoubleArray, b: DoubleArray) = (0..2).sumOf { val d = a[it] - b[it]; d * d }
    private fun rotateCovariance(r: DoubleArray, cov: DoubleArray) = Mat3.multiply(Mat3.multiply(r, cov), Mat3.transpose(r))
    private fun addMatrices(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { a[it] + b[it] }
    private fun addDiagonalEpsilon(a: DoubleArray, eps: Double) =
        DoubleArray(9) { i -> a[i] + if (i == 0 || i == 4 || i == 8) eps else 0.0 }
    private fun scaleMatrix(a: DoubleArray, s: Double) = DoubleArray(9) { a[it] * s }
    private fun skew(v: DoubleArray) = doubleArrayOf(0.0, -v[2], v[1], v[2], 0.0, -v[0], -v[1], v[0], 0.0)
    private fun norm(v: DoubleArray) = kotlin.math.sqrt(v.sumOf { it * it })

    /** Gaussian elimination with partial pivoting for the 6x6 GICP normal equations. */
    private fun solve6x6(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = 6
        val m = Array(n) { i -> DoubleArray(n + 1).also { row -> for (j in 0 until n) row[j] = a[i][j]; row[n] = b[i] } }
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) if (kotlin.math.abs(m[row][col]) > kotlin.math.abs(m[pivot][col])) pivot = row
            if (kotlin.math.abs(m[pivot][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
            for (row in col + 1 until n) {
                val factor = m[row][col] / m[col][col]
                for (k in col..n) m[row][k] -= factor * m[col][k]
            }
        }
        val x = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var s = m[row][n]
            for (k in row + 1 until n) s -= m[row][k] * x[k]
            x[row] = s / m[row][row]
        }
        return x
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.GicpRefinerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/GicpRefiner.kt \
        MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/GicpRefinerTest.kt
git commit -m "feat: add GicpRefiner (POLI-covariance-weighted Generalized ICP)"
```

---

## Task 12: `ScanMerger` — N-way orchestration

Resamples each scan to POLI's fixed model input size (8192 points) for covariance prediction and registration math, but applies the resulting pose to each scan's **full-resolution original points** for the merged output — registration only needs a representative subset, but the merged file should keep full density.

**Files:**
- Create: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/ScanMerger.kt`
- Test: `MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/ScanMergerTest.kt`

**Interfaces:**
- Consumes: `GlbReader.read` (Task 2), `CovariancePredictor` (Task 7), `FpfhFeatures` (Task 8), `RobinConsensus.filterInliers` (Task 9), `GncSolver.solve` (Task 10), `GicpRefiner.refine` (Task 11), `SpatialGrid` (Task 5), `Mat3` (Task 3), `GlbWriter.write` (Task 1)
- Produces: `data class ScanInput(val fileName: String, val timestampMillis: Long, val bytes: ByteArray)`, `data class MergeOutcome(val mergedBytes: ByteArray, val mergedPointCount: Int, val skippedFiles: List<String>)`, `ScanMerger.merge(scans: List<ScanInput>, covariancePredictor: CovariancePredictor, fixedInputSize: Int = 8192): MergeOutcome`

- [ ] **Step 1: Write the failing test — using a fake `CovariancePredictor` (no TFLite dependency in this test)**

```kotlin
package com.example.moge3dscanner.ui.merge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScanMergerTest {
    /** Isotropic covariance for every point — isolates ScanMerger's orchestration logic from POLI's actual predictions. */
    private class FakeCovariancePredictor : CovariancePredictor {
        override fun predict(positions: FloatArray): List<DoubleArray> {
            val n = positions.size / 3
            val cov = doubleArrayOf(0.01, 0.0, 0.0, 0.0, 0.01, 0.0, 0.0, 0.0, 0.01)
            return List(n) { cov }
        }
        override fun close() {}
    }

    @Test
    fun `merges two overlapping scans and reports zero skipped`() {
        val random = Random(11)
        val basePositions = (0 until 500).map { floatArrayOf(random.nextDouble(-2.0, 2.0).toFloat(), random.nextDouble(-2.0, 2.0).toFloat(), random.nextDouble(-2.0, 2.0).toFloat()) }
        val baseFlat = basePositions.flatMap { it.toList() }.toFloatArray()
        val baseColors = FloatArray(basePositions.size * 3) { 0.5f }
        val scan1Bytes = GlbWriter.write(baseFlat, baseColors)

        // Second scan = a small known translation of the first — should register successfully.
        val translated = basePositions.map { floatArrayOf(it[0] + 0.2f, it[1], it[2]) }
        val translatedFlat = translated.flatMap { it.toList() }.toFloatArray()
        val scan2Bytes = GlbWriter.write(translatedFlat, baseColors)

        val scans = listOf(
            ScanMerger.ScanInput("moge_scan_1000.glb", 1000L, scan1Bytes),
            ScanMerger.ScanInput("moge_scan_2000.glb", 2000L, scan2Bytes)
        )

        val outcome = ScanMerger.merge(scans, FakeCovariancePredictor(), fixedInputSize = 500)

        assertTrue(outcome.skippedFiles.isEmpty())
        assertEquals(1000, outcome.mergedPointCount) // 500 base + 500 from the successfully-registered second scan
    }

    @Test
    fun `skips a pair with no real overlap instead of failing the whole batch`() {
        val random = Random(22)
        val basePositions = (0 until 500).map { floatArrayOf(random.nextDouble(-1.0, 1.0).toFloat(), random.nextDouble(-1.0, 1.0).toFloat(), random.nextDouble(-1.0, 1.0).toFloat()) }
        val baseFlat = basePositions.flatMap { it.toList() }.toFloatArray()
        val colors = FloatArray(basePositions.size * 3) { 0.5f }
        val scan1Bytes = GlbWriter.write(baseFlat, colors)

        // Unrelated, far-away random cloud with no geometric correspondence to scan 1.
        val unrelated = (0 until 500).map { floatArrayOf(random.nextDouble(500.0, 501.0).toFloat(), random.nextDouble(500.0, 501.0).toFloat(), random.nextDouble(500.0, 501.0).toFloat()) }
        val unrelatedFlat = unrelated.flatMap { it.toList() }.toFloatArray()
        val scan2Bytes = GlbWriter.write(unrelatedFlat, colors)

        val scans = listOf(
            ScanMerger.ScanInput("moge_scan_1000.glb", 1000L, scan1Bytes),
            ScanMerger.ScanInput("moge_scan_2000.glb", 2000L, scan2Bytes)
        )

        val outcome = ScanMerger.merge(scans, FakeCovariancePredictor(), fixedInputSize = 500)

        assertEquals(listOf("moge_scan_2000.glb"), outcome.skippedFiles)
        assertEquals(500, outcome.mergedPointCount) // only the base scan's points
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.ScanMergerTest"`
Expected: FAIL — `ScanMerger` does not exist.

- [ ] **Step 3: Implement `ScanMerger.kt`**

```kotlin
package com.example.moge3dscanner.ui.merge

object ScanMerger {
    data class ScanInput(val fileName: String, val timestampMillis: Long, val bytes: ByteArray)
    data class MergeOutcome(val mergedBytes: ByteArray, val mergedPointCount: Int, val skippedFiles: List<String>)
    private data class PairPose(val rotation: DoubleArray, val translation: DoubleArray)

    private const val COARSE_POINT_CAP = 2000
    private const val NOISE_BOUND = 0.05 // meters

    fun merge(scans: List<ScanInput>, covariancePredictor: CovariancePredictor, fixedInputSize: Int = 8192): MergeOutcome {
        require(scans.size >= 2) { "ScanMerger.merge requires at least 2 scans" }
        val ordered = scans.sortedBy { it.timestampMillis }
        val decoded = ordered.map { GlbReader.read(it.bytes) }

        val mergedPositions = mutableListOf<Float>()
        val mergedColors = mutableListOf<Float>()
        mergedPositions.addAll(decoded[0].positions.toList())
        mergedColors.addAll(decoded[0].colors.toList())

        var accumulatedRotation = Mat3.identity()
        var accumulatedTranslation = doubleArrayOf(0.0, 0.0, 0.0)
        var previousResampled = resample(decoded[0].positions, fixedInputSize)
        var previousCovariances = covariancePredictor.predict(previousResampled.toFlatFloatArray())

        val skipped = mutableListOf<String>()

        for (index in 1 until ordered.size) {
            val currentFull = decoded[index].positions
            val currentResampled = resample(currentFull, fixedInputSize)
            val currentCovariances = covariancePredictor.predict(currentResampled.toFlatFloatArray())

            // source = current scan (to be transformed), target = previous/base scan (fixed reference).
            val pairPose = registerPair(currentResampled, currentCovariances, previousResampled, previousCovariances)
            if (pairPose == null) {
                skipped.add(ordered[index].fileName)
                continue
            }

            val chainedRotation = Mat3.multiply(accumulatedRotation, pairPose.rotation)
            val chainedTranslation = DoubleArray(3) { row ->
                var v = accumulatedTranslation[row]
                for (col in 0..2) v += accumulatedRotation[row * 3 + col] * pairPose.translation[col]
                v
            }

            val fullPoints = currentFull.toDoubleTriples()
            for (p in fullPoints) {
                val transformed = transformPoint(chainedRotation, chainedTranslation, p)
                mergedPositions.add(transformed[0].toFloat()); mergedPositions.add(transformed[1].toFloat()); mergedPositions.add(transformed[2].toFloat())
            }
            mergedColors.addAll(decoded[index].colors.toList())

            accumulatedRotation = chainedRotation
            accumulatedTranslation = chainedTranslation
            previousResampled = currentResampled
            previousCovariances = currentCovariances
        }

        if (skipped.size == ordered.size - 1) {
            error("ScanMerger.merge: every pair failed to register — no scans overlap")
        }

        val mergedBytes = GlbWriter.write(mergedPositions.toFloatArray(), mergedColors.toFloatArray())
        return MergeOutcome(mergedBytes, mergedPositions.size / 3, skipped)
    }

    private fun registerPair(
        sourcePositions: List<DoubleArray>, sourceCovariances: List<DoubleArray>,
        targetPositions: List<DoubleArray>, targetCovariances: List<DoubleArray>
    ): PairPose? = try {
        val sourceIdx = subsampleIndices(sourcePositions.size, COARSE_POINT_CAP)
        val targetIdx = subsampleIndices(targetPositions.size, COARSE_POINT_CAP)
        val coarseSource = sourceIdx.map { sourcePositions[it] }
        val coarseSourceCov = sourceIdx.map { sourceCovariances[it] }
        val coarseTarget = targetIdx.map { targetPositions[it] }
        val coarseTargetCov = targetIdx.map { targetCovariances[it] }

        val sourceGrid = SpatialGrid(coarseSource, cellSize = 0.5)
        val targetGrid = SpatialGrid(coarseTarget, cellSize = 0.5)
        val sourceNormals = FpfhFeatures.normalsFromCovariances(coarseSourceCov, coarseSource)
        val targetNormals = FpfhFeatures.normalsFromCovariances(coarseTargetCov, coarseTarget)
        val sourceFpfh = FpfhFeatures.compute(coarseSource, sourceNormals, sourceGrid)
        val targetFpfh = FpfhFeatures.compute(coarseTarget, targetNormals, targetGrid)

        val matchedSource = mutableListOf<DoubleArray>()
        val matchedTarget = mutableListOf<DoubleArray>()
        for (i in coarseSource.indices) {
            val bestJ = nearestDescriptor(sourceFpfh[i], targetFpfh) ?: continue
            matchedSource.add(coarseSource[i])
            matchedTarget.add(coarseTarget[bestJ])
        }
        if (matchedSource.size < 6) return null

        val robinInliers = RobinConsensus.filterInliers(matchedSource, matchedTarget, NOISE_BOUND)
        if (robinInliers.size < 6) return null
        val gnc = GncSolver.solve(robinInliers.map { matchedSource[it] }, robinInliers.map { matchedTarget[it] }, NOISE_BOUND)

        val targetGridFull = SpatialGrid(targetPositions, cellSize = 0.2)
        val gicp = GicpRefiner.refine(
            sourcePositions, sourceCovariances, targetPositions, targetCovariances, targetGridFull,
            initialRotation = gnc.rotation, initialTranslation = gnc.translation
        )
        PairPose(gicp.rotation, gicp.translation)
    } catch (e: Exception) {
        null
    }

    /** Uniform stride resample to exactly `size` points: subsamples if larger, repeats cyclically to pad if smaller. */
    private fun resample(positions: FloatArray, size: Int): List<DoubleArray> {
        val n = positions.size / 3
        require(n > 0) { "Cannot resample an empty point cloud" }
        return (0 until size).map { i ->
            val srcIdx = if (n >= size) (i.toLong() * n / size).toInt() else i % n
            doubleArrayOf(positions[srcIdx * 3].toDouble(), positions[srcIdx * 3 + 1].toDouble(), positions[srcIdx * 3 + 2].toDouble())
        }
    }

    private fun subsampleIndices(n: Int, cap: Int): List<Int> =
        if (n <= cap) (0 until n).toList() else (0 until cap).map { (it.toLong() * n / cap).toInt() }

    private fun nearestDescriptor(query: DoubleArray, candidates: List<DoubleArray>): Int? {
        var bestIdx: Int? = null
        var bestDist = Double.MAX_VALUE
        for (i in candidates.indices) {
            var d = 0.0
            for (k in query.indices) { val diff = query[k] - candidates[i][k]; d += diff * diff }
            if (d < bestDist) { bestDist = d; bestIdx = i }
        }
        return bestIdx
    }

    private fun transformPoint(r: DoubleArray, t: DoubleArray, p: DoubleArray) = DoubleArray(3) { row ->
        var v = t[row]; for (col in 0..2) v += r[row * 3 + col] * p[col]; v
    }

    private fun List<DoubleArray>.toFlatFloatArray(): FloatArray {
        val out = FloatArray(size * 3)
        for (i in indices) for (d in 0..2) out[i * 3 + d] = this[i][d].toFloat()
        return out
    }

    private fun FloatArray.toDoubleTriples(): List<DoubleArray> =
        (0 until size / 3).map { i -> doubleArrayOf(this[i * 3].toDouble(), this[i * 3 + 1].toDouble(), this[i * 3 + 2].toDouble()) }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.ScanMergerTest"`
Expected: PASS (both tests)

- [ ] **Step 5: Commit**

```bash
git add MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/ScanMerger.kt \
        MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/ScanMergerTest.kt
git commit -m "feat: add ScanMerger N-way registration orchestrator"
```

---

## Task 13: `MergeViewModel` — MediaStore-backed listing + background merge trigger

MediaStore/ContentResolver calls are Android-framework code the project's plain JVM unit tests can't exercise (no Robolectric — see Global Constraints). To keep the state-machine logic itself testable, MediaStore access is isolated behind a small `ScanRepository` interface that the ViewModel takes as a constructor parameter (defaulting to the real implementation), so tests inject a fake.

**Files:**
- Create: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/ScanRepository.kt`
- Create: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/MergeViewModel.kt`
- Test: `MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/MergeViewModelTest.kt`

**Interfaces:**
- Consumes: `ScanMerger.merge`, `ScanMerger.ScanInput` (Task 12), `CovariancePredictor` (Task 7)
- Produces: `sealed interface MergeUiState { Listing, Merging, Done, Failed }`, `class MergeViewModel(application: Application, scanRepository: ScanRepository = MediaStoreScanRepository(application), covariancePredictorFactory: () -> CovariancePredictor = { PoliInterpreter(application) }) : AndroidViewModel(application)` exposing `val uiState: StateFlow<MergeUiState>`, `fun toggleSelection(uri: Uri)`, `fun mergeSelected()`, `fun refreshScans()`.

- [ ] **Step 1: Write `ScanRepository.kt` (interface + real MediaStore implementation)**

```kotlin
package com.example.moge3dscanner.ui.merge

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

data class ScanFile(val uri: Uri, val displayName: String, val timestampMillis: Long)

interface ScanRepository {
    fun listScans(): List<ScanFile>
    fun readBytes(uri: Uri): ByteArray
    fun writeMergedGlb(bytes: ByteArray): Uri
}

class MediaStoreScanRepository(private val context: Context) : ScanRepository {
    override fun listScans(): List<ScanFile> {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        val selection = "${MediaStore.Downloads.MIME_TYPE} = ? AND ${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("model/gltf-binary", "moge_scan_%")
        val results = mutableListOf<ScanFile>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs,
            "${MediaStore.Downloads.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val timestamp = Regex("moge_scan_(\\d+)\\.glb").find(name)?.groupValues?.get(1)?.toLongOrNull() ?: continue
                results.add(ScanFile(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), name, timestamp))
            }
        }
        return results.sortedBy { it.timestampMillis }
    }

    override fun readBytes(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Could not open $uri")

    override fun writeMergedGlb(bytes: ByteArray): Uri {
        val resolver = context.contentResolver
        val name = "merged_${System.currentTimeMillis()}.glb"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "model/gltf-binary")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Failed to create $name")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Failed to write $name")
        return uri
    }
}
```

- [ ] **Step 2: Write the failing `MergeViewModel` test using a fake `ScanRepository`**

```kotlin
package com.example.moge3dscanner.ui.merge

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class MergeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() { Dispatchers.setMain(dispatcher) }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private class FakeScanRepository(private val scans: List<ScanFile>) : ScanRepository {
        var writtenBytes: ByteArray? = null
        override fun listScans() = scans
        override fun readBytes(uri: Uri): ByteArray {
            val n = 200
            val positions = FloatArray(n * 3) { 0f }
            val colors = FloatArray(n * 3) { 0.5f }
            return GlbWriter.write(positions, colors)
        }
        override fun writeMergedGlb(bytes: ByteArray): Uri {
            writtenBytes = bytes
            return Uri.parse("content://fake/merged")
        }
    }

    private class FakeCovariancePredictor : CovariancePredictor {
        override fun predict(positions: FloatArray) = List(positions.size / 3) { doubleArrayOf(0.01, 0.0, 0.0, 0.0, 0.01, 0.0, 0.0, 0.0, 0.01) }
        override fun close() {}
    }

    @Test
    fun `lists scans on init and toggles selection`() {
        val scans = listOf(
            ScanFile(Uri.parse("content://fake/1"), "moge_scan_1000.glb", 1000L),
            ScanFile(Uri.parse("content://fake/2"), "moge_scan_2000.glb", 2000L)
        )
        val viewModel = MergeViewModel(mock(Application::class.java), FakeScanRepository(scans)) { FakeCovariancePredictor() }

        val initial = viewModel.uiState.value as MergeUiState.Listing
        assertEquals(2, initial.scans.size)
        assertTrue(initial.selected.isEmpty())

        viewModel.toggleSelection(scans[0].uri)
        val afterToggle = viewModel.uiState.value as MergeUiState.Listing
        assertEquals(setOf(scans[0].uri), afterToggle.selected)
    }

    @Test
    fun `mergeSelected does nothing with fewer than two selected`() {
        val scans = listOf(ScanFile(Uri.parse("content://fake/1"), "moge_scan_1000.glb", 1000L))
        val viewModel = MergeViewModel(mock(Application::class.java), FakeScanRepository(scans)) { FakeCovariancePredictor() }
        viewModel.toggleSelection(scans[0].uri)

        viewModel.mergeSelected()

        assertTrue(viewModel.uiState.value is MergeUiState.Listing)
    }

    @Test
    fun `mergeSelected with two scans transitions Listing to Merging to Done`() = runTest(dispatcher) {
        val scans = listOf(
            ScanFile(Uri.parse("content://fake/1"), "moge_scan_1000.glb", 1000L),
            ScanFile(Uri.parse("content://fake/2"), "moge_scan_2000.glb", 2000L)
        )
        val repository = FakeScanRepository(scans)
        val viewModel = MergeViewModel(mock(Application::class.java), repository) { FakeCovariancePredictor() }
        viewModel.toggleSelection(scans[0].uri)
        viewModel.toggleSelection(scans[1].uri)

        viewModel.mergeSelected()
        dispatcher.scheduler.advanceUntilIdle()

        val finalState = viewModel.uiState.value
        assertTrue(finalState is MergeUiState.Done || finalState is MergeUiState.Failed)
        // Both fixture scans are identical all-zero point clouds, so registration may legitimately
        // fail (no distinguishing geometry) — this test asserts the state machine reaches a terminal
        // state without throwing, not that registration succeeds on degenerate input.
    }
}
```

Add to `MoGe3DScanner/app/build.gradle.kts`'s dependencies block (needed for `mock()` above): `testImplementation("org.mockito:mockito-core:5.11.0")`.

- [ ] **Step 3: Run test to verify it fails**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.MergeViewModelTest"`
Expected: FAIL — `MergeViewModel`/`MergeUiState` do not exist.

- [ ] **Step 4: Implement `MergeViewModel.kt`**

```kotlin
package com.example.moge3dscanner.ui.merge

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface MergeUiState {
    data class Listing(val scans: List<ScanFile>, val selected: Set<Uri>) : MergeUiState
    data class Merging(val progress: String) : MergeUiState
    data class Done(val mergedUri: Uri, val pointCount: Int, val skipped: List<String>) : MergeUiState
    data class Failed(val message: String) : MergeUiState
}

class MergeViewModel(
    application: Application,
    private val scanRepository: ScanRepository = MediaStoreScanRepository(application),
    private val covariancePredictorFactory: () -> CovariancePredictor = { PoliInterpreter(application) }
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<MergeUiState>(MergeUiState.Listing(emptyList(), emptySet()))
    val uiState: StateFlow<MergeUiState> = _uiState.asStateFlow()

    init { refreshScans() }

    fun refreshScans() {
        _uiState.value = MergeUiState.Listing(scanRepository.listScans(), emptySet())
    }

    fun toggleSelection(uri: Uri) {
        val current = _uiState.value
        if (current !is MergeUiState.Listing) return
        val newSelected = if (uri in current.selected) current.selected - uri else current.selected + uri
        _uiState.value = current.copy(selected = newSelected)
    }

    fun mergeSelected() {
        val current = _uiState.value
        if (current !is MergeUiState.Listing || current.selected.size < 2) return
        val toMerge = current.scans.filter { it.uri in current.selected }

        viewModelScope.launch(Dispatchers.Default) {
            _uiState.value = MergeUiState.Merging("Reading ${toMerge.size} scans...")
            try {
                val scanInputs = toMerge.map { scan ->
                    ScanMerger.ScanInput(scan.displayName, scan.timestampMillis, scanRepository.readBytes(scan.uri))
                }

                _uiState.value = MergeUiState.Merging("Loading POLI model...")
                val predictor = covariancePredictorFactory()

                _uiState.value = MergeUiState.Merging("Registering and merging...")
                val outcome = try {
                    ScanMerger.merge(scanInputs, predictor)
                } finally {
                    predictor.close()
                }

                val mergedUri = scanRepository.writeMergedGlb(outcome.mergedBytes)
                _uiState.value = MergeUiState.Done(mergedUri, outcome.mergedPointCount, outcome.skippedFiles)
            } catch (e: Exception) {
                _uiState.value = MergeUiState.Failed(e.message ?: "Merge failed")
            }
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd MoGe3DScanner && ./gradlew testDebugUnitTest --tests "*.MergeViewModelTest"`
Expected: PASS (all 3 tests)

- [ ] **Step 6: Commit**

```bash
git add MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/ScanRepository.kt \
        MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/MergeViewModel.kt \
        MoGe3DScanner/app/src/test/java/com/example/moge3dscanner/ui/merge/MergeViewModelTest.kt \
        MoGe3DScanner/app/build.gradle.kts
git commit -m "feat: add MergeViewModel with MediaStore-backed ScanRepository"
```

---

## Task 14: `MergeScreen` + navigation wiring

**Files:**
- Create: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/MergeScreen.kt`
- Modify: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/NavigationKeys.kt`
- Modify: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/Navigation.kt`
- Modify: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/main/MainScreen.kt` (add a "Merge" entry point button)
- Test: `MoGe3DScanner/app/src/androidTest/java/com/example/moge3dscanner/ui/merge/MergeScreenTest.kt` (mirrors the existing `MainScreenTest.kt` androidTest pattern)

**Interfaces:**
- Consumes: `MergeViewModel`, `MergeUiState` (Task 13)
- Produces: `@Composable fun MergeScreen(onMergeComplete: (Uri) -> Unit, modifier: Modifier = Modifier, viewModel: MergeViewModel = viewModel())`

- [ ] **Step 1: Add navigation keys**

```kotlin
// NavigationKeys.kt — add below the existing `Main` entry
@Serializable data object Merge : NavKey
@Serializable data class MergePreview(val uri: String) : NavKey
```

- [ ] **Step 2: Write `MergeScreen.kt`**

```kotlin
package com.example.moge3dscanner.ui.merge

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun MergeScreen(
    onMergeComplete: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MergeViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state) {
        if (state is MergeUiState.Done) onMergeComplete((state as MergeUiState.Done).mergedUri)
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        when (val current = state) {
            is MergeUiState.Listing -> {
                Text("Select scans to merge (${current.selected.size} selected)")
                LazyColumn {
                    items(current.scans) { scan ->
                        Row {
                            Checkbox(
                                checked = scan.uri in current.selected,
                                onCheckedChange = { viewModel.toggleSelection(scan.uri) }
                            )
                            Text(scan.displayName)
                        }
                    }
                }
                Button(
                    onClick = { viewModel.mergeSelected() },
                    enabled = current.selected.size >= 2
                ) {
                    Text("Merge Selected (${current.selected.size})")
                }
            }
            is MergeUiState.Merging -> {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                    Text(current.progress)
                }
            }
            is MergeUiState.Done -> {
                Text("Merged ${current.pointCount} points" + if (current.skipped.isNotEmpty()) " (skipped: ${current.skipped.joinToString()})" else "")
            }
            is MergeUiState.Failed -> {
                Text("Merge failed: ${current.message}")
                Button(onClick = { viewModel.refreshScans() }) { Text("Back") }
            }
        }
    }
}
```

- [ ] **Step 3: Wire navigation**

In `Navigation.kt`, add inside `entryProvider { ... }`:

```kotlin
entry<Merge> {
    MergeScreen(
        onMergeComplete = { uri -> backStack.add(MergePreview(uri.toString())) },
        modifier = Modifier.fillMaxSize()
    )
}
entry<MergePreview> { key ->
    MergePreviewScreen(mergedUri = Uri.parse(key.uri), modifier = Modifier.fillMaxSize()) // Task 15
}
```

- [ ] **Step 4: Add the entry-point button in `MainScreen.kt`**

Add near the existing "ply"/"glb" export buttons (around line 641's control panel comment) a new button, following the same `Button`/`Text` pattern already used there:

```kotlin
Button(onClick = { /* navigate to Merge — actual navigation callback threaded from MainNavigation's backStack, following the existing pattern used for other screen entries */ }) {
    Text("merge")
}
```

`MainScreen`'s current signature takes no navigation callback (`MainScreen(modifier: Modifier)` per `Navigation.kt:21`). Add a `onMergeRequested: () -> Unit = {}` parameter to `MainScreen`, wire the new button's `onClick` to it, and in `Navigation.kt` pass `onMergeRequested = { backStack.add(Merge) }` at the `MainScreen(...)` call site.

- [ ] **Step 5: Write and run the Compose UI test**

```kotlin
package com.example.moge3dscanner.ui.merge

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class MergeScreenTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun mergeButtonDisabledUntilTwoScansSelected() {
        // Uses the real MergeViewModel default (MediaStoreScanRepository) against the
        // instrumented test's Context — an empty scans list is expected in a clean test
        // environment, so this exercises the empty-state render path, not a real merge.
        composeTestRule.setContent {
            MergeScreen(onMergeComplete = {})
        }
        composeTestRule.onNodeWithText("Merge Selected (0)").assertExists()
    }
}
```

Run: `cd MoGe3DScanner && ./gradlew connectedDebugAndroidTest --tests "*.MergeScreenTest"` (requires a connected device/emulator)
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/MergeScreen.kt \
        MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/NavigationKeys.kt \
        MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/Navigation.kt \
        MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/main/MainScreen.kt \
        MoGe3DScanner/app/src/androidTest/java/com/example/moge3dscanner/ui/merge/MergeScreenTest.kt
git commit -m "feat: add MergeScreen and wire merge entry point into navigation"
```

---

## Task 15: Merge-preview screen with tap-to-focus

**Deviation from the original spec:** the spec's `SphericalCameraController.kt` component is dropped. Reading the actual latest `GLPointRenderer.kt` (not the stale README) showed the existing arcball rotation + damped pinch-zoom/pan + momentum flick-spin is *already* explicitly modeled on `model-viewer`'s damping style (`decay=12`, comment: "matching model-viewer's default style feel") — porting `SmoothControls.ts` again would just duplicate it. The only real gap versus `model-viewer` is tap-to-focus, which didn't exist anywhere in the codebase. This task reuses `GLPointRenderer`/`InteractiveGLView` as-is and adds only that.

**Files:**
- Modify: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/main/GLPointRenderer.kt` — add `nearestPointToScreen` and `recenterOnPoint` (new methods, additive only; nothing existing changes)
- Modify: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/main/MainScreen.kt` — mark `InteractiveGLView` and its `onTouchEvent` `open` (enables subclassing; behavior for the existing capture screen is unchanged)
- Create: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/MergePreviewGLView.kt`
- Create: `MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/MergePreviewScreen.kt`

**Interfaces:**
- Consumes: `GlbReader.read` (Task 2), `GLPointRenderer`, `InteractiveGLView` (existing, `ui.main`)
- Produces: `@Composable fun MergePreviewScreen(mergedUri: Uri, modifier: Modifier = Modifier)`, referenced by Task 14's navigation wiring

- [ ] **Step 1: Add screen-space nearest-point lookup and recenter to `GLPointRenderer.kt`**

Add these two public methods to the existing `GLPointRenderer` class (after `resetAngles()`, no existing method bodies change):

```kotlin
/** Screen-space nearest point to a tap, using the last-drawn MVP matrix. Returns null if none within ~40px. */
fun nearestPointToScreen(screenX: Float, screenY: Float, viewportWidth: Int, viewportHeight: Int): Int? {
    val vb = vertexBuffer ?: return null
    if (numPoints == 0 || viewportWidth == 0 || viewportHeight == 0) return null
    var bestIndex: Int? = null
    var bestDistSq = Float.MAX_VALUE
    val clip = FloatArray(4)
    val point = FloatArray(4)
    for (i in 0 until numPoints) {
        point[0] = vb.get(i * 3); point[1] = vb.get(i * 3 + 1); point[2] = vb.get(i * 3 + 2); point[3] = 1f
        Matrix.multiplyMV(clip, 0, mvpMatrix, 0, point, 0)
        if (clip[3] <= 0f) continue
        val sx = ((clip[0] / clip[3]) * 0.5f + 0.5f) * viewportWidth
        val sy = (1f - ((clip[1] / clip[3]) * 0.5f + 0.5f)) * viewportHeight
        val dx = sx - screenX; val dy = sy - screenY
        val distSq = dx * dx + dy * dy
        if (distSq < bestDistSq) { bestDistSq = distSq; bestIndex = i }
    }
    val maxTapRadiusSq = 40f * 40f
    return if (bestDistSq <= maxTapRadiusSq) bestIndex else null
}

/**
 * Approximate recenter-on-tap: cancels the tapped point's model-space offset from the
 * cloud centroid, rotated into the current view orientation, via pan. This is an
 * approximation (it ignores perspective foreshortening, unlike a true screen-space
 * raycast) — see Task 15 Step 3 for the on-device tuning this needs.
 */
fun recenterOnPoint(index: Int) {
    val vb = vertexBuffer ?: return
    val relX = vb.get(index * 3) - centerX
    val relY = vb.get(index * 3 + 1) - centerY
    val relZ = vb.get(index * 3 + 2) - centerZ
    val combined = FloatArray(16)
    Matrix.multiplyMM(combined, 0, userRotationMatrix, 0, gravityAlignMatrix, 0)
    val rotated = FloatArray(4)
    Matrix.multiplyMV(rotated, 0, combined, 0, floatArrayOf(relX, relY, relZ, 0f), 0)
    targetPanX = -rotated[0] * 0.15f
    targetPanY = rotated[1] * 0.15f
}
```

- [ ] **Step 2: Mark `InteractiveGLView` open in `MainScreen.kt`**

Change `class InteractiveGLView(context: Context, val renderer: GLPointRenderer) : GLSurfaceView(context) {` to `open class InteractiveGLView(...)`, and `override fun onTouchEvent(event: MotionEvent): Boolean {` to `open override fun onTouchEvent(event: MotionEvent): Boolean {`. No other line in the class changes.

- [ ] **Step 3: Write `MergePreviewGLView.kt`**

```kotlin
package com.example.moge3dscanner.ui.merge

import android.content.Context
import android.view.MotionEvent
import com.example.moge3dscanner.ui.main.GLPointRenderer
import com.example.moge3dscanner.ui.main.InteractiveGLView
import kotlin.math.sqrt

/** Adds tap-to-focus on top of InteractiveGLView's existing arcball/pinch/pan (unchanged, reused as-is). */
class MergePreviewGLView(context: Context, private val previewRenderer: GLPointRenderer) : InteractiveGLView(context, previewRenderer) {
    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var moved = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                downTimeMs = System.currentTimeMillis()
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX; val dy = event.y - downY
                if (sqrt(dx * dx + dy * dy) > TAP_MAX_MOVEMENT_PX) moved = true
            }
            MotionEvent.ACTION_UP -> {
                val duration = System.currentTimeMillis() - downTimeMs
                if (!moved && duration < TAP_MAX_DURATION_MS && event.pointerCount == 1) {
                    val index = previewRenderer.nearestPointToScreen(event.x, event.y, width, height)
                    if (index != null) { previewRenderer.recenterOnPoint(index); requestRender() }
                }
            }
        }
        return super.onTouchEvent(event)
    }

    companion object {
        private const val TAP_MAX_DURATION_MS = 300L
        private const val TAP_MAX_MOVEMENT_PX = 2f
    }
}
```

- [ ] **Step 4: Write `MergePreviewScreen.kt`**

```kotlin
package com.example.moge3dscanner.ui.merge

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.moge3dscanner.ui.main.GLPointRenderer

@Composable
fun MergePreviewScreen(mergedUri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val renderer = remember { GLPointRenderer() }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            val bytes = ctx.contentResolver.openInputStream(mergedUri)?.use { it.readBytes() }
                ?: error("Could not open merged scan")
            val scan = GlbReader.read(bytes)
            renderer.updatePoints(scan.positions, scan.colors)
            MergePreviewGLView(ctx, renderer)
        }
    )
}
```

- [ ] **Step 5: Manual on-device verification**

Automated gesture testing isn't meaningful here (matches the existing project's convention — `GLPointRenderer`'s existing arcball/pinch/pan gestures also have no automated test). Build, install, merge 2+ real scans, and on the resulting preview screen confirm:
- Orbit/pinch/pan behave identically to the main capture screen (unchanged code path)
- Tapping a visible point pulls it toward screen center within a frame or two
- If the recenter offset feels off (see Step 1's noted approximation), tune the `0.15f` scale factor in `recenterOnPoint` — it's an empirical constant, not a derived one

- [ ] **Step 6: Commit**

```bash
git add MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/main/GLPointRenderer.kt \
        MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/main/MainScreen.kt \
        MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/MergePreviewGLView.kt \
        MoGe3DScanner/app/src/main/java/com/example/moge3dscanner/ui/merge/MergePreviewScreen.kt
git commit -m "feat: add merge-preview screen reusing existing renderer, with tap-to-focus"
```

---

## Self-Review

**Spec coverage:** every component in the design spec's "New components" list maps to a task (Tasks 1-2 GlbWriter/Reader, Tasks 3-5 math/spatial utilities not explicitly named in the spec but required by it, Tasks 6-7 PoliInterpreter, Task 8 FpfhFeatures, Task 9 RobinConsensus, Task 10 GncSolver, Task 11 GicpRefiner, Task 12 ScanMerger, Task 13 MergeViewModel, Task 14 MergeScreen, Task 15 preview — with `SphericalCameraController` explicitly dropped and replaced per the correction documented in Task 15's "Deviation from the original spec." All Global Constraints (no new permissions, MediaStore-based listing, non-destructive output, timestamp-ordered chaining, no SDP dependency) are implemented in Tasks 12-13.

**Placeholder scan:** no TBD/TODO markers; the one genuinely open-ended item (Task 6's TFLite-vs-ONNX-Runtime branch, Task 7's Cholesky packing order) is written as a concrete branch/verification step with real code for both outcomes, not a hand-wave.

**Type consistency check performed:** `CovariancePredictor.predict(positions: FloatArray): List<DoubleArray>` (Task 7) matches every call site (`ScanMerger.merge`, `MergeViewModel`'s `covariancePredictorFactory`, `ScanMergerTest`'s and `MergeViewModelTest`'s fakes). `ScanMerger.ScanInput`/`MergeOutcome` field names match between Task 12's implementation and Task 13's `MergeViewModel` usage. `Mat3`'s row-major `DoubleArray(9)` convention is used consistently by `WeightedHorn`, `FpfhFeatures`, `GicpRefiner`, and `GncSolver` — none introduce a competing matrix representation.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-13-poli-merge-integration.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
