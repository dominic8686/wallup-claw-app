package io.livekit.android.example.voiceassistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.livekit.android.example.voiceassistant.settings.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDrawer(
    visible: Boolean,
    onDismiss: () -> Unit,
    settings: AppSettings,
    haConnectionOk: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    val callMode by settings.callMode.collectAsState(initial = CallMode.WAKEWORD)
    val selectedModel by settings.wakeWordModel.collectAsState(initial = BUNDLED_MODELS.first().id)
    val sensitivity by settings.wakeWordSensitivity.collectAsState(initial = 0.5f)
    val haUrl by settings.haUrl.collectAsState(initial = AppSettings.DEFAULT_HA_URL)
    val livekitUrl by settings.livekitServerUrl.collectAsState(initial = AppSettings.DEFAULT_LIVEKIT_URL)
    val tokenUrl by settings.tokenServerUrl.collectAsState(initial = AppSettings.DEFAULT_TOKEN_SERVER_URL)
    val anamEnabled by settings.anamEnabled.collectAsState(initial = false)
    val anamApiKey by settings.anamApiKey.collectAsState(initial = "")
    val anamAvatarId by settings.anamAvatarId.collectAsState(initial = "")
    val deviceId by settings.deviceId.collectAsState(initial = AppSettings.DEFAULT_DEVICE_ID)
    val deviceDisplayName by settings.deviceDisplayName.collectAsState(initial = AppSettings.DEFAULT_DEVICE_DISPLAY_NAME)
    val deviceRoomLocation by settings.deviceRoomLocation.collectAsState(initial = AppSettings.DEFAULT_DEVICE_ROOM_LOCATION)

    // Scrim + drawer
    if (visible) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Scrim (tap to dismiss)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.4f)
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Text("✕", fontSize = 20.sp)
                    }
                }

                HorizontalDivider()

                // Device Identity
                Text("Device Identity", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                var deviceIdEdit by remember(deviceId) { mutableStateOf(deviceId) }
                OutlinedTextField(
                    value = deviceIdEdit,
                    onValueChange = { deviceIdEdit = it },
                    label = { Text("Device ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (deviceIdEdit != deviceId) {
                            TextButton(onClick = {
                                scope.launch { settings.setDeviceId(deviceIdEdit) }
                            }) { Text("Save") }
                        }
                    }
                )

                var displayNameEdit by remember(deviceDisplayName) { mutableStateOf(deviceDisplayName) }
                OutlinedTextField(
                    value = displayNameEdit,
                    onValueChange = { displayNameEdit = it },
                    label = { Text("Tablet Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (displayNameEdit != deviceDisplayName) {
                            TextButton(onClick = {
                                scope.launch { settings.setDeviceDisplayName(displayNameEdit) }
                            }) { Text("Save") }
                        }
                    }
                )

                var roomEdit by remember(deviceRoomLocation) { mutableStateOf(deviceRoomLocation) }
                OutlinedTextField(
                    value = roomEdit,
                    onValueChange = { roomEdit = it },
                    label = { Text("Room Location") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (roomEdit != deviceRoomLocation) {
                            TextButton(onClick = {
                                scope.launch { settings.setDeviceRoomLocation(roomEdit) }
                            }) { Text("Save") }
                        }
                    }
                )

                HorizontalDivider()

                // Home Assistant URL
                Text("Home Assistant", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                var haUrlEdit by remember(haUrl) { mutableStateOf(haUrl) }
                OutlinedTextField(
                    value = haUrlEdit,
                    onValueChange = { haUrlEdit = it },
                    label = { Text("HA URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (haUrlEdit != haUrl) {
                            TextButton(onClick = {
                                scope.launch { settings.setHaUrl(haUrlEdit) }
                            }) { Text("Save") }
                        }
                    }
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                if (haConnectionOk) Color(0xFF4CAF50) else Color(0xFFF44336),
                                RoundedCornerShape(50)
                            )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (haConnectionOk) "Connected" else "Not reachable",
                        fontSize = 13.sp,
                        color = if (haConnectionOk) Color(0xFF4CAF50) else Color(0xFFF44336)
                    )
                }

                HorizontalDivider()

                // Wake Word
                Text("Wake Word", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                // Call mode toggle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mode:", fontSize = 14.sp, modifier = Modifier.width(48.dp))
                    FilterChip(
                        selected = callMode == CallMode.WAKEWORD,
                        onClick = {
                            scope.launch {
                                settings.setCallMode(
                                    if (callMode == CallMode.WAKEWORD) CallMode.MANUAL else CallMode.WAKEWORD
                                )
                            }
                        },
                        label = { Text(if (callMode == CallMode.WAKEWORD) "Wake Word" else "Manual") }
                    )
                }

                // Model selector
                var modelExpanded by remember { mutableStateOf(false) }
                val currentModel = BUNDLED_MODELS.find { it.id == selectedModel } ?: BUNDLED_MODELS.first()

                ExposedDropdownMenuBox(
                    expanded = modelExpanded,
                    onExpandedChange = { modelExpanded = !modelExpanded }
                ) {
                    OutlinedTextField(
                        value = currentModel.displayName,
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        label = { Text("Model") }
                    )
                    ExposedDropdownMenu(
                        expanded = modelExpanded,
                        onDismissRequest = { modelExpanded = false }
                    ) {
                        BUNDLED_MODELS.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.displayName) },
                                onClick = {
                                    scope.launch { settings.setWakeWordModel(model.id) }
                                    modelExpanded = false
                                }
                            )
                        }
                    }
                }

                // Sensitivity
                Text("Sensitivity: ${"%.1f".format(sensitivity)}", fontSize = 14.sp)
                Slider(
                    value = sensitivity,
                    onValueChange = { scope.launch { settings.setWakeWordSensitivity(it) } },
                    valueRange = 0.1f..0.9f,
                    steps = 7,
                    modifier = Modifier.fillMaxWidth()
                )

                HorizontalDivider()

                // Server URLs
                Text("Server", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                var livekitEdit by remember(livekitUrl) { mutableStateOf(livekitUrl) }
                OutlinedTextField(
                    value = livekitEdit,
                    onValueChange = { livekitEdit = it },
                    label = { Text("LiveKit URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (livekitEdit != livekitUrl) {
                            TextButton(onClick = {
                                scope.launch { settings.setLivekitServerUrl(livekitEdit) }
                            }) { Text("Save") }
                        }
                    }
                )

                var tokenEdit by remember(tokenUrl) { mutableStateOf(tokenUrl) }
                OutlinedTextField(
                    value = tokenEdit,
                    onValueChange = { tokenEdit = it },
                    label = { Text("Token Server URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (tokenEdit != tokenUrl) {
                            TextButton(onClick = {
                                scope.launch { settings.setTokenServerUrl(tokenEdit) }
                            }) { Text("Save") }
                        }
                    }
                )

                HorizontalDivider()

                // Avatar
                Text("Avatar", fontWeight = FontWeight.Bold, fontSize = 16.sp)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enable Avatar", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(
                        checked = anamEnabled,
                        onCheckedChange = { scope.launch { settings.setAnamEnabled(it) } }
                    )
                }

                if (anamEnabled) {
                    var apiKeyEdit by remember(anamApiKey) { mutableStateOf(anamApiKey) }
                    OutlinedTextField(
                        value = apiKeyEdit,
                        onValueChange = { apiKeyEdit = it },
                        label = { Text("Anam API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (apiKeyEdit != anamApiKey) {
                                TextButton(onClick = {
                                    scope.launch { settings.setAnamApiKey(apiKeyEdit) }
                                }) { Text("Save") }
                            }
                        }
                    )

                    var avatarIdEdit by remember(anamAvatarId) { mutableStateOf(anamAvatarId) }
                    OutlinedTextField(
                        value = avatarIdEdit,
                        onValueChange = { avatarIdEdit = it },
                        label = { Text("Avatar ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            if (avatarIdEdit != anamAvatarId) {
                                TextButton(onClick = {
                                    scope.launch { settings.setAnamAvatarId(avatarIdEdit) }
                                }) { Text("Save") }
                            }
                        }
                    )
                }
            }
        }
    }
}
