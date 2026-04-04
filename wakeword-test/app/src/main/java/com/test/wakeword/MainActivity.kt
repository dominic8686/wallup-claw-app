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
    private lateinit var historyText: TextView
    private lateinit var statsText: TextView

    private val recordingManager = RecordingManager()
    private lateinit var wakeWordManager: WakeWordManager
    private var isRunning = false
    private var captureJob: Job? = null

    private val detectionHistory = mutableListOf<DetectionEntry>()
    private var peakScore = 0f
    private var totalFrames = 0

    data class DetectionEntry(val time: String, val score: Float, val frameNum: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = android.widget.ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }
        scrollView.addView(layout)

        TextView(this).apply {
            text = "Wake Word Test"
            textSize = 24f
            layout.addView(this)
        }

        TextView(this).apply {
            text = "Model: jarvis_v2.onnx  |  Threshold: 0.5"
            textSize = 12f
            layout.addView(this)
        }

        scoreBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            layout.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 48
            ).apply { topMargin = 16 })
        }

        scoreText = TextView(this).apply {
            text = "Score: 0.000000  |  Peak: 0.000"
            textSize = 18f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            layout.addView(this)
        }

        statusText = TextView(this).apply {
            text = "Status: Stopped"
            textSize = 14f
            layout.addView(this)
        }

        statsText = TextView(this).apply {
            text = "Detections: 0  |  Frames: 0"
            textSize = 12f
            layout.addView(this)
        }

        startButton = Button(this).apply {
            text = "START"
            setOnClickListener { toggle() }
            layout.addView(this, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 })
        }

        // Detection history table header
        TextView(this).apply {
            text = "\n  #  |  Time      |  Score  |  Frame"
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            layout.addView(this)
        }
        TextView(this).apply {
            text = "─────┼────────────┼─────────┼────────"
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            layout.addView(this)
        }

        historyText = TextView(this).apply {
            text = "  (no detections yet)"
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            layout.addView(this)
        }

        setContentView(scrollView)

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
                totalFrames++
                if (score > peakScore) peakScore = score

                scoreText.text = "Score: ${"%.6f".format(score)}  |  Peak: ${"%.3f".format(peakScore)}"
                scoreBar.progress = (score * 1000).toInt().coerceIn(0, 1000)
                statsText.text = "Detections: ${detectionHistory.size}  |  Frames: $totalFrames"

                if (score > 0.5f) {
                    statusText.text = "Status: DETECTED! (${"%.3f".format(score)})"
                    statusText.setTextColor(0xFF00AA00.toInt())

                    // Add to history (deduplicate: skip if last detection was <1s ago)
                    val now = System.currentTimeMillis()
                    val lastTime = if (detectionHistory.isNotEmpty()) {
                        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).parse(detectionHistory.last().time)?.time ?: 0
                    } else 0L
                    val timeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())

                    if (detectionHistory.isEmpty() || score > detectionHistory.last().score + 0.01f || totalFrames - detectionHistory.last().frameNum > 10) {
                        detectionHistory.add(DetectionEntry(timeStr, score, totalFrames))
                        updateHistoryTable()
                    }

                    Log.i("WakeWordTest", "DETECTED #${detectionHistory.size} score=${"%.4f".format(score)} frame=$totalFrames")
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

    private fun updateHistoryTable() {
        val sb = StringBuilder()
        detectionHistory.forEachIndexed { i, entry ->
            sb.append("  ${(i+1).toString().padStart(2)}  |  ${entry.time}  |  ${"%.4f".format(entry.score)}  |  ${entry.frameNum}\n")
        }
        historyText.text = if (sb.isEmpty()) "  (no detections yet)" else sb.toString()
    }

    private fun start() {
        // Reset stats
        detectionHistory.clear()
        peakScore = 0f
        totalFrames = 0
        updateHistoryTable()

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
