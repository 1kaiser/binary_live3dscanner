package com.example.moge3dscanner.ui.main

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GLPointRenderer : GLSurfaceView.Renderer {

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition;
        attribute vec4 aColor;
        varying vec4 vColor;
        void main() {
            gl_Position = uMVPMatrix * vPosition;
            gl_PointSize = 6.0;
            vColor = aColor;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec4 vColor;
        void main() {
            gl_FragColor = vColor;
        }
    """.trimIndent()

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var colorHandle: Int = 0
    private var mvpMatrixHandle: Int = 0

    // Buffers
    private var vertexBuffer: FloatBuffer? = null
    private var colorBuffer: FloatBuffer? = null
    private var numPoints: Int = 0

    private val vMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Center offsets
    private var centerX: Float = 0f
    private var centerY: Float = 0f
    private var centerZ: Float = 0f

    // Listener for rendering trigger
    var onNewPointsListener: (() -> Unit)? = null

    // Touch interaction values
    var angleX: Float = 0f
    var angleY: Float = 0f
    var zoom: Float = 3.0f
    var panX: Float = 0f
    var panY: Float = 0f

    // Gravity-aligned base orientation captured at scan time (4x4 column-major)
    val gravityAlignMatrix: FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    /** Resets user-applied rotation and pan back to the gravity-aligned default. */
    fun resetAngles() {
        angleX = 0f
        angleY = 0f
        panX = 0f
        panY = 0f
    }

    @Synchronized
    fun updatePoints(positions: FloatArray, colors: FloatArray) {
        numPoints = positions.size / 3
        if (numPoints > 0) {
            var sumX = 0f
            var sumY = 0f
            var sumZ = 0f
            for (i in 0 until numPoints) {
                sumX += positions[i * 3]
                sumY += positions[i * 3 + 1]
                sumZ += positions[i * 3 + 2]
            }
            centerX = sumX / numPoints
            centerY = sumY / numPoints
            centerZ = sumZ / numPoints
        }
        
        val vbb = ByteBuffer.allocateDirect(positions.size * 4)
        vbb.order(ByteOrder.nativeOrder())
        vertexBuffer = vbb.asFloatBuffer()
        vertexBuffer?.put(positions)
        vertexBuffer?.position(0)

        val cbb = ByteBuffer.allocateDirect(colors.size * 4)
        cbb.order(ByteOrder.nativeOrder())
        colorBuffer = cbb.asFloatBuffer()
        colorBuffer?.put(colors)
        colorBuffer?.position(0)

        onNewPointsListener?.invoke()
    }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        // Set clear color to light theme background (#f7f6f2)
        GLES20.glClearColor(0.97f, 0.96f, 0.95f, 1.0f)

        // Compile shaders
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        // Get handles
        positionHandle = GLES20.glGetAttribLocation(program, "vPosition")
        colorHandle = GLES20.glGetAttribLocation(program, "aColor")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height
        // Perspective projection
        Matrix.perspectiveM(projMatrix, 0, 45f, ratio, 0.1f, 100f)
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Camera setup
        Matrix.setLookAtM(vMatrix, 0, 0f, 0f, zoom, 0f, 0f, 0f, 0f, 1f, 0f)
        // Apply camera pan (translate view matrix)
        Matrix.translateM(vMatrix, 0, panX, panY, 0f)

        // Step 1: translate cloud to centroid origin
        val translateM = FloatArray(16)
        Matrix.setIdentityM(translateM, 0)
        Matrix.translateM(translateM, 0, -centerX, -centerY, -centerZ)

        // Step 2: apply gravity-aligned base orientation (captured at scan time)
        val gravModel = FloatArray(16)
        Matrix.multiplyMM(gravModel, 0, gravityAlignMatrix, 0, translateM, 0)

        // Step 3: apply user rotation in WORLD space as a left-multiply.
        // This guarantees horizontal drag (angleX) ALWAYS spins around world-Y,
        // and vertical drag (angleY) ALWAYS tilts around world-X,
        // regardless of how gravityAlignMatrix has oriented the cloud.
        val userRot = FloatArray(16)
        Matrix.setIdentityM(userRot, 0)
        Matrix.rotateM(userRot, 0, angleX, 0f, 1f, 0f)  // spin left/right: world Y
        Matrix.rotateM(userRot, 0, angleY, 1f, 0f, 0f)  // tilt up/down:   world X

        val modelMatrix = FloatArray(16)
        Matrix.multiplyMM(modelMatrix, 0, userRot, 0, gravModel, 0)

        // MVP = Proj * View * Model
        val scratch = FloatArray(16)
        Matrix.multiplyMM(scratch, 0, vMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, scratch, 0)

        // Draw points
        synchronized(this) {
            val curVertexBuffer = vertexBuffer
            val curColorBuffer = colorBuffer
            if (curVertexBuffer != null && curColorBuffer != null && numPoints > 0) {
                GLES20.glUseProgram(program)

                // Pass MVP Matrix
                GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)

                // Pass positions
                GLES20.glEnableVertexAttribArray(positionHandle)
                GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, curVertexBuffer)

                // Pass colors
                GLES20.glEnableVertexAttribArray(colorHandle)
                GLES20.glVertexAttribPointer(colorHandle, 3, GLES20.GL_FLOAT, false, 0, curColorBuffer)

                // Draw points!
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, numPoints)

                GLES20.glDisableVertexAttribArray(positionHandle)
                GLES20.glDisableVertexAttribArray(colorHandle)
            }
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}
