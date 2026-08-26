package com.example.moge3dscanner.ui.main

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Interactive 4-Corner (A, B, C, D) Perspective Calibration Overlay.
 * Renders directly over the live Camera preview in fullscreen mode.
 */
@Composable
fun ThermalCalibrationInteractiveOverlay(
    liveThermalBitmap: Bitmap?,
    initialCalibration: ThermalCalibration,
    onSaveAndClose: (ThermalCalibration) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var thermalRotation by remember { mutableStateOf(initialCalibration.thermalRotationDegrees) }
    var cornerA by remember { mutableStateOf(Offset(initialCalibration.cornerA.first, initialCalibration.cornerA.second)) }
    var cornerB by remember { mutableStateOf(Offset(initialCalibration.cornerB.first, initialCalibration.cornerB.second)) }
    var cornerC by remember { mutableStateOf(Offset(initialCalibration.cornerC.first, initialCalibration.cornerC.second)) }
    var cornerD by remember { mutableStateOf(Offset(initialCalibration.cornerD.first, initialCalibration.cornerD.second)) }
    var overlayAlpha by remember { mutableStateOf(0.65f) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenW = maxWidth.value
        val screenH = maxHeight.value

        // 1. Perspective Thermal Overlay & Interactive Quad Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height

            val pxA = Offset(cornerA.x * canvasW, cornerA.y * canvasH)
            val pxB = Offset(cornerB.x * canvasW, cornerB.y * canvasH)
            val pxC = Offset(cornerC.x * canvasW, cornerC.y * canvasH)
            val pxD = Offset(cornerD.x * canvasW, cornerD.y * canvasH)

            // A. Draw Perspective-Warped Thermal Bitmap
            liveThermalBitmap?.let { rawTh ->
                val cal = ThermalCalibration(
                    thermalRotationDegrees = thermalRotation,
                    cornerA = Pair(cornerA.x, cornerA.y),
                    cornerB = Pair(cornerB.x, cornerB.y),
                    cornerC = Pair(cornerC.x, cornerC.y),
                    cornerD = Pair(cornerD.x, cornerD.y)
                )
                val baseBmp = Bitmap.createBitmap(
                    canvasW.toInt().coerceAtLeast(1),
                    canvasH.toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val warped = ThermalCalibrationManager.createFusedColorBitmap(baseBmp, rawTh, cal, overlayAlpha)
                drawContext.canvas.nativeCanvas.drawBitmap(warped, 0f, 0f, null)
            }

            // B. Draw Quad Perimeter Lines (A -> B -> C -> D -> A)
            val quadPath = Path().apply {
                moveTo(pxA.x, pxA.y)
                lineTo(pxB.x, pxB.y)
                lineTo(pxC.x, pxC.y)
                lineTo(pxD.x, pxD.y)
                close()
            }

            // Diagonal Guidelines
            drawLine(
                color = Color.White.copy(alpha = 0.35f),
                start = pxA, end = pxC,
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            )
            drawLine(
                color = Color.White.copy(alpha = 0.35f),
                start = pxB, end = pxD,
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
            )

            // Neon Outer Border
            drawPath(
                path = quadPath,
                color = Color(0xFF00E5FF),
                style = Stroke(width = 3.dp.toPx())
            )
        }

        // 2. Draggable Corner Handles
        CornerHandle(
            label = "A",
            color = Color(0xFF00E5FF),
            normalizedOffset = cornerA,
            containerSize = Pair(maxWidth, maxHeight),
            onDrag = { dx, dy ->
                val newX = (cornerA.x + dx / screenW).coerceIn(0.01f, 0.99f)
                val newY = (cornerA.y + dy / screenH).coerceIn(0.01f, 0.99f)
                cornerA = Offset(newX, newY)
            }
        )

        CornerHandle(
            label = "B",
            color = Color(0xFFFFD700),
            normalizedOffset = cornerB,
            containerSize = Pair(maxWidth, maxHeight),
            onDrag = { dx, dy ->
                val newX = (cornerB.x + dx / screenW).coerceIn(0.01f, 0.99f)
                val newY = (cornerB.y + dy / screenH).coerceIn(0.01f, 0.99f)
                cornerB = Offset(newX, newY)
            }
        )

        CornerHandle(
            label = "C",
            color = Color(0xFFFF5252),
            normalizedOffset = cornerC,
            containerSize = Pair(maxWidth, maxHeight),
            onDrag = { dx, dy ->
                val newX = (cornerC.x + dx / screenW).coerceIn(0.01f, 0.99f)
                val newY = (cornerC.y + dy / screenH).coerceIn(0.01f, 0.99f)
                cornerC = Offset(newX, newY)
            }
        )

        CornerHandle(
            label = "D",
            color = Color(0xFF69F0AE),
            normalizedOffset = cornerD,
            containerSize = Pair(maxWidth, maxHeight),
            onDrag = { dx, dy ->
                val newX = (cornerD.x + dx / screenW).coerceIn(0.01f, 0.99f)
                val newY = (cornerD.y + dy / screenH).coerceIn(0.01f, 0.99f)
                cornerD = Offset(newX, newY)
            }
        )

        // 3. Top Header Banner
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp, start = 16.dp, end = 16.dp)
                .background(Color(0xFF161719).copy(alpha = 0.90f), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "🎯 4-CORNER THERMAL/RGB CALIBRATION",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF00E5FF)
            )
            Text(
                text = "Drag handles A, B, C, D to match thermal heatmap over real RGB scene",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        // 4. Bottom Interactive Toolbar
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp, start = 12.dp, end = 12.dp)
                .background(Color(0xFF161719).copy(alpha = 0.92f), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF33353A), RoundedCornerShape(16.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Rotate Thermal Stream Button
            Button(
                onClick = {
                    thermalRotation = (thermalRotation + 90) % 360
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF24262B)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.RotateRight, contentDescription = "Rotate", tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("${thermalRotation}°", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color.White)
            }

            // Alpha / Opacity Cycler Button
            Button(
                onClick = {
                    overlayAlpha = when {
                        overlayAlpha >= 0.9f -> 0.35f
                        overlayAlpha >= 0.6f -> 0.90f
                        else -> 0.65f
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF24262B)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Alpha ${(overlayAlpha * 100).toInt()}%", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color.White)
            }

            // Reset Quad Button
            Button(
                onClick = {
                    cornerA = Offset(0.15f, 0.20f)
                    cornerB = Offset(0.85f, 0.20f)
                    cornerC = Offset(0.85f, 0.80f)
                    cornerD = Offset(0.15f, 0.80f)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF24262B)),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Reset", tint = Color(0xFFFFB300), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reset", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color.White)
            }

            // Save Calibration Button
            Button(
                onClick = {
                    val cal = ThermalCalibration(
                        thermalRotationDegrees = thermalRotation,
                        cornerA = Pair(cornerA.x, cornerA.y),
                        cornerB = Pair(cornerB.x, cornerB.y),
                        cornerC = Pair(cornerC.x, cornerC.y),
                        cornerD = Pair(cornerD.x, cornerD.y)
                    )
                    val ok = ThermalCalibrationManager.saveCalibration(context, cal)
                    if (ok) {
                        Toast.makeText(context, "✓ Calibration saved to Downloads folder!", Toast.LENGTH_SHORT).show()
                        onSaveAndClose(cal)
                    } else {
                        Toast.makeText(context, "Failed to save calibration", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = "Save", tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Save", fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            // Done / Exit Button
            IconButton(
                onClick = { onClose() },
                modifier = Modifier
                    .background(Color(0xFFE53935), RoundedCornerShape(8.dp))
                    .size(32.dp)
            ) {
                Icon(Icons.Default.Check, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * High-visibility draggable circular corner handle with label.
 */
@Composable
private fun CornerHandle(
    label: String,
    color: Color,
    normalizedOffset: Offset,
    containerSize: Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp>,
    onDrag: (Float, Float) -> Unit
) {
    val handleRadius = 24.dp
    val handlePx = 48.dp

    val posX = containerSize.first * normalizedOffset.x - handleRadius
    val posY = containerSize.second * normalizedOffset.y - handleRadius

    Box(
        modifier = Modifier
            .offset(x = posX, y = posY)
            .size(handlePx)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Outer glowing halo
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(color.copy(alpha = 0.35f), CircleShape)
        )
        // Inner opaque target
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(color, CircleShape)
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                color = Color.Black
            )
        }
    }
}
