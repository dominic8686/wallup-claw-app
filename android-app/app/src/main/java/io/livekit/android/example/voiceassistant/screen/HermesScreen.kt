package io.livekit.android.example.voiceassistant.screen

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.livekit.android.LiveKit
import io.livekit.android.example.voiceassistant.LIVEKIT_WS_URL
import io.livekit.android.example.voiceassistant.TOKEN_SERVER_URL
import io.livekit.android.example.voiceassistant.audio.AudioPipelineManager
import io.livekit.android.example.voiceassistant.audio.WakeWordManager
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.net.URL

@Serializable
object HermesRoute

enum class HermesState {
    CONNECTING,      // Connecting to LiveKit room
    WAKE_WORD,       // Listening for wake word
    CONVERSATION,    // Active conversation with agent
    ERROR            // Something went wrong
}

@Composable
fun HermesScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(HermesState.CONNECTING) }
    var statusText by remember { mutableStateOf("Connecting...") }
    var lastScore by remember { mutableFloatStateOf(0f) }
    var detectionCount by remember { mutableIntStateOf(0) }

    // Core managers
    val audioPipeline = remember { AudioPipelineManager() }
    val wakeWordManager = remember { WakeWordManager(context) }

    // LiveKit room
    val room = remember { LiveKit.create(context) }
    var roomConnected by remember { mutableStateOf(false) }

    // Initialize on first composition
    LaunchedEffect(Unit) {
        // 1. Initialize wake word model
        withContext(Dispatchers.Default) {
            wakeWordManager.initialize("wakeword_models/jarvis_v2.onnx")
        }

        // 2. Start audio pipeline (own the mic for wake word)
        audioPipeline.start()
        state = HermesState.WAKE_WORD
        statusText = "Say \"Hey Jarvis\"..."
        Log.i("HermesScreen", "Audio pipeline started, wake word active")

        // 3. DON'T connect LiveKit yet - it interferes with AudioRecord
        //    Will connect when wake word is detected

        // 4. Process wake word audio (convert ShortArray to ByteArray for WakeWordManager)
        launch(Dispatchers.Default) {
            for (shorts in audioPipeline.wakeWordAudio) {
                val bytes = java.nio.ByteBuffer.allocate(shorts.size * 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .apply { asShortBuffer().put(shorts) }.array()
                wakeWordManager.processAudio(bytes, bytes.size)
            }
        }

        // 5. Consume LiveKit audio channel (prevent backpressure)
        launch(Dispatchers.IO) {
            for (shorts in audioPipeline.livekitAudio) {
                // Audio goes through LiveKit's built-in mic track
                // This channel is for future custom AudioSource usage
            }
        }

        // 6. Listen for wake word detections
        launch {
            wakeWordManager.scores.collectLatest { score ->
                lastScore = score
                if (score > 0.5f && state == HermesState.WAKE_WORD) {
                    detectionCount++
                    Log.i("HermesScreen", "Wake word detected #$detectionCount score=${"%.3f".format(score)}")
                    state = HermesState.CONVERSATION
                    statusText = "Conversation active..."

                    // Launch connection in separate scope (not cancelled by collectLatest)
                    scope.launch {
                        // Stop our AudioRecord so LiveKit can take the mic
                        audioPipeline.stop()
                        Log.i("HermesScreen", "AudioPipeline stopped for LiveKit")

                        // Connect to LiveKit and enable mic
                        try {
                            if (!roomConnected) {
                                val response = withContext(Dispatchers.IO) {
                                    URL("$TOKEN_SERVER_URL/token?identity=android-user&room=voice-room").readText()
                                }
                                val json = JSONObject(response)
                                val token = json.getString("token")
                                room.connect(LIVEKIT_WS_URL, token)
                                roomConnected = true
                                Log.i("HermesScreen", "LiveKit connected")
                            }

                            room.localParticipant.setMicrophoneEnabled(true)
                            Log.i("HermesScreen", "LiveKit mic enabled")

                            // Signal server agent
                            val signal = "{\"type\":\"wake_word_detected\",\"score\":${"%.3f".format(score)}}"
                            room.localParticipant.publishData(signal.toByteArray())
                            Log.i("HermesScreen", "Sent wake_word_detected")
                        } catch (e: Exception) {
                            Log.e("HermesScreen", "LiveKit failed: ${e.message}")
                        }

                        // Auto-return to wake word after timeout
                        delay(30000)
                        if (state == HermesState.CONVERSATION) {
                            try {
                                room.localParticipant.setMicrophoneEnabled(false)
                            } catch (_: Exception) {}
                            room.disconnect()
                            roomConnected = false
                            audioPipeline.start()
                            Log.i("HermesScreen", "Back to wake word mode")
                            state = HermesState.WAKE_WORD
                            statusText = "Say \"Hey Jarvis\"..."
                        }
                    }
                }
            }
        }

        // 7. Listen for server data messages
        launch {
            // TODO: Listen for conversation_ended from server
            // room.events.collect { event -> ... }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            audioPipeline.stop()
            wakeWordManager.release()
            room.disconnect()
            room.release()
        }
    }

    // UI
    val bgColor by animateColorAsState(
        when (state) {
            HermesState.CONNECTING -> Color(0xFFF5F5F5)
            HermesState.WAKE_WORD -> Color(0xFFF8F9FA)
            HermesState.CONVERSATION -> Color(0xFFE8F5E9)
            HermesState.ERROR -> Color(0xFFFFEBEE)
        }, label = "bg"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().background(bgColor)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // Pulsing circle indicator
            val pulseScale by rememberInfiniteTransition(label = "pulse").animateFloat(
                initialValue = 1f,
                targetValue = if (state == HermesState.WAKE_WORD) 1.2f else if (state == HermesState.CONVERSATION) 1.4f else 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000),
                    repeatMode = RepeatMode.Reverse
                ), label = "pulseScale"
            )

            val circleColor = when (state) {
                HermesState.CONNECTING -> Color.Gray
                HermesState.WAKE_WORD -> Color(0xFF2196F3) // Blue
                HermesState.CONVERSATION -> Color(0xFF4CAF50) // Green
                HermesState.ERROR -> Color(0xFFF44336) // Red
            }

            Box(
                modifier = Modifier
                    .size(120.dp)
                    .scale(pulseScale)
                    .background(circleColor.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(circleColor.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(circleColor, CircleShape)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))

            // State label
            Text(
                text = when (state) {
                    HermesState.CONNECTING -> "Connecting..."
                    HermesState.WAKE_WORD -> "Listening"
                    HermesState.CONVERSATION -> "Conversation Active"
                    HermesState.ERROR -> "Error"
                },
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = circleColor
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = statusText,
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            // Score indicator (visible in wake word mode)
            if (state == HermesState.WAKE_WORD || state == HermesState.CONVERSATION) {
                LinearProgressIndicator(
                    progress = { lastScore.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(0.6f).height(8.dp),
                    color = if (lastScore > 0.5f) Color(0xFF4CAF50) else Color(0xFF2196F3),
                    trackColor = Color(0xFFE0E0E0),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Score: ${"%.3f".format(lastScore)}  |  Detections: $detectionCount",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }

        // Settings gear (top right)
        Box(
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        ) {
            IconButton(onClick = { /* TODO: navigate to settings */ }) {
                Text("⚙", fontSize = 24.sp)
            }
        }
    }
}
