package app.aaps.pump.apex

import app.aaps.pump.apex.connectivity.commands.pump.Alarm
import app.aaps.pump.apex.connectivity.commands.pump.PumpAlarm
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Which pump alarms get announced, which are urgent, and which of them may ring.
 *
 * Card bookkeeping - one card per standing alarm, and taking down exactly the right one - lives in
 * [ApexAlarmCardsTest].
 *
 * The case these exist for: the driver used to announce only when the alarm list went from empty to
 * non-empty. A low reservoir stands for hours, so anything arriving behind it — an occlusion, an empty
 * cartridge, a motor fault — was never shown, never sent to Nightscout, and never marked a running bolus
 * as failed.
 */
class ApexAlarmsTest {

    @Test
    fun anAlarmArrivingBehindOneAlreadyStandingIsAnnounced() {
        val added = ApexService.newAlarms(
            previous = listOf(PumpAlarm(Alarm.LowReservoir)),
            current = listOf(PumpAlarm(Alarm.LowReservoir), PumpAlarm(Alarm.NoDelivery))
        )

        assertThat(added).containsExactly(PumpAlarm(Alarm.NoDelivery))
    }

    @Test
    fun anAlarmThatWasAlreadyStandingIsNotAnnouncedAgain() {
        val added = ApexService.newAlarms(
            previous = listOf(PumpAlarm(Alarm.LowReservoir), PumpAlarm(Alarm.NoDelivery)),
            current = listOf(PumpAlarm(Alarm.LowReservoir), PumpAlarm(Alarm.NoDelivery))
        )

        assertThat(added).isEmpty()
    }

    @Test
    fun theSameCodeStandingInTwoSlotsIsNotSwallowedByTheFirst() {
        // The pump can report one code in two slots at once. A set difference would report nothing new
        // here and the second fault would be invisible.
        val added = ApexService.newAlarms(
            previous = listOf(PumpAlarm.of(0x300)),
            current = listOf(PumpAlarm.of(0x300), PumpAlarm.of(0x300))
        )

        assertThat(added).containsExactly(PumpAlarm.of(0x300))
    }

    @Test
    fun oneUnrecognisedCodeReplacedByAnotherIsANewAlarm() {
        // Both decode to Alarm.Unknown. While the raw code was thrown away they compared equal, so a
        // fault that REPLACED another unknown fault produced no new event at all: no card, no sound and
        // no Nightscout announcement. Replacement is the case that needs the raw code in equals -
        // an added second code would come out as new even without it.
        assertThat(PumpAlarm.of(0x300)).isNotEqualTo(PumpAlarm.of(0x301))

        val added = ApexService.newAlarms(
            previous = listOf(PumpAlarm.of(0x300)),
            current = listOf(PumpAlarm.of(0x301))
        )

        assertThat(added).containsExactly(PumpAlarm.of(0x301))
    }

    @Test
    fun anUnrecognisedCodeKeepsItsNumber() {
        assertThat(PumpAlarm.of(0x2FF).type).isEqualTo(Alarm.Unknown)
        assertThat(PumpAlarm.of(0x2FF).code).isEqualTo("0x2FF")
        assertThat(PumpAlarm.of(0x104)).isEqualTo(PumpAlarm(Alarm.LowReservoir))
    }

    @Test
    fun everythingIsNewOnTheFirstFrameAfterAnAppStart() {
        val added = ApexService.newAlarms(previous = null, current = listOf(PumpAlarm(Alarm.NoReservoir)))

        assertThat(added).containsExactly(PumpAlarm(Alarm.NoReservoir))
    }

    @Test
    fun anAlarmThatClearedIsNotReportedAsNew() {
        val added = ApexService.newAlarms(
            previous = listOf(PumpAlarm(Alarm.LowReservoir), PumpAlarm(Alarm.NoDelivery)),
            current = listOf(PumpAlarm(Alarm.LowReservoir))
        )

        assertThat(added).isEmpty()
    }

    @Test
    fun alarmsThatStopDeliveryAreUrgentAndTheInformingOnesAreNot() {
        for (alarm in listOf(Alarm.NoDelivery, Alarm.ReservoirEmpty, Alarm.NoReservoir, Alarm.DeadBattery, Alarm.MotorError))
            assertThat(ApexDriverLogic.isUrgent(alarm)).isTrue()
        for (alarm in listOf(Alarm.LowBattery, Alarm.LowReservoir, Alarm.CheckGlucose))
            assertThat(ApexDriverLogic.isUrgent(alarm)).isFalse()
    }

    @Test
    fun onlyAlarmsThatStopInsulinAreAllowedToRing() {
        for (alarm in listOf(Alarm.NoDelivery, Alarm.ReservoirEmpty, Alarm.NoReservoir, Alarm.DeadBattery, Alarm.MotorError))
            assertThat(ApexDriverLogic.ringsFor(alarm)).isTrue()
    }

    @Test
    fun aCodeTheDriverCannotNameRings() {
        // The alarm table is reverse engineered and still has Reserved entries, so an occlusion could
        // arrive under a code that lands on Unknown. Fail loud: a spurious ring can be silenced, a
        // silent occlusion cannot be heard.
        assertThat(ApexDriverLogic.ringsFor(Alarm.Unknown)).isTrue()
    }

    @Test
    fun faultsThatDoNotStopInsulinStayQuiet() {
        // A looping alarm bypasses Do Not Disturb and runs until acknowledged. A drifted clock, a screen
        // fault or a code this driver does not recognise must not do that at three in the morning — the
        // user would mute the channel and lose the occlusion alarm with it.
        for (alarm in listOf(Alarm.ClockError, Alarm.TimeError, Alarm.ScreenError, Alarm.ButtonError,
                             Alarm.CommunicationError, Alarm.Reserved1, Alarm.Reserved2,
                             Alarm.NoError, Alarm.LowReservoir, Alarm.LowBattery, Alarm.CheckGlucose))
            assertThat(ApexDriverLogic.ringsFor(alarm)).isFalse()
    }

    @Test
    fun aCodeMeaningNoErrorIsNotAnAlarmAtAll() {
        assertThat(ApexDriverLogic.isUrgent(Alarm.NoError)).isFalse()
        assertThat(ApexDriverLogic.ringsFor(Alarm.NoError)).isFalse()
    }

    @Test
    fun soundOnlyEverGoesToAnAlarmThatAlsoGetsTheUrgentCard() {
        // The only invariant that ties the two lists together: a ring without an urgent card would be a
        // siren with no way to see what it is about.
        for (alarm in Alarm.entries)
            if (ApexDriverLogic.ringsFor(alarm)) assertThat(ApexDriverLogic.isUrgent(alarm)).isTrue()
    }

    @Test
    fun everyKnownCodeIsClassifiedOnPurpose() {
        // Adding a code to Alarm must be a decision in THREE lists here - isUrgent, ringsFor and
        // stopsDelivery - and in a fourth place the compiler guards by itself: ApexService.alarmName is
        // exhaustive with no `else` arm, so a new entry does not build until it has a text.
        // The lists are kept apart on purpose. isUrgent is a catch-all, so an unknown code is still put
        // in front of the user. ringsFor is closed, so an unknown code cannot start a night-time siren.
        // stopsDelivery is closed in the other direction, so an unknown code is never taken as proof
        // that the motor stopped.
        assertThat(Alarm.entries).hasSize(28)
        assertThat(Alarm.entries.filterNot { ApexDriverLogic.isUrgent(it) })
            .containsExactly(Alarm.NoError, Alarm.LowBattery, Alarm.LowReservoir, Alarm.CheckGlucose)
        assertThat(Alarm.entries.filter { ApexDriverLogic.ringsFor(it) })
            .containsExactly(
                Alarm.Unknown, Alarm.DeadBattery, Alarm.NoDelivery, Alarm.MotorError, Alarm.EncoderError,
                Alarm.ReservoirEmpty, Alarm.NoReservoir, Alarm.FRAMError, Alarm.MotorAbnormal,
                Alarm.MotorPowerAbnormal, Alarm.BolusOrBasalDoseAbnormal, Alarm.PressureAbnormal
            )
        assertThat(Alarm.entries.filter { ApexDriverLogic.stopsDelivery(it) })
            .containsExactly(
                Alarm.DeadBattery, Alarm.NoDelivery, Alarm.MotorError, Alarm.EncoderError,
                Alarm.ReservoirEmpty, Alarm.NoReservoir, Alarm.MotorAbnormal, Alarm.MotorPowerAbnormal,
                Alarm.BolusOrBasalDoseAbnormal, Alarm.PressureAbnormal
            )
    }

    @Test
    fun proofOfAStoppedMotorIsNarrowerThanTheSirenAndNarrowerThanTheCard() {
        // A code that is proof of a stop must also ring and must be urgent. The reverse does not hold,
        // and that is the whole point of keeping a third list.
        for (alarm in Alarm.entries) if (ApexDriverLogic.stopsDelivery(alarm)) {
            assertThat(ApexDriverLogic.ringsFor(alarm)).isTrue()
            assertThat(ApexDriverLogic.isUrgent(alarm)).isTrue()
        }
    }

    @Test
    fun aCodeTheDriverCannotNameIsNeverProofThatTheMotorStopped() {
        // The opposite direction from ringsFor, on purpose. Declaring a running bolus dead on a code
        // that says nothing about the motor throws away the record that ties the pump's own history
        // entry to the row AAPS already wrote, and the same insulin is then counted twice, for good.
        for (alarm in listOf(Alarm.Unknown, Alarm.FRAMError, Alarm.ButtonError, Alarm.ScreenError,
                             Alarm.TimeError, Alarm.ClockError, Alarm.TDDLimitTriggered,
                             Alarm.Reserved1, Alarm.Reserved2, Alarm.NoError))
            assertThat(ApexDriverLogic.stopsDelivery(alarm)).isFalse()
    }
}
