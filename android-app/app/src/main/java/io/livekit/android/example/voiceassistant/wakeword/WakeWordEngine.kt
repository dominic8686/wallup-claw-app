package io.livekit.android.example.voiceassistant.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

/**
 * Swappable wake word detection engine interface.
 */
interface WakeWordEngine {
    val engineName: String
    val isInitialized: Boolean
    fun initialize(context: Context, modelAssetPath: String, sensitivity: Float)
    fun processAudio(audioData: ShortArray): Float
    fun release()
}

/**
 * OpenWakeWord engine using ONNX Runtime for on-device inference.
 * Expects 16kHz mono 16-bit PCM audio.
 */
class OpenWakeWordEngine : WakeWordEngine {

    override val engineName: String = "OpenWakeWord"
    override var isInitialized: Boolean = false
        private set

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var sensitivity: Float = 0.5f

    // Accumulate audio to fill model's expected input size
    private val audioBuffer = mutableListOf<Float>()
    private val FRAME_SIZE = 1280 // 80ms at 16kHz — openWakeWord expects this

    override fun initialize(context: Context, modelAssetPath: String, sensitivity: Float) {
        try {
            this.sensitivity = sensitivity
            ortEnvironment = OrtEnvironment.getEnvironment()

            val modelBytes = context.assets.open(modelAssetPath).readBytes()
            ortSession = ortEnvironment!!.createSession(modelBytes)
            isInitialized = true
            Log.i(TAG, "OpenWakeWord model loaded: $modelAssetPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load OpenWakeWord model: $modelAssetPath", e)
            isInitialized = false
        }
    }

    override fun processAudio(audioData: ShortArray): Float {
        if (!isInitialized || ortSession == null) return 0f

        // Convert to float and accumulate
        for (sample in audioData) {
            audioBuffer.add(sample.toFloat() / 32768f)
        }

        // Need enough data for one frame
        if (audioBuffer.size < FRAME_SIZE) return 0f

        return try {
            val chunk = audioBuffer.take(FRAME_SIZE).toFloatArray()
            repeat(FRAME_SIZE) { audioBuffer.removeAt(0) }

            val inputShape = longArrayOf(1, FRAME_SIZE.toLong())
            val inputBuffer = FloatBuffer.wrap(chunk)
            val inputTensor = OnnxTensor.createTensor(ortEnvironment, inputBuffer, inputShape)

            val results = ortSession!!.run(mapOf(ortSession!!.inputNames.first() to inputTensor))
            val output = results[0].value

            val score = when (output) {
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val arr = output as? Array<FloatArray>
                    arr?.firstOrNull()?.firstOrNull() ?: 0f
                }
                is FloatArray -> output.firstOrNull() ?: 0f
                else -> 0f
            }

            inputTensor.close()
            results.close()

            score
        } catch (e: Exception) {
            Log.e(TAG, "Inference error", e)
            0f
        }
    }

    override fun release() {
        ortSession?.close()
        ortEnvironment?.close()
        ortSession = null
        ortEnvironment = null
        isInitialized = false
        audioBuffer.clear()
    }

    companion object {
        private const val TAG = "OpenWakeWord"
    }
}
