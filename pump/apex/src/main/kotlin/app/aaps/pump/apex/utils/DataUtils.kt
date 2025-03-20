package app.aaps.pump.apex.utils

import app.aaps.core.interfaces.profile.Profile
import app.aaps.pump.apex.connectivity.ProtocolVersion
import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import org.joda.time.DateTime

fun Int.shortMSB(): Byte = (this shr 8).toByte()
fun Int.shortLSB(): Byte = toByte()

// Pump uses little-endian numbers
fun Int.asShortAsByteArray(): ByteArray = byteArrayOf(shortLSB(), shortMSB())

// What were they smoking?
fun Byte.hexAsDecToDec(): Int = ((this.toUByte() / 0x10u * 10u) + (this.toUByte() % 0x10u)).toInt()

fun Boolean.toByte(): Byte = if (this) 1 else 0
fun Byte.toBoolean(): Boolean = this == 0x01.toByte()

fun getDateTime(data: ByteArray, pos: Int, apexDeviceInfo: ApexDeviceInfo): DateTime {
    if (apexDeviceInfo.version?.atleastProto(ProtocolVersion.PROTO_4_11) == true) {
        return DateTime(
            data[pos].toUInt().toInt() + 2000, // year
            data[pos + 1].toUInt().toInt(), // day
            data[pos + 2].toUInt().toInt(), // month
            data[pos + 3].toUInt().toInt(), // hour
            data[pos + 4].toUInt().toInt(), // minute
            data[pos + 5].toUInt().toInt(), // second
        )
    } else {
        return DateTime(
            data[pos].hexAsDecToDec() + 2000, // year
            data[pos + 1].hexAsDecToDec(), // day
            data[pos + 2].hexAsDecToDec(), // month
            data[pos + 3].hexAsDecToDec(), // hour
            data[pos + 4].hexAsDecToDec(), // minute
            data[pos + 5].hexAsDecToDec(), // second
        )
    }
}

fun getUnsignedShort(data: ByteArray, pos: Int): Int {
    val msb = data[pos + 1].toUByte().toInt()
    val lsb = data[pos].toUByte().toInt()
    return (msb shl 8) or lsb
}

fun getUnsignedInt(data: ByteArray, pos: Int): Int {
    val msb1 = data[pos + 3].toUByte().toInt()
    val msb2 = data[pos + 2].toUByte().toInt()
    val lsb1 = data[pos + 1].toUByte().toInt()
    val lsb2 = data[pos].toUByte().toInt()
    return (msb1 shl 24) or (msb2 shl 16) or (lsb1 shl 8) or lsb2
}

fun Profile.toApexReadableProfile(): List<Double> = List(48) { getBasalTimeFromMidnight(it * 30 * 60) }
