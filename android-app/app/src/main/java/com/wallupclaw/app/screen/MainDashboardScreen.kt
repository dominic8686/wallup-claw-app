package com.wallupclaw.app.screen

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import com.wallupclaw.app.audio.AudioPipelineManager
import com.wallupclaw.app.audio.WakeWordManager
import com.wallupclaw.app.intercom.IntercomManager
import com.wallupclaw.app.intercom.IntercomState
import com.wallupclaw.app.settings.AppSettings
import com.wallupclaw.app.settings.BUNDLED_MODELS
import com.wallupclaw.app.settings.ButtonCorner
import com.wallupclaw.app.dlna.JupnpRendererService
import com.wallupclaw.app.state.DeviceState
import com.wallupclaw.app.state.DeviceStateManager
import com.wallupclaw.app.ui.*
import com.wallupclaw.app.util.HomeAssistantDetector
import com.wallupclaw.app.util.TokenServerClient
import kotlinx.coroutines.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import kotlinx.serialization.Serializable
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import org.json.JSONObject

private const val TAG = "MainDashboard"

@Serializable
object MainDashboardRoute

/** Whether the LiveKit mic is unmuted and the native agent is active. */
enum class MicGateState {
    INITIALIZING,   // Waiting for wake word engine to initialize
    WAKE_WORD,      // AudioPipeline running, LiveKit mic muted
    AGENT_ACTIVE,   // AudioPipeline stopped, LiveKit mic unmuted — native agent handles everything
}

@OptIn(Beta::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MainDashboardScreen() {
    // Permissions are guaranteed by PermissionGateScreen before reaching here.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appSettings = remember { AppSettings(context) }

    // --- Settings state ---
    val haUrl by appSettings.haUrl.collectAsState(initial = AppSettings.DEFAULT_HA_URL)
    val livekitUrl by appSettings.livekitServerUrl.collectAsState(initial = AppSettings.DEFAULT_LIVEKIT_URL)
    val tokenServerUrl by appSettings.tokenServerUrl.collectAsState(initial = AppSettings.DEFAULT_TOKEN_SERVER_URL)
    val deviceId by appSettings.deviceId.collectAsState(initial = "__LOADING__")
    val deviceDisplayName by appSettings.deviceDisplayName.collectAsState(initial = AppSettings.DEFAULT_DEVICE_DISPLAY_NAME)
    val deviceRoomLocation by appSettings.deviceRoomLocation.collectAsState(initial = AppSettings.DEFAULT_DEVICE_ROOM_LOCATION)
    val intercomApiKey by appSettings.intercomApiKey.collectAsState(initial = "")
    val selectedWakeWordModel by appSettings.wakeWordModel.collectAsState(initial = BUNDLED_MODELS.first().id)
    val buttonCorner by appSettings.buttonCorner.collectAsState(initial = ButtonCorner.BOTTOM_END)

    // --- Token server HTTP client (attaches auth header) ---
    val tokenServerClient = remember(tokenServerUrl) { TokenServerClient(tokenServerUrl) }
    // Keep API key in sync
    LaunchedEffect(intercomApiKey) { tokenServerClient.apiKey = intercomApiKey }

    // --- Voice state (mic-gate pattern) ---
    var micGateState by remember { mutableStateOf(MicGateState.INITIALIZING) }
    var conversationStatus by remember { mutableStateOf(ConversationStatus.LISTENING) }
    val chatMessages = remember { mutableStateListOf<ChatMessage>() }

    // --- HA state ---
    var haConnectionOk by remember { mutableStateOf(false) }
    var haLoading by remember { mutableStateOf(true) }
    var haResolvedUrl by remember { mutableStateOf<String?>(null) }

    // --- Settings drawer ---
    var settingsVisible by remember { mutableStateOf(false) }

    // --- Contacts panel (left side) ---
    var contactsVisible by remember { mutableStateOf(false) }

    // --- Audio/LiveKit ---
    val audioPipeline = remember { AudioPipelineManager() }
    val wakeWordManager = remember { WakeWordManager(context) }
    val room = remember {
        LiveKit.create(context).apply {
            // Camera: 720p @ 15fps for security cam + vision AI
            videoTrackCaptureDefaults = io.livekit.android.room.track.LocalVideoTrackOptions(
                captureParams = io.livekit.android.room.track.VideoCaptureParameter(
                    width = 1280,
                    height = 720,
                    maxFps = 15,
                ),
            )
        }
    }
    var roomConnected by remember { mutableStateOf(false) }

    // --- Central resource coordinator ---
    val securityCameraEnabled by appSettings.securityCameraEnabled.collectAsState(initial = true)
    val deviceStateManager = remember(audioPipeline, room) {
        DeviceStateManager(
            context = context,
            audioPipeline = audioPipeline,
            voiceRoom = room,
            securityCameraEnabled = true,
        )
    }
    // Keep DeviceStateManager in sync with the setting
    LaunchedEffect(securityCameraEnabled) {
        deviceStateManager.setSecurityCameraEnabled(securityCameraEnabled)
    }
    val deviceState by deviceStateManager.state.collectAsState()

    // --- Local camera track for conversation self-view ---
    var localCameraTrack by remember { mutableStateOf<VideoTrack?>(null) }

    // --- Layout ---
    val isConversation = micGateState == MicGateState.AGENT_ACTIVE

    // --- Mic-gate lifecycle: wake word toggles LiveKit mic on/off ---
    // All resource management (mic, camera, audio focus) is handled by DeviceStateManager.
    val activateMic: () -> Unit = remember(room, deviceStateManager) {
        {
            if (micGateState == MicGateState.WAKE_WORD) {
                micGateState = MicGateState.AGENT_ACTIVE
                conversationStatus = ConversationStatus.LISTENING
                chatMessages.clear()
                scope.launch {
                    // DeviceStateManager: stop audioPipeline → unmute LiveKit mic → duck DLNA → speaker on
                    deviceStateManager.transitionTo(DeviceState.CONVERSATION)
                    Log.i(TAG, "Mic activated — native agent is now listening")
                    // Capture local camera track (fallback if TrackPublished event was missed)
                    delay(500)
                    if (localCameraTrack == null) {
                        val camPub = room.localParticipant.getTrackPublication(Track.Source.CAMERA)
                        val track = camPub?.track
                        if (track is VideoTrack) {
                            localCameraTrack = track
                            Log.i(TAG, "Camera track captured via fallback")
                        }
                    }
                }
            }
        }
    }

    val deactivateMic: () -> Unit = remember(room, deviceStateManager) {
        {
            if (micGateState == MicGateState.AGENT_ACTIVE) {
                scope.launch {
                    // DeviceStateManager: mute LiveKit mic → restart audioPipeline → un-duck DLNA
                    deviceStateManager.transitionTo(DeviceState.IDLE)
                    micGateState = MicGateState.WAKE_WORD
                    chatMessages.clear()  // dismiss conversation card
                    Log.i(TAG, "Mic deactivated — back to wake word")
                }
            }
        }
    }

    // --- Request battery optimization exemption (one-time) ---
    LaunchedEffect(Unit) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            try {
                val intent = android.content.Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "Requested battery optimization exemption")
            } catch (e: Exception) {
                Log.w(TAG, "Could not request battery optimization exemption: ${e.message}")
            }
        }
    }

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

    // --- Resolve effective device identity (auto-generate if not set) ---
    LaunchedEffect(deviceId) {
        if (deviceId == "__LOADING__") return@LaunchedEffect // DataStore still loading
        if (deviceId.isEmpty()) {
            val generated = "tablet-" + java.util.UUID.randomUUID().toString().take(8)
            appSettings.setDeviceId(generated)
            Log.i(TAG, "Auto-generated device ID: $generated")
        }
    }
    val effectiveDeviceId = when {
        deviceId == "__LOADING__" || deviceId.isEmpty() -> "tablet-pending"
        else -> deviceId
    }

    // --- Intercom ---
    val intercomManager = remember(tokenServerUrl, effectiveDeviceId) {
        IntercomManager(tokenServerUrl, effectiveDeviceId, tokenServerClient)
    }
    val intercomState by intercomManager.state.collectAsState()
    val currentCall by intercomManager.currentCall.collectAsState()
    val autoAnswerCalls by appSettings.autoAnswerCalls.collectAsState(initial = false)
    val isCallActive = intercomState == IntercomState.RINGING || intercomState == IntercomState.IN_CALL || intercomState == IntercomState.CALLING
    val showLeftPanel = contactsVisible || isCallActive
    val showRightPanel = isConversation || chatMessages.isNotEmpty()

    // --- Auto-answer incoming calls ---
    LaunchedEffect(intercomState, autoAnswerCalls) {
        if (intercomState == IntercomState.RINGING && autoAnswerCalls) {
            Log.i(TAG, "Auto-answering incoming call")
            intercomManager.acceptCall()
        }
    }

    // --- Call room state (separate from voice-room) ---
    var callRoom by remember { mutableStateOf<io.livekit.android.room.Room?>(null) }
    var remoteVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var localVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }

    // --- Clean up call room when remote side hangs up ---
    // IntercomManager sets state to IDLE when it receives call_hangup/call_decline from remote.
    // We need to also disconnect the LiveKit call room and restore DeviceStateManager.
    LaunchedEffect(intercomState) {
        if (intercomState == IntercomState.IDLE && callRoom != null) {
            Log.i(TAG, "Remote hangup detected — cleaning up call room")
            scope.launch {
                deviceStateManager.transitionTo(DeviceState.IDLE)
                callRoom?.disconnect()
                callRoom?.release()
                callRoom = null
                deviceStateManager.callRoom = null
                remoteVideoTrack = null
                localVideoTrack = null
            }
        }
    }

    // --- Auto-join call room when CALLING/IN_CALL and not yet connected ---
    LaunchedEffect(effectiveDeviceId, intercomManager) {
        snapshotFlow { Triple(intercomState, currentCall, callRoom) }.collect { (state, call, room) ->
            if ((state == IntercomState.IN_CALL || state == IntercomState.CALLING) && call != null && room == null) {
                Log.i(TAG, "$state state detected, joining room: ${call.roomName} (outgoing=${call.isOutgoing})")
                try {
                    val cr = LiveKit.create(context)
                    val tokenResp = withContext(Dispatchers.IO) {
                        tokenServerClient.get("/token?identity=$effectiveDeviceId&room=${call.roomName}")
                    }
                    val tokenJson = JSONObject(tokenResp)
                    cr.connect(livekitUrl, tokenJson.getString("token"))
                    // DeviceStateManager handles mic/camera/audio focus transition
                    deviceStateManager.callRoom = cr
                    deviceStateManager.transitionTo(DeviceState.INTERCOM_CALL)
                    callRoom = cr
                    Log.i(TAG, "Joined call room: ${call.roomName} (outgoing=${call.isOutgoing})")
                    // Listen for remote tracks in outer scope so it survives snapshotFlow re-emissions
                    scope.launch {
                        cr.events.collect { event ->
                            when (event) {
                                is RoomEvent.TrackSubscribed -> {
                                    if (event.track is VideoTrack) {
                                        remoteVideoTrack = event.track as VideoTrack
                                        Log.i(TAG, "Call: remote video subscribed")
                                    }
                                }
                                is RoomEvent.TrackUnsubscribed -> {
                                    if (event.track is VideoTrack) {
                                        remoteVideoTrack = null
                                    }
                                }
                                else -> {}
                            }
                        }
                    }
                    val localCamPub = cr.localParticipant.getTrackPublication(Track.Source.CAMERA)
                    localVideoTrack = localCamPub?.track as? VideoTrack
                    // Check for already-published remote video tracks
                    for (participant in cr.remoteParticipants.values) {
                        val videoPub = participant.getTrackPublication(Track.Source.CAMERA)
                        val track = videoPub?.track
                        if (track is VideoTrack) {
                            remoteVideoTrack = track
                            Log.i(TAG, "Call: found existing remote video from ${participant.identity}")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to join call room: ${e.message}")
                }
            }
        }
    }

    // --- Register device & connect to LiveKit (with retry until success) ---
    LaunchedEffect(effectiveDeviceId) {
        if (effectiveDeviceId == "tablet-pending") return@LaunchedEffect

        // Register + connect loop — retries every 10s until both succeed
        while (!roomConnected) {
            // Register with token server
            try {
                val regBody = JSONObject().apply {
                    put("device_id", effectiveDeviceId)
                    put("display_name", deviceDisplayName.ifEmpty { effectiveDeviceId })
                    put("room_location", deviceRoomLocation)
                }
                withContext(Dispatchers.IO) {
                    tokenServerClient.post("/register", regBody.toString())
                }
                Log.i(TAG, "Device registered: $effectiveDeviceId")
            } catch (e: Exception) {
                Log.w(TAG, "Device registration failed: ${e.message}, will retry...")
                delay(10_000)
                continue
            }

            // Connect to LiveKit with device identity — mic starts MUTED
            // (AudioPipelineManager owns the mic for wake word detection)
            try {
                val response = withContext(Dispatchers.IO) {
                    tokenServerClient.get("/token?identity=$effectiveDeviceId&room=voice-room-$effectiveDeviceId")
                }
                val json = JSONObject(response)
                val token = json.getString("token")
                room.connect(livekitUrl, token)
                roomConnected = true
                // Mic starts MUTED — wake word detection via AudioPipelineManager.
                // DeviceStateManager.transitionTo(CONVERSATION) will unmute when triggered.
                room.localParticipant.setMicrophoneEnabled(false)
                Log.i(TAG, "LiveKit connected as '$effectiveDeviceId', mic muted (wake word active)")
            } catch (e: Exception) {
                Log.e(TAG, "LiveKit connect failed: ${e.message}, retrying in 10s...")
                delay(10_000)
            }
        }
    }

    // --- Start intercom signal polling ---
    LaunchedEffect(intercomManager) {
        if (effectiveDeviceId == "tablet-pending") return@LaunchedEffect
        intercomManager.start()
        Log.i(TAG, "IntercomManager signal polling started")
    }

    // --- TTS announcements from intercom ---
    val tts = remember {
        android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                Log.i(TAG, "TTS engine ready")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }
    val pendingAnnouncement by intercomManager.pendingAnnouncement.collectAsState()
    LaunchedEffect(pendingAnnouncement) {
        val message = pendingAnnouncement ?: return@LaunchedEffect
        Log.i(TAG, "Speaking announcement: ${message.take(50)}")
        tts.speak(message, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "announcement")
        intercomManager.clearAnnouncement()
    }
    DisposableEffect(tts) {
        onDispose { tts.shutdown() }
    }

    // --- Remote conversation trigger from HA ---
    val pendingConversation by intercomManager.pendingConversation.collectAsState()
    LaunchedEffect(pendingConversation) {
        if (!pendingConversation) return@LaunchedEffect
        Log.i(TAG, "Remote conversation trigger received")
        activateMic()
        intercomManager.clearConversation()
    }

    // --- Background heartbeat every 15s + config polling ---
    LaunchedEffect(effectiveDeviceId) {
        if (effectiveDeviceId == "tablet-pending") return@LaunchedEffect
        while (true) {
            delay(15_000)
            try {
                val hbBody = JSONObject().apply {
                    put("device_id", effectiveDeviceId)
                }
                val hbCode = withContext(Dispatchers.IO) {
                    tokenServerClient.postGetCode("/heartbeat", hbBody.toString())
                }
                if (hbCode == 404) {
                    Log.w(TAG, "Heartbeat 404 — re-registering device")
                    withContext(Dispatchers.IO) {
                        val regBody = JSONObject().apply {
                            put("device_id", effectiveDeviceId)
                            put("display_name", deviceDisplayName.ifEmpty { effectiveDeviceId })
                            put("room_location", deviceRoomLocation)
                        }
                        tokenServerClient.post("/register", regBody.toString())
                    }
                    Log.i(TAG, "Re-registered after 404")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Heartbeat failed: ${e.message}")
            }

            // Poll for pending config from HA
            try {
                val configResp = withContext(Dispatchers.IO) {
                    tokenServerClient.get("/configure?device_id=$effectiveDeviceId")
                }
                val configJson = JSONObject(configResp)
                val config = configJson.optJSONObject("config")
                if (config != null) {
                    if (config.has("display_name")) {
                        appSettings.setDeviceDisplayName(config.getString("display_name"))
                        Log.i(TAG, "Remote config: display_name = ${config.getString("display_name")}")
                    }
                    if (config.has("room_location")) {
                        appSettings.setDeviceRoomLocation(config.getString("room_location"))
                        Log.i(TAG, "Remote config: room_location = ${config.getString("room_location")}")
                    }
                    if (config.has("security_camera_enabled")) {
                        val enabled = config.getBoolean("security_camera_enabled")
                        appSettings.setSecurityCameraEnabled(enabled)
                        deviceStateManager.setSecurityCameraEnabled(enabled)
                        Log.i(TAG, "Remote config: security_camera_enabled = $enabled")
                    }
                    if (config.has("auto_answer_calls")) {
                        appSettings.setAutoAnswerCalls(config.getBoolean("auto_answer_calls"))
                        Log.i(TAG, "Remote config: auto_answer_calls = ${config.getBoolean("auto_answer_calls")}")
                    }
                    if (config.has("auto_start_on_boot")) {
                        appSettings.setAutoStartOnBoot(config.getBoolean("auto_start_on_boot"))
                        Log.i(TAG, "Remote config: auto_start_on_boot = ${config.getBoolean("auto_start_on_boot")}")
                    }
                    if (config.has("wakeword_model")) {
                        appSettings.setWakeWordModel(config.getString("wakeword_model"))
                        Log.i(TAG, "Remote config: wakeword_model = ${config.getString("wakeword_model")}")
                    }
                    if (config.has("wakeword_sensitivity")) {
                        appSettings.setWakeWordSensitivity(config.getDouble("wakeword_sensitivity").toFloat())
                        Log.i(TAG, "Remote config: wakeword_sensitivity = ${config.getDouble("wakeword_sensitivity")}")
                    }
                    if (config.has("call_mode")) {
                        appSettings.setCallMode(com.wallupclaw.app.settings.CallMode.fromValue(config.getString("call_mode")))
                        Log.i(TAG, "Remote config: call_mode = ${config.getString("call_mode")}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Config poll failed: ${e.message}")
            }
        }
    }

    // --- Audio diagnostics logger (every 10s while connected) ---
    LaunchedEffect(roomConnected) {
        if (!roomConnected) return@LaunchedEffect
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        while (true) {
            delay(10_000)
            try {
                // Android audio state
                val mode = when (am.mode) {
                    android.media.AudioManager.MODE_NORMAL -> "NORMAL"
                    android.media.AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
                    android.media.AudioManager.MODE_IN_CALL -> "IN_CALL"
                    android.media.AudioManager.MODE_RINGTONE -> "RINGTONE"
                    else -> "UNKNOWN(${am.mode})"
                }
                @Suppress("DEPRECATION")
                val spk = am.isSpeakerphoneOn
                Log.i("AudioDiag", "Android: mode=$mode speaker=$spk vol=${am.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)}/${am.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL)} music=${am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}")

                // WebRTC subscriber stats (incoming audio from agent)
                val statsKeys = listOf("mimeType", "clockRate", "channels", "bytesReceived",
                    "packetsReceived", "packetsLost", "jitter", "audioLevel",
                    "totalSamplesReceived", "concealedSamples", "jitterBufferDelay",
                    "jitterBufferEmittedCount", "codecId", "kind")
                room.getSubscriberRTCStats { report ->
                    report.statsMap.forEach { entry ->
                        val stat = entry.value
                        if (stat.type == "inbound-rtp" || stat.type == "codec") {
                            val sb = StringBuilder("WebRTC ${stat.type}: ")
                            for (key in statsKeys) {
                                val v = stat.members[key]
                                if (v != null) sb.append("$key=$v ")
                            }
                            Log.i("AudioDiag", sb.toString().trim())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("AudioDiag", "Stats collection failed: ${e.message}")
            }
        }
    }

    // --- Start DLNA speaker service ---
    LaunchedEffect(effectiveDeviceId) {
        if (effectiveDeviceId == "tablet-pending") return@LaunchedEffect
        val dlnaIntent = android.content.Intent(context, JupnpRendererService::class.java).apply {
            putExtra("device_id", effectiveDeviceId)
            putExtra("friendly_name", deviceDisplayName.ifEmpty { effectiveDeviceId })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(dlnaIntent)
        } else {
            context.startService(dlnaIntent)
        }
        Log.i(TAG, "DLNA renderer service started for $effectiveDeviceId")
    }

    // --- Initialize voice pipeline (wake word → mic gate) ---
    LaunchedEffect(selectedWakeWordModel) {
        // Initialize wake word with selected model from settings
        val model = BUNDLED_MODELS.find { it.id == selectedWakeWordModel } ?: BUNDLED_MODELS.first()
        Log.i(TAG, "Initializing wake word: ${model.displayName} (${model.assetPath})")
        withContext(Dispatchers.Default) {
            wakeWordManager.initialize(model.assetPath)
        }

        // Start audio pipeline (wake word owns the mic; LiveKit mic is muted)
        audioPipeline.start()
        micGateState = MicGateState.WAKE_WORD
        Log.i(TAG, "Voice pipeline ready — listening for wake word")

        // Process wake word audio
        launch(Dispatchers.Default) {
            for (shorts in audioPipeline.wakeWordAudio) {
                val bytes = java.nio.ByteBuffer.allocate(shorts.size * 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .apply { asShortBuffer().put(shorts) }.array()
                wakeWordManager.processAudio(bytes, bytes.size)
            }
        }

        // Consume LiveKit audio channel (prevent backpressure)
        launch(Dispatchers.IO) {
            for (shorts in audioPipeline.livekitAudio) {
                // Not used — LiveKit handles its own audio when mic is unmuted
            }
        }

        // Listen for wake word detections → activate mic
        launch {
            wakeWordManager.scores.collectLatest { score ->
                if (score > 0.5f && micGateState == MicGateState.WAKE_WORD) {
                    Log.i(TAG, "Wake word detected! score=${"%.3f".format(score)}")
                    activateMic()
                }
            }
        }
    }

    // --- Listen for LiveKit room events; reconnect on disconnect ---
    // Native agent sends TranscriptionReceived events automatically.
    LaunchedEffect(roomConnected) {
        if (!roomConnected) return@LaunchedEffect
        Log.i(TAG, "Starting room event listener (native agent mode)")

        room.events.collect { event ->
            when (event) {
                is RoomEvent.Disconnected -> {
                    Log.w(TAG, "Room disconnected — will reconnect")
                    roomConnected = false  // triggers retry loop
                }
                is RoomEvent.TrackPublished -> {
                    // Capture local camera track for conversation self-view
                    val track = event.publication.track
                    val isLocal = event.participant.sid == room.localParticipant.sid
                    Log.d(TAG, "TrackPublished: isLocal=$isLocal kind=${track?.kind} participant=${event.participant.identity}")
                    if (isLocal && track is VideoTrack) {
                        localCameraTrack = track
                        Log.i(TAG, "Local camera track captured for self-view")
                    }
                }
                is RoomEvent.TrackUnpublished -> {
                    val track = event.publication.track
                    val isLocal = event.participant.sid == room.localParticipant.sid
                    if (isLocal && track is VideoTrack) {
                        localCameraTrack = null
                        Log.i(TAG, "Local camera track removed")
                    }
                }
                is RoomEvent.TranscriptionReceived -> {
                    // Native LiveKit agent sends transcription events for both user and agent
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
                else -> {
                    Log.d(TAG, "Room event: ${event::class.simpleName}")
                }
            }
        }
    }

    // Cleanup
    DisposableEffect(Unit) {
        onDispose {
            intercomManager.stop()
            intercomManager.release()
            deviceStateManager.release()
            audioPipeline.stop()
            wakeWordManager.release()
            room.disconnect()
            room.release()
        }
    }

    // --- Corner alignment for bubbles ---
    val bubbleAlignment = when (buttonCorner) {
        ButtonCorner.BOTTOM_END -> Alignment.BottomEnd
        ButtonCorner.BOTTOM_START -> Alignment.BottomStart
        ButtonCorner.TOP_END -> Alignment.TopEnd
        ButtonCorner.TOP_START -> Alignment.TopStart
    }

    // ==================== UI ====================
    Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val chatCardWidth = maxWidth / 3
            val leftPanelWidth = maxWidth / 3
            val webViewWidth by animateDpAsState(
                targetValue = when {
                    showLeftPanel && showRightPanel -> maxWidth - chatCardWidth - leftPanelWidth
                    showLeftPanel -> maxWidth - leftPanelWidth
                    showRightPanel -> maxWidth - chatCardWidth
                    else -> maxWidth
                },
                animationSpec = tween(durationMillis = 300),
                label = "webViewWidth"
            )
            val webViewStartPadding by animateDpAsState(
                targetValue = if (showLeftPanel) leftPanelWidth else 0.dp,
                animationSpec = tween(durationMillis = 300),
                label = "webViewStartPadding"
            )

            // Left panel — Contacts + Call UI, slides in from the left
            AnimatedVisibility(
                visible = showLeftPanel,
                modifier = Modifier.align(Alignment.CenterStart),
                enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
            ) {
                Box(
                    modifier = Modifier
                        .width(leftPanelWidth)
                        .fillMaxHeight()
                ) {
                    when {
                        // Incoming call ringing
                        intercomState == IntercomState.RINGING && currentCall != null -> {
                            val call = currentCall!!
                            IncomingCallOverlay(
                                callInfo = IncomingCallInfo(
                                    callId = call.callId,
                                    fromDeviceId = call.remoteDeviceId,
                                    fromDisplayName = call.remoteDisplayName,
                                    roomName = call.roomName,
                                ),
                                onAccept = {
                                    scope.launch {
                                        intercomManager.acceptCall()
                                        // snapshotFlow will detect IN_CALL state and join the room
                                    }
                                },
                                onDecline = {
                                    scope.launch { intercomManager.declineCall() }
                                },
                            )
                        }
                        // Active call
                        intercomState == IntercomState.IN_CALL && currentCall != null -> {
                            val call = currentCall!!
                            ActiveCallScreen(
                                callInfo = ActiveCallInfo(
                                    callId = call.callId,
                                    remoteDeviceId = call.remoteDeviceId,
                                    remoteDisplayName = call.remoteDisplayName,
                                    roomName = call.roomName,
                                ),
                                onToggleMic = { enabled ->
                                    scope.launch { callRoom?.localParticipant?.setMicrophoneEnabled(enabled) }
                                },
                                onToggleCamera = { enabled ->
                                    scope.launch { callRoom?.localParticipant?.setCameraEnabled(enabled) }
                                },
                                onHangup = {
                                    scope.launch {
                                        intercomManager.hangup()
                                        // Transition back to IDLE — restores wake word, security cam, releases audio focus
                                        deviceStateManager.transitionTo(DeviceState.IDLE)
                                        callRoom?.disconnect()
                                        callRoom?.release()
                                        callRoom = null
                                        deviceStateManager.callRoom = null
                                        remoteVideoTrack = null
                                        localVideoTrack = null
                                        Log.i(TAG, "Call ended, room cleaned up")
                                    }
                                },
                                remoteVideoTrack = remoteVideoTrack,
                                localVideoTrack = localVideoTrack,
                            )
                        }
                        // Outgoing call (waiting for answer)
                        intercomState == IntercomState.CALLING && currentCall != null -> {
                            val call = currentCall!!
                            ActiveCallScreen(
                                callInfo = ActiveCallInfo(
                                    callId = call.callId,
                                    remoteDeviceId = call.remoteDeviceId,
                                    remoteDisplayName = call.remoteDisplayName,
                                    roomName = call.roomName,
                                ),
                                onToggleMic = { enabled ->
                                    scope.launch { callRoom?.localParticipant?.setMicrophoneEnabled(enabled) }
                                },
                                onToggleCamera = { enabled ->
                                    scope.launch { callRoom?.localParticipant?.setCameraEnabled(enabled) }
                                },
                                onHangup = {
                                    scope.launch {
                                        intercomManager.hangup()
                                        deviceStateManager.transitionTo(DeviceState.IDLE)
                                        callRoom?.disconnect()
                                        callRoom?.release()
                                        callRoom = null
                                        deviceStateManager.callRoom = null
                                        remoteVideoTrack = null
                                        localVideoTrack = null
                                        Log.i(TAG, "Outgoing call cancelled")
                                    }
                                },
                                remoteVideoTrack = remoteVideoTrack,
                                localVideoTrack = localVideoTrack,
                            )
                        }
                        // Contacts list (default when panel is open)
                        else -> {
                            ContactsPanel(
                                tokenServerUrl = tokenServerUrl,
                                myDeviceId = effectiveDeviceId,
                                client = tokenServerClient,
                                onCallDevice = { targetId, targetName ->
                                    scope.launch {
                                        val result = intercomManager.callDevice(targetId, targetName)
                                        if (result.isSuccess) {
                                            Log.i(TAG, "Call initiated to $targetName ($targetId)")
                                            // LaunchedEffect will handle room joining when state changes to CALLING
                                        } else {
                                            Log.e(TAG, "Call to $targetId failed")
                                        }
                                    }
                                },
                                onClose = { contactsVisible = false },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            // Home Assistant WebView — padded to make room for left panel
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(start = webViewStartPadding)
                    .width(webViewWidth)
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
                visible = showRightPanel,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            ) {
                ConversationCard(
                    messages = chatMessages,
                    status = conversationStatus,
                    videoTrack = localCameraTrack,
                    room = room,
                    onClose = {
                        deactivateMic()
                        chatMessages.clear()  // always dismiss card even if mic already muted
                        localCameraTrack = null
                    },
                    modifier = Modifier
                        .width(chatCardWidth)
                        .fillMaxHeight()
                )
            }
        }

        // Tap-to-dismiss scrim when contacts panel is open
        if (contactsVisible && !isCallActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { contactsVisible = false }
                    )
            )
        }

        // Bubble buttons — stacked in the chosen corner
        val isBottom = buttonCorner == ButtonCorner.BOTTOM_END || buttonCorner == ButtonCorner.BOTTOM_START
        Column(
            modifier = Modifier
                .align(bubbleAlignment)
                .padding(vertical = 16.dp),
            horizontalAlignment = if (buttonCorner == ButtonCorner.BOTTOM_END || buttonCorner == ButtonCorner.TOP_END)
                Alignment.End else Alignment.Start,
        ) {
            if (!isBottom) {
                // Top corners: keep primary controls closest to the corner edge
                VoiceBubble(
                    isListening = micGateState == MicGateState.WAKE_WORD,
                    isAgentActive = micGateState == MicGateState.AGENT_ACTIVE,
                    onClick = {
                        if (micGateState == MicGateState.AGENT_ACTIVE) {
                            deactivateMic()
                        } else {
                            activateMic()
                        }
                    },
                )
                IntercomBubble(
                    isOpen = contactsVisible,
                    isCallActive = isCallActive,
                    onClick = { contactsVisible = !contactsVisible },
                )
                if (!isConversation) {
                    SettingsBubble(
                        onClick = { settingsVisible = true },
                    )
                }
            } else {
                // Bottom corners: keep primary controls closest to the corner edge
                if (!isConversation) {
                    SettingsBubble(
                        onClick = { settingsVisible = true },
                    )
                }
                IntercomBubble(
                    isOpen = contactsVisible,
                    isCallActive = isCallActive,
                    onClick = { contactsVisible = !contactsVisible },
                )
                VoiceBubble(
                    isListening = micGateState == MicGateState.WAKE_WORD,
                    isAgentActive = micGateState == MicGateState.AGENT_ACTIVE,
                    onClick = {
                        if (micGateState == MicGateState.AGENT_ACTIVE) {
                            deactivateMic()
                        } else {
                            activateMic()
                        }
                    },
                )
            }
        }

        // Settings Drawer overlay
        SettingsDrawer(
            visible = settingsVisible,
            onDismiss = { settingsVisible = false },
            settings = appSettings,
            haConnectionOk = haConnectionOk,
        )

    } // end main Box
}
