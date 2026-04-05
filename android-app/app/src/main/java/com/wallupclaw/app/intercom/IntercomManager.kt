package com.wallupclaw.app.intercom

import android.util.Log
import com.wallupclaw.app.util.TokenServerClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

private const val TAG = "IntercomManager"

enum class IntercomState {
    IDLE,
    CALLING,      // We initiated a call, waiting for answer
    RINGING,      // Someone is calling us
    IN_CALL,      // Active call
}

data class CallSession(
    val callId: String,
    val remoteDeviceId: String,
    val remoteDisplayName: String,
    val roomName: String,
    val isOutgoing: Boolean,
)

/**
 * Manages intercom call signaling via the token server's /signal and /signals endpoints.
 * Runs a long-poll loop to receive incoming signals.
 */
class IntercomManager(
    private val tokenServerUrl: String,
    private val myDeviceId: String,
    private val client: TokenServerClient,
) {
    private val _state = MutableStateFlow(IntercomState.IDLE)
    val state: StateFlow<IntercomState> = _state

    private val _currentCall = MutableStateFlow<CallSession?>(null)
    val currentCall: StateFlow<CallSession?> = _currentCall

    /** Emits announcement messages to be spoken via TTS. */
    private val _pendingAnnouncement = MutableStateFlow<String?>(null)
    val pendingAnnouncement: StateFlow<String?> = _pendingAnnouncement

    /** Call after TTS has spoken the announcement. */
    fun clearAnnouncement() { _pendingAnnouncement.value = null }

    private var pollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Start the long-poll loop to receive incoming signals. */
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            Log.i(TAG, "Signal polling started for device=$myDeviceId")
            while (isActive) {
                try {
                    val response = client.longPollGet("/signals?device_id=$myDeviceId")
                    val json = JSONObject(response)
                    val signals = json.getJSONArray("signals")
                    for (i in 0 until signals.length()) {
                        handleSignal(signals.getJSONObject(i))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Signal poll error: ${e.message}")
                    delay(3_000) // Brief backoff on error
                }
            }
        }
    }

    /** Stop the long-poll loop. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Initiate a call to another device. */
    suspend fun callDevice(targetDeviceId: String, targetDisplayName: String = targetDeviceId): Result<CallSession> {
        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("type", "call_request")
                    put("from", myDeviceId)
                    put("to", targetDeviceId)
                }
                val response = postSignal(body)
                val callId = response.getString("call_id")
                val roomName = response.getString("room_name")

                val session = CallSession(
                    callId = callId,
                    remoteDeviceId = targetDeviceId,
                    remoteDisplayName = targetDisplayName,
                    roomName = roomName,
                    isOutgoing = true,
                )
                _currentCall.value = session
                _state.value = IntercomState.CALLING
                Log.i(TAG, "Call initiated: $callId -> $targetDeviceId")
                Result.success(session)
            } catch (e: Exception) {
                Log.e(TAG, "Call failed: ${e.message}")
                Result.failure(e)
            }
        }
    }

    /** Accept an incoming call. */
    suspend fun acceptCall() {
        val call = _currentCall.value ?: return
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("type", "call_accept")
                    put("from", myDeviceId)
                    put("to", call.remoteDeviceId)
                    put("call_id", call.callId)
                }
                postSignal(body)
            } catch (e: Exception) {
                Log.e(TAG, "Accept signal failed: ${e.message}")
            }
        }
        _state.value = IntercomState.IN_CALL
        Log.i(TAG, "Call accepted: ${call.callId}")
    }

    /** Decline an incoming call. */
    suspend fun declineCall() {
        val call = _currentCall.value ?: return
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("type", "call_decline")
                    put("from", myDeviceId)
                    put("to", call.remoteDeviceId)
                    put("call_id", call.callId)
                }
                postSignal(body)
            } catch (e: Exception) {
                Log.e(TAG, "Decline failed: ${e.message}")
            }
        }
        _state.value = IntercomState.IDLE
        _currentCall.value = null
        Log.i(TAG, "Call declined: ${call.callId}")
    }

    /** Hang up an active or outgoing call. */
    suspend fun hangup() {
        val call = _currentCall.value ?: return
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("type", "call_hangup")
                    put("from", myDeviceId)
                    put("to", call.remoteDeviceId)
                    put("call_id", call.callId)
                }
                postSignal(body)
            } catch (e: Exception) {
                Log.e(TAG, "Hangup failed: ${e.message}")
            }
        }
        _state.value = IntercomState.IDLE
        _currentCall.value = null
        Log.i(TAG, "Call hung up: ${call.callId}")
    }

    private fun handleSignal(signal: JSONObject) {
        val type = signal.optString("type", "")
        Log.i(TAG, "Received signal: $type (current state: ${_state.value})")

        when (type) {
            "call_request" -> {
                // Ignore if we're already in a call or ringing
                if (_state.value != IntercomState.IDLE) {
                    Log.w(TAG, "Ignoring call_request, already in state ${_state.value}")
                    return
                }
                val callId = signal.getString("call_id")
                val fromDevice = signal.getString("from")
                val roomName = signal.getString("room_name")
                val displayName = signal.optString("from_display_name", fromDevice)

                _currentCall.value = CallSession(
                    callId = callId,
                    remoteDeviceId = fromDevice,
                    remoteDisplayName = displayName,
                    roomName = roomName,
                    isOutgoing = false,
                )
                _state.value = IntercomState.RINGING
                Log.i(TAG, "Incoming call from $fromDevice ($callId)")
            }

            "call_accept" -> {
                _state.value = IntercomState.IN_CALL
                Log.i(TAG, "Call accepted by remote")
            }

            "call_decline" -> {
                _state.value = IntercomState.IDLE
                _currentCall.value = null
                Log.i(TAG, "Call declined by remote")
            }

            "call_hangup" -> {
                _state.value = IntercomState.IDLE
                _currentCall.value = null
                Log.i(TAG, "Call hung up by remote")
            }

            "announcement" -> {
                val message = signal.optString("message", "")
                if (message.isNotEmpty()) {
                    Log.i(TAG, "Announcement received: ${message.take(50)}")
                    _pendingAnnouncement.value = message
                }
            }
        }
    }

    private fun postSignal(body: JSONObject): JSONObject {
        val responseText = client.post("/signal", body.toString())
        return JSONObject(responseText)
    }

    fun release() {
        scope.cancel()
    }
}
