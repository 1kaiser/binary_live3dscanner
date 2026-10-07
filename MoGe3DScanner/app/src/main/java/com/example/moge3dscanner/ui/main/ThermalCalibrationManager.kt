package com.example.moge3dscanner.ui.main

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages 4-corner perspective calibration and rotation for overlaying
 * low-resolution UVC thermal imagery onto high-resolution RGB depth maps.
 */
data class ThermalCalibration(
    val timestamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()),
    val thermalRotationDegrees: Int = 180,
    val isFlippedHorizontally: Boolean = true,
    val cornerA: Pair<Float, Float> = Pair(0.15f, 0.20f), // Top-Left (u, v)
    val cornerB: Pair<Float, Float> = Pair(0.85f, 0.20f), // Top-Right (u, v)
    val cornerC: Pair<Float, Float> = Pair(0.85f, 0.80f), // Bottom-Right (u, v)
    val cornerD: Pair<Float, Float> = Pair(0.15f, 0.80f)  // Bottom-Left (u, v)
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("timestamp", timestamp)
        json.put("thermal_rotation_degrees", thermalRotationDegrees)
        json.put("is_flipped_horizontally", isFlippedHorizontally)
        
        val cornersObj = JSONObject()
        cornersObj.put("A", JSONObject().apply { put("u", cornerA.first); put("v", cornerA.second) })
        cornersObj.put("B", JSONObject().apply { put("u", cornerB.first); put("v", cornerB.second) })
        cornersObj.put("C", JSONObject().apply { put("u", cornerC.first); put("v", cornerC.second) })
        cornersObj.put("D", JSONObject().apply { put("u", cornerD.first); put("v", cornerD.second) })
        json.put("corners_normalized", cornersObj)
        
        return json.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): ThermalCalibration? {
            return try {
                val json = JSONObject(jsonStr)
                val ts = json.optString("timestamp", "")
                val rot = json.optInt("thermal_rotation_degrees", 180)
                val flipH = json.optBoolean("is_flipped_horizontally", true)
                val corners = json.getJSONObject("corners_normalized")
                
                val a = corners.getJSONObject("A")
                val b = corners.getJSONObject("B")
                val c = corners.getJSONObject("C")
                val d = corners.getJSONObject("D")
                
                ThermalCalibration(
                    timestamp = ts,
                    thermalRotationDegrees = rot,
                    isFlippedHorizontally = flipH,
                    cornerA = Pair(a.getDouble("u").toFloat(), a.getDouble("v").toFloat()),
                    cornerB = Pair(b.getDouble("u").toFloat(), b.getDouble("v").toFloat()),
                    cornerC = Pair(c.getDouble("u").toFloat(), c.getDouble("v").toFloat()),
                    cornerD = Pair(d.getDouble("u").toFloat(), d.getDouble("v").toFloat())
                )
            } catch (e: Exception) {
                Log.e("ThermalCalibration", "Failed to parse calibration JSON", e)
                null
            }
        }
    }
}

object ThermalCalibrationManager {
    private const val PREFS_NAME = "moge_thermal_calibration_v2"
    private const val KEY_ACTIVE_CALIBRATION = "active_calibration_json"
    private const val TAG = "ThermalCalibration"

    private var cachedCalibration: ThermalCalibration? = null

    fun getActiveCalibration(context: Context): ThermalCalibration {
        cachedCalibration?.let { return it }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_ACTIVE_CALIBRATION, null)
        if (jsonStr != null) {
            val cal = ThermalCalibration.fromJson(jsonStr)
            if (cal != null) {
                cachedCalibration = cal
                return cal
            }
        }
        val defaultCal = ThermalCalibration()
        cachedCalibration = defaultCal
        return defaultCal
    }

    fun saveCalibration(context: Context, calibration: ThermalCalibration): Boolean {
        return try {
            cachedCalibration = calibration
            val jsonStr = calibration.toJson()

            // 1. Save to SharedPreferences for active runtime recall
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_ACTIVE_CALIBRATION, jsonStr).apply()

            // 2. Save timestamped JSON to /sdcard/Download/
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "moge_calibration_$ts.json"

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(jsonStr.toByteArray(Charsets.UTF_8))
                }
            }

            Log.i(TAG, "Calibration saved successfully: $fileName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save calibration", e)
            false
        }
    }

    /**
     * Applies rotation and horizontal flipping to the thermal bitmap.
     */
    fun getTransformedThermalBitmap(
        thermalBitmap: Bitmap,
        calibration: ThermalCalibration
    ): Bitmap {
        val needsRotation = calibration.thermalRotationDegrees % 360 != 0
        val needsFlip = calibration.isFlippedHorizontally
        if (!needsRotation && !needsFlip) return thermalBitmap

        val matrix = Matrix().apply {
            if (needsRotation) {
                postRotate(calibration.thermalRotationDegrees.toFloat())
            }
            if (needsFlip) {
                postScale(-1f, 1f)
            }
        }
        return Bitmap.createBitmap(
            thermalBitmap, 0, 0,
            thermalBitmap.width, thermalBitmap.height,
            matrix, true
        )
    }

    /**
     * Warps and blends the rotated thermal bitmap onto the high-resolution RGB bitmap
     * according to the 4-corner perspective calibration quad ABCD.
     */
    fun createFusedColorBitmap(
        rgbBitmap: Bitmap,
        thermalBitmap: Bitmap?,
        calibration: ThermalCalibration,
        alpha: Float = 1.0f
    ): Bitmap {
        if (thermalBitmap == null) return rgbBitmap

        val outWidth = rgbBitmap.width
        val outHeight = rgbBitmap.height
        val fused = rgbBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(fused)

        // 1. Rotate and/or flip thermal image if required
        val transformedThermal = getTransformedThermalBitmap(thermalBitmap, calibration)

        // 2. Set up 4-corner perspective transform matrix (poly-to-poly)
        val thW = transformedThermal.width.toFloat()
        val thH = transformedThermal.height.toFloat()

        val src = floatArrayOf(
            0f, 0f,         // A (Top-Left)
            thW, 0f,        // B (Top-Right)
            thW, thH,       // C (Bottom-Right)
            0f, thH         // D (Bottom-Left)
        )

        val dst = floatArrayOf(
            calibration.cornerA.first * outWidth, calibration.cornerA.second * outHeight,
            calibration.cornerB.first * outWidth, calibration.cornerB.second * outHeight,
            calibration.cornerC.first * outWidth, calibration.cornerC.second * outHeight,
            calibration.cornerD.first * outWidth, calibration.cornerD.second * outHeight
        )

        val warpMatrix = Matrix()
        val success = warpMatrix.setPolyToPoly(src, 0, dst, 0, 4)

        if (success) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                this.alpha = (alpha * 255).toInt().coerceIn(0, 255)
            }
            canvas.drawBitmap(transformedThermal, warpMatrix, paint)
        } else {
            // Fallback: draw centered if degenerate quad
            canvas.drawBitmap(transformedThermal, 0f, 0f, null)
        }

        return fused
    }

    /**
     * Warps the thermal heatmap onto an isolated neutral dark background matching the RGB resolution
     * according to the 4-corner perspective calibration quad ABCD.
     */
    fun createPureThermalColorBitmap(
        width: Int,
        height: Int,
        thermalBitmap: Bitmap?,
        calibration: ThermalCalibration
    ): Bitmap {
        val pureThermal = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(pureThermal)
        canvas.drawColor(android.graphics.Color.BLACK) // Pure black background for isolated thermal rendering

        if (thermalBitmap == null) return pureThermal

        val transformedThermal = getTransformedThermalBitmap(thermalBitmap, calibration)

        val thW = transformedThermal.width.toFloat()
        val thH = transformedThermal.height.toFloat()

        val src = floatArrayOf(
            0f, 0f,
            thW, 0f,
            thW, thH,
            0f, thH
        )

        val dst = floatArrayOf(
            calibration.cornerA.first * width, calibration.cornerA.second * height,
            calibration.cornerB.first * width, calibration.cornerB.second * height,
            calibration.cornerC.first * width, calibration.cornerC.second * height,
            calibration.cornerD.first * width, calibration.cornerD.second * height
        )

        val warpMatrix = Matrix()
        val success = warpMatrix.setPolyToPoly(src, 0, dst, 0, 4)

        if (success) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(transformedThermal, warpMatrix, paint)
        } else {
            canvas.drawBitmap(transformedThermal, 0f, 0f, null)
        }

        return pureThermal
    }

    /**
     * Filters a dense 3D point cloud and corresponding color buffer so that only points
     * lying strictly within the calibrated 4-corner perspective quad ABCD are retained.
     */
    fun filterPointsInsideQuad(
        positions: FloatArray,
        colors: FloatArray,
        stride: Int,
        isMoge3: Boolean,
        calibration: ThermalCalibration
    ): Pair<FloatArray, FloatArray> {
        val outDim = if (isMoge3) 672 else 518
        val stepsX = (outDim + stride - 1) / stride
        val numPoints = positions.size / 3

        val corners = arrayOf(
            calibration.cornerA,
            calibration.cornerB,
            calibration.cornerC,
            calibration.cornerD
        )

        var insideCount = 0
        val insideFlags = BooleanArray(numPoints)
        for (j in 0 until numPoints) {
            val gridX = j % stepsX
            val gridY = j / stepsX
            val u = (gridX * stride).toFloat() / outDim
            val v = (gridY * stride).toFloat() / outDim

            var allPos = true
            var allNeg = true
            for (i in 0 until 4) {
                val c1 = corners[i]
                val c2 = corners[(i + 1) % 4]
                val cp = (c2.first - c1.first) * (v - c1.second) - (c2.second - c1.second) * (u - c1.first)
                if (cp < 0f) allPos = false
                if (cp > 0f) allNeg = false
            }
            val inside = allPos || allNeg
            insideFlags[j] = inside
            if (inside) insideCount++
        }

        if (insideCount == 0) {
            return Pair(positions, colors)
        }

        val prunedPos = FloatArray(insideCount * 3)
        val prunedCol = FloatArray(insideCount * 3)
        var outIdx = 0
        for (j in 0 until numPoints) {
            if (insideFlags[j]) {
                prunedPos[outIdx * 3]     = positions[j * 3]
                prunedPos[outIdx * 3 + 1] = positions[j * 3 + 1]
                prunedPos[outIdx * 3 + 2] = positions[j * 3 + 2]

                prunedCol[outIdx * 3]     = colors[j * 3]
                prunedCol[outIdx * 3 + 1] = colors[j * 3 + 1]
                prunedCol[outIdx * 3 + 2] = colors[j * 3 + 2]
                outIdx++
            }
        }

        return Pair(prunedPos, prunedCol)
    }
}
