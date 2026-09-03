package com.example.multicamapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.multicamapp.camera.MultiCameraManager
import com.example.multicamapp.capture.MultiCamVideoRecorder
import com.example.multicamapp.location.GpsLocationManager
import com.example.multicamapp.ui.MultiCamScreen

class MainActivity : ComponentActivity() {

    private lateinit var cameraManager: MultiCameraManager
    private lateinit var locationManager: GpsLocationManager
    private lateinit var videoRecorder: MultiCamVideoRecorder

    private var hasRequiredPermissions by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraGranted = results[Manifest.permission.CAMERA] == true
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true
        hasRequiredPermissions = cameraGranted && audioGranted

        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            // Location permission granted
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraManager = MultiCameraManager(applicationContext)
        locationManager = GpsLocationManager(applicationContext)
        videoRecorder = MultiCamVideoRecorder(applicationContext)

        checkAndRequestPermissions()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF0F0F0F),
                    surface = Color(0xFF1E1E1E),
                    primary = Color(0xFF69F0AE)
                )
            ) {
                if (hasRequiredPermissions) {
                    MultiCamScreen(
                        cameraManager = cameraManager,
                        locationManager = locationManager,
                        videoRecorder = videoRecorder
                    )
                } else {
                    PermissionRequestScreen(
                        onRequestAgain = { checkAndRequestPermissions() }
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            hasRequiredPermissions = true
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraManager.onDestroy()
        locationManager.onDestroy()
        videoRecorder.onDestroy()
    }
}

@Composable
fun PermissionRequestScreen(onRequestAgain: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Permissions Required",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "MultiCam requires Camera and Microphone access to stream and record multiple cameras simultaneously. Location access is optional for GPS tagging.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.LightGray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRequestAgain,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853))
            ) {
                Text("Grant Permissions", color = Color.White)
            }
        }
    }
}
