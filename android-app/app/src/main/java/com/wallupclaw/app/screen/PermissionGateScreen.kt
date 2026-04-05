package com.wallupclaw.app.screen

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale
import kotlinx.serialization.Serializable

@Serializable
object PermissionGateRoute

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionGateScreen(
    onAllGranted: () -> Unit,
) {
    val context = LocalContext.current

    val permissionsState = rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.CAMERA,
        )
    )

    // Auto-navigate when all permissions are granted
    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            onAllGranted()
        }
    }

    val micGranted = permissionsState.permissions
        .find { it.permission == android.Manifest.permission.RECORD_AUDIO }
        ?.status?.isGranted == true
    val cameraGranted = permissionsState.permissions
        .find { it.permission == android.Manifest.permission.CAMERA }
        ?.status?.isGranted == true

    // Check if any permission is permanently denied (user selected "Don't ask again")
    val anyPermanentlyDenied = permissionsState.permissions.any {
        !it.status.isGranted && !it.status.shouldShowRationale
    }
    // Track whether we've already asked once (to distinguish first launch from permanent denial)
    var hasRequestedOnce by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier
                .padding(32.dp)
                .widthIn(max = 500.dp),
        ) {
            // Title
            Text(
                text = "Welcome to Wallup Claw",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            Text(
                text = "This app needs microphone and camera access to work as your voice assistant, security camera, and intercom.",
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Permission cards
            PermissionCard(
                icon = Icons.Default.Mic,
                title = "Microphone",
                description = "Wake word detection and voice conversations",
                granted = micGranted,
            )

            PermissionCard(
                icon = Icons.Default.Camera,
                title = "Camera",
                description = "Vision AI, security camera, and video calls",
                granted = cameraGranted,
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (permissionsState.allPermissionsGranted) {
                // All granted — show success (will auto-navigate)
                Text(
                    text = "All permissions granted!",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF4CAF50),
                )
            } else if (hasRequestedOnce && anyPermanentlyDenied) {
                // Permanently denied — direct to settings
                Text(
                    text = "Permissions were denied. Please enable them in app settings.",
                    fontSize = 14.sp,
                    color = Color(0xFFFF9800),
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open App Settings", fontSize = 16.sp)
                }
            } else {
                // Request permissions
                Button(
                    onClick = {
                        hasRequestedOnce = true
                        permissionsState.launchMultiplePermissionRequest()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Text("Grant Permissions", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (granted) Color(0xFF1B5E20).copy(alpha = 0.3f)
                else Color.White.copy(alpha = 0.08f),
                RoundedCornerShape(12.dp),
            )
            .padding(16.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (granted) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                text = description,
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
        Text(
            text = if (granted) "✓" else "•",
            fontSize = 20.sp,
            color = if (granted) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.3f),
        )
    }
}
