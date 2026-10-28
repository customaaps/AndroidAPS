package app.aaps.pump.apex

import app.aaps.pump.apex.connectivity.commands.pump.AlarmLength
import app.aaps.pump.apex.connectivity.commands.pump.PumpCommand
import app.aaps.pump.apex.connectivity.commands.pump.StatusV2
import app.aaps.pump.apex.utils.ApexCrypto

/** Pump frames built by hand, shared by the tests that need one. */
internal object ApexTestFrames {

    /** Build a well-formed pump frame (correct length + CRC) around the given object data. */
    fun pumpFrame(objectData: UByteArray): PumpCommand {
        val length = 4 + objectData.size + 2
        val frame = ByteArray(length)
        frame[0] = 0xAA.toByte()
        frame[1] = length.toByte()
        frame[2] = 0x00
        frame[3] = 0xA3.toByte() // GetValue
        objectData.forEachIndexed { i, b -> frame[4 + i] = b.toByte() }
        val crc = ApexCrypto.crc16(frame, length - 2)
        frame[length - 2] = crc[0]
        frame[length - 1] = crc[1]
        val command = PumpCommand(frame)
        assert(command.isCompleteCommand())
        assert(command.verify())
        return command
    }

    /**
     * StatusV2 payload: valueId 0x0C, index, 2-byte absolute insulin, alarm length, battery
     * centivolts. Six bytes exactly, so the optional audio-bolus fields stay absent.
     */
    fun statusV2(centiVolts: Int) = StatusV2(
        pumpFrame(ubyteArrayOf(0x0Cu, 0x00u, 0x00u, 0x00u, AlarmLength.Medium.raw.toUByte(), centiVolts.toUByte()))
    )
}
