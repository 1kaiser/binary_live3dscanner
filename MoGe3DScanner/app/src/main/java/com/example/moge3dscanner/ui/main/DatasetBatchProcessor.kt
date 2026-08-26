package com.example.moge3dscanner.ui.main

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Data class representing a recorded dataset folder.
 */
data class DatasetItem(
    val directory: File,
    val name: String,
    val frameCount: Int,
    val timestamp: Long
)

/**
 * Result bundle containing all 3 representations: Fused, Pure RGB, and Pure Thermal.
 */
data class TripleReconstructionResult(
    val fused: Pair<FloatArray, FloatArray>,
    val rgb: Pair<FloatArray, FloatArray>,
    val thermal: Pair<FloatArray, FloatArray>
)

/**
 * Post-Processing Engine for recorded datasets.
 * Performs offline batch monocular depth estimation on RGB frames,
 * projects synchronized radiometric thermal heatmaps using 4-corner homography,
 * and generates separate 3D Point Clouds (.glb / .ply) for:
 * 1. FUSED: Natural RGB context with Thermal region-of-interest overlay
 * 2. RGB: Pure optical camera colors
 * 3. THERMAL: Pure isolated thermal temperature false colors
 */
object DatasetBatchProcessor {

    private const val TAG = "DatasetBatchProcessor"

    fun listDatasets(context: Context): List<DatasetItem> {
        val baseDir = context.getExternalFilesDir("datasets") ?: return emptyList()
        val dirs = baseDir.listFiles { file -> file.isDirectory } ?: return emptyList()

        return dirs.map { dir ->
            val jpgFiles = dir.listFiles { f -> f.extension == "jpg" || f.name.endsWith("_rgb.jpg") || f.name.endsWith("_rgb.png") }
            val count = jpgFiles?.size ?: 0
            DatasetItem(
                directory = dir,
                name = dir.name,
                frameCount = count,
                timestamp = dir.lastModified()
            )
        }.sortedByDescending { it.timestamp }
    }

    suspend fun processDataset(
        context: Context,
        datasetItem: DatasetItem,
        interpreter: MogeInterpreter,
        onProgress: (current: Int, total: Int, status: String) -> Unit
    ): TripleReconstructionResult? = withContext(Dispatchers.Default) {
        try {
            val dir = datasetItem.directory
            val files = dir.listFiles() ?: return@withContext null

            // 1. Load Calibration
            val calFile = File(dir, "calibration.json")
            val calibration = if (calFile.exists()) {
                ThermalCalibration.fromJson(calFile.readText()) ?: ThermalCalibration()
            } else {
                ThermalCalibrationManager.getActiveCalibration(context)
            }

            // 2. Find and sort RGB frame files
            val rgbFiles = files.filter { f ->
                f.extension.lowercase() == "jpg" || f.name.endsWith("_rgb.png") || f.name.endsWith("_rgb.jpg")
            }.sortedBy { it.name }

            val totalFrames = rgbFiles.size
            if (totalFrames == 0) return@withContext null

            val fusedAccumulator = PointCloudAccumulator()
            val rgbAccumulator = PointCloudAccumulator()
            val thermalAccumulator = PointCloudAccumulator()

            for ((index, rgbFile) in rgbFiles.withIndex()) {
                withContext(Dispatchers.Main) {
                    onProgress(index + 1, totalFrames, "Processing frame ${index + 1}/$totalFrames (RGB + Thermal + Fused)...")
                }

                val prefix = rgbFile.name.substringBefore(".").substringBefore("_rgb")

                // A. Load RGB Frame
                val rgbBitmap = BitmapFactory.decodeFile(rgbFile.absolutePath) ?: continue

                // B. Load Thermal Frame if available
                val thermalFilePng = File(dir, "${prefix}_thermal.png")
                val thermalFileJpg = File(dir, "${prefix}_thermal.jpg")
                val thermalBitmap: Bitmap? = when {
                    thermalFilePng.exists() -> BitmapFactory.decodeFile(thermalFilePng.absolutePath)
                    thermalFileJpg.exists() -> BitmapFactory.decodeFile(thermalFileJpg.absolutePath)
                    else -> null
                }

                // C. Load Rotation Matrix (.mat) if available
                val matFile = File(dir, "$prefix.mat")
                val rRel = FloatArray(9).apply {
                    this[0] = 1f; this[4] = 1f; this[8] = 1f // Identity fallback
                }
                if (matFile.exists()) {
                    try {
                        val lines = matFile.readLines().filter { it.isNotBlank() }
                        if (lines.size >= 3) {
                            val row0 = lines[0].trim().split(Regex("\\s+")).map { it.toFloat() }
                            val row1 = lines[1].trim().split(Regex("\\s+")).map { it.toFloat() }
                            val row2 = lines[2].trim().split(Regex("\\s+")).map { it.toFloat() }
                            rRel[0] = row0[0]; rRel[1] = -row0[1]; rRel[2] = -row0[2]
                            rRel[3] = row1[0]; rRel[4] = -row1[1]; rRel[5] = -row1[2]
                            rRel[6] = row2[0]; rRel[7] = -row2[1]; rRel[8] = -row2[2]
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not parse .mat for $prefix, using identity matrix", e)
                    }
                }

                // D. Prepare Textures: Fused and Pure Thermal
                val fusedColorBitmap = if (thermalBitmap != null) {
                    ThermalCalibrationManager.createFusedColorBitmap(
                        rgbBitmap = rgbBitmap,
                        thermalBitmap = thermalBitmap,
                        calibration = calibration,
                        alpha = 1.0f
                    )
                } else {
                    rgbBitmap
                }

                val pureThermalColorBitmap = if (thermalBitmap != null) {
                    ThermalCalibrationManager.createPureThermalColorBitmap(
                        width = rgbBitmap.width,
                        height = rgbBitmap.height,
                        thermalBitmap = thermalBitmap,
                        calibration = calibration
                    )
                } else {
                    rgbBitmap
                }

                // E. Run MoGe Metric Depth Inference ONCE on pure RGB frame
                val result = interpreter.runInferenceWithColor(rgbBitmap, rgbBitmap, stride = 4)
                if (result != null) {
                    val positions = result.first
                    val rgbColors = result.second
                    val fusedColors = interpreter.sampleColors(fusedColorBitmap, stride = 4)
                    val thermalColors = interpreter.sampleColors(pureThermalColorBitmap, stride = 4)

                    val numPoints = positions.size / 3
                    val glPositions = FloatArray(positions.size)

                    for (j in 0 until numPoints) {
                        glPositions[j * 3]     =  positions[j * 3]
                        glPositions[j * 3 + 1] = -positions[j * 3 + 1]
                        glPositions[j * 3 + 2] = -positions[j * 3 + 2]
                    }

                    // Rotate points into world frame using recorded R_rel
                    for (j in 0 until numPoints) {
                        rotatePoint3x3(glPositions, j * 3, rRel)
                    }

                    fusedAccumulator.addFrame(glPositions, fusedColors, accumulate = true)
                    rgbAccumulator.addFrame(glPositions, rgbColors, accumulate = true)
                    thermalAccumulator.addFrame(glPositions, thermalColors, accumulate = true)
                }
            }

            withContext(Dispatchers.Main) {
                onProgress(totalFrames, totalFrames, "Exporting 3D models (Fused, RGB, Thermal)...")
            }

            val fusedData = fusedAccumulator.getPositionsAndColors()
            val rgbData = rgbAccumulator.getPositionsAndColors()
            val thermalData = thermalAccumulator.getPositionsAndColors()

            // 3. Export all 3 Variations to Downloads
            val ts = System.currentTimeMillis()
            saveGlbToDownloads(context, "moge_batch_${datasetItem.name}_${ts}_fused.glb", exportGlb(fusedData.first, fusedData.second))
            saveGlbToDownloads(context, "moge_batch_${datasetItem.name}_${ts}_rgb.glb", exportGlb(rgbData.first, rgbData.second))
            saveGlbToDownloads(context, "moge_batch_${datasetItem.name}_${ts}_thermal.glb", exportGlb(thermalData.first, thermalData.second))

            Log.i(TAG, "Triple batch reconstructions exported for ${datasetItem.name}")

            TripleReconstructionResult(
                fused = fusedData,
                rgb = rgbData,
                thermal = thermalData
            )
        } catch (e: Exception) {
            Log.e(TAG, "Batch processing failed for dataset ${datasetItem.name}", e)
            null
        }
    }

    private fun saveGlbToDownloads(context: Context, fileName: String, glbBytes: ByteArray) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "model/gltf-binary")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(glbBytes)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save $fileName", e)
        }
    }
}
