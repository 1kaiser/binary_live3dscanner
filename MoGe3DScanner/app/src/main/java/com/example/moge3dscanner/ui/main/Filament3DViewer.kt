package com.example.moge3dscanner.ui.main

import android.content.Context
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceView
import com.google.android.filament.Colors
import com.google.android.filament.utils.Float3
import com.google.android.filament.utils.Manipulator
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer

class Filament3DViewer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs) {

    init {
        Utils.init()
    }

    var modelViewer: ModelViewer? = null
        private set

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            Choreographer.getInstance().postFrameCallback(this)
            modelViewer?.render(frameTimeNanos)
        }
    }

    init {
        val viewer = ModelViewer(this)
        // Set clean light theme background (#F7F6F2)
        val clearColor = Colors.cct(6500.0f)
        viewer.view.blendMode = com.google.android.filament.View.BlendMode.OPAQUE
        viewer.camera.setExposure(16.0f, 1.0f / 125.0f, 100.0f)
        modelViewer = viewer
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        modelViewer?.destroyModel()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        modelViewer?.onTouchEvent(event)
        return true
    }

    fun loadGlbData(glbBytes: ByteArray) {
        val buffer = ByteBuffer.wrap(glbBytes)
        modelViewer?.let { viewer ->
            viewer.loadModelGlb(buffer)
            viewer.transformToUnitCube()
        }
    }

    fun resetCamera() {
        modelViewer?.transformToUnitCube()
    }
}

