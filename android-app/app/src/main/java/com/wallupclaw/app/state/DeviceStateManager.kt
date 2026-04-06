package com.wallupclaw.app.state

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.wallupclaw.app.audio.AudioPipelineManager
import io.livekit.android.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "DeviceStateManager"

/**
 * Primary device states — mutually exclusive for mic/camera priority.
 *
 * Resource ownership per state:
 *   IDLE:
 *     Mic      → AudioPipelineManager (wake word detection), LiveKit mic MUTED
 *     Camera   → voice-room low-res track (security cam, if enabled)
 *     Speaker  → DLNA free to play music
 *
 *   CONVERSATION:
 *     Mic      → LiveKit (unmuted), AudioPipelineManager STOPPED
 *     Camera   → voice-room (for vision AI queries)
 *     Speaker  → voice-room agent audio, DLNA ducked
 *
 *   INTERCOM_CALL:
 *     Mic      → call-room (LiveKit)
 *     Camera   → call-room (intercom video)
 *     Speaker  → call-room audio, DLNA paused
 *     Note:    wake word disabled, security camera paused
 */
enum class DeviceState {
    IDLE,
    CONVERSATION,
    INTERCOM_CALL,
}

/**
 * Central resource coordinator for the tablet.
 *
 * Ensures only one feature owns each shared resource (mic, camera, speaker)
 * at a time. All state transitions go through [transitionTo], which handles
 * teardown of the previous state and setup of the new one.
 */
class DeviceStateManager(
    private val context: Context,
    private val audioPipeline: AudioPipelineManager,
    private val voiceRoom: Room,
    var securityCameraEnabled: Boolean = false,
) {
    private val _state = MutableStateFlow(DeviceState.IDLE)
    val state: StateFlow<DeviceState> = _state

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Audio focus for ducking DLNA during conversations
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    // Call room reference — set externally when a call room is created
    var callRoom: Room? = null

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Safely enable camera only if permission is granted. */
    private suspend fun safeEnableCamera(room: Room, label: String) {
        if (!hasCameraPermission()) {
            Log.w(TAG, "$label: camera permission not granted, skipping")
            return
        }
        try {
            room.localParticipant.setCameraEnabled(true)
        } catch (e: Exception) {
            Log.w(TAG, "$label: setCameraEnabled failed: ${e.message}")
        }
    }

    /**
     * Transition to a new device state.
     *
     * Handles all resource teardown for the old state and setup for the new one.
     * Transitions are serialized — only one can run at a time.
     *
     * @param newState The target state to transition to.
     * @param onMidTransition Optional callback invoked after old state teardown
     *        but before new state setup (e.g., to connect a call room).
     */
    suspend fun transitionTo(
        newState: DeviceState,
        onMidTransition: (suspend () -> Unit)? = null,
    ) {
        val oldState = _state.value
        if (oldState == newState) {
            Log.d(TAG, "Already in state $newState, skipping transition")
            return
        }
        Log.i(TAG, "Transition: $oldState → $newState")

        // --- Teardown old state ---
        when (oldState) {
            DeviceState.IDLE -> {
                // Stop wake word audio pipeline (releases AudioRecord so LiveKit can take mic)
                audioPipeline.stop()
                Log.d(TAG, "Teardown IDLE: audioPipeline stopped")
            }
            DeviceState.CONVERSATION -> {
                // Mute voice-room mic (LiveKit stops capturing, frees hardware for wake word)
                try { voiceRoom.localParticipant.setMicrophoneEnabled(false) } catch (e: Exception) {
                    Log.w(TAG, "Failed to mute voice-room mic: ${e.message}")
                }
                // Disable voice-room camera (unless security cam keeps it on — handled in setup)
                try { voiceRoom.localParticipant.setCameraEnabled(false) } catch (e: Exception) {
                    Log.w(TAG, "Failed to disable voice-room camera: ${e.message}")
                }
                // Release audio focus (un-duck DLNA)
                releaseAudioFocus()
                Log.d(TAG, "Teardown CONVERSATION: mic muted, camera off, audio focus released")
            }
            DeviceState.INTERCOM_CALL -> {
                // Disable call-room mic + camera (caller is responsible for disconnecting the call room)
                callRoom?.let { room ->
                    try { room.localParticipant.setMicrophoneEnabled(false) } catch (_: Exception) {}
                    try { room.localParticipant.setCameraEnabled(false) } catch (_: Exception) {}
                }
                // Release audio focus (un-pause DLNA)
                releaseAudioFocus()
                Log.d(TAG, "Teardown INTERCOM_CALL: call-room mic/camera off, audio focus released")
            }
        }

        // Brief delay to let hardware release
        delay(200)

        // --- Mid-transition callback (e.g., connect call room) ---
        onMidTransition?.invoke()

        // --- Setup new state ---
        when (newState) {
            DeviceState.IDLE -> {
                // Restart wake word detection (AudioPipelineManager owns the mic)
                audioPipeline.start()
                // Re-enable security camera on voice-room if configured
                if (securityCameraEnabled) {
                    safeEnableCamera(voiceRoom, "Setup IDLE: security camera")
                }
                Log.d(TAG, "Setup IDLE: audioPipeline started, LiveKit mic muted")
            }
            DeviceState.CONVERSATION -> {
                // Unmute voice-room mic (LiveKit agent starts hearing us)
                try { voiceRoom.localParticipant.setMicrophoneEnabled(true) } catch (e: Exception) {
                    Log.e(TAG, "Failed to unmute voice-room mic: ${e.message}")
                }
                // Enable voice-room camera for vision AI
                safeEnableCamera(voiceRoom, "Setup CONVERSATION: camera")
                // Request audio focus to duck DLNA
                requestAudioFocus(duck = true)
                // Force loudspeaker so agent audio is audible
                forceSpeakerOutput()
                Log.d(TAG, "Setup CONVERSATION: mic unmuted, camera on, DLNA ducked, speaker on")
            }
            DeviceState.INTERCOM_CALL -> {
                // Pause security camera on voice-room (single camera can't serve two rooms)
                try { voiceRoom.localParticipant.setCameraEnabled(false) } catch (_: Exception) {}
                // Enable call-room mic + camera (call room must already be connected)
                callRoom?.let { room ->
                    try { room.localParticipant.setMicrophoneEnabled(true) } catch (e: Exception) {
                        Log.e(TAG, "Failed to enable call-room mic: ${e.message}")
                    }
                    safeEnableCamera(room, "Setup INTERCOM_CALL: camera")
                }
                // Request exclusive audio focus (pause DLNA)
                requestAudioFocus(duck = false)
                Log.d(TAG, "Setup INTERCOM_CALL: security cam paused, call-room mic/camera on, DLNA paused")
            }
        }

        _state.value = newState
        Log.i(TAG, "Transition complete: now in $newState")
    }

    /** Check if a transition is safe/allowed. */
    fun canTransitionTo(newState: DeviceState): Boolean {
        return when (_state.value) {
            DeviceState.IDLE -> newState in listOf(DeviceState.CONVERSATION, DeviceState.INTERCOM_CALL)
            DeviceState.CONVERSATION -> newState in listOf(DeviceState.IDLE, DeviceState.INTERCOM_CALL)
            DeviceState.INTERCOM_CALL -> newState == DeviceState.IDLE
        }
    }

    /** Enable/disable security camera (continuous front camera stream). */
    suspend fun setSecurityCameraEnabled(enabled: Boolean) {
        securityCameraEnabled = enabled
        if (_state.value == DeviceState.IDLE) {
            if (enabled) {
                safeEnableCamera(voiceRoom, "Security camera enable")
            } else {
                try {
                    voiceRoom.localParticipant.setCameraEnabled(false)
                    Log.i(TAG, "Security camera disabled")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to disable security camera: ${e.message}")
                }
            }
        }
    }

    private fun requestAudioFocus(duck: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusGain = if (duck) {
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            } else {
                AudioManager.AUDIOFOCUS_GAIN
            }
            val request = AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { focusChange ->
                    Log.d(TAG, "Audio focus changed: $focusChange")
                }
                .build()
            audioManager.requestAudioFocus(request)
            audioFocusRequest = request
            Log.d(TAG, "Audio focus requested (duck=$duck)")
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
                audioFocusRequest = null
                Log.d(TAG, "Audio focus released")
            }
        }
    }

    /** Route audio to the loudspeaker instead of earpiece and boost volume. */
    @Suppress("DEPRECATION")
    private fun forceSpeakerOutput() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+ — use setCommunicationDevice with the built-in speaker
            val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (speaker != null) {
                val ok = audioManager.setCommunicationDevice(speaker)
                Log.i(TAG, "setCommunicationDevice(speaker) = $ok")
            } else {
                audioManager.isSpeakerphoneOn = true
                Log.i(TAG, "Fallback: isSpeakerphoneOn = true")
            }
        } else {
            audioManager.isSpeakerphoneOn = true
            Log.i(TAG, "isSpeakerphoneOn = true (pre-S)")
        }
        // Boost voice call volume to max so agent audio is clearly audible
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVol, 0)
        Log.i(TAG, "Voice call volume set to max: $maxVol")
    }

    fun release() {
        scope.cancel()
        releaseAudioFocus()
        // Clear communication device override on release
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
    }
}
