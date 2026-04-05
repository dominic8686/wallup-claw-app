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
import io.livekit.android.example.voiceassistant.audio.AudioPipelineManager
import io.livekit.android.example.voiceassistant.audio.WakeWordManager
import io.livekit.android.example.voiceassistant.intercom.IntercomManager
import io.livekit.android.example.voiceassistant.intercom.IntercomState
import io.livekit.android.example.voiceassistant.settings.AppSettings
import io.livekit.android.example.voiceassistant.ui.*
import io.livekit.android.example.voiceassistant.util.HomeAssistantDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
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
    ENDING,
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
    val avatarEnabled by appSettings.avatarEnabled.collectAsState(initial = false)
    val deviceId
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

    // --- Contacts panel (left side) ---
    var contactsVisible by remember { mutableStateOf(false) }

    // --- Audio/LiveKit ---
    val audioPipeline = remember { AudioPipelineManager() }
    val wakeWordManager = remember { WakeWordManager(context) }
    val room = remember { LiveKit.create(context) }
    var roomConnected by remember { mutableStateOf(false) }

    // --- Avatar WebView ref (for JS bridge calls into TalkingHead.js) ---
    val avatarWebViewRef = remember { mutableStateOf<WebView?>(null) }

    // --- Ending fallback job ---
    var endingTimeoutJob by remember { mutableStateOf<Job?>(null) }

    // --- Layout ---
    val isConversation = voiceState == VoiceState.CONVERSATION || voiceState == VoiceState.ENDING

    // --- Shared conversation lifecycle functions ---
    val startConversation: (String) -> Unit = remember(room, audioPipeline, avatarEnabled) {
        { score: String ->
            if (voiceState == VoiceState.WAKE_WORD) {
                voiceState = VoiceState.CONVERSATION
                conversationStatus = ConversationStatus.LISTENING
                chatMessages.clear()
                scope.launch {
                    audioPipeline.stop()
                    try {
                        room.localParticipant.setMicrophoneEnabled(true)
                        Log.i(TAG, "LiveKit mic enabled")
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
            try { room.localParticipant.setMicrophoneEnabled(false) } catch (_: Exception) {}
            delay(200) // Let LiveKit release AudioRecord before we reclaim it
            audioPipeline.start()
            voiceState = VoiceState.WAKE_WORD
            Log.i(TAG, "Reset to WAKE_WORD")
        }
    }

    val endConversation: () -> Unit = remember(room) {
        {
            if (voiceState == VoiceState.CONVERSATION) {
                voiceState = VoiceState.ENDING
                Log.i(TAG, "Ending conversation, waiting for server confirmation...")
                scope.launch {
                    try {
                        val endSignal = JSONObject().put("type", "end_conversation")
                        room.localParticipant.publishData(endSignal.toString().toByteArray())
                    } catch (_: Exception) {}
                    try { room.localParticipant.setMicrophoneEnabled(false) } catch (_: Exception) {}
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
        IntercomManager(tokenServerUrl, effectiveDeviceId)
    }
    val intercomState by intercomManager.state.collectAsState()
    val currentCall by intercomManager.currentCall.collectAsState()
    val isCallActive = intercomState == IntercomState.RINGING || intercomState == IntercomState.IN_CALL || intercomState == IntercomState.CALLING
    val showLeftPanel = contactsVisible || isCallActive
    val showRightPanel = isConversation

    // --- Call room state (separate from voice-room) ---
    var callRoom by remember { mutableStateOf<io.livekit.android.room.Room?>(null) }
    var remoteVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var localVideoTrack by remember { mutableStateOf<VideoTrack?>(null) }

    // --- Auto-join call room when CALLING/IN_CALL and not yet connected ---
    LaunchedEffect(effectiveDeviceId, intercomManager) {
        snapshotFlow { Triple(intercomState, currentCall, callRoom) }.collect { (state, call, room) ->
            if ((state == IntercomState.IN_CALL || state == IntercomState.CALLING) && call != null && room == null) {
                Log.i(TAG, "$state state detected, joining room: ${call.roomName} (outgoing=${call.isOutgoing})")
                try {
                    val cr = LiveKit.create(context)
                    val tokenResp = withContext(Dispatchers.IO) {
                        URL("$tokenServerUrl/token?identity=$effectiveDeviceId&room=${call.roomName}").readText()
                    }
                    val tokenJson = JSONObject(tokenResp)
                    cr.connect(livekitUrl, tokenJson.getString("token"))
                    cr.localParticipant.setMicrophoneEnabled(true)
                    try {
                        cr.localParticipant.setCameraEnabled(true)
                    } catch (camErr: Exception) {
                        Log.w(TAG, "Camera not available: ${camErr.message}")
                    }
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

    // --- Register device & connect to LiveKit ---
    LaunchedEffect(effectiveDeviceId) {
        if (effectiveDeviceId == "tablet-pending") return@LaunchedEffect
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

    // --- Start intercom signal polling ---
    LaunchedEffect(intercomManager) {
        if (effectiveDeviceId == "tablet-pending") return@LaunchedEffect
        intercomManager.start()
        Log.i(TAG, "IntercomManager signal polling started")
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

            // Poll for pending config from HA
            try {
                val configResp = withContext(Dispatchers.IO) {
                    URL("$tokenServerUrl/configure?device_id=$effectiveDeviceId").readText()
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
                    startConversation("%.3f".format(score))
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
                                onHangup = {
                                    scope.launch {
                                        intercomManager.hangup()
                                        callRoom?.disconnect()
                                        callRoom?.release()
                                        callRoom = null
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
                                onHangup = {
                                    scope.launch {
                                        intercomManager.hangup()
                                        callRoom?.disconnect()
                                        callRoom?.release()
                                        callRoom = null
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
                                onCallDevice = { targetId ->
                                    scope.launch {
                                        val result = intercomManager.callDevice(targetId)
                                        if (result.isSuccess) {
                                            Log.i(TAG, "Call initiated to $targetId")
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

    } // end main Box
}
