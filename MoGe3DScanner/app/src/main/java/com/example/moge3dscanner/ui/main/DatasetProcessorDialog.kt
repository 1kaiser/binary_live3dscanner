package com.example.moge3dscanner.ui.main

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dialog allowing users to select recorded RGB+Thermal datasets
 * and execute offline batch MoGe depth reconstruction and 3D point cloud generation.
 */
@Composable
fun DatasetProcessorDialog(
    interpreter: MogeInterpreter?,
    onModelReconstructed: (positions: FloatArray, colors: FloatArray) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var datasets by remember { mutableStateOf(DatasetBatchProcessor.listDatasets(context)) }
    var selectedDataset by remember { mutableStateOf<DatasetItem?>(datasets.firstOrNull()) }
    var isProcessing by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var progressFraction by remember { mutableFloatStateOf(0f) }

    Dialog(onDismissRequest = { if (!isProcessing) onDismiss() }) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1F24)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .border(1.dp, Color(0xFF3A3D45), RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚡ POST-PROCESS DATASET",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF)
                    )
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isProcessing,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.Gray)
                    }
                }

                Text(
                    text = "Select a recorded RGB + Thermal dataset to compute metric depth maps and generate the merged 3D Point Cloud:",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    fontFamily = FontFamily.Monospace
                )

                if (datasets.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .background(Color(0xFF141518), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No recorded datasets found.\nEnable 'Dataset Rec' in scanner to record frames.",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color.Gray,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(datasets) { item ->
                            val isSelected = (item.name == selectedDataset?.name)
                            val dateStr = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date(item.timestamp))
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.15f) else Color(0xFF141518),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) Color(0xFF00E5FF) else Color(0xFF2E3038),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable(enabled = !isProcessing) { selectedDataset = item }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Folder, contentDescription = null, tint = if (isSelected) Color(0xFF00E5FF) else Color.Gray, modifier = Modifier.size(18.dp))
                                    Column {
                                        Text(item.name, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        Text("$dateStr • ${item.frameCount} frames", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = Color.Gray)
                                    }
                                }
                            }
                        }
                    }
                }

                // Processing Progress Bar
                if (isProcessing) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF141518), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { progressFraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = Color(0xFF00E5FF),
                            trackColor = Color(0xFF2A2C33)
                        )
                        Text(
                            text = progressText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = Color(0xFF00E5FF)
                        )
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val target = selectedDataset ?: return@Button
                            val interp = interpreter ?: run {
                                Toast.makeText(context, "Model not loaded yet", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            isProcessing = true
                            progressFraction = 0f
                            progressText = "Starting batch processing..."

                            scope.launch {
                                val result = DatasetBatchProcessor.processDataset(
                                    context = context,
                                    datasetItem = target,
                                    interpreter = interp,
                                    onProgress = { cur, tot, status ->
                                        progressFraction = cur.toFloat() / tot.coerceAtLeast(1)
                                        progressText = status
                                    }
                                )
                                isProcessing = false
                                if (result != null) {
                                    Toast.makeText(context, "✓ 3D Model Reconstructed & Exported!", Toast.LENGTH_LONG).show()
                                    onModelReconstructed(result.first, result.second)
                                    onDismiss()
                                } else {
                                    Toast.makeText(context, "Reconstruction failed or empty dataset", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !isProcessing && selectedDataset != null && (selectedDataset?.frameCount ?: 0) > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Run", tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (isProcessing) "Processing..." else "Reconstruct 3D Scene",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
