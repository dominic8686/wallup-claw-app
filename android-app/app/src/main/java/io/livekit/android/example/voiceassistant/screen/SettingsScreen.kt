package io.livekit.android.example.voiceassistant.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.livekit.android.example.voiceassistant.settings.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object SettingsRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    val scope = rememberCoroutineScope()

    val callMode by settings.callMode.collectAsState(initial = CallMode.MANUAL)
    val selectedModel by settings.wakeWordModel.collectAsState(initial = BUNDLED_MODELS.first().id)
    val sensitivity by settings.wakeWordSensitivity.collectAsState(initial = 0.5f)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", fontSize = 24.sp)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Call Mode
            Text("Call Mode", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = callMode == CallMode.MANUAL,
                        onClick = { scope.launch { settings.setCallMode(CallMode.MANUAL) } }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Manual Start", fontWeight = FontWeight.Medium)
                        Text("Tap 'Start Call' to begin", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = callMode == CallMode.WAKEWORD,
                        onClick = { scope.launch { settings.setCallMode(CallMode.WAKEWORD) } }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Wake Word Detection", fontWeight = FontWeight.Medium)
                        Text("Say the wake word to start a call", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            HorizontalDivider()

            // Wake Word Model
            Text("Wake Word Model", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            var expanded by remember { mutableStateOf(false) }
            val currentModel = BUNDLED_MODELS.find { it.id == selectedModel } ?: BUNDLED_MODELS.first()

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = currentModel.displayName,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    label = { Text("Wake word") }
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    BUNDLED_MODELS.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.displayName) },
                            onClick = {
                                scope.launch { settings.setWakeWordModel(model.id) }
                                expanded = false
                            }
                        )
                    }
                }
            }

            HorizontalDivider()

            // Sensitivity
            Text("Sensitivity", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Higher = more sensitive (more false positives). Lower = less sensitive.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column {
                Slider(
                    value = sensitivity,
                    onValueChange = { scope.launch { settings.setWakeWordSensitivity(it) } },
                    valueRange = 0.1f..0.9f,
                    steps = 7,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Low", fontSize = 12.sp)
                    Text("%.1f".format(sensitivity), fontWeight = FontWeight.Bold)
                    Text("High", fontSize = 12.sp)
                }
            }
        }
    }
}
