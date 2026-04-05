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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import com.wallupclaw.app.audio.AudioPipelineManager
import com.wallupclaw.app.audio.WakeWordManager
import com.wallupclaw.app.intercom.IntercomManager
import com.wallupclaw.app.intercom.IntercomState
import com.wallupclaw.app.settings.AppSettings
import com.wallupclaw.app.settings.BUNDLED_MODELS
import com.wallupclaw.app.dlna.DlnaRendererService
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
import org.json.JSONObject

private const val TAG = "MainDashboard"

@Serializable
object MainDashboardRoute

enum class VoiceState {
    INITIALIZING,
    WAKE_WORD,
    CONVERSATION,
    ENDING,
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
    val avatarEnabled by appSettings.avatarEnabled.collectAsState(initial = false)
    val deviceId by appSettings.deviceId.collectAsState(initial = "__LOADING__")
    val deviceDisplayName by appSettings.deviceDisplayName.collectAsState(initial = AppSettings.DEFAULT_DEVICE_DISPLAY_NAME)
    val deviceRoomLocation by appSettings.deviceRoomLocation.collectAsState(initial = AppSettings.DEFAULT_DEVICE_ROOM_LOCATION)
    val intercomApiKey by appSettings.intercomApiKey.collectAsState(initial = "")
    val selectedWakeWordModel by appSettings.wakeWordModel.collectAsState(initial = BUNDLED_MODELS.first().id)

    // --- Token server HTTP client (attaches auth header) ---
    val tokenServerClient = remember(tokenServerUrl) { TokenServerClient(tokenServerUrl) }
    // Keep API key in sync
    LaunchedEffect(intercomApiKey) { tokenServerClient.apiKey = intercomApiKey }

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

    // --- Avatar WebView ref (for JS bridge calls into TalkingHead.js) ---
    val avatarWebViewRef = remember { mutableStateOf<WebView?>(null) }

    // --- Ending fallback job ---
    var endingTimeoutJob by remember { mutableStateOf<Job?>(null) }

    // --- Layout ---
    val isConversation = voiceState == VoiceState.CONVERSATION || voiceState == VoiceState.ENDING

    // --- Shared conversation lifecycle functions ---
    // All resource management (mic, camera, audio focus) is handled by DeviceStateManager.
    val startConversation: (String) -> Unit = remember(room, deviceStateManager, avatarEnabled) {
        { score: String ->
            if (voiceState == VoiceState.WAKE_WORD) {
                voiceState = VoiceState.CONVERSATION
                conversationStatus = ConversationStatus.LISTENING
                chatMessages.clear()
                scope.launch {
                    // DeviceStateManager handles: stop audioPipeline, enable mic + camera, duck DLNA
                    deviceStateManager.transitionTo(DeviceState.CONVERSATION)
                    try {
                        val signalJson = JSONObject().apply {
                            put("type", "wake_word_detected")
                            put("score", score)
                            put("avatar_enabled", avatarEnabled)
                        }
                        room.localParticipant.publishData(signalJson.toString().toByteArray())
                        Log.i(TAG, "Sent wake_word_detected (score=$score, avatar=$avatarEnabled)")
                    } catch (e: Exception) {
                        Log.e(TAG, "startConversation failed: ${e.message}")
                    }
                }
            }
        }
    }

    fun resetToWakeWord() {
        endingTimeoutJob?.cancel()
        endingTimeoutJob = null
        scope.launch {
            // DeviceStateManager handles: disable mic + camera, restart audioPipeline, re-enable security cam
            deviceStateManager.transitionTo(DeviceState.IDLE)
            voiceState = VoiceState.WAKE_WORD
            Log.i(TAG, "Reset to WAKE_WORD")
        }
    }

    val endConversation: () -> Unit = remember(room, deviceStateManager) {
        {
            if (voiceState == VoiceState.CONVERSATION) {
                voiceState = VoiceState.ENDING
                Log.i(TAG, "Ending conversation, waiting for server confirmation...")
                scope.launch {
                    try {
                        val endSignal = JSONObject().put("type", "end_conversation")
                        room.localParticipant.publishData(endSignal.toString().toByteArray())
                    } catch (_: Exception) {}
                }
                // Fallback: if server doesn't confirm within 3s, force reset
                endingTimeoutJob = scope.launch {
                    delay(3000)
                    if (voiceState == VoiceState.ENDING) {
                        Log.w(TAG, "Server confirmation timeout, force-resetting to WAKE_WORD")
                        resetToWakeWord()
                    }
                }
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
    val showRightPanel = isConversation

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

            // Connect to LiveKit with device identity
            try {
                val response = withContext(Dispatchers.IO) {
                    tokenServerClient.get("/token?identity=$effectiveDeviceId&room=voice-room-$effectiveDeviceId")
                }
                val json = JSONObject(response)
                val token = json.getString("token")
                room.connect(livekitUrl, token)
                roomConnected = true
                Log.i(TAG, "LiveKit connected as '$effectiveDeviceId'")
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
                }
            } catch (e: Exception) {
                Log.w(TAG, "Config poll failed: ${e.message}")
            }
        }
    }

    // --- Start DLNA speaker service ---
    LaunchedEffect(effectiveDeviceId) {
        if (effectiveDeviceId == "tablet-pending") return@LaunchedEffect
        val dlnaIntent = android.content.Intent(context, DlnaRendererService::class.java).apply {
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

    // --- Initialize voice pipeline ---
    LaunchedEffect(selectedWakeWordModel) {
        // Initialize wake word with selected model from settings
        val model = BUNDLED_MODELS.find { it.id == selectedWakeWordModel } ?: BUNDLED_MODELS.first()
        Log.i(TAG, "Initializing wake word: ${model.displayName} (${model.assetPath})")
        withContext(Dispatchers.Default) {
            wakeWordManager.initialize(model.assetPath)
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
                    startConversation("%.3f".format(score))
                }
            }
        }
    }

    // --- Listen for LiveKit room events; reconnect on disconnect ---
    LaunchedEffect(roomConnected) {
        if (!roomConnected) return@LaunchedEffect
        Log.i(TAG, "Starting room event listener (persistent)")

        room.events.collect { event ->
            Log.d(TAG, "Room event: ${event::class.simpleName}")
            when (event) {
                is RoomEvent.Disconnected -> {
                    Log.w(TAG, "Room disconnected — will reconnect")
                    roomConnected = false  // triggers retry loop
                }
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
                is RoomEvent.DataReceived -> {
                    try {
                        val text = event.data.toString(Charsets.UTF_8)
                        val json = JSONObject(text)
                        val type = json.optString("type", "")
                        Log.d(TAG, "Data received: type=$type")
                        when (type) {
                            "conversation_ended" -> {
                                Log.i(TAG, "Server sent conversation_ended")
                                resetToWakeWord()
                            }
                            "agent_speak" -> {
                                // TalkingHead.js avatar: route text to the avatar WebView
                                val text = json.optString("text", "")
                                if (text.isNotEmpty()) {
                                    val escaped = text
                                        .replace("\\", "\\\\")
                                        .replace("'", "\\'")
                                        .replace("\n", " ")
                                    avatarWebViewRef.value?.evaluateJavascript(
                                        "if(window.talkingHeadSpeak) window.talkingHeadSpeak('$escaped');", null
                                    )
                                    conversationStatus = ConversationStatus.SPEAKING
                                    Log.d(TAG, "Avatar speak: ${text.take(60)}")
                                }
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
            intercomManager.stop()
            intercomManager.release()
            deviceStateManager.release()
            audioPipeline.stop()
            wakeWordManager.release()
            room.disconnect()
            room.release()
        }
    }

    // --- Swipe gesture detection: right = contacts, left = settings ---
    val swipeModifier = Modifier.pointerInput(Unit) {
        detectHorizontalDragGestures { _, dragAmount ->
            if (dragAmount < -30) { // Swipe left → settings
                settingsVisible = true
            } else if (dragAmount > 30) { // Swipe right → contacts
                contactsVisible = true
            }
        }
    }

    // ==================== UI ====================
    Box(modifier = Modifier.fillMaxSize().then(swipeModifier)) {
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
                    avatarEnabled = avatarEnabled,
                    avatarUrl = if (avatarEnabled) "$tokenServerUrl/avatar" else "",
                    avatarWebViewRef = avatarWebViewRef,
                    onClose = { endConversation() },
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
                onClick = { startConversation("1.0") },
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }

        // Settings gear icon (top-right) — hidden while conversation card is open
        if (!isConversation) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                androidx.compose.material3.IconButton(onClick = { settingsVisible = true }) {
                    Text("⚙", fontSize = 24.sp, color = Color.White.copy(alpha = 0.8f))
                }
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
