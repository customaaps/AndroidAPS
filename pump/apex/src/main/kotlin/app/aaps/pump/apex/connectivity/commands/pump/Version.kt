package app.aaps.pump.apex.connectivity.commands.pump

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.utils.toHex
import app.aaps.pump.apex.R
import app.aaps.pump.apex.connectivity.ProtocolVersion

class Version: PumpObjectModel {
    private var _fwMajor: Int? = null
    private var _fwMinor: Int? = null
    private var _proto: ProtocolVersion? = null
    private var command: PumpCommand? = null

    constructor(command: PumpCommand) {
        _fwMajor = null
        _fwMinor = null
        _proto = null
        this.command = command
    }

    constructor(fwMajor: Int, fwMinor: Int, protocolVersion: ProtocolVersion) {
        _fwMajor = fwMajor
        _fwMinor = fwMinor
        _proto = protocolVersion
        command = null
    }

    /** Firmware major part of version */
    val firmwareMajor get() = _fwMajor ?: command!!.objectData[6].toUByte().toInt()

    /** Firmware minor part of version */
    val firmwareMinor get() = _fwMinor ?: command!!.objectData[7].toUByte().toInt()

    /** Protocol major part of version */
    val protocolMajor get() = _proto?.major ?: command!!.objectData[8].toUByte().toInt()

    /** Protocol minor part of version */
    val protocolMinor get() = _proto?.minor ?: command!!.objectData[9].toUByte().toInt()

    /** Firmware patch part of version, or null if the pump does not report one.
     *
     * Evidence (CommandsTest `pump_version` capture, fw 6.25 / proto 4.10): the Version
     * response `objectData` is exactly 10 bytes — [0]=valueId, [1]=index, [2..5]=reserved
     * zeros, [6]=fwMajor, [7]=fwMinor, [8]=protoMajor, [9]=protoMinor. There is NO patch
     * byte on this firmware, so this stays null and the display is unchanged. (Blueberry's
     * "firmwarePatchVersion" is thus absent here; its "hardware"/"securityPatch" are Android
     * device info, not pump fields.) If a different/newer firmware returns a longer packet,
     * the trailing byte surfaces automatically — confirm the exact layout from the raw bytes
     * that `toString()` now logs before trusting it. */
    val firmwarePatch: Int? get() = command?.objectData?.getOrNull(10)?.toUByte()?.toInt()

    fun toLocalString(rh: ResourceHelper): String {
        val patch = firmwarePatch
        return if (patch != null)
            rh.gs(R.string.overview_pump_fw_patch, firmwareMajor, firmwareMinor, patch, protocolMajor, protocolMinor)
        else
            rh.gs(R.string.overview_pump_fw, firmwareMajor, firmwareMinor, protocolMajor, protocolMinor)
    }

    fun atleastProto(proto: ProtocolVersion): Boolean {
        // Lexicographic (major, minor) compare — a higher major with a lower minor still counts as "at least".
        return protocolMajor > proto.major || (protocolMajor == proto.major && protocolMinor >= proto.minor)
    }

    fun isSupported(min: ProtocolVersion, max: ProtocolVersion): Boolean {
        if (min.major > protocolMajor || max.major < protocolMajor) return false
        if (max.major > protocolMajor) return true
        if (max.minor < protocolMinor) return false
        return true
    }

    override fun toString(): String =
        "Version(fw = $firmwareMajor.$firmwareMinor${firmwarePatch?.let { ".$it" } ?: ""}, " +
            "proto = $protocolMajor.$protocolMinor" +
            (command?.let { ", raw = ${it.objectData.toHex()}" } ?: "") + ")"
}
