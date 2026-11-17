package app.aaps.plugins.source.libre2

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

internal data class Libre2DecoderState(
    val sensorStartSeconds: Long = 0,
    val algorithmEndSeconds: Long = 0,
    val warmupMinutes: Int = 0,
    val wearMinutes: Int = 0,
    val person: Byte = 0,
    val arrays: Array<ByteArray> = Array(3) { byteArrayOf() },
) {
    fun metadata() = longArrayOf(sensorStartSeconds, algorithmEndSeconds, warmupMinutes.toLong(), wearMinutes.toLong(), person.toLong())

    fun encode(): ByteArray = ByteArrayOutputStream().also { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(1)
            metadata().forEach(output::writeLong)
            require(arrays.size == 3)
            arrays.forEach { array ->
                require(array.size <= MAX_ARRAY)
                output.writeInt(array.size)
                output.write(array)
            }
        }
    }.toByteArray()

    companion object {
        private const val MAX_ARRAY = 1024 * 1024

        fun decode(bytes: ByteArray): Libre2DecoderState {
            require(bytes.size <= 3 * MAX_ARRAY + 56) { "Oversized Libre2 state" }
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == 1) { "Unsupported Libre2 state version" }
                val meta = LongArray(5) { input.readLong() }
                val arrays = Array(3) {
                    val length = input.readInt()
                    require(length in 0..MAX_ARRAY && length <= input.available()) { "Invalid Libre2 state array length" }
                    ByteArray(length).also(input::readFully)
                }
                require(input.available() == 0) { "Trailing Libre2 state data" }
                fromNative(meta, arrays)
            }
        }

        fun fromNative(metadata: LongArray, arrays: Array<ByteArray>): Libre2DecoderState {
            require(metadata.size == 5 && arrays.size == 3) { "Invalid Libre2 state shape" }
            require(metadata[0] >= 0 && metadata[1] >= 0)
            require(metadata[2] in 0..44_640 && metadata[3] in 0..44_640)
            require(metadata[4] in -128..127)
            require(arrays.all { it.size <= MAX_ARRAY })
            return Libre2DecoderState(
                metadata[0], metadata[1], metadata[2].toInt(), metadata[3].toInt(), metadata[4].toByte(),
                arrays.map { it.copyOf() }.toTypedArray()
            )
        }
    }
}

internal data class Libre2DecodeResult(
    val status: Int,
    val state: Libre2DecoderState,
    val glucoseMgdl: Int,
    val quality: Int,
    val sampleId: Int,
    val trend: Int,
    val removed: Boolean,
    val rateMgdlPerMinute: Float,
) {
    val publicationRejection: String?
        get() = when {
            status != 0 -> "vendor_status"
            removed -> "sensor_removed"
            quality != 0 -> "quality"
            glucoseMgdl !in 1..1000 -> "glucose_range"
            state.sensorStartSeconds <= 0 -> "sensor_start"
            state.algorithmEndSeconds < state.sensorStartSeconds -> "algorithm_time"
            else -> null
        }

    val canPublish: Boolean get() = publicationRejection == null

    fun diagnosticSummary(): String =
        "status=$status, quality=$quality, sampleId=$sampleId, glucoseMgdl=$glucoseMgdl, removed=$removed, " +
            "sensorStartSeconds=${state.sensorStartSeconds}, algorithmEndSeconds=${state.algorithmEndSeconds}, " +
            "rejection=${publicationRejection ?: "none"}"

    companion object {
        fun fromNative(reply: Array<Any>): Libre2DecodeResult {
            require(reply.size == 3)
            val metadata = reply[0] as? LongArray ?: error("Missing Libre2 decoder metadata")
            val rate = reply[1] as? FloatArray ?: error("Missing Libre2 decoder rate")
            val rawArrays = reply[2] as? Array<*> ?: error("Missing Libre2 algorithm state")
            require(metadata.size == 11 && rate.size == 1 && rawArrays.size == 3)
            require(listOf(0, 6, 7, 8, 9).all { metadata[it] in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() })
            require(metadata[1] in 0..Long.MAX_VALUE / 1000 && metadata[2] in 0..Long.MAX_VALUE / 1000)
            require(metadata[10] in 0..1)
            val arrays = rawArrays.map { (it as? ByteArray) ?: error("Invalid Libre2 state array") }.toTypedArray()
            return Libre2DecodeResult(
                metadata[0].toInt(),
                Libre2DecoderState.fromNative(metadata.copyOfRange(1, 6), arrays),
                metadata[6].toInt(), metadata[7].toInt(), metadata[8].toInt(), metadata[9].toInt(),
                metadata[10] != 0L, rate[0].takeIf { it.isFinite() } ?: Float.NaN,
            )
        }
    }
}
