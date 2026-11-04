package dev.screenveil.app.model

import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Owns one downloaded TFLite model using the standalone, APK-bundled LiteRT CPU runtime.
 *
 * This deliberately uses LiteRT's backward-compatible CPU Interpreter API rather than
 * CompiledModel for these two existing models. EfficientDet ends in TFLite_Detection_PostProcess;
 * LiteRT CompiledModel's CPU CreateOutputBuffers path has an upstream issue that can allocate
 * four-byte output buffers for this operator. Caller-owned direct buffers sized from Interpreter
 * tensor metadata avoid that unsafe path. This adapter adds no MediaPipe Tasks SDK, remote runtime,
 * analytics, or usage telemetry.
 *
 * Instances are created, invoked and closed by ScreenProtectionAccessibilityService's
 * single-thread inference executor. Direct input/output buffers are allocated once and reused.
 */
internal class LocalLiteRtCpuModel(
    modelFile: File,
    expectedInputShape: IntArray,
    expectedOutputShapes: List<IntArray>,
    expectedInputDataType: DataType = DataType.FLOAT32,
    expectedOutputDataTypes: List<DataType> = List(expectedOutputShapes.size) { DataType.FLOAT32 }
) : AutoCloseable {
    private val modelBuffer: MappedByteBuffer
    private val interpreter: Interpreter
    private val inputBuffer: ByteBuffer
    private val inputDataType: DataType
    private val outputDataTypes: List<DataType>
    private val outputBuffers: List<ByteBuffer>
    private val outputMap: MutableMap<Int, Any>
    private var closed = false

    init {
        require(modelFile.isFile && modelFile.canRead()) {
            "LiteRT model is not a readable private model file: ${modelFile.name}"
        }
        require(modelFile.length() > 0L) { "LiteRT model file is empty: ${modelFile.name}" }
        require(expectedInputShape.isNotEmpty() && expectedInputShape.all { it > 0 } &&
            expectedOutputShapes.isNotEmpty() && expectedOutputShapes.all { shape -> shape.isNotEmpty() && shape.all { it > 0 } } &&
            expectedOutputDataTypes.size == expectedOutputShapes.size
        ) {
            "LiteRT model contract must declare fixed, positive input and output tensor shapes"
        }

        val mapped = FileInputStream(modelFile).channel.use { channel ->
            require(channel.size() in 1L..MAX_MODEL_BYTES) {
                "LiteRT model size is outside the supported local mapping limit: ${modelFile.name}"
            }
            channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size())
        }
        val options = Interpreter.Options().setNumThreads(CPU_THREADS)
        val created = Interpreter(mapped, options)
        try {
            created.allocateTensors()
            check(created.inputTensorCount == 1) {
                "Unexpected LiteRT input tensor count for ${modelFile.name}: found ${created.inputTensorCount}"
            }
            if (created.outputTensorCount != expectedOutputShapes.size) {
                val actualOutputContract = (0 until created.outputTensorCount).joinToString { index ->
                    val tensor = created.getOutputTensor(index)
                    "$index:${tensor.dataType()}${tensor.shape().contentToString()}"
                }
                error(
                    "Unexpected LiteRT output tensor count for ${modelFile.name}: " +
                        "expected ${expectedOutputShapes.size}, found ${created.outputTensorCount}; " +
                        "actual outputs=[$actualOutputContract]"
                )
            }

            val inputTensor = created.getInputTensor(0)
            check(inputTensor.dataType() == expectedInputDataType &&
                inputTensor.dataType() in SUPPORTED_INPUT_DATA_TYPES
            ) {
                "Unsupported LiteRT input type for ${modelFile.name}: expected $expectedInputDataType, found ${inputTensor.dataType()}"
            }
            check(inputTensor.shape().contentEquals(expectedInputShape)) {
                "Unexpected LiteRT input shape for ${modelFile.name}: " +
                    "expected ${expectedInputShape.contentToString()}, found ${inputTensor.shape().contentToString()}"
            }
            val inputBytes = checkedTensorBytes(
                inputTensor.shape(), inputTensor.dataType(), inputTensor.numBytes(), modelFile.name
            )

            val allocatedOutputs = ArrayList<ByteBuffer>(expectedOutputShapes.size)
            val allocatedOutputMap = LinkedHashMap<Int, Any>(expectedOutputShapes.size)
            for (index in expectedOutputShapes.indices) {
                val tensor = created.getOutputTensor(index)
                check(tensor.dataType() == expectedOutputDataTypes[index] &&
                    tensor.dataType() in SUPPORTED_OUTPUT_DATA_TYPES
                ) {
                    "Unsupported LiteRT output type $index for ${modelFile.name}: " +
                        "expected ${expectedOutputDataTypes[index]}, found ${tensor.dataType()}"
                }
                check(tensor.shape().contentEquals(expectedOutputShapes[index])) {
                    "Unexpected LiteRT output shape $index for ${modelFile.name}: " +
                        "expected ${expectedOutputShapes[index].contentToString()}, found ${tensor.shape().contentToString()}"
                }
                val bytes = checkedTensorBytes(tensor.shape(), tensor.dataType(), tensor.numBytes(), modelFile.name)
                val buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
                allocatedOutputs += buffer
                allocatedOutputMap[index] = buffer
            }

            modelBuffer = mapped // Keep the mapped model alive for the lifetime of Interpreter.
            interpreter = created
            inputBuffer = ByteBuffer.allocateDirect(inputBytes).order(ByteOrder.nativeOrder())
            inputDataType = inputTensor.dataType()
            outputDataTypes = expectedOutputDataTypes.toList()
            outputBuffers = allocatedOutputs
            outputMap = allocatedOutputMap
        } catch (error: Throwable) {
            runCatching { created.close() }
            throw error
        }
    }

    /** Runs one float32 input tensor. The caller validates the model-specific input shape. */
    fun runFloat(input: FloatArray): List<FloatArray> {
        check(inputDataType == DataType.FLOAT32) {
            "LiteRT model expects $inputDataType input, not FLOAT32"
        }
        require(input.isNotEmpty()) { "LiteRT input tensor cannot be empty" }
        require(input.size.toLong() * Float.SIZE_BYTES == inputBuffer.capacity().toLong()) {
            "LiteRT input element count mismatch: expected ${inputBuffer.capacity() / Float.SIZE_BYTES}, found ${input.size}"
        }
        inputBuffer.clear()
        input.forEach { inputBuffer.putFloat(it) }
        return runPreparedInput()
    }

    /** Runs one unsigned-byte RGB input tensor (pixel values 0..255), without normalization. */
    fun runUInt8(input: ByteArray): List<FloatArray> {
        check(inputDataType == DataType.UINT8) {
            "LiteRT model expects $inputDataType input, not UINT8"
        }
        require(input.isNotEmpty()) { "LiteRT input tensor cannot be empty" }
        require(input.size == inputBuffer.capacity()) {
            "LiteRT input byte count mismatch: expected ${inputBuffer.capacity()}, found ${input.size}"
        }
        inputBuffer.clear()
        inputBuffer.put(input)
        return runPreparedInput()
    }

    private fun runPreparedInput(): List<FloatArray> {
        check(!closed) { "LiteRT model is closed" }
        check(modelBuffer.limit() > 0) { "LiteRT mapped model buffer is no longer available" }
        inputBuffer.rewind()
        outputBuffers.forEach { it.clear() }
        interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputBuffer), outputMap)

        return outputBuffers.mapIndexed { index, buffer ->
            val readable = buffer.duplicate().order(ByteOrder.nativeOrder())
            readable.rewind()
            when (outputDataTypes[index]) {
                DataType.FLOAT32 -> {
                    check(readable.remaining() % Float.SIZE_BYTES == 0) {
                        "LiteRT output tensor $index contains a partial float"
                    }
                    FloatArray(readable.remaining() / Float.SIZE_BYTES) { readable.float }
                }
                DataType.INT32 -> {
                    check(readable.remaining() % Int.SIZE_BYTES == 0) {
                        "LiteRT output tensor $index contains a partial integer"
                    }
                    FloatArray(readable.remaining() / Int.SIZE_BYTES) { readable.int.toFloat() }
                }
                else -> error("Unsupported LiteRT output type at inference: ${outputDataTypes[index]}")
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { interpreter.close() }
        // modelBuffer is intentionally retained until Interpreter.close(); it is mapped read-only.
    }

    private fun checkedTensorBytes(shape: IntArray, dataType: DataType, actualBytes: Int, modelName: String): Int {
        require(shape.isNotEmpty() && shape.all { it > 0 }) {
            "LiteRT tensor has a dynamic, empty or invalid shape for $modelName: ${shape.contentToString()}"
        }
        var elements = 1L
        for (dimension in shape) {
            elements = Math.multiplyExact(elements, dimension.toLong())
            require(elements <= MAX_TENSOR_ELEMENTS) {
                "LiteRT tensor exceeds the approved allocation limit for $modelName: ${shape.contentToString()}"
            }
        }
        val bytesPerElement = when (dataType) {
            DataType.FLOAT32, DataType.INT32 -> 4L
            DataType.UINT8 -> 1L
            else -> error("Unsupported LiteRT tensor type for $modelName: $dataType")
        }
        val expectedBytes = Math.multiplyExact(elements, bytesPerElement)
        require(expectedBytes == actualBytes.toLong() && expectedBytes <= Int.MAX_VALUE) {
            "LiteRT tensor byte-size mismatch for $modelName: shape=${shape.contentToString()}, " +
                "type=$dataType, expected=$expectedBytes, runtime=$actualBytes"
        }
        return expectedBytes.toInt()
    }

    private companion object {
        val SUPPORTED_INPUT_DATA_TYPES = setOf(DataType.FLOAT32, DataType.UINT8)
        val SUPPORTED_OUTPUT_DATA_TYPES = setOf(DataType.FLOAT32, DataType.INT32)
        const val CPU_THREADS = 2
        const val MAX_TENSOR_ELEMENTS = 1_000_000L
        const val MAX_MODEL_BYTES = 32_000_000L
    }
}
