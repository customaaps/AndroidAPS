package app.aaps.pump.apex.connectivity.commands.pump

import app.aaps.pump.apex.connectivity.commands.CommandId
import androidx.annotation.VisibleForTesting
import app.aaps.core.utils.toHex
import app.aaps.pump.apex.utils.ApexCrypto

// Read-only commands which we get from the pump.
// Format is:
// AA [UByte length*] [UByte flags] [UByte id] [data...] [UShort crc]
// * - heartbeat (a5) has incorrect length, 06 expected vs 08 actual.
class PumpCommand(private var data: ByteArray) {
    companion object {
        const val MIN_SIZE = 6
    }

    // Use getters here cause data may be changed
    val type: Int get() = data[0].toUByte().toInt() // Should always be AA
    val length: Int get() = data[1].toUByte().toInt() // May be greater or less than packet length!

    /**
     * Byte 2 of the frame, which on some reads is how many records the pump says the series holds.
     *
     * The maker's own app reads it as `dataCount` for its history series, stamps it onto every record
     * and ends the read when that many have arrived. This driver does not: it ends a chunked read
     * after 500 ms of silence, because the pump sends no end marker. Whether the two agree is what
     * `ApexService` logs it for; nothing decides anything on it.
     *
     * It is a count only on the reads the maker's parser treats that way - `ApexService` keeps that
     * list. On others the byte means something else entirely: the captured LatestBoluses frame holds
     * 0x80 against a ring that answers with ten records every time, and StatusV1 holds 1.
     *
     * Null only for a buffer too short to have a byte 2, which a real frame never is.
     */
    val declaredRecordCount: Int? get() = if (data.size > 2) data[2].toUByte().toInt() else null

    val id: CommandId? get() = CommandId.entries.find { it.raw.toUByte() == data[3].toUByte() }
    val objectData: ByteArray get() = data.copyOfRange(4, realLength() - 2)

    val checksum: ByteArray
        get() = data.copyOfRange(minOf(data.size, realLength()) - 2, minOf(data.size, realLength()))

    private fun realLength(): Int =
        if (id == CommandId.Heartbeat)
            length + 2
        else length

    @VisibleForTesting
    fun calculatedChecksum(): ByteArray {
        return ApexCrypto.crc16(data, realLength() - 2)
    }

    /** Verify checksum */
    fun verify(): Boolean {
        val calc = calculatedChecksum()
        return calc.contentEquals(checksum)
    }

    /** Is command complete? */
    fun isCompleteCommand(): Boolean {
        return data.size >= realLength()
    }

    /** Returns the next trailing command if present. */
    val trailing: PumpCommand?
        get() {
            // Trailing is present only on GetValue
            if (id != CommandId.GetValue) return null
            if (data.size <= length) return null
            if (data.size - length < MIN_SIZE) return null
            return PumpCommand(data.copyOfRange(length, data.size))
        }

    /** Add remaining data to the command. */
    fun update(remainingData: ByteArray): Boolean {
        data += remainingData
        return isCompleteCommand()
    }

    override fun toString(): String  = "PumpCommand(type=0x${type.toString(16)}, data=${objectData.toHex()})"
}