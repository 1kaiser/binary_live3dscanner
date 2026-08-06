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

class MogeInterpreter(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    // Input buffer: 1 * 518 * 518 * 3 float values
    private val inputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * 518 * 518 * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val inputFloatBuffer: FloatBuffer = inputBuffer.asFloatBuffer()

    // Output buffer: 1 * 518 * 518 * 3 float values
    private val outputBuffer: ByteBuffer = ByteBuffer.allocateDirect(1 * 518 * 518 * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val outputFloatBuffer: FloatBuffer = outputBuffer.asFloatBuffer()

    private val pixels = IntArray(518 * 518)

    var activeAccelerator = "CPU (XNNPACK)"

    init {
        try {
            val modelBuffer = loadModelFile(context, "moge_v2_fp16.tflite")
            try {
                val gpuOptions = Interpreter.Options().apply {
                    gpuDelegate = GpuDelegate()
                    addDelegate(gpuDelegate)
                }
                interpreter = Interpreter(modelBuffer, gpuOptions)
                activeAccelerator = "GPU"
                Log.i("MogeInterpreter", "Successfully initialized TFLite Interpreter with GPU Delegate.")
            } catch (gpuException: Exception) {
                Log.w("MogeInterpreter", "GPU Delegate failed to apply. Falling back to CPU.", gpuException)
                gpuDelegate?.close()
                gpuDelegate = null

                val cpuOptions = Interpreter.Options().apply {
                    setNumThreads(4)
                }
                interpreter = Interpreter(modelBuffer, cpuOptions)
                activeAccelerator = "CPU (XNNPACK)"
                Log.i("MogeInterpreter", "Successfully initialized TFLite Interpreter with CPU (XNNPACK).")
            }
        } catch (e: Exception) {
            Log.e("MogeInterpreter", "Failed to initialize interpreter", e)
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

        // 1. Resize depth bitmap and extract pixels for model input
        val scaledDepth = Bitmap.createScaledBitmap(depthBitmap, 518, 518, true)
        scaledDepth.getPixels(pixels, 0, 518, 0, 0, 518, 518)

        // 2. Preprocess depth input: normalize to [0, 1]
        inputFloatBuffer.rewind()
        for (i in pixels.indices) {
            val pixel = pixels[i]
            inputFloatBuffer.put(((pixel shr 16) and 0xFF) / 255.0f)
            inputFloatBuffer.put(((pixel shr 8) and 0xFF) / 255.0f)
            inputFloatBuffer.put((pixel and 0xFF) / 255.0f)
        }

        // 3. Run depth model
        val inputs = arrayOf<Any>(inputBuffer)
        val outputs = mutableMapOf<Int, Any>()
        outputs[0] = FloatArray(1)
        outputs[1] = outputBuffer

        inputBuffer.rewind()
        outputBuffer.rewind()
        outputFloatBuffer.rewind()
        interp.runForMultipleInputsOutputs(inputs, outputs)

        // 4. Resize color bitmap separately (may differ from depth source)
        val colorPixels: IntArray
        if (colorBitmap === depthBitmap) {
            colorPixels = pixels
        } else {
            val scaledColor = Bitmap.createScaledBitmap(colorBitmap, 518, 518, true)
            colorPixels = IntArray(518 * 518)
            scaledColor.getPixels(colorPixels, 0, 518, 0, 0, 518, 518)
        }

        // 5. Subsample output points
        val step = stride
        val stepsX = (518 + step - 1) / step
        val size = stepsX * stepsX
        val positions = FloatArray(size * 3)
        val colors = FloatArray(size * 3)

        var idx = 0
        for (y in 0 until 518 step step) {
            for (x in 0 until 518 step step) {
                val i = y * 518 + x
                positions[idx * 3]     = outputFloatBuffer.get(i * 3)
                positions[idx * 3 + 1] = outputFloatBuffer.get(i * 3 + 1)
                positions[idx * 3 + 2] = outputFloatBuffer.get(i * 3 + 2)

                val pixel = colorPixels[i]
                colors[idx * 3]     = ((pixel shr 16) and 0xFF) / 255.0f
                colors[idx * 3 + 1] = ((pixel shr 8)  and 0xFF) / 255.0f
                colors[idx * 3 + 2] = (pixel and 0xFF) / 255.0f
                idx++
            }
        }

        return Pair(positions, colors)
    }

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }
}
