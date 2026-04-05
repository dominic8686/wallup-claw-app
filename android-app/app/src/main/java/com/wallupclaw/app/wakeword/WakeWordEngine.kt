package com.wallupclaw.app.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.ShortBuffer

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
 * OpenWakeWord engine ported from wyoming-satellite-android.
 * Uses the proven 3-stage pipeline: mel → embedding → detection.
 */
class OpenWakeWordEngine : WakeWordEngine {

    override val engineName = "OpenWakeWord"
    override var isInitialized = false; private set
    override var onScoreUpdate: ((Float) -> Unit)? = null

    private var model: PredictionModel? = null
    private var sensitivity = 0.5f

    override fun initialize(context: Context, modelAssetPath: String, sensitivity: Float) {
        try {
            this.sensitivity = sensitivity
            model = PredictionModel(
                wakeWordModelBytes = context.assets.open(modelAssetPath).readBytes(),
                melspecModelBytes = context.assets.open("wakeword_models/melspectrogram.onnx").readBytes(),
                embeddingModelBytes = context.assets.open("wakeword_models/embedding_model.onnx").readBytes(),
            )
            isInitialized = true
            Log.i(TAG, "Pipeline ready: $modelAssetPath (sensitivity=$sensitivity)")
        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
            isInitialized = false
        }
    }

    override fun processAudio(audioData: ShortArray): Float {
        if (!isInitialized || model == null) return 0f
        val score = model!!.predict(audioData)
        onScoreUpdate?.invoke(score)
        if (score > sensitivity) {
            Log.i(TAG, "DETECTED! score=${"%.3f".format(score)} threshold=$sensitivity")
        }
        return score
    }

    override fun release() {
        model?.close()
        model = null
        isInitialized = false
    }

    companion object { private const val TAG = "OpenWakeWord" }
}

/**
 * Ported from wyoming-satellite-android PredictionModel + AudioFeatures.
 * Original: https://github.com/walluptech/wyoming-satellite-android
 */
private class PredictionModel(
    wakeWordModelBytes: ByteArray,
    melspecModelBytes: ByteArray,
    embeddingModelBytes: ByteArray,
) {
    private val ortEnv = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        addCPU(true)
    }
    private val session = ortEnv.createSession(wakeWordModelBytes, options)
    private val preprocessor = AudioFeatures(ortEnv, melspecModelBytes, embeddingModelBytes, options)
    private val modelInputShape = (session.inputInfo.values.first().info as TensorInfo).shape[1].toInt()
    private var buffer: FloatBuffer? = null

    @Suppress("UNCHECKED_CAST")
    fun predict(data: ShortArray): Float {
        val features = preprocessor.process(data, modelInputShape)
        val featureSize = features.size
        if (featureSize < modelInputShape) return 0f
        val numFeatures = features[0].size

        val floatBuffer = buffer ?: FloatBuffer.allocate(featureSize * numFeatures).also { buffer = it }
        floatBuffer.clear()
        features.forEach { floatBuffer.put(it) }
        floatBuffer.flip()

        val inputTensor = OnnxTensor.createTensor(
            ortEnv, floatBuffer,
            longArrayOf(1L, featureSize.toLong(), numFeatures.toLong())
        )
        val result = session.run(mapOf(session.inputNames.first() to inputTensor))
        inputTensor.close()
        return (result[0].value as Array<FloatArray>)[0][0]
    }

    fun reset() {
        preprocessor.reset()
        buffer?.clear()
    }

    fun close() {
        preprocessor.close()
        session.close()
    }

    private class AudioFeatures(
        val ortEnv: OrtEnvironment,
        melspecModelBytes: ByteArray,
        embeddingModelBytes: ByteArray,
        sessionOptions: OrtSession.SessionOptions
    ) {
        companion object {
            private const val MEL_BANDS = 76
            private const val MEL_BANDS_L = 76L
            private const val MEL_FEATURES = 32
            private const val MEL_FEATURES_L = 32L
            private const val MEL_FRAMES_PER_SEC = 97
            private const val MEL_MAX_LEN = 10 * MEL_FRAMES_PER_SEC
            private const val CHUNK_LEN = 1280
            private const val RATE = 16_000
        }

        private val melspecSession = ortEnv.createSession(melspecModelBytes, sessionOptions)
        private val embeddingSession = ortEnv.createSession(embeddingModelBytes, sessionOptions)

        private val rawDataBuffer = ShortRingBuffer(10 * RATE)
        private val featureBuffer = FixedRingBuffer<FloatArray>(120)

        private var accumulatedSamples = 0
        private val rawDataRemainderBuffer: ShortBuffer = ShortBuffer.allocate(CHUNK_LEN)
        private val rawCombinedBuffer: ShortBuffer = ShortBuffer.allocate(4096)

        private val melspectrogramBuffer = MutableList(MEL_BANDS) { FloatArray(MEL_FEATURES) { 1.0f } }
        private val melTempBuffer = ShortArray((CHUNK_LEN * 10) + (160 * 3))
        private val melTempShortBuffer = ShortBuffer.wrap(melTempBuffer)
        private val melTempFloatBuffer = FloatBuffer.wrap(FloatArray(RATE * 4))
        private val melLoopBuffer = FloatBuffer.allocate(MEL_BANDS * MEL_FEATURES)

        init {
            listOf(getEmbeddings()).forEach { featureBuffer.add(it) }
        }

        private fun getEmbeddings(): FloatArray {
            val randomAudio = ShortBuffer.wrap(ShortArray(RATE * 4) {
                (Math.random() * 2000 - 1000).toInt().toShort()
            })
            val spec = getMelspectrogram(randomAudio)
            val windowSize = MEL_BANDS
            val stepSize = 8
            val batchSize = (spec.size - windowSize) / stepSize + 1
            if (batchSize <= 0) return floatArrayOf()

            val width = spec[0].size
            val floatBuffer = FloatBuffer.allocate(batchSize * windowSize * width * 1).apply {
                for (step in 0 until batchSize) {
                    val start = step * stepSize
                    for (i in 0 until windowSize) put(spec[start + i])
                }
                flip()
            }

            val inputTensor = OnnxTensor.createTensor(
                ortEnv, floatBuffer,
                longArrayOf(batchSize.toLong(), windowSize.toLong(), width.toLong(), 1L)
            )
            return embeddingSession.runOnce("input_1", inputTensor)[0]
        }

        fun process(data: ShortArray, nFeatureFrames: Int): List<FloatArray> {
            rawCombinedBuffer.apply {
                clear()
                put(rawDataRemainderBuffer).also { rawDataRemainderBuffer.clear() }
                put(data)
                flip()
            }

            val totalSamples = accumulatedSamples + rawCombinedBuffer.remaining()
            val fullChunkLength = rawCombinedBuffer.remaining() - (totalSamples % CHUNK_LEN)

            if (fullChunkLength > 0) {
                val slice = rawCombinedBuffer.slice()
                slice.limit(fullChunkLength)
                rawDataBuffer.put(slice, fullChunkLength)
                accumulatedSamples += fullChunkLength
                rawCombinedBuffer.position(rawCombinedBuffer.position() + fullChunkLength)
            }

            if (rawCombinedBuffer.hasRemaining()) {
                rawDataRemainderBuffer.apply {
                    clear()
                    put(rawCombinedBuffer)
                    flip()
                }
            }

            val numNewChunks = accumulatedSamples / CHUNK_LEN
            if (numNewChunks > 0 && accumulatedSamples % CHUNK_LEN == 0) {
                streamingMelspectrogram(accumulatedSamples)

                val melspecSize = melspectrogramBuffer.size
                for (i in (numNewChunks - 1) downTo 0) {
                    val ndx = if (i != 0) melspecSize - 8 * i else melspecSize
                    val startIndex = ndx - MEL_BANDS
                    val endIndex = ndx

                    if (startIndex >= 0 && endIndex <= melspecSize) {
                        melLoopBuffer.clear()
                        for (j in startIndex until endIndex) melLoopBuffer.put(melspectrogramBuffer[j])
                        melLoopBuffer.flip()

                        val inputTensor = OnnxTensor.createTensor(
                            ortEnv, melLoopBuffer,
                            longArrayOf(1, MEL_BANDS_L, MEL_FEATURES_L, 1)
                        )
                        val value = embeddingSession.runOnce("input_1", inputTensor)
                        featureBuffer.add(value[0])
                    }
                }
                accumulatedSamples = 0
            }

            return featureBuffer.takeLast(nFeatureFrames)
        }

        private fun streamingMelspectrogram(nSamples: Int) {
            val extractSize = nSamples + 160 * 3
            require(extractSize <= melTempBuffer.size)

            val available = rawDataBuffer.available()
            if (available >= extractSize) {
                val readStart = available - extractSize
                rawDataBuffer.peekFrom(readStart, melTempBuffer, 0, extractSize)

                melTempShortBuffer.clear()
                melTempShortBuffer.limit(extractSize)

                melspectrogramBuffer.addAll(getMelspectrogram(melTempShortBuffer))
                while (melspectrogramBuffer.size > MEL_MAX_LEN) melspectrogramBuffer.removeAt(0)
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun getMelspectrogram(buffer: ShortBuffer): Array<FloatArray> {
            melTempFloatBuffer.apply {
                clear()
                while (buffer.hasRemaining()) put(buffer.get().toFloat())
                flip()
            }
            val inputTensor = OnnxTensor.createTensor(
                ortEnv, melTempFloatBuffer,
                longArrayOf(1L, melTempFloatBuffer.remaining().toLong())
            )
            val spec = melspecSession.runOnce("input", inputTensor)
            for (row in spec) {
                for (j in row.indices) row[j] = row[j] / 10f + 2f
            }
            return spec
        }

        fun reset() {
            rawDataBuffer.clear()
            featureBuffer.clear()
            rawDataRemainderBuffer.clear()
            accumulatedSamples = 0
            listOf(getEmbeddings()).forEach { featureBuffer.add(it) }
        }

        fun close() {
            melspecSession.close()
            embeddingSession.close()
        }

        @Suppress("UNCHECKED_CAST")
        private fun OrtSession.runOnce(inputName: String, inputTensor: OnnxTensor): Array<FloatArray> {
            val result = this.run(mapOf(inputName to inputTensor))
            inputTensor.close()
            return (result[0].value as Array<Array<Array<FloatArray>>>)[0][0]
        }
    }
}
