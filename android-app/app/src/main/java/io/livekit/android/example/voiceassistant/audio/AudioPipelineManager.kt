package io.livekit.android.example.voiceassistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext

/**
 * Owns the single AudioRecord and distributes audio to multiple consumers.
 *
 * Audio captured at 16kHz mono is sent to:
 * 1. Wake word engine (on-device, always processing)
 * 2. LiveKit AudioSource (upsampled to 48kHz for WebRTC)
 *
 * This avoids mic conflicts between wake word detection and LiveKit.
 */
class AudioPipelineManager : CoroutineScope {
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO

    companion object {
        private const val TAG = "AudioPipeline"
        const val SAMPLE_RATE = 16000
        const val BUFFER_SIZE_BYTES = 4096 // 2048 samples = 128ms at 16kHz
    }

    enum class State { STOPPED, RUNNING }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state

    // Audio subscribers
    private val _wakeWordAudio = Channel<ShortArray>(Channel.UNLIMITED)
    val wakeWordAudio: Channel<ShortArray> = _wakeWordAudio

    private val _livekitAudio = Channel<ShortArray>(Channel.UNLIMITED)
    val livekitAudio: Channel<ShortArray> = _livekitAudio

    // RMS for UI
    private val _rms = MutableSharedFlow<Float>(extraBufferCapacity = 1)
    val rms: SharedFlow<Float> = _rms

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (_state.value == State.RUNNING) return

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )

        // Attach audio effects (keeps Rockchip HAL alive)
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                Log.i(TAG, "AEC enabled")
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
                Log.i(TAG, "NS enabled")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio effects not available: ${e.message}")
        }

        record.startRecording()
        audioRecord = record
        _state.value = State.RUNNING
        Log.i(TAG, "Started (sr=$SAMPLE_RATE, buf=${minBuf * 2})")

        captureJob = launch {
            val byteBuffer = ByteArray(BUFFER_SIZE_BYTES)
            var frameCount = 0
            try {
                while (isActive) {
                    val bytesRead = record.read(byteBuffer, 0, byteBuffer.size)
                    if (bytesRead > 0) {
                        frameCount++
                        // Convert to shorts
                        val shorts = ShortArray(bytesRead / 2)
                        ByteBuffer.wrap(byteBuffer, 0, bytesRead)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .asShortBuffer().get(shorts)

                        // Calculate RMS
                        if (frameCount % 10 == 0) {
                            var sum = 0.0
                            for (s in shorts) sum += s.toDouble() * s.toDouble()
                            _rms.tryEmit(Math.sqrt(sum / shorts.size).toFloat())
                        }

                        // Distribute to both consumers
                        _wakeWordAudio.trySend(shorts)
                        _livekitAudio.trySend(shorts)

                        if (frameCount <= 3 || frameCount % 500 == 0) {
                            Log.d(TAG, "Frame #$frameCount: $bytesRead bytes, ${shorts.size} samples")
                        }
                    } else {
                        Log.e(TAG, "Read error: $bytesRead")
                        break
                    }
                }
            } finally {
                Log.i(TAG, "Capture ended after $frameCount frames")
            }
        }
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        audioRecord?.apply { runCatching { stop(); release() } }
        audioRecord = null
        aec?.apply { enabled = false; release() }; aec = null
        ns?.apply { enabled = false; release() }; ns = null
        _state.value = State.STOPPED
        Log.i(TAG, "Stopped")
    }
}
