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

        // IMPORTANT: mel model expects raw int16 values as float, NOT normalized to -1..1
        for (s in audioData) audioBuffer.add(s.toFloat())

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
                if (melFrameCount <= 80) Log.d(TAG, "Running embedding (melFrames=${melFeatures.size}, count=$melFrameCount)")
                val emb = runEmbedding(melFeatures.takeLast(MEL_FRAMES_PER_EMBEDDING)) ?: continue
                if (embeddings.size < 20) Log.d(TAG, "Embedding: ${emb.size} dims, first=${emb.firstOrNull()}")
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
        val raw = r[0].value
        if (melFrameCount <= 3) Log.d(TAG, "Mel output type: ${raw?.javaClass?.name}")

        // openWakeWord mel output is typically [1, 1, N, 32]
        // But could also be float[][] or float[][][] depending on version
        val out: FloatArray? = when (raw) {
            is Array<*> -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    (raw as Array<Array<Array<FloatArray>>>)[0][0].lastOrNull()
                } catch (_: Exception) {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        (raw as Array<Array<FloatArray>>)[0].lastOrNull()
                    } catch (_: Exception) {
                        @Suppress("UNCHECKED_CAST")
                        (raw as? Array<FloatArray>)?.lastOrNull()
                    }
                }
            }
            is FloatArray -> raw
            else -> null
        }

        // Apply openWakeWord normalization: value / 10.0 + 2.0
        val normalized = out?.map { (it / 10f) + 2f }?.toFloatArray()
        if (melFrameCount <= 3) Log.d(TAG, "Mel result: ${normalized?.size ?: "null"} bins")
        t.close(); r.close(); normalized
    } catch (e: Exception) { Log.e(TAG, "Mel err: ${e.message}"); null }

    private fun runEmbedding(mels: List<FloatArray>): FloatArray? = try {
        val n = mels.size; val bins = mels[0].size
        val flat = FloatArray(n * bins)
        mels.forEachIndexed { i, m -> System.arraycopy(m, 0, flat, i * bins, bins) }
        val t = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(flat), longArrayOf(1, n.toLong(), bins.toLong(), 1))
        val r = embSession!!.run(mapOf(embSession!!.inputNames.first() to t))
        // Output: [batch, 1, 1, 96]
        val raw = r[0].value
        val out: FloatArray? = try {
            @Suppress("UNCHECKED_CAST")
            (raw as Array<Array<Array<FloatArray>>>)[0][0][0]
        } catch (_: Exception) {
            try {
                @Suppress("UNCHECKED_CAST")
                (raw as Array<Array<FloatArray>>)[0][0]
            } catch (_: Exception) {
                @Suppress("UNCHECKED_CAST")
                (raw as? Array<FloatArray>)?.get(0) ?: (raw as? FloatArray)
            }
        }
        t.close(); r.close(); out
    } catch (e: Exception) { Log.e(TAG, "Emb err: ${e.message}"); null }

    private fun runDetection(embs: List<FloatArray>): Float = try {
        val n = embs.size; val sz = embs[0].size
        val flat = FloatArray(n * sz)
        embs.forEachIndexed { i, e -> System.arraycopy(e, 0, flat, i * sz, sz) }
        // Shape: [1, 16, 96] — 3D, not flattened
        val t = OnnxTensor.createTensor(ortEnv, FloatBuffer.wrap(flat), longArrayOf(1, n.toLong(), sz.toLong()))
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
