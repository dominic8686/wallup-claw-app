package com.test.wakeword

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class MainActivity : AppCompatActivity(), CoroutineScope {
    override val coroutineContext = SupervisorJob() + Dispatchers.Main

    private lateinit var scoreText: TextView
    private lateinit var statusText: TextView
    private lateinit var scoreBar: ProgressBar
    private lateinit var startButton: Button

    private val recordingManager = RecordingManager()
    private lateinit var wakeWordManager: WakeWordManager
    private var isRunning = false
    private var captureJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple programmatic UI - no XML needed
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        TextView(this).apply {
            text = "Wake Word Test"
            textSize = 24f
            layout.addView(this)
        }

        TextView(this).apply {
            text = "\nModel: jarvis_v2.onnx\nThreshold: 0.5\n"
            layout.addView(this)
        }

        scoreBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            layout.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 64
            ))
        }

        scoreText = TextView(this).apply {
            text = "Score: 0.000000"
            textSize = 20f
            layout.addView(this)
        }

        statusText = TextView(this).apply {
            text = "Status: Stopped"
            textSize = 16f
            layout.addView(this)
        }

        startButton = Button(this).apply {
            text = "START"
            setOnClickListener { toggle() }
            layout.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 32 })
        }

        setContentView(layout)

        // Request mic permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        // Initialize wake word model
        wakeWordManager = WakeWordManager(this)
        wakeWordManager.initialize("jarvis_v2.onnx")

        // Collect scores
        launch {
            wakeWordManager.scores.collectLatest { score ->
                scoreText.text = "Score: ${"%.6f".format(score)}"
                scoreBar.progress = (score * 1000).toInt().coerceIn(0, 1000)
                if (score > 0.5f) {
                    statusText.text = "Status: DETECTED! (${("%.3f".format(score))})"
                    statusText.setTextColor(0xFF00AA00.toInt())
                    Log.i("WakeWordTest", "DETECTED! score=${"%.4f".format(score)}")
                } else {
                    statusText.text = "Status: Listening..."
                    statusText.setTextColor(0xFF666666.toInt())
                }
            }
        }
    }

    private fun toggle() {
        if (isRunning) {
            stop()
        } else {
            start()
        }
    }

    private fun start() {
        val audioChannel = recordingManager.start { error ->
            launch { statusText.text = "Error: $error" }
        } ?: return

        isRunning = true
        startButton.text = "STOP"
        statusText.text = "Status: Listening..."

        captureJob = launch(Dispatchers.Default) {
            for (audioData in audioChannel) {
                wakeWordManager.processAudio(audioData.data, audioData.size)
            }
        }
    }

    private fun stop() {
        captureJob?.cancel()
        recordingManager.stop()
        isRunning = false
        startButton.text = "START"
        statusText.text = "Status: Stopped"
    }

    override fun onDestroy() {
        stop()
        wakeWordManager.release()
        super.onDestroy()
    }
}
