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
        // 1. Android TextureView for hardware Camera2 streaming
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    cameraManager.registerTextureView(cameraInfo.cameraId, this)
                }
            },
            onRelease = {
                cameraManager.unregisterTextureView(cameraInfo.cameraId)
            },
            modifier = Modifier.fillMaxSize()
        )

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
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
