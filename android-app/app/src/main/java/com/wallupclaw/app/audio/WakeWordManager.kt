package com.wallupclaw.app.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import com.wallupclaw.app.audio.FixedRingBuffer
import com.wallupclaw.app.audio.ShortRingBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.coroutines.CoroutineContext

class WakeWordManager(private val context: Context) : CoroutineScope {
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default

    @Volatile
    private var model: PredictionModel? = null
    private val _scores = MutableSharedFlow<Float>(extraBufferCapacity = 10)
    val scores: SharedFlow<Float> = _scores

    fun initialize(modelAsset: String) {
        model?.close()
        // Resolve model directory from the model asset path
        val modelDir = modelAsset.substringBeforeLast("/", "").let { if (it.isNotEmpty()) "$it/" else "" }
        model = PredictionModel(
            context.assets.open(modelAsset).readBytes(),
            context.assets.open("${modelDir}melspectrogram.onnx").readBytes(),
            context.assets.open("${modelDir}embedding_model.onnx").readBytes(),
        )
        Log.i(TAG, "Model loaded: $modelAsset")
    }

    fun processAudio(data: ByteArray, size: Int) {
        launch {
            try {
                val score = model?.predict(data, size) ?: 0f
                _scores.emit(score)
            } catch (_: Exception) {
                // Session may be closed during cleanup
            }
        }
    }

    fun release() {
        val m = model
        model = null  // Null first to prevent concurrent access
        Thread.sleep(100)  // Let in-flight predictions finish
        m?.close()
    }

    companion object { private const val TAG = "WakeWordMgr" }
}

// Exact port from wyoming-satellite-android
private class PredictionModel(
    wakeWordModelBytes: ByteArray,
    melspecModelBytes: ByteArray,
    embeddingModelBytes: ByteArray,
) {
    private val mutex = Mutex()
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
    suspend fun predict(data: ByteArray, size: Int): Float {
        mutex.withLock {
            val shorts = prepare(data, size)
            val features = preprocessor.process(shorts, modelInputShape)
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
    }

    private fun prepare(byteArray: ByteArray, size: Int): ShortArray {
        val buffer = ByteBuffer.wrap(byteArray, 0, size).order(ByteOrder.LITTLE_ENDIAN)
        val shortArray = ShortArray(size / 2)
        buffer.asShortBuffer().get(shortArray)
        return shortArray
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

        init { listOf(getEmbeddings()).forEach { featureBuffer.add(it) } }

        private fun getEmbeddings(): FloatArray {
            val randomAudio = ShortBuffer.wrap(ShortArray(RATE * 4) { (Math.random() * 2000 - 1000).toInt().toShort() })
            val spec = getMelspectrogram(randomAudio)
            val windowSize = MEL_BANDS; val stepSize = 8
            val batchSize = (spec.size - windowSize) / stepSize + 1
            if (batchSize <= 0) return floatArrayOf()
            val width = spec[0].size
            val floatBuffer = FloatBuffer.allocate(batchSize * windowSize * width).apply {
                for (step in 0 until batchSize) { val start = step * stepSize; for (i in 0 until windowSize) put(spec[start + i]) }
                flip()
            }
            val inputTensor = OnnxTensor.createTensor(ortEnv, floatBuffer, longArrayOf(batchSize.toLong(), windowSize.toLong(), width.toLong(), 1L))
            return embeddingSession.runOnce("input_1", inputTensor)[0]
        }

        fun process(data: ShortArray, nFeatureFrames: Int): List<FloatArray> {
            rawCombinedBuffer.apply { clear(); put(rawDataRemainderBuffer).also { rawDataRemainderBuffer.clear() }; put(data); flip() }
            val totalSamples = accumulatedSamples + rawCombinedBuffer.remaining()
            val fullChunkLength = rawCombinedBuffer.remaining() - (totalSamples % CHUNK_LEN)
            if (fullChunkLength > 0) {
                val slice = rawCombinedBuffer.slice(); slice.limit(fullChunkLength)
                rawDataBuffer.put(slice, fullChunkLength)
                accumulatedSamples += fullChunkLength
                rawCombinedBuffer.position(rawCombinedBuffer.position() + fullChunkLength)
            }
            if (rawCombinedBuffer.hasRemaining()) { rawDataRemainderBuffer.apply { clear(); put(rawCombinedBuffer); flip() } }

            val numNewChunks = accumulatedSamples / CHUNK_LEN
            if (numNewChunks > 0 && accumulatedSamples % CHUNK_LEN == 0) {
                streamingMelspectrogram(accumulatedSamples)
                val melspecSize = melspectrogramBuffer.size
                for (i in (numNewChunks - 1) downTo 0) {
                    val ndx = if (i != 0) melspecSize - 8 * i else melspecSize
                    val startIndex = ndx - MEL_BANDS; val endIndex = ndx
                    if (startIndex >= 0 && endIndex <= melspecSize) {
                        melLoopBuffer.clear()
                        for (j in startIndex until endIndex) melLoopBuffer.put(melspectrogramBuffer[j])
                        melLoopBuffer.flip()
                        val inputTensor = OnnxTensor.createTensor(ortEnv, melLoopBuffer, longArrayOf(1, MEL_BANDS_L, MEL_FEATURES_L, 1))
                        featureBuffer.add(embeddingSession.runOnce("input_1", inputTensor)[0])
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
                rawDataBuffer.peekFrom(available - extractSize, melTempBuffer, 0, extractSize)
                melTempShortBuffer.clear(); melTempShortBuffer.limit(extractSize)
                melspectrogramBuffer.addAll(getMelspectrogram(melTempShortBuffer))
                while (melspectrogramBuffer.size > MEL_MAX_LEN) melspectrogramBuffer.removeAt(0)
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun getMelspectrogram(buffer: ShortBuffer): Array<FloatArray> {
            melTempFloatBuffer.apply { clear(); while (buffer.hasRemaining()) put(buffer.get().toFloat()); flip() }
            val inputTensor = OnnxTensor.createTensor(ortEnv, melTempFloatBuffer, longArrayOf(1L, melTempFloatBuffer.remaining().toLong()))
            val spec = melspecSession.runOnce("input", inputTensor)
            for (row in spec) { for (j in row.indices) row[j] = row[j] / 10f + 2f }
            return spec
        }

        fun close() { melspecSession.close(); embeddingSession.close() }

        @Suppress("UNCHECKED_CAST")
        private fun OrtSession.runOnce(inputName: String, inputTensor: OnnxTensor): Array<FloatArray> {
            val result = this.run(mapOf(inputName to inputTensor))
            inputTensor.close()
            return (result[0].value as Array<Array<Array<FloatArray>>>)[0][0]
        }
    }
}
