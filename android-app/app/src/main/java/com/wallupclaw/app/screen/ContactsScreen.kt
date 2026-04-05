package com.wallupclaw.app.screen

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wallupclaw.app.settings.AppSettings
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.net.URL

private const val TAG = "ContactsScreen"

@Serializable
object ContactsRoute

data class DeviceInfo(
    val deviceId: String,
    val displayName: String,
    val roomLocation: String,
    val status: String,
    val callState: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    onCallDevice: (DeviceInfo) -> Unit,
    myDeviceId: String = "",
) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val tokenServerUrl by settings.tokenServerUrl.collectAsState(initial = AppSettings.DEFAULT_TOKEN_SERVER_URL)

    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Fetch devices on launch and refresh every 5s
    LaunchedEffect(tokenServerUrl) {
        while (true) {
            try {
                val response = withContext(Dispatchers.IO) {
                    URL("$tokenServerUrl/devices").readText()
                }
                val json = JSONObject(response)
                val devicesArray = json.getJSONArray("devices")
                val list = mutableListOf<DeviceInfo>()
                for (i in 0 until devicesArray.length()) {
                    val d = devicesArray.getJSONObject(i)
                    val id = d.getString("device_id")
                    // Don't show self in the contacts list
                    if (id == myDeviceId) continue
                    list.add(
                        DeviceInfo(
                            deviceId = id,
                            displayName = d.optString("display_name", id),
                            roomLocation = d.optString("room_location", ""),
                            status = d.optString("status", "offline"),
                            callState = d.optString("call_state", "idle"),
                        )
                    )
                }
                devices = list
                errorMessage = null
                isLoading = false
                Log.d(TAG, "Fetched ${list.size} devices")
            } catch (e: Exception) {
                errorMessage = "Could not reach token server"
                isLoading = false
                Log.w(TAG, "Fetch devices failed: ${e.message}")
            }
            delay(5_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Intercom") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", fontSize = 24.sp)
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                errorMessage != null -> {
                    Text(
                        text = errorMessage!!,
                        color = Color.Red,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp)
                    )
                }
                devices.isEmpty() -> {
                    Text(
                        text = "No other devices found.\nMake sure other tablets are online and registered.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp)
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(devices) { device ->
                            DeviceCard(
                                device = device,
                                onCall = { onCallDevice(device) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceInfo,
    onCall: () -> Unit,
) {
    val isOnline = device.status == "online"
    val isAvailable = isOnline && device.callState == "idle"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isOnline)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Status indicator dot
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            !isOnline -> Color.Gray
                            device.callState == "in_call" -> Color(0xFFFF9800) // Orange
                            device.callState == "ringing" -> Color(0xFFFFEB3B) // Yellow
                            device.callState == "do_not_disturb" -> Color.Red
                            else -> Color(0xFF4CAF50) // Green
                        }
                    )
            )

            Spacer(Modifier.width(16.dp))

            // Device info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                if (device.roomLocation.isNotEmpty()) {
                    Text(
                        text = device.roomLocation,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = when (device.callState) {
                        "idle" -> if (isOnline) "Available" else "Offline"
                        "in_call" -> "In a call"
                        "ringing" -> "Ringing..."
                        "calling" -> "Calling..."
                        "do_not_disturb" -> "Do Not Disturb"
                        else -> device.callState
                    },
                    fontSize = 12.sp,
                    color = when {
                        !isOnline -> Color.Gray
                        device.callState == "idle" -> Color(0xFF4CAF50)
                        else -> Color(0xFFFF9800)
                    }
                )
            }

            // Call button
            if (isAvailable) {
                Button(
                    onClick = onCall,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    ),
                    contentPadding = PaddingValues(12.dp),
                    modifier = Modifier.size(48.dp),
                ) {
                    Text("📞", fontSize = 20.sp)
                }
            }
        }
    }
}
