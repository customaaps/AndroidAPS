package app.aaps.pump.apex.connectivity.commands.pump

import app.aaps.pump.apex.utils.getUnsignedShort

class CommandResponse(val command: PumpCommand): PumpObjectModel() {
    /** Command response code */
    val code get() = Code.entries.find { it.raw.toByte() == command.objectData[0] } ?: Code.Unknown

    /** Bolus dose if present */
    val dose get() = getUnsignedShort(command.objectData, 2)

    enum class Code(val raw: Int) {
        Accepted(0x55),

        /**
         * A rejection the maker's app treats exactly like [Invalid] (both map to its
         * `TYPE_RESPONSE_FAILURE`). Named so the log says "Rejected" instead of "Unknown"; no caller
         * branches on it, so a frame carrying it behaves exactly as before - it falls through to the
         * same arm and the command waits out its timeout. Never seen on the wire in any capture from
         * this pump, so whether the firmware ever sends it is still open. Give it a branch beside
         * [Invalid] only once a log proves it real.
         */
        Rejected(0x5A),
        Invalid(0xA5),
        Completed(0xAA),
        StandardBolusProgress(0xA0),
        ExtendedBolusProgress(0xA1),
        Unknown(0xBADC0DE)
    }
}
