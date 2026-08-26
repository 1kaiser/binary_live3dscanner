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
    val thermalRotationDegrees: Int = 90,
    val cornerA: Pair<Float, Float> = Pair(0.15f, 0.20f), // Top-Left (u, v)
    val cornerB: Pair<Float, Float> = Pair(0.85f, 0.20f), // Top-Right (u, v)
    val cornerC: Pair<Float, Float> = Pair(0.85f, 0.80f), // Bottom-Right (u, v)
    val cornerD: Pair<Float, Float> = Pair(0.15f, 0.80f)  // Bottom-Left (u, v)
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("timestamp", timestamp)
        json.put("thermal_rotation_degrees", thermalRotationDegrees)
        
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
                val rot = json.optInt("thermal_rotation_degrees", 90)
                val corners = json.getJSONObject("corners_normalized")
                
                val a = corners.getJSONObject("A")
                val b = corners.getJSONObject("B")
                val c = corners.getJSONObject("C")
                val d = corners.getJSONObject("D")
                
                ThermalCalibration(
                    timestamp = ts,
                    thermalRotationDegrees = rot,
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
    private const val PREFS_NAME = "moge_thermal_calibration"
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

        // 1. Rotate thermal image if required
        val rotatedThermal = if (calibration.thermalRotationDegrees % 360 != 0) {
            val rotMatrix = Matrix().apply {
                postRotate(calibration.thermalRotationDegrees.toFloat())
            }
            Bitmap.createBitmap(
                thermalBitmap, 0, 0,
                thermalBitmap.width, thermalBitmap.height,
                rotMatrix, true
            )
        } else {
            thermalBitmap
        }

        // 2. Set up 4-corner perspective transform matrix (poly-to-poly)
        val thW = rotatedThermal.width.toFloat()
        val thH = rotatedThermal.height.toFloat()

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
            canvas.drawBitmap(rotatedThermal, warpMatrix, paint)
        } else {
            // Fallback: draw centered if degenerate quad
            canvas.drawBitmap(rotatedThermal, 0f, 0f, null)
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
        canvas.drawColor(android.graphics.Color.rgb(247, 246, 242)) // Light neutral original background

        if (thermalBitmap == null) return pureThermal

        val rotatedThermal = if (calibration.thermalRotationDegrees % 360 != 0) {
            val rotMatrix = Matrix().apply {
                postRotate(calibration.thermalRotationDegrees.toFloat())
            }
            Bitmap.createBitmap(
                thermalBitmap, 0, 0,
                thermalBitmap.width, thermalBitmap.height,
                rotMatrix, true
            )
        } else {
            thermalBitmap
        }

        val thW = rotatedThermal.width.toFloat()
        val thH = rotatedThermal.height.toFloat()

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
            canvas.drawBitmap(rotatedThermal, warpMatrix, paint)
        } else {
            canvas.drawBitmap(rotatedThermal, 0f, 0f, null)
        }

        return pureThermal
    }
}
