package app.aaps.plugins.source.libre2

import android.content.Context
import java.io.*
import java.util.zip.CRC32

/** Stable identity makes retries idempotent, including after either process restarts. */
internal data class Libre2Reading(
    val sensorId: String,
    val sampleId: Int,
    val timestamp: Long,
    val sensorStart: Long,
    val mgdl: Int,
    val trend: Int,
) {
    val identity: String get() = "$sensorId:$sampleId"
    val token: String get() = "$identity:$timestamp"
    fun valid() = sensorId.matches(Regex("[0-9A-F]{16}")) && sampleId >= 0 &&
        timestamp > 0 && sensorStart in 1..timestamp && mgdl in 1..1000
}

/** Write-ahead outbox: a reading is removed only after APS confirms its database transaction. */
internal class Libre2DeliveryStore(context: Context) {
    private val file = Libre2AtomicFile(File(context.filesDir, "libre2/delivery.bin"))
    private var readings = if (file.exists()) Libre2DeliveryCodec.decode(file.read(256 * 1024)) else emptyList()

    fun add(reading: Libre2Reading, now: Long): Libre2Reading {
        require(reading.valid() && Libre2ReadingPolicy.accepts(reading.timestamp, reading.mgdl, 0, now))
        val retained = readings.filter { Libre2ReadingPolicy.accepts(it.timestamp, it.mgdl, 0, now) }
        retained.firstOrNull { it.identity == reading.identity }?.let { return it }
        check(retained.size < Libre2DeliveryCodec.MAX_READINGS) { "Libre2 delivery queue is full" }
        commit(retained + reading)
        return reading
    }

    fun next(now: Long): Libre2Reading? {
        val retained = readings.filter { Libre2ReadingPolicy.accepts(it.timestamp, it.mgdl, 0, now) }
        if (retained.size != readings.size) commit(retained)
        return readings.firstOrNull()
    }

    fun acknowledge(token: String): Libre2Reading? {
        val reading = readings.firstOrNull { it.token == token } ?: return null
        commit(readings.filterNot { it.token == token })
        return reading
    }

    private fun commit(updated: List<Libre2Reading>) {
        val bytes = Libre2DeliveryCodec.encode(updated)
        file.write(bytes)
        readings = updated
    }
}

internal object Libre2DeliveryCodec {
    const val MAX_READINGS = 2048
    private const val MAX_BYTES = 256 * 1024
    private const val MAGIC = 0x4c324431

    fun encode(readings: List<Libre2Reading>): ByteArray {
        require(readings.size <= MAX_READINGS && readings.all { it.valid() })
        require(readings.map { it.identity }.distinct().size == readings.size)
        val body = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(readings.size)
                readings.forEach {
                    out.writeUTF(it.sensorId)
                    out.writeInt(it.sampleId)
                    out.writeLong(it.timestamp)
                    out.writeLong(it.sensorStart)
                    out.writeInt(it.mgdl)
                    out.writeInt(it.trend)
                }
            }
        }.toByteArray()
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.write(body)
                out.writeLong(CRC32().apply { update(body) }.value)
            }
        }.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): List<Libre2Reading> {
        require(bytes.size in 16..MAX_BYTES)
        val body = bytes.copyOf(bytes.size - 8)
        val expected = DataInputStream(ByteArrayInputStream(bytes, bytes.size - 8, 8)).readLong()
        require(CRC32().apply { update(body) }.value == expected) { "Corrupt Libre2 delivery queue" }
        return DataInputStream(ByteArrayInputStream(body)).use { input ->
            require(input.readInt() == MAGIC)
            val count = input.readInt()
            require(count in 0..MAX_READINGS)
            List(count) {
                Libre2Reading(input.readUTF(), input.readInt(), input.readLong(), input.readLong(),
                    input.readInt(), input.readInt()).also { require(it.valid()) }
            }.also {
                require(input.available() == 0 && it.map { r -> r.identity }.distinct().size == it.size)
            }
        }
    }
}
