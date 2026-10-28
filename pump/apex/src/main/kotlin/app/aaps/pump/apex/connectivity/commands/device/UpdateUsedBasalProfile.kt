package app.aaps.pump.apex.connectivity.commands.device

import app.aaps.pump.apex.interfaces.ApexDeviceInfo

/** Set basal profile [index] to be used now.
 *
 * * [index] - Basal profile index
 * * [proto411Plus] - use the protocol 4.11+ wire format (valueId 0x34 + 16-byte payload).
 *   Older firmware (proto < 4.11) uses valueId 0x04 + a single index byte.
 */
class UpdateUsedBasalProfile(
    info: ApexDeviceInfo,
    val index: Int,
    val proto411Plus: Boolean = true,
) : BaseValueCommand(info) {
    override val valueId get() = if (proto411Plus) 0x34 else 0x04
    override val isWrite = true

    override val additionalData: ByteArray
        get() = if (proto411Plus) ByteArray(16).also { it[0] = index.toByte() }
        else byteArrayOf(index.toByte())

    override fun toString(): String = "UpdateUsedBasalProfile($index, proto411Plus=$proto411Plus)"
}