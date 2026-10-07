package com.example.moge3dscanner.ui.main

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * High-performance LiteRT / TFLite interpreter for MoGe-3 (and backwards-compatible with MoGe v2).
 * Automatically detects whether the active model is MoGe-3 (CHW 672x672 factorized) or MoGe v2 (HWC 518x518).
 */
class MogeInterpreter(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    var isMoge3: Boolean = false
        private set

    var activeModelName: String = "moge3_dense_stage_weight_only_int8.tflite"
        private set

    var activeAccelerator = "CPU (XNNPACK)"
        private set

    // MoGe Input buffer: 1 * 3 * 518 * 518 * 4 bytes (fits both CHW and HWC)
    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * 3 * 518 * 518 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val inputFloatBuffer: FloatBuffer = inputBuffer.asFloatBuffer()

    // MoGe-3 Output buffers:
    // Output 0: Points [1, 3, 672, 672] (x/z, y/z, logz)
    private val moge3PointsBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * 3 * 672 * 672 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val moge3PointsFloatBuffer: FloatBuffer = moge3PointsBuffer.asFloatBuffer()

    // Output 1: Normals [1, 3, 672, 672]
    private val moge3NormalsBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * 3 * 672 * 672 * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    // Output 2: Mask [1, 1, 672, 672]
    private val moge3MaskBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * 1 * 672 * 672 * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    // Output 3: Metric Scale [1, 1]
    private val moge3ScaleArray = FloatArray(1)

    // Output 4: Features [1, 1024, 42, 42]
    private val moge3FeaturesBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * 1024 * 42 * 42 * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    // MoGe v2 Output buffer: [1, 518, 518, 3]
    private val mogeV2OutputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * 518 * 518 * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val mogeV2OutputFloatBuffer: FloatBuffer = mogeV2OutputBuffer.asFloatBuffer()

    private val pixels518 = IntArray(518 * 518)

    init {
        initModel()
    }

    private fun initModel() {
        // Preferred order: MoGe-3 INT8 -> MoGe v2 FP16
        val candidates = listOf(
            "moge3_dense_stage_weight_only_int8.tflite",
            "moge3_dense_stage_fp16.tflite",
            "moge_v2_fp16.tflite"
        )

        var loadedModel: String? = null
        var modelBuffer: MappedByteBuffer? = null

        val assetList = try { context.assets.list("")?.toSet() ?: emptySet() } catch (_: Exception) { emptySet() }

        for (candidate in candidates) {
            if (assetList.contains(candidate)) {
                try {
                    modelBuffer = loadModelFile(context, candidate)
                    loadedModel = candidate
                    break
                } catch (e: Exception) {
                    Log.w("MogeInterpreter", "Failed to load candidate $candidate: ${e.message}")
                }
            }
        }

        if (modelBuffer == null) {
            Log.e("MogeInterpreter", "No suitable MoGe model found in assets!")
            return
        }

        activeModelName = loadedModel ?: "unknown"

        // Attempt GPU acceleration first, fall back to CPU (XNNPACK)
        try {
            val gpuOptions = Interpreter.Options().apply {
                gpuDelegate = GpuDelegate()
                addDelegate(gpuDelegate)
            }
            interpreter = Interpreter(modelBuffer, gpuOptions)
            activeAccelerator = "GPU"
            Log.i("MogeInterpreter", "Successfully initialized TFLite ($activeModelName) with GPU Delegate.")
        } catch (gpuException: Exception) {
            Log.w("MogeInterpreter", "GPU Delegate unavailable. Falling back to CPU.", gpuException)
            gpuDelegate?.close()
            gpuDelegate = null

            val cpuOptions = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, cpuOptions)
            activeAccelerator = "CPU (XNNPACK)"
            Log.i("MogeInterpreter", "Successfully initialized TFLite ($activeModelName) with CPU.")
        }

        // Determine architecture from input tensor layout:
        val interp = interpreter
        if (interp != null) {
            val inShape = interp.getInputTensor(0).shape()
            // MoGe-3 has shape [1, 3, 518, 518] (CHW), whereas MoGe v2 has [1, 518, 518, 3] (HWC)
            isMoge3 = (inShape.size == 4 && inShape[1] == 3)
            Log.i("MogeInterpreter", "Architecture detected: isMoge3=$isMoge3 (Input Shape: ${inShape.joinToString()})")
        }
    }

    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    @Synchronized
    fun runInference(bitmap: Bitmap, stride: Int): Pair<FloatArray, FloatArray>? =
        runInferenceWithColor(bitmap, bitmap, stride)

    @Synchronized
    fun runInferenceWithColor(depthBitmap: Bitmap, colorBitmap: Bitmap, stride: Int): Pair<FloatArray, FloatArray>? {
        val interp = interpreter ?: return null

        val scaledDepth = Bitmap.createScaledBitmap(depthBitmap, 518, 518, true)
        scaledDepth.getPixels(pixels518, 0, 518, 0, 0, 518, 518)

        inputFloatBuffer.rewind()

        if (isMoge3) {
            // Planar format: CHW [1, 3, 518, 518]
            val totalPixels = 518 * 518
            // R channel
            for (i in 0 until totalPixels) {
                inputFloatBuffer.put(((pixels518[i] shr 16) and 0xFF) / 255.0f)
            }
            // G channel
            for (i in 0 until totalPixels) {
                inputFloatBuffer.put(((pixels518[i] shr 8) and 0xFF) / 255.0f)
            }
            // B channel
            for (i in 0 until totalPixels) {
                inputFloatBuffer.put((pixels518[i] and 0xFF) / 255.0f)
            }

            // Outputs for MoGe-3
            val inputs = arrayOf<Any>(inputBuffer)
            val outputs = mutableMapOf<Int, Any>()
            outputs[0] = moge3PointsBuffer
            outputs[1] = moge3NormalsBuffer
            outputs[2] = moge3MaskBuffer
            outputs[3] = moge3ScaleArray
            outputs[4] = moge3FeaturesBuffer

            inputBuffer.rewind()
            moge3PointsBuffer.rewind()
            moge3PointsFloatBuffer.rewind()

            interp.runForMultipleInputsOutputs(inputs, outputs)

            val metricScale = exp(moge3ScaleArray[0].toDouble()).toFloat()

            // Output resolution is 672 x 672
            val outW = 672
            val outH = 672
            val planeSize = outW * outH

            val scaledColor = Bitmap.createScaledBitmap(colorBitmap, outW, outH, true)
            val colorPixels = IntArray(planeSize)
            scaledColor.getPixels(colorPixels, 0, outW, 0, 0, outW, outH)

            val step = stride
            val stepsX = (outW + step - 1) / step
            val size = stepsX * stepsX
            val positions = FloatArray(size * 3)
            val colors = FloatArray(size * 3)

            var idx = 0
            for (y in 0 until outH step step) {
                for (x in 0 until outW step step) {
                    val pixelIdx = y * outW + x
                    // CHW planar layout:
                    val xOverZ = moge3PointsFloatBuffer.get(pixelIdx)
                    val yOverZ = moge3PointsFloatBuffer.get(planeSize + pixelIdx)
                    val logZ = moge3PointsFloatBuffer.get(planeSize * 2 + pixelIdx)

                    val z = exp(logZ.toDouble()).toFloat() * metricScale
                    val px = xOverZ * z
                    val py = yOverZ * z

                    positions[idx * 3]     = px
                    positions[idx * 3 + 1] = py
                    positions[idx * 3 + 2] = z

                    val colorP = colorPixels[pixelIdx]
                    colors[idx * 3]     = ((colorP shr 16) and 0xFF) / 255.0f
                    colors[idx * 3 + 1] = ((colorP shr 8)  and 0xFF) / 255.0f
                    colors[idx * 3 + 2] = (colorP and 0xFF) / 255.0f
                    idx++
                }
            }

            return Pair(positions, colors)

        } else {
            // Interleaved format: HWC [1, 518, 518, 3] (MoGe v2)
            for (i in pixels518.indices) {
                val pixel = pixels518[i]
                inputFloatBuffer.put(((pixel shr 16) and 0xFF) / 255.0f)
                inputFloatBuffer.put(((pixel shr 8) and 0xFF) / 255.0f)
                inputFloatBuffer.put((pixel and 0xFF) / 255.0f)
            }

            val inputs = arrayOf<Any>(inputBuffer)
            val outputs = mutableMapOf<Int, Any>()
            val scaleArr = FloatArray(1)
            outputs[0] = scaleArr
            outputs[1] = mogeV2OutputBuffer

            inputBuffer.rewind()
            mogeV2OutputBuffer.rewind()
            mogeV2OutputFloatBuffer.rewind()
            interp.runForMultipleInputsOutputs(inputs, outputs)

            val scale = if (scaleArr[0] > 0f) scaleArr[0] else 1.0f

            val scaledColor = Bitmap.createScaledBitmap(colorBitmap, 518, 518, true)
            val colorPixels = IntArray(518 * 518)
            scaledColor.getPixels(colorPixels, 0, 518, 0, 0, 518, 518)

            val step = stride
            val stepsX = (518 + step - 1) / step
            val size = stepsX * stepsX
            val positions = FloatArray(size * 3)
            val colors = FloatArray(size * 3)

            var idx = 0
            for (y in 0 until 518 step step) {
                for (x in 0 until 518 step step) {
                    val i = y * 518 + x
                    positions[idx * 3]     = mogeV2OutputFloatBuffer.get(i * 3) * scale
                    positions[idx * 3 + 1] = mogeV2OutputFloatBuffer.get(i * 3 + 1) * scale
                    positions[idx * 3 + 2] = mogeV2OutputFloatBuffer.get(i * 3 + 2) * scale

                    val pixel = colorPixels[i]
                    colors[idx * 3]     = ((pixel shr 16) and 0xFF) / 255.0f
                    colors[idx * 3 + 1] = ((pixel shr 8)  and 0xFF) / 255.0f
                    colors[idx * 3 + 2] = (pixel and 0xFF) / 255.0f
                    idx++
                }
            }

            return Pair(positions, colors)
        }
    }

    fun sampleColors(bitmap: Bitmap, stride: Int = 4): FloatArray {
        val outDim = if (isMoge3) 672 else 518
        val scaled = Bitmap.createScaledBitmap(bitmap, outDim, outDim, true)
        val pixels = IntArray(outDim * outDim)
        scaled.getPixels(pixels, 0, outDim, 0, 0, outDim, outDim)

        val step = stride
        val stepsX = (outDim + step - 1) / step
        val size = stepsX * stepsX
        val colors = FloatArray(size * 3)

        var idx = 0
        for (y in 0 until outDim step step) {
            for (x in 0 until outDim step step) {
                val i = y * outDim + x
                val pixel = pixels[i]
                colors[idx * 3]     = ((pixel shr 16) and 0xFF) / 255.0f
                colors[idx * 3 + 1] = ((pixel shr 8)  and 0xFF) / 255.0f
                colors[idx * 3 + 2] = (pixel and 0xFF) / 255.0f
                idx++
            }
        }
        return colors
    }

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}
