package io.livekit.android.example.voiceassistant.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.livekit.android.example.voiceassistant.settings.*
import io.livekit.android.example.voiceassistant.wakeword.OpenWakeWordEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

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
            HorizontalDivider()

            // Live Wake Word Test
            Text("Test Wake Word", fontWeight = FontWeight.Bold, fontSize = 18.sp)

            var isTesting by remember { mutableStateOf(false) }
            var currentScore by remember { mutableFloatStateOf(0f) }

            Column {
                // Score bar
                LinearProgressIndicator(
                    progress = { currentScore.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(24.dp),
                    color = if (currentScore >= sensitivity) Color(0xFF4CAF50) else Color(0xFFFF5722),
                    trackColor = Color(0xFFE0E0E0),
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Score: ${"%08.6f".format(currentScore)}", fontWeight = FontWeight.Bold)
                    Text(
                        if (currentScore >= sensitivity) "DETECTED" else "listening...",
                        color = if (currentScore >= sensitivity) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (currentScore >= sensitivity) FontWeight.Bold else FontWeight.Normal
                    )
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (isTesting) {
                            isTesting = false
                        } else {
                            isTesting = true
                            val modelInfo = BUNDLED_MODELS.find { it.id == selectedModel } ?: BUNDLED_MODELS.first()
                            // Split capture and processing like wyoming does
                            val audioChannel = kotlinx.coroutines.channels.Channel<ShortArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)

                            // Audio capture on IO thread (never blocked by ONNX)
                            scope.launch(Dispatchers.IO) {
                                val sr = 16000
                                val bufSize = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                                try {
                                    val recorder = AudioRecord(
                                        MediaRecorder.AudioSource.MIC, sr,
                                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                                        bufSize * 2
                                    )
                                    recorder.startRecording()
                                    android.util.Log.i("WakeWordTest", "Capture started (IO thread)")
                                    val byteBuffer = ByteArray(4096)
                                    var frameCount = 0
                                    while (isTesting) {
                                        val bytesRead = recorder.read(byteBuffer, 0, byteBuffer.size)
                                        if (bytesRead > 0) {
                                            frameCount++
                                            val shorts = ShortArray(bytesRead / 2)
                                            java.nio.ByteBuffer.wrap(byteBuffer, 0, bytesRead)
                                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                                .asShortBuffer().get(shorts)
                                            if (frameCount <= 5 || frameCount % 100 == 0) {
                                                val rms = Math.sqrt(shorts.map { it.toDouble() * it.toDouble() }.average())
                                                android.util.Log.d("WakeWordTest", "Capture #$frameCount: rms=${"%,.0f".format(rms)}")
                                            }
                                            audioChannel.trySend(shorts)
                                        }
                                    }
                                    recorder.stop()
                                    recorder.release()
                                    audioChannel.close()
                                    android.util.Log.i("WakeWordTest", "Capture stopped")
                                } catch (e: Exception) {
                                    android.util.Log.e("WakeWordTest", "Capture error: ${e.message}")
                                }
                            }

                            // ONNX processing on Default thread (separate from capture)
                            scope.launch(Dispatchers.Default) {
                                val engine = OpenWakeWordEngine()
                                engine.onScoreUpdate = { score ->
                                    scope.launch(Dispatchers.Main) { currentScore = score }
                                }
                                engine.initialize(context, modelInfo.assetPath, sensitivity)
                                if (!engine.isInitialized) {
                                    isTesting = false
                                    return@launch
                                }
                                for (shorts in audioChannel) {
                                    engine.processAudio(shorts)
                                }
                                engine.release()
                                android.util.Log.i("WakeWordTest", "Processing stopped")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isTesting) Color(0xFFFF5722) else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isTesting) "STOP TEST" else "START TEST")
                }
            }
        }
    }
}
