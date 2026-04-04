package io.livekit.android.example.voiceassistant.screen

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.LiveKit
import io.livekit.android.annotations.Beta
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.example.voiceassistant.audio.AudioPipelineManager
import io.livekit.android.example.voiceassistant.audio.WakeWordManager
import io.livekit.android.example.voiceassistant.settings.AppSettings
import io.livekit.android.example.voiceassistant.ui.*
import io.livekit.android.example.voiceassistant.util.HomeAssistantDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.net.URL

private const val TAG = "MainDashboard"

@Serializable
object MainDashboardRoute

enum class VoiceState {
    INITIALIZING,
    WAKE_WORD,
    CONVERSATION,
}

@OptIn(Beta::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MainDashboardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appSettings = remember { AppSettings(context) }

    // --- Settings state ---
    val haUrl by appSettings.haUrl.collectAsState(initial = AppSettings.DEFAULT_HA_URL)
    val livekitUrl by appSettings.livekitServerUrl.collectAsState(initial = AppSettings.DEFAULT_LIVEKIT_URL)
    val tokenServerUrl by appSettings.tokenServerUrl.collectAsState(initial = AppSettings.DEFAULT_TOKEN_SERVER_URL)
    val anamEnabled by appSettings.anamEnabled.collectAsState(initial = false)
    val anamApiKey by appSettings.anamApiKey.collectAsState(initial = "")
    val anamAvatarId by appSettings.anamAvatarId.collectAsState(initial = "")
    val deviceId by appSettings.deviceId.collectAsState(initial = AppSettings.DEFAULT_DEVICE_ID)
    val deviceDisplayName by appSettings.deviceDisplayName.collectAsState(initial = AppSettings.DEFAULT_DEVICE_DISPLAY_NAME)
    val deviceRoomLocation by appSettings.deviceRoomLocation.collectAsState(initial = AppSettings.DEFAULT_DEVICE_ROOM_LOCATION)

    // --- Voice state ---
    var voiceState by remember { mutableStateOf(VoiceState.INITIALIZING) }
    var conversationStatus by remember { mutableStateOf(ConversationStatus.LISTENING) }
    val chatMessages = remember { mutableStateListOf<ChatMessage>() }
    var messageCounter by remember { mutableIntStateOf(0) }

    // --- HA state ---
    var haConnectionOk by remember { mutableStateOf(false) }
    var haLoading by remember { mutableStateOf(true) }
    var haResolvedUrl by remember { mutableStateOf<String?>(null) }

    // --- Settings drawer ---
    var settingsVisible by remember { mutableStateOf(false) }

    // --- Audio/LiveKit ---
    val audioPipeline = remember { AudioPipelineManager() }
    val wakeWordManager = remember { WakeWordManager(context) }
    val room = remember { LiveKit.create(context) }
    var roomConnected by remember { mutableStateOf(false) }

    // --- Avatar ---
    var avatarVideoTrack by remember { mutableStateOf<io.livekit.android.room.track.VideoTrack?>(null) }

    // --- Layout ---
    val isConversation = voiceState == VoiceState.CONVERSATION

    // --- Auto-detect HA on launch ---
    LaunchedEffect(Unit) {
        val stored = haUrl
        // Test stored URL first
        val ok = withContext(Dispatchers.IO) { HomeAssistantDetector.testHaUrl(stored) }
        if (ok) {
            haResolvedUrl = stored
            haConnectionOk = true
            Log.i(TAG, "HA reachable at $stored")
        } else {
            // Try auto-detection
            val result = HomeAssistantDetector.detect()
            if (result.found && result.url != null) {
                haResolvedUrl = result.url
                haConnectionOk = true
                appSettings.setHaUrl(result.url)
                appSettings.setHaAutoDetected(true)
                Log.i(TAG, "HA auto-detected at ${result.url}")
            } else {
                // Fall back to stored URL anyway (user might fix in settings)
                haResolvedUrl = stored
                haConnectionOk = false
                Log.w(TAG, "HA not reachable, loading $stored anyway")
            }
        }
    }

    // --- Resolve effective device identity (fall back to "android-user" if not set) ---
    val effectiveDeviceId = if (deviceId.isNotEmpty()) deviceId else "android-user"

    // --- Register device & connect to LiveKit ---
    LaunchedEffect(effectiveDeviceId) {
        // Register with token server
        try {
            val regBody = JSONObject().apply {
                put("device_id", effectiveDeviceId)
                put("display_name", deviceDisplayName.ifEmpty { effectiveDeviceId })
                put("room_location", deviceRoomLocation)
            }
            withContext(Dispatchers.IO) {
                val conn = java.net.URL("$tokenServerUrl/register").openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.write(regBody.toString().toByteArray())
                conn.inputStream.bufferedReader().readText()
                conn.disconnect()
            }
            Log.i(TAG, "Device registered: $effectiveDeviceId")
        } catch (e: Exception) {
            Log.w(TAG, "Device registration failed: ${e.message}")
        }

        // Connect to LiveKit with device identity
        try {
            val response = withContext(Dispatchers.IO) {
                URL("$tokenServerUrl/token?identity=$effectiveDeviceId&room=voice-room").readText()
            }
            val json = JSONObject(response)
            val token = json.getString("token")
            room.connect(livekitUrl, token)
            roomConnected = true
            Log.i(TAG, "LiveKit connected as '$effectiveDeviceId'")
        } catch (e: Exception) {
            Log.e(TAG, "LiveKit connect failed: ${e.message}")
        }
    }

    // --- Background heartbeat every 15s ---
    LaunchedEffect(effectiveDeviceId) {
        while (true) {
            delay(15_000)
            try {
                val hbBody = JSONObject().apply {
                    put("device_id", effectiveDeviceId)
                }
                withContext(Dispatchers.IO) {
                    val conn = java.net.URL("$tokenServerUrl/heartbeat").openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.doOutput = true
                    conn.outputStream.write(hbBody.toString().toByteArray())
                    conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Heartbeat failed: ${e.message}")
            }
        }
    }

    // --- Initialize voice pipeline ---
    LaunchedEffect(Unit) {
        // Initialize wake word
        withContext(Dispatchers.Default) {
            wakeWordManager.initialize("wakeword_models/jarvis_v2.onnx")
        }

        // Start audio pipeline
        audioPipeline.start()
        voiceState = VoiceState.WAKE_WORD
        Log.i(TAG, "Voice pipeline ready")

        // Process wake word audio
        launch(Dispatchers.Default) {
            for (shorts in audioPipeline.wakeWordAudio) {
                val bytes = java.nio.ByteBuffer.allocate(shorts.size * 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .apply { asShortBuffer().put(shorts) }.array()
                wakeWordManager.processAudio(bytes, bytes.size)
            }
        }

        // Consume LiveKit audio channel
        launch(Dispatchers.IO) {
            for (shorts in audioPipeline.livekitAudio) {
                // Placeholder for custom AudioSource
            }
        }

        // Listen for wake word detections
        launch {
            wakeWordManager.scores.collectLatest { score ->
                if (score > 0.5f && voiceState == VoiceState.WAKE_WORD) {
                    Log.i(TAG, "Wake word detected! score=${"%.3f".format(score)}")
                    voiceState = VoiceState.CONVERSATION
                    conversationStatus = ConversationStatus.LISTENING
                    chatMessages.clear()

                    scope.launch {
                        // Stop AudioRecord so LiveKit can use the mic
                        audioPipeline.stop()

                        try {
                            room.localParticipant.setMicrophoneEnabled(true)
                            Log.i(TAG, "LiveKit mic enabled")

                            // Signal server (include avatar config if enabled)
                            val signalJson = JSONObject().apply {
                                put("type", "wake_word_detected")
                                put("score", "%.3f".format(score))
                                if (anamEnabled && anamApiKey.isNotEmpty() && anamAvatarId.isNotEmpty()) {
                                    put("anam_enabled", true)
                                    put("anam_api_key", anamApiKey)
                                    put("anam_avatar_id", anamAvatarId)
                                }
                            }
                            room.localParticipant.publishData(signalJson.toString().toByteArray())
                            Log.i(TAG, "Sent wake_word_detected (avatar=${anamEnabled})")
                        } catch (e: Exception) {
                            Log.e(TAG, "LiveKit failed: ${e.message}")
                        }
                        // No client-side timeout — server sends conversation_ended
                    }
                }
            }
        }
    }

    // --- Listen for LiveKit room events (always active) ---
    LaunchedEffect(roomConnected) {
        if (!roomConnected) return@LaunchedEffect
        Log.i(TAG, "Starting room event listener (persistent)")

        room.events.collect { event ->
            Log.d(TAG, "Room event: ${event::class.simpleName}")
            when (event) {
                is RoomEvent.TranscriptionReceived -> {
                    for (segment in event.transcriptionSegments) {
                        val isUser = event.participant?.identity == room.localParticipant.identity
                        val existingIdx = chatMessages.indexOfFirst { it.id == segment.id }
                        val msg = ChatMessage(
                            id = segment.id,
                            text = segment.text,
                            isUser = isUser,
                        )
                        if (existingIdx >= 0) {
                            chatMessages[existingIdx] = msg
                        } else {
                            chatMessages.add(msg)
                        }
                        conversationStatus = if (isUser) ConversationStatus.THINKING else ConversationStatus.SPEAKING
                        Log.d(TAG, "Transcription [${if (isUser) "user" else "agent"}]: ${segment.text}")
                    }
                }
                is RoomEvent.TrackSubscribed -> {
                    val participant = event.participant
                    val track = event.track
                    if (participant.identity.toString().startsWith("anam") &&
                        track is io.livekit.android.room.track.VideoTrack) {
                        avatarVideoTrack = track
                        Log.i(TAG, "Avatar video track subscribed")
                    }
                }
                is RoomEvent.TrackUnsubscribed -> {
                    val track = event.track
                    if (track == avatarVideoTrack) {
                        avatarVideoTrack = null
                        Log.i(TAG, "Avatar video track unsubscribed")
                    }
                }
                is RoomEvent.DataReceived -> {
                    try {
                        val text = event.data.toString(Charsets.UTF_8)
                        val json = JSONObject(text)
                        val type = json.optString("type", "")
                        Log.d(TAG, "Data received: type=$type")
                        when (type) {
                            "conversation_ended" -> {
                                Log.i(TAG, "Server sent conversation_ended")
                                // Disable mic, restart wake word pipeline
                                try { room.localParticipant.setMicrophoneEnabled(false) } catch (_: Exception) {}
                                audioPipeline.start()
                                voiceState = VoiceState.WAKE_WORD
                            }
                            "chat_message" -> {
                                val message = json.optString("message", "")
                                if (message.isNotEmpty()) {
                                    val isUser = message.startsWith("\uD83C\uDFA4") // 🎤 prefix
                                    // Strip emoji prefix
                                    val cleanText = message
                                        .removePrefix("\uD83C\uDFA4 You: ")  // 🎤 You:
                                        .removePrefix("\uD83E\uDD16 Hermes: ") // 🤖 Hermes:
                                        .removePrefix("\uD83C\uDF1F ")  // 🌟 system
                                        .removePrefix("\uD83D\uDCA4 ")  // 💤 system
                                        .removePrefix("\uD83C\uDFE0 ")  // 🏠 system
                                        .trim()
                                    if (cleanText.isNotEmpty()) {
                                        messageCounter++
                                        chatMessages.add(
                                            ChatMessage(
                                                id = "data-$messageCounter",
                                                text = cleanText,
                                                isUser = isUser,
                                            )
                                        )
                                        conversationStatus = if (isUser) ConversationStatus.THINKING else ConversationStatus.SPEAKING
                                        Log.d(TAG, "Chat [${if (isUser) "user" else "agent"}]: $cleanText")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Non-JSON data received: ${event.data.size} bytes")
                    }
                }
                else -> {
                    Log.d(TAG, "Unhandled event: ${event::class.simpleName}")
                }
            }
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

    // --- Swipe gesture detection for settings ---
    val swipeModifier = Modifier.pointerInput(Unit) {
        detectHorizontalDragGestures { _, dragAmount ->
            if (dragAmount < -30) { // Swipe left
                settingsVisible = true
            }
        }
    }

    // ==================== UI ====================
    Box(modifier = Modifier.fillMaxSize().then(swipeModifier)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val chatCardWidth = maxWidth / 3
            val webViewWidth by animateDpAsState(
                targetValue = if (isConversation) maxWidth - chatCardWidth else maxWidth,
                animationSpec = tween(durationMillis = 300),
                label = "webViewWidth"
            )

            // Home Assistant WebView — left-aligned, animated width
            Box(
                modifier = Modifier
                    .width(webViewWidth)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
            ) {
                if (haResolvedUrl != null) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        haLoading = false
                                    }
                                }
                                webChromeClient = WebChromeClient()
                                loadUrl(haResolvedUrl!!)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (haLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(16.dp))
                                Text("Loading Home Assistant...", fontSize = 14.sp)
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Detecting Home Assistant...",
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Conversation Chat Card — right-aligned, slides in
            AnimatedVisibility(
                visible = isConversation,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            ) {
                ConversationCard(
                    messages = chatMessages,
                    status = conversationStatus,
                    anamEnabled = anamEnabled,
                    anamApiKey = anamApiKey,
                    anamAvatarId = anamAvatarId,
                    onClose = {
                        scope.launch {
                            // Signal server to end conversation
                            try {
                                val endSignal = JSONObject().put("type", "end_conversation")
                                room.localParticipant.publishData(endSignal.toString().toByteArray())
                            } catch (_: Exception) {}
                            // Disable mic, restart wake word
                            try { room.localParticipant.setMicrophoneEnabled(false) } catch (_: Exception) {}
                            audioPipeline.start()
                            voiceState = VoiceState.WAKE_WORD
                        }
                    },
                    modifier = Modifier
                        .width(chatCardWidth)
                        .fillMaxHeight()
                )
            }
        }

        // Voice Bubble (bottom-right, only in wake word / initializing state)
        if (!isConversation) {
            VoiceBubble(
                isListening = voiceState == VoiceState.WAKE_WORD,
                onClick = {
                    // Manual trigger — simulate wake word detection
                    if (voiceState == VoiceState.WAKE_WORD) {
                        voiceState = VoiceState.CONVERSATION
                        conversationStatus = ConversationStatus.LISTENING
                        chatMessages.clear()
                        scope.launch {
                            audioPipeline.stop()
                            try {
                room.localParticipant.setMicrophoneEnabled(true)
                                val manualSignal = JSONObject().apply {
                                    put("type", "wake_word_detected")
                                    put("score", "1.0")
                                    if (anamEnabled && anamApiKey.isNotEmpty() && anamAvatarId.isNotEmpty()) {
                                        put("anam_enabled", true)
                                        put("anam_api_key", anamApiKey)
                                        put("anam_avatar_id", anamAvatarId)
                                    }
                                }
                                room.localParticipant.publishData(manualSignal.toString().toByteArray())
                            } catch (e: Exception) {
                                Log.e(TAG, "Manual trigger failed: ${e.message}")
                            }
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }

        // Settings gear icon (top-right)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
        ) {
            androidx.compose.material3.IconButton(onClick = { settingsVisible = true }) {
                Text("⚙", fontSize = 24.sp, color = Color.White.copy(alpha = 0.8f))
            }
        }

        // Settings Drawer overlay
        SettingsDrawer(
            visible = settingsVisible,
            onDismiss = { settingsVisible = false },
            settings = appSettings,
            haConnectionOk = haConnectionOk,
        )
    }
}
