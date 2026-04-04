package io.livekit.android.example.voiceassistant.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.util.LinkedList

/**
 * Swappable wake word detection engine interface.
 */
interface WakeWordEngine {
    val engineName: String
    val isInitialized: Boolean
    var onScoreUpdate: ((Float) -> Unit)?
    fun initialize(context: Context, modelAssetPath: String, sensitivity: Float)
    fun processAudio(audioData: ShortArray): Float
    fun release()
}

/**
 * OpenWakeWord engine using ONNX Runtime for on-device inference.
 *
 * Implements the full 3-stage pipeline:
 *   1. melspectrogram.onnx - Raw 16kHz PCM -> mel features
 *   2. embedding_model.onnx - mel features -> 96-dim embedding
 *   3. <wakeword>.onnx - embeddings -> detection score
 */
class OpenWakeWordEngine : WakeWordEngine {

    override val engineName: String = "OpenWakeWord"
    override var isInitialized: Boolean = false
        private set
    override var onScoreUpdate: ((Float) -> Unit)? = null

    private var ortEnv: OrtEnvironment? = null
    private var melSession: OrtSession? = null
    private var embSession: OrtSession? = null
    private var wwSession: OrtSession? = null
    private var sensitivity: Float = 0.5f

    // Audio buffer (80ms = 1280 samples at 16kHz per mel frame)
    private val audioBuffer = mutableListOf<Float>()
    private val MEL_FRAME_SAMPLES = 1280

    // Mel feature accumulation
    private val melFeatures = LinkedList<FloatArray>()
    private val MEL_FRAMES_PER_EMBEDDING = 76
    private val MEL_STEP = 8
    private var melFrameCount = 0

    // Embedding accumulation
    private val embeddings = LinkedList<FloatArray>()
    private val EMBEDDINGS_PER_DETECTION = 16

    override fun initialize(context: Context, modelAssetPath: String, sensitivity: Float) {
        try {
            this.sensitivity = sensitivity
            ortEnv = OrtEnvironment.getEnvironment()

            val opts = OrtSession.SessionOptions()
            opts.setInterOpNumThreads(1)
            opts.setIntraOpNumThreads(1)

            melSession = ortEnv!!.createSession(
                context.assets.open("wakeword_models/melspectrogram.onnx").readBytes(), opts
            )
            Log.i(TAG, "Loaded melspectrogram model")

            embSession = ortEnv!!.createSession(
                context.assets.open("wakeword_models/embedding_model.onnx").readBytes(), opts
            )
            Log.i(TAG, "Loaded embedding model")

            wwSession = ortEnv!!.createSession(
                context.assets.open(modelAssetPath).readBytes(), opts
            )
            Log.i(TAG, "Loaded wake word model: $modelAssetPath")

            isInitialized = true
            Log.i(TAG, "Pipeline ready (sensitivity=$sensitivity)")
        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
            isInitialized = false
        }
    }

    override fun processAudio(audioData: ShortArray): Float {
        if (!isInitialized) return 0f

        for (s in audioData) audioBuffer.add(s.toFloat() / 32768f)

        var bestScore = 0f

        while (audioBuffer.size >= MEL_FRAME_SAMPLES) {
            val frame = FloatArray(MEL_FRAME_SAMPLES)
            for (i in 0 until MEL_FRAME_SAMPLES) frame[i] = audioBuffer.removeAt(0)

            // Stage 1: Mel spectrogram
            val mel = runMel(frame) ?: continue
            melFeatures.add(mel)
            melFrameCount++

            // Stage 2: Embedding (every MEL_STEP frames)
            if (melFeatures.size >= MEL_FRAMES_PER_EMBEDDING && melFrameCount % MEL_STEP == 0) {
                val emb = runEmbedding(melFeatures.takeLast(MEL_FRAMES_PER_EMBEDDING)) ?: continue
                embeddings.add(emb)
                if (embeddings.size > EMBEDDINGS_PER_DETECTION) embeddings.removeFirst()

                // Stage 3: Detection
                if (embeddings.size >= EMBEDDINGS_PER_DETECTION) {
                    val score = runDetection(embeddings.toList())
                    if (score > bestScore) bestScore = score
                    onScoreUpdate?.invoke(score)

                    if (score > sensitivity) {
                        Log.i(TAG, "DETECTED! score=${"%.3f".format(score)} threshold=$sensitivity")
                    }
                }
            }

            // Trim
            while (melFeatures.size > MEL_FRAMES_PER_EMBEDDING + 20) melFeatures.removeFirst()
        }

        return bestScore
    }

    private fun runMel(pcm: FloatArray): FloatArray? = try {
        val t = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(pcm), longArrayOf(1, pcm.size.toLong()))
        val r = melSession!!.run(mapOf(melSession!!.inputNames.first() to t))
        // Output: [1, 1, N, 32] - take last frame's 32 mel bins
        @Suppress("UNCHECKED_CAST")
        val out = (r[0].value as? Array<Array<Array<FloatArray>>>)?.get(0)?.get(0)?.lastOrNull()
        t.close(); r.close(); out
    } catch (e: Exception) { Log.e(TAG, "Mel err: ${e.message}"); null }

    private fun runEmbedding(mels: List<FloatArray>): FloatArray? = try {
        val n = mels.size; val bins = mels[0].size
        val flat = FloatArray(n * bins)
        mels.forEachIndexed { i, m -> System.arraycopy(m, 0, flat, i * bins, bins) }
        val t = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(flat), longArrayOf(1, 1, n.toLong(), bins.toLong()))
        val r = embSession!!.run(mapOf(embSession!!.inputNames.first() to t))
        @Suppress("UNCHECKED_CAST")
        val out = (r[0].value as? Array<FloatArray>)?.get(0) ?: (r[0].value as? FloatArray)
        t.close(); r.close(); out
    } catch (e: Exception) { Log.e(TAG, "Emb err: ${e.message}"); null }

    private fun runDetection(embs: List<FloatArray>): Float = try {
        val n = embs.size; val sz = embs[0].size
        val flat = FloatArray(n * sz)
        embs.forEachIndexed { i, e -> System.arraycopy(e, 0, flat, i * sz, sz) }
        val t = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(flat), longArrayOf(1, flat.size.toLong()))
        val r = wwSession!!.run(mapOf(wwSession!!.inputNames.first() to t))
        @Suppress("UNCHECKED_CAST")
        val score = (r[0].value as? Array<FloatArray>)?.get(0)?.get(0)
            ?: (r[0].value as? FloatArray)?.get(0) ?: 0f
        t.close(); r.close(); score
    } catch (e: Exception) { Log.e(TAG, "Det err: ${e.message}"); 0f }

    override fun release() {
        wwSession?.close(); embSession?.close(); melSession?.close(); ortEnv?.close()
        wwSession = null; embSession = null; melSession = null; ortEnv = null
        isInitialized = false
        audioBuffer.clear(); melFeatures.clear(); embeddings.clear(); melFrameCount = 0
    }

    companion object { private const val TAG = "OpenWakeWord" }
}
