package app.aaps.pump.danarv2.comm

import app.aaps.pump.danar.comm.DanaRTestBase
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.whenever

class MsgHistoryEventsRv2Test : DanaRTestBase() {

    @BeforeEach
    fun stubLocalizedHistoryLabels() {
        doReturn("Timeout waiting for history").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_timeout)
        doReturn("Temporary basal started").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_temp_start)
        doReturn("Temporary basal stopped").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_temp_stop)
        doReturn("Extended bolus started").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_extended_start)
        doReturn("Extended bolus stopped").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_extended_stop)
        doReturn("Bolus").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_bolus)
        doReturn("Dual bolus").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_dual_bolus)
        doReturn("Dual extended bolus started").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_dual_extended_start)
        doReturn("Dual extended bolus stopped").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_dual_extended_stop)
        doReturn("Delivery suspended").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_suspend_on)
        doReturn("Delivery resumed").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_suspend_off)
        doReturn("Reservoir refill").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_refill)
        doReturn("Priming").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_prime)
        doReturn("Profile changed").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_profile_change)
        doReturn("Carbohydrates").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_carbs)
        doReturn("Cannula priming").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_prime_cannula)
        doReturn("Time changed").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_time_change)
        doReturn("Step bolus command").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_step_bolus_command)
        doReturn("Basal rate changed").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_basal_rate_change)
        doReturn("Unknown event").whenever(rh).gs(app.aaps.pump.dana.R.string.localization_history_unknown)
    }

    @Test fun runTest() {
        val packet = MsgHistoryEventsV2(injector, 0)

        // test message decoding
        val array = createArray(100, 2)

        putByteToArray(array, 0, 0x01.toByte())
        packet.handleMessage(array)
        Assertions.assertEquals(false, danaPump.historyDoneReceived)

        putByteToArray(array, 0, 0xFF.toByte())
        packet.handleMessage(array)
        Assertions.assertEquals(true, danaPump.historyDoneReceived)
    }
}
