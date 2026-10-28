package app.aaps.pump.apex.connectivity.commands.pump

import app.aaps.pump.apex.utils.getUnsignedShort

class BasalProfile(val command: PumpCommand): PumpObjectModel() {
    /** Basal profile index */
    val index get() = command.objectData[1].toUByte().toInt()

    /** Basal profile rates, in 0.025U steps, for every 30 minutes */
    val rates: List<Int> get() = List(48) {
        getUnsignedShort(command.objectData, 2 + it * 2)
    }

    // index byte (offset 1) + 48 rates * 2 bytes read at offset 2 -> need at least 98 bytes.
    // Guards getBasalProfiles()/isThisProfileSet() against IndexOutOfBounds on a truncated frame.
    override fun validate(): String? {
        if (command.objectData.size < 2 + 48 * 2) return "truncated basal profile"
        return null
    }
}
