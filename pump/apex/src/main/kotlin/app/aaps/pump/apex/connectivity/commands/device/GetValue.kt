package app.aaps.pump.apex.connectivity.commands.device

import app.aaps.pump.apex.interfaces.ApexDeviceInfo

/** Get pump value.
 *
 * [value] - Value to get
 */
class GetValue(
    info: ApexDeviceInfo,
    val value: Value,
) : BaseValueCommand(info) {
    override val type = value.type
    override val valueId = value.valueId
    override val isWrite = false

    override val paddingValue: Int
        get() = when (value) {
            Value.LatestBoluses -> 0x01
            else -> 0xAA
        }

    enum class Value(val valueId: Int, val type: Int = 0x35) {
        /** Pump status and settings, proto <=4.10 */
        StatusV1(0x00),

        /**
         * The pump's whole bolus history, and the only bolus read the maker's own app uses. Returns
         * far more records than [LatestBoluses]. Nothing here sends it - see `ApexService.getBoluses`
         * for why - but the record layout is the same one, so `PumpObject.BolusEntry` accepts it and
         * wiring it up would need a trigger, not a decoder.
         */
        BolusHistory(0x01, 0x55),

        /** Pump alarms, returns latest 20 ones */
        Alarms(0x03, 0x55),

        /**
         * Cannula and tubing fills. Log-only diagnostic, off by default - see
         * `ApexBooleanKey.ReadFillHistory`. Never requested by the maker's app at connect either.
         */
        FillHistory(0x04, 0x55),

        /** Latest TDDs */
        TDDs(0x06, 0x55),

        /** Pump bolus wizard status */
        WizardStatus(0x07),

        /** Pump basal profiles */
        BasalProfiles(0x08),

        /** Pump status and settings, proto >=4.11 */
        StatusV2(0x0c),

        /** Latest boluses */
        LatestBoluses(0x21),

        /** Firmware version, proto >=4.10 */
        Version(0x31),
    }

    override fun toString(): String = "GetValue(${value.name})"
}