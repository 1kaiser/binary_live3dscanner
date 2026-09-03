package com.example.multicamapp.ui

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.multicamapp.camera.CameraDeviceInfo
import com.example.multicamapp.camera.CameraStreamState
import com.example.multicamapp.camera.CameraStreamStatus
import com.example.multicamapp.camera.MultiCameraManager

@Composable
fun CameraFeedView(
    cameraInfo: CameraDeviceInfo,
    cameraManager: MultiCameraManager,
    status: CameraStreamStatus,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    isFloating: Boolean = false
) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    // Calculate natural camera stream aspect ratio (width / height when upright)
    val streamSize = status.activeSize ?: android.util.Size(1280, 720)
    val streamAspect = if (isLandscape) {
        streamSize.width.toFloat() / streamSize.height.toFloat() // 16:9
    } else {
        streamSize.height.toFloat() / streamSize.width.toFloat() // 9:16
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val displayRotation = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        context.display?.rotation ?: android.view.Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
    }

    val cornerRadius = if (isFullscreen) 0.dp else 12.dp
    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .clip(shape)
            .background(Color.Black)
            .then(
                if (!isFullscreen) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.6f), shape)
                else Modifier
            )
    ) {
        // 1. Android TextureView centered and cropped without reshaping
        CenterCropContainer(
            aspectRatio = streamAspect,
            modifier = Modifier.fillMaxSize()
        ) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                            val w = right - left
                            val h = bottom - top
                            configureTextureViewTransform(
                                textureView = this,
                                viewWidth = w,
                                viewHeight = h,
                                displayRotation = displayRotation,
                                streamSize = streamSize,
                                isFront = cameraInfo.lensType == com.example.multicamapp.camera.LensType.FRONT
                            )
                        }
                        cameraManager.registerTextureView(cameraInfo.cameraId, this)
                    }
                },
                update = { textureView ->
                    configureTextureViewTransform(
                        textureView = textureView,
                        viewWidth = textureView.width,
                        viewHeight = textureView.height,
                        displayRotation = displayRotation,
                        streamSize = streamSize,
                        isFront = cameraInfo.lensType == com.example.multicamapp.camera.LensType.FRONT
                    )
                },
                onRelease = {
                    cameraManager.unregisterTextureView(cameraInfo.cameraId)
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Status overlays (Starting, Error, or Offline)
        when (status.state) {
            CameraStreamState.STARTING -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = Color(0xFF64B5F6),
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Opening ${cameraInfo.displayName}...",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        )
                    }
                }
            }
            CameraStreamState.ERROR -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF2A1212).copy(alpha = 0.85f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = "⚠️ STREAM UNAVAILABLE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF5252),
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = status.errorMessage ?: "Hardware ISP limit reached",
                            fontSize = 9.sp,
                            color = Color.White.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { cameraManager.restartCameraStream(cameraInfo.cameraId) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Retry Stream", fontSize = 9.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
            CameraStreamState.STOPPED -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${cameraInfo.displayName}\n(Paused)",
                        fontSize = 10.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            CameraStreamState.STREAMING -> {
                // Active stream running cleanly
            }
        }

        // 3. Top-Left Fullscreen Expand/Collapse button
        IconButton(
            onClick = onToggleFullscreen,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .size(28.dp)
                .background(Color.Black.copy(alpha = 0.65f), CircleShape)
        ) {
            Icon(
                imageVector = if (isFullscreen) Icons.Default.Close else Icons.Default.Fullscreen,
                contentDescription = if (isFullscreen) "Exit Fullscreen" else "Fullscreen",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }

        // 4. Camera Info & FPS Badge (Bottom-Start)
        val badgeText = buildString {
            append(cameraInfo.displayName)
            if (status.state == CameraStreamState.STREAMING) {
                append(" • ${status.activeSize.width}x${status.activeSize.height}")
                if (status.fps > 0) {
                    append(" • ${status.fps} FPS")
                }
            }
        }

        Text(
            text = badgeText,
            fontSize = if (isFloating) 7.5.sp else 8.5.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF69F0AE),
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
        )
    }
}

@Composable
fun CenterCropContainer(
    aspectRatio: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    androidx.compose.ui.layout.Layout(
        content = content,
        modifier = modifier.clipToBounds()
    ) { measurables, constraints ->
        val containerWidth = constraints.maxWidth
        val containerHeight = constraints.maxHeight

        if (containerWidth == 0 || containerHeight == 0) {
            return@Layout layout(0, 0) {}
        }

        val containerAspect = containerWidth.toFloat() / containerHeight.toFloat()
        val (targetWidth, targetHeight) = if (containerAspect > aspectRatio) {
            // Container is wider than aspect ratio: match width, expand height
            val w = containerWidth
            val h = (containerWidth / aspectRatio).toInt()
            Pair(w, h)
        } else {
            // Container is taller than aspect ratio: match height, expand width
            val h = containerHeight
            val w = (containerHeight * aspectRatio).toInt()
            Pair(w, h)
        }

        val childConstraints = androidx.compose.ui.unit.Constraints.fixed(targetWidth, targetHeight)
        val placeables = measurables.map { it.measure(childConstraints) }

        layout(containerWidth, containerHeight) {
            val x = (containerWidth - targetWidth) / 2
            val y = (containerHeight - targetHeight) / 2
            placeables.forEach { placeable ->
                placeable.place(x, y)
            }
        }
    }
}

fun configureTextureViewTransform(
    textureView: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    displayRotation: Int,
    streamSize: android.util.Size,
    isFront: Boolean
) {
    if (viewWidth == 0 || viewHeight == 0) return
    val matrix = android.graphics.Matrix()
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f

    val is480p = streamSize.width == 640 && streamSize.height == 480

    if (displayRotation == android.view.Surface.ROTATION_0) {
        if (is480p) {
            // MediaTek HAL 4:3 (640x480) buffer orientation fix in portrait:
            // Back camera requires +90 deg, front selfie requires -90 deg to be perfectly upright
            val viewRect = android.graphics.RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            val bufferRect = android.graphics.RectF(0f, 0f, streamSize.height.toFloat(), streamSize.width.toFloat())
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL)

            val scale = Math.max(
                viewWidth.toFloat() / streamSize.height.toFloat(),
                viewHeight.toFloat() / streamSize.width.toFloat()
            )
            matrix.postScale(scale, scale, centerX, centerY)
            val rotDegrees = -90f
            matrix.postRotate(rotDegrees, centerX, centerY)
        }
    } else if (android.view.Surface.ROTATION_90 == displayRotation || android.view.Surface.ROTATION_270 == displayRotation) {
        val viewRect = android.graphics.RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = android.graphics.RectF(0f, 0f, streamSize.height.toFloat(), streamSize.width.toFloat())
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL)
        val scale = Math.max(
            viewWidth.toFloat() / streamSize.height.toFloat(),
            viewHeight.toFloat() / streamSize.width.toFloat()
        )
        matrix.postScale(scale, scale, centerX, centerY)
        val baseDegrees = (90 * (displayRotation - 2)).toFloat()
        val rotDegrees = if (isFront) -baseDegrees + 180f else baseDegrees
        matrix.postRotate(rotDegrees, centerX, centerY)
    } else if (android.view.Surface.ROTATION_180 == displayRotation) {
        matrix.postRotate(180f, centerX, centerY)
    }
    textureView.setTransform(matrix)
}
