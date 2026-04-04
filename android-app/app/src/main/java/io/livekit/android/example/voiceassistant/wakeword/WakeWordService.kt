package io.livekit.android.example.voiceassistant.wakeword

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Foreground service that continuously listens for the wake word.
 * Emits detection events via [wakeWordDetected] flow.
 */
class WakeWordService : Service() {

    private val binder = WakeWordBinder()
    private var engine: WakeWordEngine? = null
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _wakeWordDetected = MutableSharedFlow<Float>(extraBufferCapacity = 1)
    val wakeWordDetected: SharedFlow<Float> = _wakeWordDetected

    inner class WakeWordBinder : Binder() {
        fun getService(): WakeWordService = this@WakeWordService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Listening for wake word..."))
    }

    fun startListening(modelAssetPath: String, sensitivity: Float = 0.5f) {
        if (isListening) return

        engine = OpenWakeWordEngine()
        engine!!.initialize(applicationContext, modelAssetPath, sensitivity)

        if (!engine!!.isInitialized) {
            Log.e(TAG, "Failed to initialize wake word engine")
            return
        }

        startAudioCapture()
    }

    fun stopListening() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        engine?.release()
        engine = null
    }

    private fun startAudioCapture() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2
        )

        audioRecord?.startRecording()
        isListening = true
        Log.i(TAG, "Wake word listening started")

        serviceScope.launch {
            val buffer = ShortArray(1280) // 80ms at 16kHz
            while (isListening) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    val score = engine?.processAudio(buffer.copyOf(read)) ?: 0f
                    val threshold = 0.5f // Use sensitivity from settings
                    if (score > threshold) {
                        Log.i(TAG, "Wake word detected! Score: $score")
                        _wakeWordDetected.emit(score)
                        // Cooldown to avoid rapid re-triggers
                        delay(3000)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        stopListening()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wake Word Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Listening for wake word activation"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes Voice Assistant")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "WakeWordService"
        private const val CHANNEL_ID = "wake_word_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
