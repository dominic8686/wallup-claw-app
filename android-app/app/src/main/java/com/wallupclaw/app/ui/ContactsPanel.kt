package com.wallupclaw.app.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wallupclaw.app.util.TokenServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "ContactsPanel"

data class DeviceInfo(
    val deviceId: String,
    val displayName: String,
    val roomLocation: String,
    val status: String,
    val callState: String,
)

@Composable
fun ContactsPanel(
    tokenServerUrl: String,
    myDeviceId: String,
    client: TokenServerClient,
    onCallDevice: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }

    // Poll /devices every 10s
    LaunchedEffect(tokenServerUrl) {
        while (true) {
            try {
                val response = withContext(Dispatchers.IO) {
                    client.get("/devices")
                }
                val json = JSONObject(response)
                val arr = json.getJSONArray("devices")
                val list = mutableListOf<DeviceInfo>()
                for (i in 0 until arr.length()) {
                    val d = arr.getJSONObject(i)
                    list.add(
                        DeviceInfo(
                            deviceId = d.getString("device_id"),
                            displayName = d.optString("display_name", d.getString("device_id")),
                            roomLocation = d.optString("room_location", ""),
                            status = d.optString("status", "offline"),
                            callState = d.optString("call_state", "idle"),
                        )
                    )
                }
                devices = list
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch devices: ${e.message}")
            }
            delay(10_000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Intercom",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Text("✕", fontSize = 18.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        // Device list — only show online devices that aren't us
        val otherDevices = devices.filter { it.deviceId != myDeviceId && it.status == "online" }

        if (otherDevices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("No other tablets online", color = Color.Gray, fontSize = 14.sp)
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                otherDevices.forEach { device ->
                    DeviceRow(
                        device = device,
                        onCall = { onCallDevice(device.deviceId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: DeviceInfo,
    onCall: () -> Unit,
) {
    val isOnline = device.status == "online"
    val canCall = isOnline && device.callState == "idle"

    val statusColor = when {
        !isOnline -> Color(0xFF999999)
        device.callState == "in_call" -> Color(0xFFFF9800)
        device.callState == "ringing" -> Color(0xFFFFD600)
        device.callState == "do_not_disturb" -> Color(0xFFF44336)
        else -> Color(0xFF4CAF50)
    }

    val statusText = when {
        !isOnline -> "Offline"
        device.callState == "idle" -> "Available"
        device.callState == "in_call" -> "In Call"
        device.callState == "ringing" -> "Ringing"
        device.callState == "do_not_disturb" -> "DND"
        else -> device.callState
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(statusColor, CircleShape)
        )

        Spacer(Modifier.width(12.dp))

        // Name + room + status
        Column(modifier = Modifier.weight(1f)) {
            Text(
                device.displayName,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    if (device.roomLocation.isNotEmpty()) {
                        append(device.roomLocation)
                        append(" · ")
                    }
                    append(statusText)
                },
                fontSize = 12.sp,
                color = Color.Gray,
            )
        }

        // Call button
        if (canCall) {
            Button(
                onClick = onCall,
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier.size(44.dp),
            ) {
                Text("📞", fontSize = 18.sp)
            }
        }
    }
}
