package app.aaps.pump.apex.connectivity.commands.device

import app.aaps.pump.apex.ApexDriverLogic
import app.aaps.pump.apex.interfaces.ApexDeviceInfo

/**
 * Set the pump's connection password: command `0x35` / `SetValue` / valueId 53, six value bytes
 * `0x00..0x09`, one per digit.
 *
 * This is the maker's real "change password" path - its Save button calls it over the main channel -
 * not the dead twelve-byte FFC1 `old+new` screen. It does not carry an old password; the pump takes it
 * on a data channel that is already open (the current password unlocked it, or the pump has no lock).
 *
 * No live path constructs this yet. The byte layout is reconstructed from the decompiled maker app and
 * has NOT been byte-compared against a capture from a real pump, so it must be verified on hardware the
 * owner controls before anything sends it. The constructor refuses a password that is not six digits.
 */
class ForceUpdatePassword(
    info: ApexDeviceInfo,
    digits: String,
) : BaseValueCommand(info) {
    override val valueId = 53
    override val isWrite = true

    override val additionalData: ByteArray =
        ApexDriverLogic.forceUpdatePasswordBytes(digits)
            ?: throw IllegalArgumentException("Password must be exactly six digits")

    // Never the digits - only that there are six of them.
    override fun toString(): String = "ForceUpdatePassword(6 bytes)"
}
