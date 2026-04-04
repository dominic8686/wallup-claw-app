package com.test.wakeword

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class RecordingManager : CoroutineScope {
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private var currentSessionJob: Job? = null
    private var currentAudioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    data class AudioData(val data: ByteArray, val size: Int, val sampleRate: Int)

    @SuppressLint("MissingPermission")
    fun start(onError: (String) -> Unit): ReceiveChannel<AudioData>? {
        if (currentSessionJob?.isActive == true) {
            Log.e(TAG, "Already running")
            return null
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize * 2
            ).also { record ->
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                    Log.i(TAG, "AEC enabled")
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
                    Log.i(TAG, "NS enabled")
                }
                record.startRecording()
                Log.i(TAG, "Recording started (sr=$sampleRate, buf=${minBufferSize * 2})")
            }
        } catch (e: Exception) {
            onError("AudioRecord init failed: ${e.message}")
            return null
        }

        val channel = Channel<AudioData>(capacity = Channel.UNLIMITED)
        currentAudioRecord = record

        currentSessionJob = launch {
            Log.i(TAG, "Capture loop started")
            var frameCount = 0
            try {
                while (isActive) {
                    val audioBuffer = ByteArray(4096)
                    val read = record.read(audioBuffer, 0, audioBuffer.size)
                    if (read > 0) {
                        frameCount++
                        if (frameCount <= 5 || frameCount % 200 == 0) {
                            // Log RMS for debugging
                            var sum = 0L
                            for (i in 0 until read step 2) {
                                if (i + 1 < read) {
                                    val sample = (audioBuffer[i].toInt() and 0xFF) or (audioBuffer[i + 1].toInt() shl 8)
                                    sum += sample.toLong() * sample.toLong()
                                }
                            }
                            val rms = Math.sqrt(sum.toDouble() / (read / 2))
                            Log.d(TAG, "Frame #$frameCount: read=$read rms=${"%.0f".format(rms)}")
                        }
                        channel.send(AudioData(audioBuffer, read, sampleRate))
                    } else {
                        Log.e(TAG, "Audio read error: $read")
                        onError("Audio recording error")
                        break
                    }
                }
            } finally {
                Log.i(TAG, "Capture loop ended after $frameCount frames")
                channel.close()
            }
        }
        return channel
    }

    fun stop() {
        Log.i(TAG, "Stop requested")
        currentSessionJob?.cancel()
        currentSessionJob = null
        currentAudioRecord?.apply {
            runCatching { stop(); release() }
        }
        currentAudioRecord = null
        ns?.apply { enabled = false; release() }; ns = null
        aec?.apply { enabled = false; release() }; aec = null
    }

    companion object { private const val TAG = "RecordingMgr" }
}
